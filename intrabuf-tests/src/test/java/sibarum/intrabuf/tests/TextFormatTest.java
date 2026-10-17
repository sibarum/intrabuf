package sibarum.intrabuf.tests;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.Format;
import sibarum.intrabuf.Header;
import sibarum.intrabuf.HeaderCodec;
import sibarum.intrabuf.IbReader;
import sibarum.intrabuf.IbWriter;
import sibarum.intrabuf.IncompatiblePayloadException;
import sibarum.intrabuf.IncompatiblePayloadException.Reason;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.MessageCodec;
import sibarum.intrabuf.dynamic.DynamicDecoder;
import sibarum.intrabuf.example.Address;
import sibarum.intrabuf.example.CustomerIntrabuf;
import sibarum.intrabuf.example.WriteSamples;
import sibarum.intrabuf.schema.HeaderDef;
import sibarum.intrabuf.tests.CompatSchemas.OrderV0WithProfileV1;
import sibarum.intrabuf.tests.CompatSchemas.PointV0;
import sibarum.intrabuf.tests.CompatSchemas.PointV1;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV0;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV1;
import sibarum.intrabuf.tests.CompatSchemas.ShapeV0;
import sibarum.intrabuf.tests.CompatSchemas.ShapeWithPointV1;
import sibarum.intrabuf.tests.CompatSchemas.TokenV0Edited;
import sibarum.intrabuf.tests.HeaderSchemas.Envelope;
import sibarum.intrabuf.tests.HeaderSchemas.Ping;
import sibarum.intrabuf.tests.HeaderSchemas.Pong;
import sibarum.intrabuf.tests.HeaderSchemas.PongV0;
import sibarum.intrabuf.tests.HeaderSchemas.Tight;
import sibarum.intrabuf.tests.TypeSchemas.Empty;
import sibarum.intrabuf.tests.TypeSchemas.Everything;
import sibarum.intrabuf.tests.TypeSchemas.Inner;
import sibarum.intrabuf.tests.TypeSchemas.TreeNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sibarum.intrabuf.tests.Deep.assertDeepEquals;
import static sibarum.intrabuf.tests.Deep.assertMatchesDynamic;

/** The text format: round trips, either format through one decode, compatibility, hand-written input, hostile input. */
class TextFormatTest {

    private static final TypeSchemas_EverythingIntrabuf EVERYTHING = TypeSchemas_EverythingIntrabuf.CODEC;
    private static final TypeSchemas_InnerIntrabuf INNER = TypeSchemas_InnerIntrabuf.CODEC;
    private final DynamicDecoder dynamic = new DynamicDecoder(Manifests.all());

    /** Text round trip through the generated codec, and the dynamic decoder agreeing with it. */
    private <T> void roundTrips(MessageCodec<T> codec, T value) {
        String text = codec.encodeText(value);
        assertDeepEquals(value, codec.decodeText(text));
        assertDeepEquals(value, codec.decode(text.getBytes(StandardCharsets.UTF_8)));
        DynamicDecoder.Decoded d = dynamic.decode(text.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(), d.warnings());
        assertMatchesDynamic(value, d.value());
    }

    private static IntrabufException fails(MessageCodec<?> codec, String text) {
        return assertThrows(IntrabufException.class, () -> codec.decodeText(text));
    }

    private static void assertMessage(IntrabufException e, String fragment) {
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    // ---- round trips -------------------------------------------------------------------------------------------

    @Test
    void everyType() {
        roundTrips(EVERYTHING, Samples.full());
        roundTrips(EVERYTHING, Samples.sparse());
    }

    @Test
    void extremes() {
        Everything base = Samples.sparse();
        for (long l : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, -1, 0}) {
            for (int i : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0}) {
                Everything v = new Everything(true, Byte.MIN_VALUE, Short.MIN_VALUE, i, l, Float.MIN_VALUE, Double.MAX_VALUE,
                        Character.MAX_VALUE, i, l, base.bBool(), base.bByte(), base.bShort(), i, l, Float.NEGATIVE_INFINITY,
                        Double.NaN, ',', "", new byte[0], null, null, null, null, List.of(i), List.of(l), null, null, null,
                        null, null, null, null, null, null, Optional.of(i), Optional.of(true), Optional.empty(),
                        Optional.of(List.of()), "r");
                roundTrips(EVERYTHING, v);
            }
        }
    }

    @Test
    void stringsInEveryForm() {
        String[] strings = {
                "plain", "two words", "", " leading", "trailing ", "\ttab", "a,b", "say \"hi\"", "back\\slash",
                "[x]", "{x}", "k=v", "&1", "$1", "#hash", "tick`tock", "line one\nline two", "\nstarts with a newline",
                "ends with one\n", "`fenced`\nblock", "crlf\r\nline", "nul\0byte", "bell\u0007", "del\u007F",
                "sep\u2028arator", "bom\uFEFF", "lone \uD800 high", "lone \uDC00 low", "pair 😀", "héllo, wörld",
                "λ", "null", "true", "42", "-", "  ", "\n", "``", "\"", "\\"};
        for (String s : strings) {
            Inner v = new Inner(s.length(), s);
            String text = INNER.encodeText(v);
            assertEquals(v, INNER.decodeText(text), text);
        }
        // Readable forms where they suffice.
        assertTrue(INNER.encodeText(new Inner(1, "two words")).contains("\n1,two words\n"));
        assertTrue(INNER.encodeText(new Inner(1, "a,b")).contains("\n1,\"a,b\"\n"));
        assertTrue(INNER.encodeText(new Inner(1, "")).contains("\n1,\"\"\n"));
        assertTrue(INNER.encodeText(new Inner(1, null)).contains("\n1,\n"));
        assertTrue(INNER.encodeText(new Inner(1, "one\ntwo")).contains("\n1,`one\ntwo`\n"));
        assertTrue(INNER.encodeText(new Inner(1, "a`b\nc")).contains("`a``b\nc`"));
        assertTrue(INNER.encodeText(new Inner(1, "cr\r\n")).contains("\"cr\\r\\n\""));
    }

    @Test
    void randomStringsRoundTrip() {
        Random rnd = new Random(20261006);
        String alphabet = "ab ,\"`[]{}=\\&$#\n\r\t\u0000\u2028é😀\uD800\uDC00";
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int n = rnd.nextInt(12); n > 0; n--) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            Inner v = new Inner(i, sb.toString());
            assertEquals(v, INNER.decodeText(INNER.encodeText(v)), INNER.encodeText(v));
        }
    }

    @Test
    void longStringsAndCollections() {
        roundTrips(INNER, new Inner(1, "😀".repeat(70000)));
        roundTrips(INNER, new Inner(2, "line\n".repeat(5000)));
    }

    @Test
    void multiWordBitsetsRecursionAndCrossModuleNesting() {
        for (long p : new long[]{0, -1, 0x5555555555555555L, 0x123456789ABCDEFL}) {
            roundTrips(TypeSchemas_WideIntrabuf.CODEC, Samples.wide(p));
        }
        roundTrips(TypeSchemas_TreeNodeIntrabuf.CODEC, Samples.tree(200));
        roundTrips(TypeSchemas_ShipmentIntrabuf.CODEC, Samples.shipment());
        roundTrips(TypeSchemas_EmptyIntrabuf.CODEC, new Empty());
        roundTrips(CustomerIntrabuf.CODEC, WriteSamples.customer());
    }

    @Test
    void nestingDepthIsLimited() {
        String text = TypeSchemas_TreeNodeIntrabuf.toText(Samples.tree(IbReader.MAX_DEPTH + 10));
        assertMessage(fails(TypeSchemas_TreeNodeIntrabuf.CODEC, text), "nested deeper");
        StringBuilder deep = new StringBuilder("#intrabuf 1 test.Inner 1.0 STRICT fp=0x00000000\na,b\n1,");
        deep.append("[".repeat(100_000));
        assertMessage(fails(INNER, deep.toString()), "nested deeper");
    }

    @Test
    void layout() {
        String text = CustomerIntrabuf.toText(WriteSamples.customer());
        String[] lines = text.split("\n");
        assertTrue(lines[0].startsWith("#intrabuf 1 example.Customer 1.2 BACKWARD id=0x"), lines[0]);
        assertEquals("id,name,email,active,favoriteColor,home,tags,nickname,loyaltyPoints", lines[1]);
        assertEquals("42,Ada Lovelace,ada@example.com,true,GREEN,&1,[vip,early-adopter],Ada,{2025=1200}", lines[2]);
        assertEquals("", lines[3]);
        assertEquals("#message example.Address 1.1", lines[4]);
        assertEquals("&,street,city,postcode,unit", lines[5]);
        assertEquals("&1,12 St James's Square,London,SW1Y 4JH,", lines[6]);

        String blobs = EVERYTHING.encodeText(Samples.full());
        assertTrue(blobs.contains("\n#blobs\n$1,AAEC/w==\n$2,CQ==\n$3,\n"), blobs);
        assertTrue(blobs.contains(",[$2,$3],"), blobs);
    }

    @Test
    void textIsDeterministic() {
        assertEquals(EVERYTHING.encodeText(Samples.full()), EVERYTHING.encodeText(Samples.full()));
    }

    @Test
    void nullsAreStillChecked() {
        IntrabufException e = assertThrows(IntrabufException.class,
                () -> CompatSchemas_ProfileV1Intrabuf.toText(new ProfileV1("a", 1, null, false, null)));
        assertMessage(e, "ProfileV1.tags");
    }

    // ---- one decode, either format -----------------------------------------------------------------------------

    @Test
    void decodeAcceptsEitherFormat() {
        Everything v = Samples.full();
        byte[] binary = EVERYTHING.encode(v, Format.BINARY);
        byte[] text = EVERYTHING.encode(v, Format.TEXT);
        assertEquals(Format.BINARY, Format.of(binary));
        assertEquals(Format.TEXT, Format.of(text));
        assertDeepEquals(v, TypeSchemas_EverythingIntrabuf.fromBytes(binary));
        assertDeepEquals(v, TypeSchemas_EverythingIntrabuf.fromBytes(text));
        assertEquals(Header.peek(binary).schemaId(), Header.peek(text).schemaId());
        assertEquals(Header.peek(binary).fingerprint(), Header.peek(text).fingerprint());

        // A UTF-8 BOM, as editors on some platforms add, and CRLF line endings.
        String crlf = INNER.encodeText(new Inner(7, "multi\nline")).replace("\n", "\r\n");
        byte[] bom = ("\uFEFF" + crlf).getBytes(StandardCharsets.UTF_8);
        assertEquals(Format.TEXT, Format.of(bom));
        assertEquals(new Inner(7, "multi\nline"), INNER.decode(bom));

        // One stream of payloads, alternating.
        for (int i = 0; i < 6; i++) {
            Inner x = new Inner(i, "n" + i);
            assertEquals(x, INNER.decode(INNER.encode(x, i % 2 == 0 ? Format.BINARY : Format.TEXT)));
        }
    }

    @Test
    void neitherFormat() {
        assertEquals(Reason.NOT_INTRABUF, assertThrows(IncompatiblePayloadException.class,
                () -> INNER.decode("hello".getBytes(StandardCharsets.UTF_8))).reason());
        assertEquals(Reason.UNSUPPORTED_WIRE_VERSION, assertThrows(IncompatiblePayloadException.class,
                () -> INNER.decodeText("#intrabuf 2 test.Inner 1.0 STRICT\na,b\n1,x\n")).reason());
    }

    // ---- compatibility: the same rules as binary ---------------------------------------------------------------

    @Test
    void backwardReadersReadOlderText() {
        String old = CompatSchemas_ProfileV0Intrabuf.toText(new ProfileV0("ann", 30));
        assertEquals(new ProfileV1("ann", 30, null, false, List.of()), CompatSchemas_ProfileV1Intrabuf.fromText(old));

        String newer = CompatSchemas_ProfileV1Intrabuf.toText(new ProfileV1("ann", 30, "e", true, List.of("t")));
        assertEquals(Reason.NEWER_MINOR, assertThrows(IncompatiblePayloadException.class,
                () -> CompatSchemas_ProfileV0Intrabuf.fromText(newer)).reason());

        String nested = CompatSchemas_OrderV0WithProfileV1Intrabuf.toText(
                new OrderV0WithProfileV1(1, new ProfileV1("a", 1, null, false, List.of())));
        assertEquals(Reason.NEWER_MINOR, assertThrows(IncompatiblePayloadException.class,
                () -> CompatSchemas_OrderV0Intrabuf.fromText(nested)).reason());
    }

    @Test
    void fullReadersSkipNewerColumns() {
        String newer = CompatSchemas_PointV1Intrabuf.toText(new PointV1(1, 2, 3, true));
        assertEquals(new PointV0(1, 2), CompatSchemas_PointV0Intrabuf.fromText(newer));
        assertEquals(new PointV1(1, 2, null, false),
                CompatSchemas_PointV1Intrabuf.fromText(CompatSchemas_PointV0Intrabuf.toText(new PointV0(1, 2))));

        String shape = CompatSchemas_ShapeWithPointV1Intrabuf.toText(
                new ShapeWithPointV1("s", List.of(new PointV1(1, 2, 3, true), new PointV1(4, 5, null, false))));
        assertEquals(new ShapeV0("s", List.of(new PointV0(1, 2), new PointV0(4, 5))),
                CompatSchemas_ShapeV0Intrabuf.fromText(shape));
    }

    @Test
    void strictTextCarriesTheFingerprint() {
        String edited = CompatSchemas_TokenV0EditedIntrabuf.toText(new TokenV0Edited("x", 5));
        assertEquals(Reason.FINGERPRINT_MISMATCH, assertThrows(IncompatiblePayloadException.class,
                () -> CompatSchemas_TokenV0Intrabuf.fromText(edited)).reason());
        assertEquals(Reason.SCHEMA_MISMATCH, assertThrows(IncompatiblePayloadException.class,
                () -> CompatSchemas_ProfileV0Intrabuf.fromText(edited)).reason());
        String major2 = CompatSchemas_TokenMajor2Intrabuf.toText(new CompatSchemas.TokenMajor2("x"));
        assertEquals(Reason.NEWER_MAJOR, assertThrows(IncompatiblePayloadException.class,
                () -> CompatSchemas_TokenV0Intrabuf.fromText(major2)).reason());
    }

    // ---- written by hand ---------------------------------------------------------------------------------------

    @Test
    void handWrittenDocuments() {
        // No id (the name implies it), spaces around cells, comments and blank lines, a multi-line note, rows in
        // any order, forward references.
        String text = """
                #intrabuf 1 example.Customer 1.2 BACKWARD
                id, name, email, active, favoriteColor, home, tags, nickname, loyaltyPoints
                7, Grace Hopper, , false, RED, &2, [ navy, "cobol, mostly" ], , { 1952 = 1, 1959 = 2 }

                # The address, with a two-line street.
                #message example.Address 1.0
                &, street, city, postcode
                &2, `Building 1
                Arlington`, "Virginia", 22201
                """;
        var c = CustomerIntrabuf.fromText(text);
        assertEquals(7, c.id());
        assertEquals("Grace Hopper", c.name());
        assertEquals(null, c.email());
        assertEquals(List.of("navy", "cobol, mostly"), c.tags());
        assertEquals(Optional.empty(), c.nickname());
        assertEquals(Map.of("1952", 1, "1959", 2), c.loyaltyPoints());
        assertEquals(new Address("Building 1\nArlington", "Virginia", "22201", null), c.home());
    }

    @Test
    void mistakesAreReportedWithTheirLine() {
        String head = "#intrabuf 1 example.Customer 1.2 BACKWARD\n"
                + "id,name,email,active,favoriteColor,home,tags,nickname,loyaltyPoints\n";
        String address = "\n#message example.Address 1.1\n&,street,city,postcode,unit\n&1,s,c,p,\n";
        MessageCodec<?> c = CustomerIntrabuf.CODEC;
        assertMessage(fails(c, head + "x,Ada,,true,RED,&1,[],,{}\n" + address), "line 3: example.Customer.id: expected an int64");
        assertMessage(fails(c, head + "1,,,true,RED,&1,[],,{}\n" + address), "example.Customer.name: empty, but this value is not nullable");
        assertMessage(fails(c, head + "1,Ada,,yes,RED,&1,[],,{}\n" + address), "expected true or false");
        assertMessage(fails(c, head + "1,Ada,,true,MAUVE,&1,[],,{}\n" + address), "'MAUVE' is not a constant of sibarum.intrabuf.example.Color [RED, GREEN, BLUE]");
        assertMessage(fails(c, head + "1,Ada,,true,RED,&2,[],,{}\n" + address), "&2 refers to no row");
        assertMessage(fails(c, head + "1,Ada,,true,RED,,[],,{}\n" + address), "row &1 (example.Address) is never referenced");
        assertMessage(fails(c, head + "1,Ada,,true,RED,&1,[],,{}\n"), "&1 refers to no row");
        assertMessage(fails(c, head + "1,Ada,,true,RED,&1,[],,{}," + address), "has 10 cell(s); its table has 9");
        assertMessage(fails(c, head + "1,Ada,,true,RED,&1,[,,{}\n" + address), "unclosed '['");
        assertMessage(fails(c, head + "1,Ada,,true,RED,&1,[a b],,{}x\n" + address), "expected ',' between cells");
        assertMessage(fails(c, head + "1,\"Ada,,true,RED,&1,[],,{}\n" + address), "unterminated \"...\"");
        assertMessage(fails(c, head + "1,`Ada,,true,RED,&1,[],,{}\n" + address), "unterminated `...`");
        assertMessage(fails(c, head + "1,\"\\q\",,true,RED,&1,[],,{}\n" + address), "unknown escape \\q");
        assertMessage(fails(c, head + "1,&Ada,,true,RED,&1,[],,{}\n" + address), "looks like a reference");
        assertMessage(fails(c, head.replace("email", "mail") + "1,Ada,,true,RED,&1,[],,{}\n" + address),
                "has columns id,name,mail");
        assertMessage(fails(c, head + "1,Ada,,true,RED,&1,[],,{}\n" + address.replace("Address 1.1", "Address 2.1")),
                "MAJOR_MISMATCH");
        assertMessage(fails(c, head + "1,Ada,,true,RED,&1,[],,{}\n" + address + "garbage\n"), "line 8: expected a '#message'");
        assertMessage(fails(c, head + "1,Ada,,true,RED,&1,[],,{}\n" + address + "#blobs\n$1,!!!\n"), "not valid base64");
        assertMessage(fails(c, "#intrabuf 1 example.Customer 1.2 BACKWARD colour=blue\n"), "colour=blue");
    }

    @Test
    void rowsBelongToExactlyOnePlace() {
        String head = "#intrabuf 1 test.TreeNode 1.0 STRICT fp=" + sibarum.intrabuf.Hex.int32(TypeSchemas_TreeNodeIntrabuf.FINGERPRINT)
                + "\nlabel,children\n";
        String rows = "\n#message test.TreeNode 1.0\n&,label,children\n";
        assertEquals(new TreeNode("root", List.of(new TreeNode("leaf", List.of()))),
                TypeSchemas_TreeNodeIntrabuf.fromText(head + "root,[&1]\n" + rows + "&1,leaf,[]\n"));
        assertMessage(fails(TypeSchemas_TreeNodeIntrabuf.CODEC, head + "root,[&1,&1]\n" + rows + "&1,leaf,[]\n"),
                "&1 is referenced more than once");
        assertMessage(fails(TypeSchemas_TreeNodeIntrabuf.CODEC, head + "root,[&1]\n" + rows + "&1,cycle,[&1]\n"),
                "&1 is referenced more than once");
        assertMessage(fails(TypeSchemas_TreeNodeIntrabuf.CODEC, head + "root,[&1]\n" + rows + "&1,a,[]\n&1,b,[]\n"),
                "row &1 is defined twice");
        assertMessage(fails(INNER, "#intrabuf 1 test.Inner 1.0 STRICT fp=" + sibarum.intrabuf.Hex.int32(TypeSchemas_InnerIntrabuf.FINGERPRINT)
                + "\na,b\n1,x\n" + rows + "&1,leaf,[]\n"), "row &1 (test.TreeNode) is never referenced");
    }

    @Test
    void anApplicationHeaderIsNotABuiltInPayload() {
        String frame = HeaderSchemas_EnvelopeIntrabuf.CODEC.encodeText(envelope(HeaderSchemas_PingIntrabuf.CODEC, 1),
                HeaderSchemas_PingIntrabuf.CODEC, new Ping(1));
        assertMessage(fails(HeaderSchemas_PingIntrabuf.CODEC, frame), "application header (#header test.Envelope)");
    }

    // ---- application headers -----------------------------------------------------------------------------------

    private static Envelope envelope(MessageCodec<?> codec, long correlationId) {
        return HeaderSchemas_EnvelopeIntrabuf.create(codec.schema(), (short) 0xEC51, (byte) 2, true, false, correlationId);
    }

    @Test
    void framesInEitherFormatThroughOneReadPath() {
        HeaderCodec<Envelope> env = HeaderSchemas_EnvelopeIntrabuf.CODEC;
        Map<Integer, MessageCodec<?>> registry = Map.of(
                HeaderSchemas_PingIntrabuf.ID, HeaderSchemas_PingIntrabuf.CODEC,
                HeaderSchemas_PongIntrabuf.ID, HeaderSchemas_PongIntrabuf.CODEC);
        for (boolean text : new boolean[]{false, true}) {
            for (Object msg : new Object[]{new Ping(41), new Pong(42, "hi\nthere"), new Pong(43, null)}) {
                @SuppressWarnings("unchecked")
                MessageCodec<Object> codec = (MessageCodec<Object>) registry.get(msg instanceof Ping ? 2 : 3);
                Envelope e = envelope(codec, 77);
                byte[] bytes;
                if (text) {
                    bytes = env.encodeTextBytes(e, codec, msg);
                } else {
                    IbWriter w = new IbWriter();
                    env.write(w, e);
                    codec.encodeBody(msg, w);
                    bytes = w.toByteArray();
                }
                IbReader r = new IbReader(bytes);
                HeaderCodec.Frame<Envelope> f = env.readFrame(r);
                assertEquals(text, f.text() != null);
                assertEquals(e, f.header());
                assertEquals(msg, registry.get(f.facts().schemaId()).decodeBody(f, r));
                assertEquals(msg, env.decode(codec, new IbReader(bytes)));
            }
        }
        String frame = env.encodeText(envelope(HeaderSchemas_PingIntrabuf.CODEC, 5), HeaderSchemas_PingIntrabuf.CODEC, new Ping(9));
        assertTrue(frame.contains("\n#header test.Envelope\nmagic,protocolVersion,request,reply,correlationId\n-5039,2,true,false,5\nseq\n9\n"),
                frame);
        assertTrue(frame.startsWith("#intrabuf 1 test.Ping 1.0 STRICT id=0x00000002 fp=0x"), frame);
    }

    @Test
    void compatibilityRulesApplyToTextFrames() {
        HeaderCodec<Envelope> env = HeaderSchemas_EnvelopeIntrabuf.CODEC;
        byte[] newer = env.encodeTextBytes(envelope(HeaderSchemas_PongIntrabuf.CODEC, 0), HeaderSchemas_PongIntrabuf.CODEC,
                new Pong(5, "new"));
        assertEquals(Reason.NEWER_MINOR, assertThrows(IncompatiblePayloadException.class,
                () -> env.decode(HeaderSchemas_PongV0Intrabuf.CODEC, new IbReader(newer))).reason());
        byte[] older = env.encodeTextBytes(envelope(HeaderSchemas_PongV0Intrabuf.CODEC, 0), HeaderSchemas_PongV0Intrabuf.CODEC,
                new PongV0(5));
        assertEquals(new Pong(5, null), env.decode(HeaderSchemas_PongIntrabuf.CODEC, new IbReader(older)));
    }

    @Test
    void primitiveFingerprintRolesAndTheBuiltInHeaderCodec() {
        String tight = HeaderSchemas_TightIntrabuf.CODEC.encodeText(HeaderSchemas_TightIntrabuf.create(HeaderSchemas_PongIntrabuf.SCHEMA),
                HeaderSchemas_PongIntrabuf.CODEC, new Pong(9, "x"));
        assertFalse(tight.contains("#header"), "Tight has no components of its own, so its table has no columns: " + tight);
        IbReader r = new IbReader(tight.getBytes(StandardCharsets.UTF_8));
        HeaderCodec.Frame<Tight> f = HeaderSchemas_TightIntrabuf.CODEC.readFrame(r);
        assertEquals(HeaderSchemas_PongIntrabuf.FINGERPRINT, f.header().fingerprint());
        assertEquals(new Pong(9, "x"), HeaderSchemas_PongIntrabuf.CODEC.decodeBody(f, r));

        byte[] plain = HeaderSchemas_PongIntrabuf.CODEC.encode(new Pong(3, "b"), Format.TEXT);
        HeaderCodec.Frame<Header> b = Header.CODEC.readFrame(new IbReader(plain));
        assertEquals(new Pong(3, "b"), HeaderSchemas_PongIntrabuf.CODEC.decodeBody(b, new IbReader(plain)));
        assertEquals(3, b.header().schemaId());
    }

    @Test
    void theDynamicDecoderReadsTextFrames() {
        HeaderDef env = Manifests.all().header("test.Envelope");
        byte[] frame = HeaderSchemas_EnvelopeIntrabuf.CODEC.encodeTextBytes(envelope(HeaderSchemas_PongIntrabuf.CODEC, 1234),
                HeaderSchemas_PongIntrabuf.CODEC, new Pong(8, "dyn"));
        DynamicDecoder.Decoded d = dynamic.decode(frame, env);
        assertMatchesDynamic(new Pong(8, "dyn"), d.value());
        assertMatchesDynamic(envelope(HeaderSchemas_PongIntrabuf.CODEC, 1234), d.frame());
        assertEquals(d.frame(), dynamic.readFrame(frame, env).record());
    }

    // ---- hostile input -----------------------------------------------------------------------------------------

    private static void survives(String what, Runnable decode) {
        try {
            decode.run();
        } catch (IntrabufException expected) {
            // fine
        } catch (Throwable t) {
            throw new AssertionError(what + ": " + t, t);
        }
    }

    @Test
    void truncationAndCorruptionFailCleanly() {
        record Case(MessageCodec<?> codec, String text) {
        }
        List<Case> cases = List.of(
                new Case(EVERYTHING, EVERYTHING.encodeText(Samples.full())),
                new Case(TypeSchemas_TreeNodeIntrabuf.CODEC, TypeSchemas_TreeNodeIntrabuf.toText(Samples.tree(30))),
                new Case(CustomerIntrabuf.CODEC, CustomerIntrabuf.toText(WriteSamples.customer())),
                new Case(CompatSchemas_ShapeWithPointV1Intrabuf.CODEC, CompatSchemas_ShapeWithPointV1Intrabuf.toText(
                        new ShapeWithPointV1("s", List.of(new PointV1(1, 2, 3, true))))));
        Random rnd = new Random(1006);
        String noise = ",\"`[]{}=\\&$#\n\r\t 0-9aZ.\u00e9";
        for (Case c : cases) {
            for (int n = 0; n < c.text().length(); n++) {
                String cut = c.text().substring(0, n);
                survives("truncated to " + n, () -> c.codec().decodeText(cut));
                survives("dynamic, truncated to " + n, () -> dynamic.decode(cut.getBytes(StandardCharsets.UTF_8)));
            }
            for (int i = 0; i < 3000; i++) {
                StringBuilder sb = new StringBuilder(c.text());
                for (int k = 1 + rnd.nextInt(3); k > 0; k--) {
                    int at = rnd.nextInt(sb.length());
                    char ch = noise.charAt(rnd.nextInt(noise.length()));
                    switch (rnd.nextInt(3)) {
                        case 0 -> sb.setCharAt(at, ch);
                        case 1 -> sb.insert(at, ch);
                        default -> sb.deleteCharAt(at);
                    }
                }
                String p = sb.toString();
                survives("corrupted " + i, () -> c.codec().decodeText(p));
                survives("dynamic, corrupted " + i, () -> dynamic.decode(p.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    @Test
    void textTreesDecodeToTheSameValueAsBinary() {
        TreeNode t = Samples.tree(50);
        assertEquals(TypeSchemas_TreeNodeIntrabuf.fromBytes(TypeSchemas_TreeNodeIntrabuf.toBytes(t)),
                TypeSchemas_TreeNodeIntrabuf.fromText(TypeSchemas_TreeNodeIntrabuf.toText(t)));
    }
}
