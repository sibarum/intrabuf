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
     *   <li>Schema id, major and compat mode must match. A major mismatch is never decodable.</li>
     *   <li>STRICT: the minor and the fingerprint must match as well.</li>
     *   <li>If the payload carries the reader's fingerprint, the structures are identical: decode.</li>
     *   <li>A snapshot writer with a different fingerprint is rejected whatever the mode, because a development
     *       build's version numbers say nothing about its schema.</li>
     *   <li>BACKWARD: the payload's minor must not exceed the reader's. FULL: any minor.</li>
     * </ol>
     */
    public static int check(SchemaInfo reader, Header h) {
        if (h.schemaId() != reader.id()) {
            throw fail(Reason.SCHEMA_MISMATCH, "payload schema id " + Hex.int32(h.schemaId()) + " is not "
                    + reader.name() + " (" + Hex.int32(reader.id()) + ")", reader, h);
        }
        if (h.major() != reader.major()) {
            throw fail(Reason.MAJOR_MISMATCH, reader.name() + " payload is major " + h.major()
                    + "; this reader is major " + reader.major(), reader, h);
        }
        if (h.compat() != reader.compat()) {
            throw fail(Reason.COMPAT_MISMATCH, reader.name() + " payload was written in " + h.compat()
                    + " mode; this reader is " + reader.compat(), reader, h);
        }
        if (reader.compat() == Compat.STRICT && h.minor() != reader.minor()) {
            throw fail(Reason.MINOR_MISMATCH, reader.name() + " is STRICT; payload is v" + h.version()
                    + ", reader is v" + reader.version(), reader, h);
        }
        boolean sameStructure = h.hasFingerprint() && h.fingerprint() == reader.fingerprint();
        if (!sameStructure) {
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
        }
        return h.minor();
    }

    private static IncompatiblePayloadException fail(Reason reason, String detail, SchemaInfo reader, Header h) {
        return new IncompatiblePayloadException(reason, detail, reader, h);
    }
}
