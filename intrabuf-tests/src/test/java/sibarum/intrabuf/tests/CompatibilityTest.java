package sibarum.intrabuf.tests;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.Header;
import sibarum.intrabuf.IncompatiblePayloadException;
import sibarum.intrabuf.IncompatiblePayloadException.Reason;
import sibarum.intrabuf.MessageCodec;
import sibarum.intrabuf.SchemaInfo;
import sibarum.intrabuf.tests.CompatSchemas.OrderV0;
import sibarum.intrabuf.tests.CompatSchemas.OrderV0WithProfileV1;
import sibarum.intrabuf.tests.CompatSchemas.PointV0;
import sibarum.intrabuf.tests.CompatSchemas.PointV1;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV0;
import sibarum.intrabuf.tests.CompatSchemas.ProfileV1;
import sibarum.intrabuf.tests.CompatSchemas.ShapeV0;
import sibarum.intrabuf.tests.CompatSchemas.ShapeWithPointV1;
import sibarum.intrabuf.tests.CompatSchemas.TokenV0;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompatibilityTest {

    private static Reason reason(MessageCodec<?> reader, byte[] payload) {
        return assertThrows(IncompatiblePayloadException.class, () -> reader.decode(payload)).reason();
    }

    // ---- BACKWARD ----------------------------------------------------------------------------------------------

    @Test
    void backwardReaderFillsDefaultsForFieldsTheWriterPredates() {
        byte[] old = CompatSchemas_ProfileV0Intrabuf.toBytes(new ProfileV0("ada", 36));
        ProfileV1 p = CompatSchemas_ProfileV1Intrabuf.fromBytes(old);
        assertEquals(new ProfileV1("ada", 36, null, false, List.of()), p);
    }

    @Test
    void backwardReaderRejectsNewerPayloads() {
        byte[] newer = CompatSchemas_ProfileV1Intrabuf.toBytes(new ProfileV1("ada", 36, "a@b", true, List.of("x")));
        assertEquals(Reason.NEWER_MINOR, reason(CompatSchemas_ProfileV0Intrabuf.CODEC, newer));
    }

    @Test
    void backwardNestedMessagesCarryTheirOwnMinor() {
        byte[] old = CompatSchemas_OrderV0Intrabuf.toBytes(new OrderV0(9, new ProfileV0("bob", 40)));
        assertEquals(new OrderV0WithProfileV1(9, new ProfileV1("bob", 40, null, false, List.of())),
                CompatSchemas_OrderV0WithProfileV1Intrabuf.fromBytes(old));

        byte[] newer = CompatSchemas_OrderV0WithProfileV1Intrabuf.toBytes(
                new OrderV0WithProfileV1(9, new ProfileV1("bob", 40, "b@c", true, List.of())));
        assertEquals(Reason.NEWER_MINOR, reason(CompatSchemas_OrderV0Intrabuf.CODEC, newer));
    }

    // ---- FULL --------------------------------------------------------------------------------------------------

    @Test
    void fullWorksInBothDirections() {
        byte[] old = CompatSchemas_PointV0Intrabuf.toBytes(new PointV0(1, -2));
        assertEquals(new PointV1(1, -2, null, false), CompatSchemas_PointV1Intrabuf.fromBytes(old));

        byte[] newer = CompatSchemas_PointV1Intrabuf.toBytes(new PointV1(1, -2, 3, true));
        assertEquals(new PointV0(1, -2), CompatSchemas_PointV0Intrabuf.fromBytes(newer));
    }

    @Test
    void fullOlderReaderSkipsUnknownFieldsOfNestedMessages() {
        ShapeWithPointV1 shape = new ShapeWithPointV1("tri", List.of(new PointV1(0, 0, 5, true),
                new PointV1(1, 0, null, false), new PointV1(0, 1, 1 << 20, true)));
        ShapeV0 seenByOld = CompatSchemas_ShapeV0Intrabuf.fromBytes(CompatSchemas_ShapeWithPointV1Intrabuf.toBytes(shape));
        assertEquals(new ShapeV0("tri", List.of(new PointV0(0, 0), new PointV0(1, 0), new PointV0(0, 1))), seenByOld);

        ShapeV0 old = new ShapeV0("sq", List.of(new PointV0(2, 2)));
        assertEquals(new ShapeWithPointV1("sq", List.of(new PointV1(2, 2, null, false))),
                CompatSchemas_ShapeWithPointV1Intrabuf.fromBytes(CompatSchemas_ShapeV0Intrabuf.toBytes(old)));
    }

    // ---- STRICT ------------------------------------------------------------------------------------------------

    @Test
    void strictCatchesAnUnversionedEdit() {
        assertEquals(CompatSchemas_TokenV0Intrabuf.CODEC.schema().version(), CompatSchemas_TokenV0EditedIntrabuf.CODEC.schema().version());
        assertNotEquals(CompatSchemas_TokenV0Intrabuf.FINGERPRINT, CompatSchemas_TokenV0EditedIntrabuf.FINGERPRINT);
        byte[] payload = CompatSchemas_TokenV0Intrabuf.toBytes(new TokenV0("t"));
        assertEquals(Reason.FINGERPRINT_MISMATCH, reason(CompatSchemas_TokenV0EditedIntrabuf.CODEC, payload));
    }

    @Test
    void strictRejectsOtherMinors() {
        byte[] payload = CompatSchemas_TokenV0Intrabuf.toBytes(new TokenV0("t"));
        assertEquals(Reason.MINOR_MISMATCH, reason(CompatSchemas_TokenV1Intrabuf.CODEC, payload));
    }

    @Test
    void majorMismatchIsAlwaysFatal() {
        byte[] payload = CompatSchemas_TokenV0Intrabuf.toBytes(new TokenV0("t"));
        assertEquals(Reason.BELOW_OLDEST, reason(CompatSchemas_TokenMajor2Intrabuf.CODEC, payload));
    }

    @Test
    void compatModeIsPartOfTheSchema() {
        byte[] payload = CompatSchemas_TokenV0Intrabuf.toBytes(new TokenV0("t"));
        assertEquals(Reason.COMPAT_MISMATCH, reason(CompatSchemas_TokenBackwardIntrabuf.CODEC, payload));
    }

    @Test
    void otherSchemasAreRejected() {
        byte[] payload = CompatSchemas_TokenV0Intrabuf.toBytes(new TokenV0("t"));
        assertEquals(Reason.SCHEMA_MISMATCH, reason(CompatSchemas_ProfileV0Intrabuf.CODEC, payload));
    }

    @Test
    void notIntrabufAtAll() {
        assertEquals(Reason.NOT_INTRABUF, reason(CompatSchemas_TokenV0Intrabuf.CODEC, "{\"json\":1}".getBytes()));
        assertEquals(Reason.NOT_INTRABUF, reason(CompatSchemas_TokenV0Intrabuf.CODEC, new byte[0]));
        byte[] payload = CompatSchemas_TokenV0Intrabuf.toBytes(new TokenV0("t"));
        payload[0] = (byte) 0xB2; // a future wire version
        assertEquals(Reason.UNSUPPORTED_WIRE_VERSION, reason(CompatSchemas_TokenV0Intrabuf.CODEC, payload));
    }

    // ---- snapshots ---------------------------------------------------------------------------------------------

    /** Re-heads a body written by {@code writer} as though a build with {@code fake}'s schema info wrote it. */
    private static byte[] reheader(byte[] payload, SchemaInfo fake) {
        int body = Header.peek(payload).size();
        byte[] h = Header.encode(fake);
        byte[] out = Arrays.copyOf(h, h.length + payload.length - body);
        System.arraycopy(payload, body, out, h.length, payload.length - body);
        return out;
    }

    private static SchemaInfo asSnapshot(SchemaInfo s, int fingerprint) {
        return new SchemaInfo(s.name(), s.javaType(), s.id(), s.major(), s.minor(), s.compat(), fingerprint, true, "2.0.0-SNAPSHOT");
    }

    @Test
    void snapshotPayloadWithTheSameStructureIsAccepted() {
        byte[] release = CompatSchemas_PointV1Intrabuf.toBytes(new PointV1(1, 2, 3, true));
        SchemaInfo s = CompatSchemas_PointV1Intrabuf.SCHEMA;
        byte[] snap = reheader(release, asSnapshot(s, s.fingerprint()));
        Header h = Header.peek(snap);
        assertEquals(true, h.snapshot());
        assertEquals(true, h.hasFingerprint());
        assertEquals(new PointV1(1, 2, 3, true), CompatSchemas_PointV1Intrabuf.fromBytes(snap));
    }

    @Test
    void snapshotPayloadWithDriftedStructureIsRejectedEvenWhereVersionsAllowIt() {
        // FULL would accept any minor from a release build. From a snapshot build, the version numbers mean nothing,
        // so only the fingerprint counts.
        byte[] release = CompatSchemas_PointV1Intrabuf.toBytes(new PointV1(1, 2, 3, true));
        SchemaInfo s = CompatSchemas_PointV1Intrabuf.SCHEMA;
        byte[] drifted = reheader(release, asSnapshot(s, s.fingerprint() ^ 1));
        assertEquals(Reason.SNAPSHOT_DRIFT, reason(CompatSchemas_PointV1Intrabuf.CODEC, drifted));
    }
}
