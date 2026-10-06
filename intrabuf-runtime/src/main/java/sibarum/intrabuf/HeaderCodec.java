package sibarum.intrabuf;

/**
 * Reads and writes a payload header of type {@code H}, and extracts from it the {@link Header} facts that
 * {@link Compatibility#check} decides on. {@link Header#CODEC} is the built-in format; the processor generates one
 * for each {@code @IntrabufHeader} record, whose components marked with {@code @Role} carry the facts and whose
 * other components belong to the application (correlation ids, flags, lengths).
 *
 * <p>Typical framing, with a generated header codec {@code EnvelopeIntrabuf}:
 *
 * <pre>{@code
 *   // write
 *   Envelope env = EnvelopeIntrabuf.create(PingIntrabuf.SCHEMA, ...app fields...);
 *   EnvelopeIntrabuf.CODEC.write(w, env);
 *   PingIntrabuf.CODEC.encodeBody(ping, w);
 *
 *   // read
 *   HeaderCodec.Frame<Envelope> f = EnvelopeIntrabuf.CODEC.readFrame(r);
 *   MessageCodec<?> codec = registry.byId(f.facts().schemaId());     // route
 *   Object msg = codec.decodeBody(f.facts(), r);                      // checked, then decoded
 * }</pre>
 */
public abstract class HeaderCodec<H> {

    /** A header as the application sees it, and the facts intrabuf reads from it. */
    public record Frame<H>(H header, Header facts) {
    }

    public final void write(IbWriter w, H header) {
        if (header == null) {
            throw new IntrabufException("cannot write a null header");
        }
        writeBody(w, header);
    }

    public final H read(IbReader r) {
        return readBody(r, 0);
    }

    /** Reads a header and its facts, leaving {@code r} at the start of the body. */
    public final Frame<H> readFrame(IbReader r) {
        int at = r.position();
        H h = read(r);
        return new Frame<>(h, facts(h, r.position() - at));
    }

    /** Reads a header, checks it against {@code codec}'s schema, and decodes the body that follows. */
    public final <T> T decode(MessageCodec<T> codec, IbReader r) {
        return codec.decodeBody(readFrame(r).facts(), r);
    }

    /** The facts {@code header} carries; {@code size} is how many bytes it occupied, or -1. */
    public abstract Header facts(H header, int size);

    protected abstract void writeBody(IbWriter w, H header);

    protected abstract H readBody(IbReader r, int minor);

    // ---- for generated create() methods ------------------------------------------------------------------------

    protected static byte narrowToByte(int v, String role) {
        if (v != (byte) v) {
            throw new IntrabufException(role + " " + v + " does not fit the header's byte field");
        }
        return (byte) v;
    }

    protected static short narrowToShort(int v, String role) {
        if (v != (short) v) {
            throw new IntrabufException(role + " " + v + " does not fit the header's short field");
        }
        return (short) v;
    }
}
