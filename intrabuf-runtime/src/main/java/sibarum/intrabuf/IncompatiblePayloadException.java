package sibarum.intrabuf;

/**
 * The payload is well-formed but this reader must not decode it: it is not intrabuf at all, it is a different
 * schema, or the writer's version of the schema is one this reader's compatibility mode does not accept.
 */
public final class IncompatiblePayloadException extends IntrabufException {

    public enum Reason {
        /** Empty, or the magic nibble is wrong. */
        NOT_INTRABUF,
        /** Written by a newer intrabuf wire format. */
        UNSUPPORTED_WIRE_VERSION,
        /** A payload of a different schema. */
        SCHEMA_MISMATCH,
        /** Same schema, different major version. Never decodable. */
        MAJOR_MISMATCH,
        /** Writer and reader disagree on the schema's compatibility mode. */
        COMPAT_MISMATCH,
        /** STRICT: same major, different minor. */
        MINOR_MISMATCH,
        /** BACKWARD: the payload is from a newer minor than the reader. */
        NEWER_MINOR,
        /** STRICT: same version numbers, different structure. Someone changed the schema without bumping it. */
        FINGERPRINT_MISMATCH,
        /** The writer was a snapshot build whose schema differs structurally from the reader's. */
        SNAPSHOT_DRIFT
    }

    private final Reason reason;
    private final transient SchemaInfo expected;
    private final transient Header found;

    public IncompatiblePayloadException(Reason reason, String detail, SchemaInfo expected, Header found) {
        super(reason + ": " + detail);
        this.reason = reason;
        this.expected = expected;
        this.found = found;
    }

    public Reason reason() {
        return reason;
    }

    /** The reader's schema, or null if the payload failed before a schema was involved. */
    public SchemaInfo expected() {
        return expected;
    }

    /** The payload's header, or null if it could not be read. */
    public Header found() {
        return found;
    }
}
