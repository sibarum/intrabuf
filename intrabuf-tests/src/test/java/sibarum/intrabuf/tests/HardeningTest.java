package sibarum.intrabuf.tests;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.Header;
import sibarum.intrabuf.Hex;
import sibarum.intrabuf.MessageCodec;
import sibarum.intrabuf.SchemaInfo;
import sibarum.intrabuf.dynamic.DynamicDecoder;
import sibarum.intrabuf.example.Address;
import sibarum.intrabuf.example.Color;
import sibarum.intrabuf.tests.CompatSchemas.PointV1;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV1;
import sibarum.intrabuf.tests.TypeSchemas.Everything;
import sibarum.intrabuf.tests.TypeSchemas.Inner;
import sibarum.intrabuf.tests.TypeSchemas.Shipment;
import sibarum.intrabuf.tests.TypeSchemas.Suit;
import sibarum.intrabuf.tests.TypeSchemas.TreeNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static sibarum.intrabuf.tests.Deep.assertDeepEquals;
import static sibarum.intrabuf.tests.Deep.assertMatchesDynamic;

/**
 * Values chosen to break things: random values of every type through both formats and both decoders, the edges of
 * each scalar, strings no encoding handles by default, and version facts that only line up by accident.
 */
class HardeningTest {

    private static final TypeSchemas_EverythingIntrabuf EVERYTHING = TypeSchemas_EverythingIntrabuf.CODEC;
    private final DynamicDecoder dynamic = new DynamicDecoder(Manifests.all());

    /**
     * Everything the promise covers: each format reads what it wrote, re-encoding what was read gives the same
     * payload, either format goes through {@code decode}, and the dynamic decoder sees what the codec sees.
     */
    private <T> void holds(MessageCodec<T> codec, T value, String what) {
        byte[] bin = codec.encode(value);
        T fromBin = codec.decode(bin);
        assertDeepEquals(value, fromBin);
        assertArrayEquals(bin, codec.encode(fromBin), what + ": binary re-encode");
        DynamicDecoder.Decoded d = dynamic.decode(bin);
        assertEquals(List.of(), d.warnings(), what);
        assertMatchesDynamic(value, d.value());

        String text = codec.encodeText(value);
        T fromText = codec.decodeText(text);
        assertDeepEquals(value, fromText);
        assertEquals(text, codec.encodeText(fromText), what + ": text re-encode");
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        assertDeepEquals(value, codec.decode(textBytes));
        DynamicDecoder.Decoded dt = dynamic.decode(textBytes);
        assertEquals(List.of(), dt.warnings(), what);
        assertMatchesDynamic(value, dt.value());
    }

    // ---- random values -----------------------------------------------------------------------------------------

    /** Code units that are syntax in the text format, need escapes, or are hard for UTF-8, alongside plain ones. */
    private static final String ALPHABET = "ab Z09 ,\"`[]{}=\\&$#\n\r\t\u0000\u007f\u0085\u00a0\u2028\uFEFF\uFFFD"
            + "é€😀\uD800\uDBFF\uDC00\uDFFF";

    private static String string(Random r) {
        int n = switch (r.nextInt(10)) {
            case 0 -> 0;
            case 1 -> 120 + r.nextInt(20); // across the 128-byte length boundary and the ASCII fast path
            default -> r.nextInt(8);
        };
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static int anyInt(Random r) {
        return switch (r.nextInt(4)) {
            case 0 -> new int[]{0, 1, -1, 63, -64, 64, -65, Integer.MAX_VALUE, Integer.MIN_VALUE}[r.nextInt(9)];
            case 1 -> r.nextInt(256) - 128;
            default -> r.nextInt();
        };
    }

    private static long anyLong(Random r) {
        return switch (r.nextInt(4)) {
            case 0 -> new long[]{0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 35, -(1L << 49)}[r.nextInt(7)];
            case 1 -> anyInt(r);
            default -> r.nextLong();
        };
    }

    private static float anyFloat(Random r) {
        return switch (r.nextInt(3)) {
            case 0 -> new float[]{0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MIN_VALUE,
                    Float.MAX_VALUE, Float.MIN_NORMAL, 0.1f, 1e-45f}[r.nextInt(10)];
            case 1 -> r.nextFloat() * 1e6f - 5e5f;
            default -> Float.intBitsToFloat(r.nextInt()); // any bits, NaN payloads included
        };
    }

    private static double anyDouble(Random r) {
        return switch (r.nextInt(3)) {
            case 0 -> new double[]{0d, -0d, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_NORMAL, 0.1, 4.9e-324}[r.nextInt(10)];
            case 1 -> r.nextGaussian() * 1e12;
            default -> Double.longBitsToDouble(r.nextLong());
        };
    }

    private static char anyChar(Random r) {
        return r.nextBoolean() ? ALPHABET.charAt(r.nextInt(ALPHABET.length())) : (char) r.nextInt(0x10000);
    }

    private static Instant anyInstant(Random r) {
        return switch (r.nextInt(4)) {
            case 0 -> new Instant[]{Instant.MIN, Instant.MAX, Instant.EPOCH, Instant.ofEpochSecond(-1, 999_999_999),
                    Instant.ofEpochSecond(253402300799L, 999_999_999), Instant.ofEpochSecond(253402300800L)}[r.nextInt(6)];
            default -> Instant.ofEpochSecond(r.nextLong(Instant.MIN.getEpochSecond(), Instant.MAX.getEpochSecond() + 1),
                    r.nextInt(1_000_000_000));
        };
    }

    private static byte[] bytes(Random r) {
        byte[] b = new byte[r.nextInt(5) == 0 ? 130 + r.nextInt(10) : r.nextInt(6)];
        r.nextBytes(b);
        return b;
    }

    private static Inner inner(Random r) {
        return new Inner(anyInt(r), r.nextInt(3) == 0 ? null : string(r));
    }

    private static Suit suit(Random r) {
        return Suit.values()[r.nextInt(Suit.values().length)];
    }

    /** Mostly short, sometimes past 127 elements so the count takes two bytes. */
    private static int size(Random r) {
        return r.nextInt(12) == 0 ? 128 + r.nextInt(8) : r.nextInt(4);
    }

    private static <T> List<T> list(Random r, Supplier<T> element) {
        List<T> out = new ArrayList<>();
        for (int n = size(r); n > 0; n--) {
            out.add(element.get());
        }
        return out;
    }

    private static <T> Set<T> set(Random r, Supplier<T> element) {
        return new LinkedHashSet<>(list(r, element));
    }

    private static <K, V> Map<K, V> map(Random r, Supplier<K> key, Supplier<V> value) {
        Map<K, V> out = new LinkedHashMap<>();
        for (int n = size(r); n > 0; n--) {
            out.put(key.get(), value.get());
        }
        return out;
    }

    private static <T> T maybe(Random r, Supplier<T> value) {
        return r.nextInt(3) == 0 ? null : value.get();
    }

    private static <T> Optional<T> opt(Random r, Supplier<T> value) {
        return r.nextInt(3) == 0 ? Optional.empty() : Optional.of(value.get());
    }

    static Everything everything(Random r) {
        return new Everything(
                r.nextBoolean(), (byte) r.nextInt(), (short) r.nextInt(), anyInt(r), anyLong(r), anyFloat(r), anyDouble(r),
                anyChar(r), anyInt(r), anyLong(r),
                maybe(r, r::nextBoolean), maybe(r, () -> (byte) r.nextInt()), maybe(r, () -> (short) r.nextInt()),
                maybe(r, () -> anyInt(r)), maybe(r, () -> anyLong(r)), maybe(r, () -> anyFloat(r)),
                maybe(r, () -> anyDouble(r)), maybe(r, () -> anyChar(r)),
                maybe(r, () -> string(r)), maybe(r, () -> bytes(r)), maybe(r, () -> new UUID(r.nextLong(), r.nextLong())),
                maybe(r, () -> anyInstant(r)), maybe(r, () -> suit(r)), maybe(r, () -> inner(r)),
                maybe(r, () -> list(r, () -> anyInt(r))), maybe(r, () -> list(r, () -> anyLong(r))),
                maybe(r, () -> set(r, () -> string(r))),
                maybe(r, () -> map(r, () -> string(r), () -> list(r, () -> anyLong(r)))),
                maybe(r, () -> map(r, () -> suit(r), () -> inner(r))),
                maybe(r, () -> map(r, () -> anyInt(r), () -> map(r, () -> string(r), r::nextBoolean))),
                maybe(r, () -> list(r, () -> list(r, () -> string(r)))), maybe(r, () -> list(r, () -> inner(r))),
                maybe(r, () -> list(r, () -> suit(r))), maybe(r, () -> list(r, r::nextBoolean)),
                maybe(r, () -> list(r, () -> bytes(r))),
                opt(r, () -> anyInt(r)), opt(r, r::nextBoolean), opt(r, () -> inner(r)), opt(r, () -> list(r, () -> string(r))),
                string(r));
    }

    @Test
    void randomValuesOfEveryTypeHoldInBothFormats() {
        Random r = new Random(20261019);
        for (int i = 0; i < 400; i++) {
            holds(EVERYTHING, everything(r), "value " + i);
        }
    }

    // ---- strings -----------------------------------------------------------------------------------------------

    @Test
    void unpairedSurrogatesSurviveEveryFormat() {
        // A Java string is UTF-16, and may hold a surrogate with no partner. UTF-8 cannot, so a plain UTF-8 encoder
        // writes '?' instead and the value comes back different.
        for (String s : new String[]{"\uD800", "lone \uDC00 low", "\uDC00\uD800", "end \uD83D", "\uDE00 start",
                "x".repeat(130) + "\uDBFF", "😀\uD83D😀", "\uFFFD"}) {
            holds(TypeSchemas_InnerIntrabuf.CODEC, new Inner(s.length(), s), s);
            assertEquals(s, TypeSchemas_InnerIntrabuf.fromBytes(TypeSchemas_InnerIntrabuf.toBytes(new Inner(0, s))).b());
        }
    }

    @Test
    void stringsBuiltFromWholeUnicodeRoundTrip() {
        Random r = new Random(31);
        for (int i = 0; i < 2000; i++) {
            char[] cs = new char[r.nextInt(i < 1500 ? 6 : 300)];
            for (int k = 0; k < cs.length; k++) {
                cs[k] = (char) r.nextInt(0x10000);
            }
            Inner v = new Inner(i, new String(cs));
            assertEquals(v, TypeSchemas_InnerIntrabuf.fromBytes(TypeSchemas_InnerIntrabuf.toBytes(v)));
            assertEquals(v, TypeSchemas_InnerIntrabuf.fromText(TypeSchemas_InnerIntrabuf.toText(v)));
        }
    }

    // ---- edges -------------------------------------------------------------------------------------------------

    @Test
    void binaryKeepsEveryBitOfAFloat() {
        // Text writes every NaN as NaN; binary keeps the payload bits.
        Everything base = Samples.sparse();
        for (int bits : new int[]{0x7fc00001, 0xffc00000, 0x7f800001, 0x80000000}) {
            float f = Float.intBitsToFloat(bits);
            double d = Double.longBitsToDouble(0x7ff0000000000001L ^ bits);
            Everything v = new Everything(base.pBool(), base.pByte(), base.pShort(), base.pInt(), base.pLong(), f, d,
                    base.pChar(), base.fixedInt(), base.fixedLong(), null, null, null, null, null, f, d, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "");
            Everything back = EVERYTHING.decode(EVERYTHING.encode(v));
            assertEquals(bits, Float.floatToRawIntBits(back.pFloat()));
            assertEquals(bits, Float.floatToRawIntBits(back.bFloat()));
            assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(back.pDouble()));
        }
    }

    @Test
    void instantsAtTheEndsOfTime() {
        Everything base = Samples.sparse();
        for (Instant t : new Instant[]{Instant.MIN, Instant.MAX, Instant.EPOCH, Instant.ofEpochSecond(-1, 1),
                Instant.ofEpochSecond(253402300800L), Instant.ofEpochSecond(-62167219200L - 1)}) {
            Everything v = new Everything(base.pBool(), base.pByte(), base.pShort(), base.pInt(), base.pLong(),
                    base.pFloat(), base.pDouble(), base.pChar(), base.fixedInt(), base.fixedLong(), null, null, null,
                    null, null, null, null, null, null, null, null, t, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "");
            holds(EVERYTHING, v, t.toString());
        }
    }

    @Test
    void longFullBodiesShiftTheirLengthPrefix() {
        // A nested FULL message is framed by its length, reserved as one byte and widened once the body is known.
        for (int n : new int[]{0, 100, 127, 128, 16_380, 16_384, 2_097_200}) {
            Shipment s = new Shipment("t", new Address("s".repeat(n), "c", n % 2 == 0 ? null : "p", "u"), Color.GREEN);
            holds(TypeSchemas_ShipmentIntrabuf.CODEC, s, "street of " + n);
        }
    }

    @Test
    void wideAndDeepTrees() {
        List<TreeNode> leaves = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            leaves.add(new TreeNode(i % 7 == 0 ? null : "leaf " + i, List.of()));
        }
        holds(TypeSchemas_TreeNodeIntrabuf.CODEC, new TreeNode("root", leaves), "wide");
        holds(TypeSchemas_TreeNodeIntrabuf.CODEC, Samples.tree(250), "deep");
    }

    // ---- transport ---------------------------------------------------------------------------------------------

    @Test
    void textSurvivesLineEndingRewritesAndABom() {
        Everything v = Samples.full();
        String text = EVERYTHING.encodeText(v);
        assertDeepEquals(v, EVERYTHING.decodeText(text.replace("\n", "\r\n")));
        assertDeepEquals(v, EVERYTHING.decode(("\uFEFF" + text).getBytes(StandardCharsets.UTF_8)));
        // A value that spans lines is a backtick block; a CRLF transport still gives back its \n.
        Inner multi = new Inner(1, "one\ntwo\n");
        assertEquals(multi, TypeSchemas_InnerIntrabuf.fromText(TypeSchemas_InnerIntrabuf.toText(multi).replace("\n", "\r\n")));
    }

    // ---- version facts -----------------------------------------------------------------------------------------

    /** {@code payload} with its header replaced by the one a writer with {@code s}'s info would write. */
    private static byte[] reheader(byte[] payload, SchemaInfo s) {
        int body = Header.peek(payload).size();
        byte[] h = Header.encode(s);
        byte[] out = Arrays.copyOf(h, h.length + payload.length - body);
        System.arraycopy(payload, body, out, h.length, payload.length - body);
        return out;
    }

    /** A snapshot of {@code s} with the same structure, at minor {@code minor}. */
    private static SchemaInfo sameStructureAt(SchemaInfo s, int minor) {
        return new SchemaInfo(s.name(), s.javaType(), s.id(), s.major(), minor, s.compat(), s.fingerprint(), true,
                "9.9.9-SNAPSHOT");
    }

    @Test
    void anIdenticalStructureIsReadWhateverItsMinor() {
        // A minor bumped before any field was added: the fingerprints agree, so the payload is decoded, and at a
        // minor newer than a BACKWARD reader's there must be no layout lookup to run off the end of.
        ProfileV1 profile = new ProfileV1("ada", 36, "a@b", true, List.of("x", "y"));
        byte[] profileBytes = CompatSchemas_ProfileV1Intrabuf.toBytes(profile);
        SchemaInfo ps = CompatSchemas_ProfileV1Intrabuf.SCHEMA;
        PointV1 point = new PointV1(1, -2, 3, true);
        byte[] pointBytes = CompatSchemas_PointV1Intrabuf.toBytes(point);
        SchemaInfo pts = CompatSchemas_PointV1Intrabuf.SCHEMA;
        for (int minor : new int[]{ps.minor(), ps.minor() + 1, 127, 128, 100_000}) {
            assertEquals(profile, CompatSchemas_ProfileV1Intrabuf.fromBytes(reheader(profileBytes, sameStructureAt(ps, minor))));
            assertEquals(point, CompatSchemas_PointV1Intrabuf.fromBytes(reheader(pointBytes, sameStructureAt(pts, minor))));
            assertMatchesDynamic(profile, dynamic.decode(reheader(profileBytes, sameStructureAt(ps, minor))).value());
        }
        // The same in text: the facts line claims the newer minor, the columns are the reader's own.
        String text = CompatSchemas_ProfileV1Intrabuf.toText(profile);
        String rest = text.substring(text.indexOf('\n'));
        String newer = "#intrabuf 1 " + ps.name() + " " + ps.major() + "." + (ps.minor() + 1) + " " + ps.compat()
                + " fp=" + Hex.int32(ps.fingerprint()) + " snapshot" + rest;
        assertEquals(profile, CompatSchemas_ProfileV1Intrabuf.fromText(newer));
    }
}
