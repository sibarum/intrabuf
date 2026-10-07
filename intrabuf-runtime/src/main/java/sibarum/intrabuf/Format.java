package sibarum.intrabuf;

/**
 * The two forms a payload can take. Every codec writes either and reads either: {@code decode} looks at the first
 * bytes and parses what it finds, so a peer can switch to text for one message (to debug, or to hand-write one) and
 * back, with nothing else changing.
 */
public enum Format {

    /** The compact binary format, {@code docs/wire-format.md}. */
    BINARY,

    /** The human-readable text format, {@code docs/text-format.md}, as UTF-8. */
    TEXT;

    /**
     * Which format {@code payload} is in. A text payload starts with {@code #intrabuf} (after an optional UTF-8 BOM);
     * a binary payload with the built-in header starts with a byte {@code 0xB0}-{@code 0xBF}, so the two can never
     * be confused. Anything else is reported as binary, and fails as such.
     */
    public static Format of(byte[] payload) {
        return of(payload, 0, payload.length);
    }

    public static Format of(byte[] payload, int off, int len) {
        return TextDocument.isText(payload, off, len) ? TEXT : BINARY;
    }
}
