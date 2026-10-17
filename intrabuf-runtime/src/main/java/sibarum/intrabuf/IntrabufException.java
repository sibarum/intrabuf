package sibarum.intrabuf;

/**
 * A payload could not be written or read: it is truncated, malformed, or a value violates its schema (a null in a
 * non-null field, an enum ordinal out of range). Compatibility failures are the subclass
 * {@link IncompatiblePayloadException}.
 */
public class IntrabufException extends RuntimeException {

    public IntrabufException(String message) {
        super(message);
    }

    public IntrabufException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Thrown by generated encoders when a field declared non-null holds null. */
    public static IntrabufException nullField(String field) {
        return new IntrabufException(field + " is null but is declared non-null");
    }

    /** Thrown by a past version's codec, which only decodes: a build writes one version of each schema. */
    public static IntrabufException pastVersion(String schema, String version) {
        return new IntrabufException(schema + " v" + version + " is a past version, kept only to be read and promoted;"
                + " write the current version instead");
    }

    /** Thrown by generated encoders for a null collection element, map key or map value. */
    public static IntrabufException nullElement(String field) {
        return new IntrabufException(field + " contains a null element; collection elements, map keys and map values must be non-null");
    }
}
