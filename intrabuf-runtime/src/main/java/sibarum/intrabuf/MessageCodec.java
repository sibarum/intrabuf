package sibarum.intrabuf;

/**
 * Base class of every generated codec. A codec for record {@code Foo} is generated as {@code FooIntrabuf} with a
 * singleton {@code FooIntrabuf.CODEC}; it is stateless (bar a size hint) and safe to share between threads.
 *
 * <p>A top-level payload is a {@link Header} followed by a body. A message nested inside another is written without
 * a header, framed according to its own compatibility mode:
 *
 * <pre>
 *   STRICT    body
 *   BACKWARD  varint minor, body
 *   FULL      varint minor, varint length, body
 * </pre>
 *
 * A body is a bitset (null/presence flags and boolean values, packed) followed by each field's value in declaration
 * order. In FULL mode the bitset is preceded by its length in bytes, so a reader can skip bits it does not know.
 */
public abstract class MessageCodec<T> {

    private final SchemaInfo schema;
    private final byte[] header;
    private int sizeHint = 64;

    protected MessageCodec(SchemaInfo schema) {
        this.schema = schema;
        this.header = Header.encode(schema);
    }

    public final SchemaInfo schema() {
        return schema;
    }

    // ---- top level ---------------------------------------------------------------------------------------------

    public final byte[] encode(T value) {
        IbWriter w = new IbWriter(sizeHint);
        encode(value, w);
        int n = w.position();
        if (n > sizeHint) {
            // Racy by design: a stale read just means one extra buffer growth.
            sizeHint = Math.min(n, 1 << 20);
        }
        return w.toByteArray();
    }

    /** Appends a complete payload (header and body) to {@code w}. */
    public final void encode(T value, IbWriter w) {
        if (value == null) {
            throw new IntrabufException("cannot encode a null " + schema.name());
        }
        w.writeRaw(header);
        writeBody(w, value);
    }

    public final T decode(byte[] payload) {
        return decode(new IbReader(payload));
    }

    public final T decode(byte[] payload, int off, int len) {
        return decode(new IbReader(payload, off, len));
    }

    /**
     * Decodes one complete payload, which must occupy the rest of {@code r}. Trailing bytes are an error unless the
     * payload is from a newer minor of a FULL schema, in which case they are fields this reader does not know.
     */
    public final T decode(IbReader r) {
        int minor;
        if (r.startsWith(header)) {
            // Byte-identical header: same schema, version, mode and (if present) fingerprint. Skip the checks.
            r.skip(header.length);
            minor = schema.minor();
        } else {
            minor = Compatibility.check(schema, Header.read(r));
        }
        T value = readBody(r, minor);
        if (r.remaining() != 0 && minor <= schema.minor()) {
            throw new IntrabufException(r.remaining() + " trailing byte(s) after " + schema.name() + " payload");
        }
        return value;
    }

    // ---- nested ------------------------------------------------------------------------------------------------

    public final void writeNested(IbWriter w, T value) {
        switch (schema.compat()) {
            case STRICT -> writeBody(w, value);
            case BACKWARD -> {
                w.writeVarint(schema.minor());
                writeBody(w, value);
            }
            case FULL -> {
                w.writeVarint(schema.minor());
                int mark = w.beginLength();
                writeBody(w, value);
                w.endLength(mark);
            }
        }
    }

    public final T readNested(IbReader r) {
        r.enter();
        T value = readNestedFrame(r);
        r.exit();
        return value;
    }

    private T readNestedFrame(IbReader r) {
        return switch (schema.compat()) {
            case STRICT -> readBody(r, schema.minor());
            case BACKWARD -> {
                int minor = r.readCount();
                if (minor > schema.minor()) {
                    throw new IncompatiblePayloadException(IncompatiblePayloadException.Reason.NEWER_MINOR,
                            "nested " + schema.name() + " is minor " + minor + ", newer than this reader's v"
                                    + schema.version(), schema, null);
                }
                yield readBody(r, minor);
            }
            case FULL -> {
                int minor = r.readCount();
                int len = r.readCount();
                if (len > r.remaining()) {
                    throw new IntrabufException("nested " + schema.name() + " claims " + len + " bytes, "
                            + r.remaining() + " remain");
                }
                int end = r.position() + len;
                T value = readBody(r, minor);
                int at = r.position();
                if (at > end || (at < end && minor <= schema.minor())) {
                    throw new IntrabufException("nested " + schema.name() + " body is " + (at - end + len)
                            + " bytes, its length prefix says " + len);
                }
                r.position(end);
                yield value;
            }
        };
    }

    // ---- generated ---------------------------------------------------------------------------------------------

    /** Writes the body of {@code value}: bitset, then field values. */
    protected abstract void writeBody(IbWriter w, T value);

    /**
     * Reads a body written by minor version {@code minor} of this schema. Fields newer than {@code minor} take their
     * defaults; for FULL schemas {@code minor} may exceed this codec's, and unknown trailing fields are left unread.
     */
    protected abstract T readBody(IbReader r, int minor);
}
