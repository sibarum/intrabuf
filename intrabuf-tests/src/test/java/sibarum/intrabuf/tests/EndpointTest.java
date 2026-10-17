package sibarum.intrabuf.tests;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.MessageCodec;
import sibarum.intrabuf.endpoint.Agreement;
import sibarum.intrabuf.endpoint.Agreement.Verdict;
import sibarum.intrabuf.endpoint.Descriptor;
import sibarum.intrabuf.endpoint.Descriptor.Role;
import sibarum.intrabuf.endpoint.EndpointDef;
import sibarum.intrabuf.endpoint.EndpointDef.Member;
import sibarum.intrabuf.endpoint.EndpointDef.Side;
import sibarum.intrabuf.endpoint.Hello;
import sibarum.intrabuf.endpoint.PeerIncompatibleException;
import sibarum.intrabuf.tests.CompatSchemas.OrderV0;
import sibarum.intrabuf.tests.CompatSchemas.OrderV0WithProfileV1;
import sibarum.intrabuf.tests.CompatSchemas.PointV0;
import sibarum.intrabuf.tests.CompatSchemas.PointV1;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV0;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV1;
import sibarum.intrabuf.tests.CompatSchemas.TokenBackward;
import sibarum.intrabuf.tests.CompatSchemas.TokenMajor2;
import sibarum.intrabuf.tests.CompatSchemas.TokenV0;
import sibarum.intrabuf.tests.CompatSchemas.TokenV0Edited;
import sibarum.intrabuf.tests.CompatSchemas.TokenV1;
import sibarum.intrabuf.tests.PromotionSchemas.Account;
import sibarum.intrabuf.tests.PromotionSchemas.AccountFrom21;
import sibarum.intrabuf.tests.PromotionSchemas.AccountV1Deployed;
import sibarum.intrabuf.tests.PromotionSchemas.AccountV20Deployed;
import sibarum.intrabuf.tests.PromotionSchemas.AccountV21Deployed;
import sibarum.intrabuf.tests.PromotionSchemas.AccountV30Deployed;
import sibarum.intrabuf.tests.PromotionSchemas.Label;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointTest {

    private record Writer(MessageCodec<?> codec, Object value) {
    }

    private static final List<Writer> WRITERS = List.of(
            new Writer(PromotionSchemas_AccountV1DeployedIntrabuf.CODEC, new AccountV1Deployed(7, "Ada Lovelace")),
            new Writer(PromotionSchemas_AccountV20DeployedIntrabuf.CODEC, new AccountV20Deployed(7, "Ada", "Lovelace")),
            new Writer(PromotionSchemas_AccountV21DeployedIntrabuf.CODEC, new AccountV21Deployed(7, "Ada", "Lovelace", "a@b")),
            new Writer(PromotionSchemas_AccountV30DeployedIntrabuf.CODEC, new AccountV30Deployed(7, "Ada", "Lovelace", "x")),
            new Writer(PromotionSchemas_AccountIntrabuf.CODEC, new Account(7, "Ada", "Lovelace", "x", new Label("Ada"))),
            new Writer(PromotionSchemas_AccountFrom21Intrabuf.CODEC, new AccountFrom21(7, "Ada", "Lovelace", "x", new Label("A"))),
            new Writer(CompatSchemas_ProfileV0Intrabuf.CODEC, new ProfileV0("ada", 36)),
            new Writer(CompatSchemas_ProfileV1Intrabuf.CODEC, new ProfileV1("ada", 36, "a@b", true, List.of("x"))),
            new Writer(CompatSchemas_OrderV0Intrabuf.CODEC, new OrderV0(1, new ProfileV0("a", 1))),
            new Writer(CompatSchemas_OrderV0WithProfileV1Intrabuf.CODEC,
                    new OrderV0WithProfileV1(1, new ProfileV1("a", 1, null, false, List.of()))),
            new Writer(CompatSchemas_PointV0Intrabuf.CODEC, new PointV0(1, 2)),
            new Writer(CompatSchemas_PointV1Intrabuf.CODEC, new PointV1(1, 2, 3, true)),
            new Writer(CompatSchemas_TokenV0Intrabuf.CODEC, new TokenV0("t")),
            new Writer(CompatSchemas_TokenV0EditedIntrabuf.CODEC, new TokenV0Edited("t", 5)),
            new Writer(CompatSchemas_TokenV1Intrabuf.CODEC, new TokenV1("t", 5)),
            new Writer(CompatSchemas_TokenMajor2Intrabuf.CODEC, new TokenMajor2("t")),
            new Writer(CompatSchemas_TokenBackwardIntrabuf.CODEC, new TokenBackward("t")));

    private static List<MessageCodec<?>> readers() {
        List<MessageCodec<?>> out = new ArrayList<>(WRITERS.stream().<MessageCodec<?>>map(Writer::codec).toList());
        out.add(PromotionSchemas_AccountV1Intrabuf.CODEC);
        out.add(PromotionSchemas_AccountV2Intrabuf.CODEC);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T> byte[] bytes(MessageCodec<T> codec, Object value) {
        return codec.encode((T) value);
    }

    @SuppressWarnings("unchecked")
    private static <T> String text(MessageCodec<T> codec, Object value) {
        return codec.encodeText((T) value);
    }

    private static Descriptor peer(String build, Role role, Member... members) {
        return new Descriptor(new EndpointDef("test.Pair", "", List.of(members)), role, build, false);
    }

    /** What the handshake says about {@code writer} sending to {@code reader}. */
    private static Verdict verdict(MessageCodec<?> writer, MessageCodec<?> reader) {
        Agreement a = Agreement.of(peer("w", Role.CLIENT, Member.of(writer, Side.REQUEST, false)),
                peer("r", Role.SERVER, Member.of(reader, Side.REQUEST, false)));
        return a.cells().getFirst().verdict();
    }

    // ---- the handshake is the payload check, decided early ------------------------------------------------------

    @Test
    void theAgreementSaysExactlyWhatDecodingDoes() {
        int pairs = 0;
        for (Writer w : WRITERS) {
            byte[] binary = bytes(w.codec(), w.value());
            byte[] textual = text(w.codec(), w.value()).getBytes(StandardCharsets.UTF_8);
            for (MessageCodec<?> reader : readers()) {
                if (!reader.schema().name().equals(w.codec().schema().name())) {
                    continue;
                }
                pairs++;
                Verdict v = verdict(w.codec(), reader);
                for (byte[] payload : List.of(binary, textual)) {
                    boolean decodes;
                    String why;
                    try {
                        reader.decode(payload);
                        decodes = true;
                        why = "decoded";
                    } catch (IntrabufException e) {
                        decodes = false;
                        why = e.getMessage();
                    }
                    assertEquals(decodes, v.ok(), w.codec().schema().javaType() + " v" + w.codec().schema().version()
                            + " → " + reader.schema().javaType() + " v" + reader.schema().version() + ": handshake says "
                            + v + ", payload " + why);
                }
            }
        }
        assertTrue(pairs > 60, "pairs checked: " + pairs);
    }

    @Test
    void verdictsNameTheCase() {
        assertEquals(Verdict.IDENTICAL, verdict(PromotionSchemas_AccountIntrabuf.CODEC, PromotionSchemas_AccountIntrabuf.CODEC));
        assertEquals(Verdict.PROMOTES, verdict(PromotionSchemas_AccountV1DeployedIntrabuf.CODEC, PromotionSchemas_AccountIntrabuf.CODEC));
        assertEquals(Verdict.PROMOTES, verdict(CompatSchemas_ProfileV0Intrabuf.CODEC, CompatSchemas_ProfileV1Intrabuf.CODEC));
        assertEquals(Verdict.DEMOTES, verdict(CompatSchemas_PointV1Intrabuf.CODEC, CompatSchemas_PointV0Intrabuf.CODEC));
        assertEquals(Verdict.NEWER_MINOR, verdict(CompatSchemas_ProfileV1Intrabuf.CODEC, CompatSchemas_ProfileV0Intrabuf.CODEC));
        assertEquals(Verdict.NEWER_MAJOR, verdict(PromotionSchemas_AccountIntrabuf.CODEC, PromotionSchemas_AccountV21DeployedIntrabuf.CODEC));
        assertEquals(Verdict.BELOW_OLDEST, verdict(PromotionSchemas_AccountV1DeployedIntrabuf.CODEC, PromotionSchemas_AccountFrom21Intrabuf.CODEC));
        assertEquals(Verdict.MINOR_MISMATCH, verdict(CompatSchemas_TokenV0Intrabuf.CODEC, CompatSchemas_TokenV1Intrabuf.CODEC));
        assertEquals(Verdict.FINGERPRINT_MISMATCH, verdict(CompatSchemas_TokenV0EditedIntrabuf.CODEC, CompatSchemas_TokenV0Intrabuf.CODEC));
        assertEquals(Verdict.COMPAT_MISMATCH, verdict(CompatSchemas_TokenBackwardIntrabuf.CODEC, CompatSchemas_TokenV0Intrabuf.CODEC));
    }

    @Test
    void aNestedMessageCanFailWhatItsHolderPasses() {
        Agreement a = Agreement.of(peer("2.0.0", Role.CLIENT, Member.of(CompatSchemas_OrderV0WithProfileV1Intrabuf.CODEC, Side.REQUEST, false)),
                peer("1.0.0", Role.SERVER, Member.of(CompatSchemas_OrderV0Intrabuf.CODEC, Side.REQUEST, false)));
        Agreement.Cell c = a.cells().getFirst();
        assertEquals(Verdict.NEWER_MINOR, c.verdict());
        assertEquals("test.Profile", c.nested().schema());
        assertTrue(a.table().contains("sends    test.Order 1.0 → server reads 1.0: it holds test.Profile 1.1, which the peer"
                + " reads as 1.0; test.Profile is BACKWARD, so a newer minor doesn't demote"), a.table());
        // The other way, the newer reader takes the older nested message.
        assertEquals(Verdict.IDENTICAL, verdict(CompatSchemas_OrderV0Intrabuf.CODEC, CompatSchemas_OrderV0WithProfileV1Intrabuf.CODEC));
    }

    @Test
    void aSnapshotWriterAgreesOnlyWithItsOwnStructure() {
        Member old = Member.of(CompatSchemas_ProfileV0Intrabuf.CODEC, Side.REQUEST, false);
        Member same = Member.of(CompatSchemas_ProfileV1Intrabuf.CODEC, Side.REQUEST, false);
        Descriptor snapshot = new Descriptor(new EndpointDef("test.Pair", "", List.of(old)), Role.CLIENT, "2.0-SNAPSHOT", true);
        assertEquals(Verdict.SNAPSHOT_DRIFT, Agreement.of(snapshot, peer("r", Role.SERVER, same)).cells().getFirst().verdict());
        assertEquals(Verdict.IDENTICAL, Agreement.of(snapshot, peer("r", Role.SERVER, old)).cells().getFirst().verdict());
    }

    // ---- both sides reach the same verdict -----------------------------------------------------------------------

    @Test
    void bothPeersComputeTheSameCells() {
        Descriptor client = EndpointSchemas_AccountsIntrabuf.client();
        Descriptor oldServer = peer("1.0.0", Role.SERVER,
                Member.of(PromotionSchemas_AccountV21DeployedIntrabuf.CODEC, Side.REQUEST, false),
                Member.of(PromotionSchemas_LedgerIntrabuf.CODEC, Side.RESPONSE, false));
        oldServer = new Descriptor(new EndpointDef("test.Accounts", "", oldServer.endpoint().members()), Role.SERVER, "1.0.0", false);
        Agreement mine = Agreement.of(client, oldServer);
        Agreement theirs = Agreement.of(oldServer, client);
        assertEquals(mine.ok(), theirs.ok());
        for (Agreement.Cell c : mine.cells()) {
            Agreement.Cell mirror = theirs.cells().stream()
                    .filter(t -> t.sender() == c.sender() && t.sent().id() == c.sent().id()).findFirst().orElseThrow();
            assertEquals(c.verdict(), mirror.verdict(), c.sent().schema());
        }
        assertFalse(mine.ok());
    }

    // ---- what a failed handshake says ----------------------------------------------------------------------------

    @Test
    void aFailedHandshakeListsEverySchemaBothWays() {
        Descriptor oldServer = new Descriptor(new EndpointDef("test.Accounts", "", List.of(
                Member.of(PromotionSchemas_AccountV21DeployedIntrabuf.CODEC, Side.REQUEST, false),
                Member.of(PromotionSchemas_LedgerIntrabuf.CODEC, Side.RESPONSE, false))), Role.SERVER, "1.0.0", false);
        PeerIncompatibleException e = assertThrows(PeerIncompatibleException.class,
                () -> Agreement.of(EndpointSchemas_AccountsIntrabuf.client(), oldServer).require());
        String table = e.getMessage();
        assertTrue(table.startsWith("test.Accounts: this client (1.0.0) and the server (1.0.0) can't interoperate"), table);
        assertTrue(table.contains("sends    test.Account 3.1 → server reads 2.0–2.1: majors never demote, so the peer must reach"
                + " major 3 first: deploy the reader before the writer"), table);
        assertTrue(table.contains("receives test.Ledger 1.0 ← server writes 1.0; this client reads 1.0: ok"), table);
        // The server doesn't send PointV1 at all: an optional response the client receives but nobody sends is fine.
        assertEquals(2, e.agreement().cells().size());
    }

    @Test
    void anOptionalSchemaThePeerCantTakeIsNotSent() {
        Descriptor server = EndpointSchemas_AccountsIntrabuf.server();
        Descriptor oldClient = new Descriptor(new EndpointDef("test.Accounts", "", List.of(
                Member.of(PromotionSchemas_AccountIntrabuf.CODEC, Side.REQUEST, false),
                Member.of(PromotionSchemas_LedgerIntrabuf.CODEC, Side.RESPONSE, false))), Role.CLIENT, "1.0.0", false);
        Agreement a = Agreement.of(server, oldClient).require();
        assertTrue(a.accepts(PromotionSchemas_LedgerIntrabuf.SCHEMA));
        assertFalse(a.accepts(CompatSchemas_PointV1Intrabuf.SCHEMA));
        PeerIncompatibleException e = assertThrows(PeerIncompatibleException.class,
                () -> a.checkSend(CompatSchemas_PointV1Intrabuf.SCHEMA));
        assertTrue(e.getMessage().startsWith("NOT_AGREED: sends    test.Point 1.1 → client doesn't receive it: optional, not sent"),
                e.getMessage());
        assertFalse(a.accepts(PromotionSchemas_AccountIntrabuf.SCHEMA), "a server doesn't send requests");
    }

    @Test
    void theSameRoleOrAnotherEndpointNeverAgrees() {
        Descriptor client = EndpointSchemas_AccountsIntrabuf.client();
        assertTrue(Agreement.of(client, client).table().contains("both peers are clients"));
        Descriptor other = new Descriptor(new EndpointDef("test.Other", "", List.of()), Role.SERVER, "1", false);
        assertTrue(Agreement.of(client, other).table().contains("this peer speaks test.Accounts and the other speaks test.Other"));
        assertFalse(Agreement.of(client, other).ok());
    }

    // ---- Hello ---------------------------------------------------------------------------------------------------

    @Test
    void helloCarriesTheDescriptor() {
        for (Descriptor d : List.of(EndpointSchemas_AccountsIntrabuf.client(), EndpointSchemas_AccountsIntrabuf.server())) {
            byte[] hello = Hello.encode(d);
            assertTrue(Hello.is(hello));
            assertEquals(d, Hello.decode(hello));
            // A newer intrabuf may append to a Hello; this one reads what it knows.
            byte[] longer = Arrays.copyOf(hello, hello.length + 3);
            assertEquals(d, Hello.decode(longer));
        }
        assertTrue(Agreement.of(EndpointSchemas_AccountsIntrabuf.client(),
                Hello.decode(Hello.encode(EndpointSchemas_AccountsIntrabuf.server()))).ok());
        assertThrows(IntrabufException.class, () -> Hello.decode(PromotionSchemas_AccountIntrabuf.toBytes(
                new Account(1, "a", "b", "c", new Label("d")))));
        byte[] cut = Hello.encode(EndpointSchemas_AccountsIntrabuf.client());
        assertThrows(IntrabufException.class, () -> Hello.decode(Arrays.copyOf(cut, cut.length - 4)));
    }

    @Test
    void theGeneratedEndpointDescribesItsMembers() {
        EndpointDef e = EndpointSchemas_AccountsIntrabuf.ENDPOINT;
        assertEquals("test.Accounts", e.name());
        assertEquals(List.of("test.Account", "test.Ledger", "test.Point"), e.members().stream().map(Member::schema).toList());
        Member account = e.members().getFirst();
        assertEquals("3.1", account.version().toString());
        assertEquals("1.0", account.oldest().toString());
        assertEquals(Side.REQUEST, account.side());
        assertTrue(e.members().get(2).optional());
        assertEquals(List.of(account), EndpointSchemas_AccountsIntrabuf.client().sends());
    }
}
