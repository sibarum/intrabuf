package sibarum.intrabuf;

import sibarum.intrabuf.IncompatiblePayloadException.Reason;

/** The rules for whether a reader may decode a payload, given its own schema and the payload's header. */
public final class Compatibility {

    private Compatibility() {
    }

    /**
     * Returns the minor version to decode the body with, or throws.
     *
     * <ol>
     *   <li>The body must be in this runtime's wire format version.</li>
     *   <li>Schema id, major and compat mode must match. A newer major is never decodable ({@code NEWER_MAJOR}); an
     *       older one is below this reader's oldest version ({@code BELOW_OLDEST}). A codec that declares promotions
     *       hands an older major to the codec of that major before calling this.</li>
     *   <li>STRICT: the minor and the fingerprint must match as well.</li>
     *   <li>If the payload carries the reader's fingerprint, the structures are identical: decode, at the reader's own
     *       minor, whatever minor the payload claims.</li>
     *   <li>A snapshot writer with a different fingerprint is rejected whatever the mode, because a development
     *       build's version numbers say nothing about its schema.</li>
     *   <li>BACKWARD: the payload's minor must not exceed the reader's. FULL: any minor.</li>
     * </ol>
     */
    public static int check(SchemaInfo reader, Header h) {
        if (h.wireVersion() != Header.WIRE_VERSION) {
            throw fail(Reason.UNSUPPORTED_WIRE_VERSION, "body is wire format version " + h.wireVersion()
                    + "; this runtime reads version " + Header.WIRE_VERSION, reader, h);
        }
        if (h.compat() == null) {
            throw fail(Reason.COMPAT_MISMATCH, "header carries no compat mode", reader, h);
        }
        if (h.major() < 0 || h.minor() < 0) {
            // The built-in header cannot express these; an application header's signed fields can.
            throw new IntrabufException("header version " + h.version() + " is negative; the header is corrupt");
        }
        if (h.schemaId() != reader.id()) {
            throw fail(Reason.SCHEMA_MISMATCH, "payload schema id " + Hex.int32(h.schemaId()) + " is not "
                    + reader.name() + " (" + Hex.int32(reader.id()) + ")", reader, h);
        }
        if (h.major() > reader.major()) {
            throw fail(Reason.NEWER_MAJOR, reader.name() + " payload is v" + h.version() + "; this reader is v"
                    + reader.version() + ", and majors never demote", reader, h);
        }
        checkOldest(reader, h, reader.major(), 0);
        if (h.compat() != reader.compat()) {
            throw fail(Reason.COMPAT_MISMATCH, reader.name() + " payload was written in " + h.compat()
                    + " mode; this reader is " + reader.compat(), reader, h);
        }
        if (reader.compat() == Compat.STRICT && h.minor() != reader.minor()) {
            throw fail(Reason.MINOR_MISMATCH, reader.name() + " is STRICT; payload is v" + h.version()
                    + ", reader is v" + reader.version(), reader, h);
        }
        if (h.hasFingerprint() && h.fingerprint() == reader.fingerprint()) {
            // An identical structure is laid out identically whatever its version numbers say (a snapshot build, or
            // a minor bumped without a structural change), so it is read as the reader's own minor. The payload's
            // minor may be newer than the reader's, which a BACKWARD body could not otherwise be read at.
            return reader.minor();
        }
        if (h.snapshot()) {
            throw fail(Reason.SNAPSHOT_DRIFT, reader.name() + " payload came from a snapshot build with fingerprint "
                    + Hex.int32(h.fingerprint()) + "; this reader's is " + Hex.int32(reader.fingerprint()), reader, h);
        }
        if (reader.compat() == Compat.STRICT) {
            throw fail(Reason.FINGERPRINT_MISMATCH, reader.name() + " v" + h.version() + " payload has fingerprint "
                    + Hex.int32(h.fingerprint()) + " but this reader's is " + Hex.int32(reader.fingerprint())
                    + "; the schema changed without a version bump", reader, h);
        }
        if (reader.compat() == Compat.BACKWARD && h.minor() > reader.minor()) {
            throw fail(Reason.NEWER_MINOR, reader.name() + " payload is v" + h.version() + ", newer than this reader's v"
                    + reader.version() + "; BACKWARD readers only accept older or equal minors", reader, h);
        }
        return h.minor();
    }

    /**
     * Rejects a payload of the reader's schema whose version is below {@code oldestMajor.oldestMinor}, the oldest the
     * reader declares, saying what would reach it.
     */
    public static void checkOldest(SchemaInfo reader, Header h, int oldestMajor, int oldestMinor) {
        if (h.major() > oldestMajor || h.major() == oldestMajor && h.minor() >= oldestMinor) {
            return;
        }
        String reads = reader.name() + " payload is v" + h.version() + "; this reader reads v" + oldestMajor + "."
                + oldestMinor + " to v" + reader.version();
        throw fail(Reason.BELOW_OLDEST, h.major() == oldestMajor
                ? reads + " (its oldest)"
                : reads + ". Reading major " + h.major() + " needs a promotion from it to every later major"
                        + " (@Promote) and oldest = \"" + h.major() + "." + h.minor() + "\" or below", reader, h);
    }

    private static IncompatiblePayloadException fail(Reason reason, String detail, SchemaInfo reader, Header h) {
        return new IncompatiblePayloadException(reason, detail, reader, h);
    }
}
