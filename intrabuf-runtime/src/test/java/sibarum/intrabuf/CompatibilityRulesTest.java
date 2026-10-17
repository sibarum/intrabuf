package sibarum.intrabuf;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.IncompatiblePayloadException.Reason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The decision table of {@link Compatibility#check}, writer by reader. */
class CompatibilityRulesTest {

    private static final int ID = 0x1234;
    private static final int FP = 0xF00D;

    private static SchemaInfo s(int major, int minor, Compat compat, int fp, boolean snapshot) {
        return new SchemaInfo("t.T", "t.T", ID, major, minor, compat, fp, snapshot, snapshot ? "1-SNAPSHOT" : "1");
    }

    private static Header h(SchemaInfo writer) {
        return Header.peek(Header.encode(writer));
    }

    private static int ok(SchemaInfo writer, SchemaInfo reader) {
        return Compatibility.check(reader, h(writer));
    }

    private static Reason fails(SchemaInfo writer, SchemaInfo reader) {
        return assertThrows(IncompatiblePayloadException.class, () -> Compatibility.check(reader, h(writer))).reason();
    }

    @Test
    void headerRoundTrips() {
        SchemaInfo w = s(3, 300, Compat.FULL, FP, true);
        Header h = h(w);
        assertEquals(new Header(1, true, Compat.FULL, ID, 3, 300, true, FP, 13), h);
        assertEquals(8, h(s(1, 0, Compat.BACKWARD, FP, false)).size());
    }

    @Test
    void strict() {
        assertEquals(0, ok(s(1, 0, Compat.STRICT, FP, false), s(1, 0, Compat.STRICT, FP, false)));
        assertEquals(Reason.MINOR_MISMATCH, fails(s(1, 1, Compat.STRICT, FP, false), s(1, 0, Compat.STRICT, FP, false)));
        assertEquals(Reason.MINOR_MISMATCH, fails(s(1, 0, Compat.STRICT, FP, false), s(1, 1, Compat.STRICT, FP, false)));
        assertEquals(Reason.FINGERPRINT_MISMATCH, fails(s(1, 0, Compat.STRICT, FP, false), s(1, 0, Compat.STRICT, FP + 1, false)));
    }

    @Test
    void backward() {
        assertEquals(0, ok(s(1, 0, Compat.BACKWARD, 1, false), s(1, 2, Compat.BACKWARD, 2, false)));
        assertEquals(2, ok(s(1, 2, Compat.BACKWARD, 1, false), s(1, 2, Compat.BACKWARD, 1, false)));
        assertEquals(Reason.NEWER_MINOR, fails(s(1, 3, Compat.BACKWARD, 1, false), s(1, 2, Compat.BACKWARD, 2, false)));
    }

    @Test
    void full() {
        assertEquals(0, ok(s(1, 0, Compat.FULL, 1, false), s(1, 2, Compat.FULL, 2, false)));
        assertEquals(5, ok(s(1, 5, Compat.FULL, 1, false), s(1, 2, Compat.FULL, 2, false)));
    }

    @Test
    void majorAndModeAndIdAlwaysMatter() {
        for (Compat c : Compat.values()) {
            assertEquals(Reason.NEWER_MAJOR, fails(s(2, 0, c, FP, false), s(1, 0, c, FP, false)));
            assertEquals(Reason.BELOW_OLDEST, fails(s(1, 0, c, FP, true), s(2, 0, c, FP, true)));
        }
        assertEquals(Reason.COMPAT_MISMATCH, fails(s(1, 0, Compat.FULL, FP, false), s(1, 0, Compat.BACKWARD, FP, false)));
        SchemaInfo other = new SchemaInfo("t.U", "t.U", ID + 1, 1, 0, Compat.STRICT, FP, false, "1");
        assertEquals(Reason.SCHEMA_MISMATCH, fails(s(1, 0, Compat.STRICT, FP, false), other));
    }

    @Test
    void snapshots() {
        // Same structure: fine, whatever the versions say (within a major and mode).
        assertEquals(0, ok(s(1, 0, Compat.BACKWARD, FP, true), s(1, 0, Compat.BACKWARD, FP, false)));
        assertEquals(7, ok(s(1, 7, Compat.BACKWARD, FP, true), s(1, 2, Compat.BACKWARD, FP, false)));
        // Different structure from a snapshot writer: rejected even where release versions would be accepted.
        assertEquals(Reason.SNAPSHOT_DRIFT, fails(s(1, 0, Compat.FULL, FP, true), s(1, 0, Compat.FULL, FP + 1, false)));
        assertEquals(Reason.SNAPSHOT_DRIFT, fails(s(1, 0, Compat.BACKWARD, FP, true), s(1, 3, Compat.BACKWARD, FP + 1, true)));
        // A snapshot reader of a release payload follows the release rules.
        assertEquals(1, ok(s(1, 1, Compat.BACKWARD, FP, false), s(1, 1, Compat.BACKWARD, FP + 1, true)));
    }

    @Test
    void malformedFactsFromApplicationHeaders() {
        SchemaInfo r = s(1, 2, Compat.BACKWARD, FP, false);
        assertThrows(IntrabufException.class, () -> Compatibility.check(r, new Header(1, false, Compat.BACKWARD, ID, 1, -3, false, 0, -1)));
        assertEquals(Reason.UNSUPPORTED_WIRE_VERSION, assertThrows(IncompatiblePayloadException.class,
                () -> Compatibility.check(r, new Header(2, false, Compat.BACKWARD, ID, 1, 0, false, 0, -1))).reason());
        assertEquals(Reason.COMPAT_MISMATCH, assertThrows(IncompatiblePayloadException.class,
                () -> Compatibility.check(r, new Header(1, false, null, ID, 1, 0, false, 0, -1))).reason());
    }

    @Test
    void nesting() {
        assertEquals(true, Compat.STRICT.canContain(Compat.STRICT));
        assertEquals(true, Compat.STRICT.canContain(Compat.FULL));
        assertEquals(false, Compat.BACKWARD.canContain(Compat.STRICT));
        assertEquals(true, Compat.BACKWARD.canContain(Compat.FULL));
        assertEquals(false, Compat.FULL.canContain(Compat.BACKWARD));
    }
}
