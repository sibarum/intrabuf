package sibarum.intrabuf;

import sibarum.intrabuf.schema.Fingerprint;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
 *   // or, as text: EnvelopeIntrabuf.CODEC.encodeText(env, PingIntrabuf.CODEC, ping)
 *
 *   // read, either format
 *   HeaderCodec.Frame<Envelope> f = EnvelopeIntrabuf.CODEC.readFrame(r);
 *   MessageCodec<?> codec = registry.byId(f.facts().schemaId());     // route
 *   Object msg = codec.decodeBody(f, r);                              // checked, then decoded
 * }</pre>
 *
 * <p>In the text format the facts are the document's first line, and the application's own components are a
 * {@code #header} table before the message's. An application header has no magic number, so {@link #readFrame}
 * tells the formats apart by the text format's first bytes, {@code "#intrabuf "}: a binary header that could
 * begin with those ten bytes should read text frames with {@link #readTextFrame} instead.
 */
public abstract class HeaderCodec<H> {

    /**
     * A header as the application sees it, and the facts intrabuf reads from it.
     *
     * @param text the whole document, if the frame is in the text format; null for binary
     */
    public record Frame<H>(H header, Header facts, TextDocument text) {
        public Frame(H header, Header facts) {
            this(header, facts, null);
        }
    }

    private final String textName;
    private final List<String> textColumns;

    /** The built-in header, which in the text format is the first line alone. */
    HeaderCodec() {
        this(null, new String[0]);
    }

    /**
     * @param textName    the {@code #header} table's name
     * @param textColumns the application's components, in order: the table's columns
     */
    protected HeaderCodec(String textName, String[] textColumns) {
        this.textName = textName;
        this.textColumns = List.of(textColumns);
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

    /** Reads a header and its facts, in either format. A binary frame leaves {@code r} at the start of the body. */
    public final Frame<H> readFrame(IbReader r) {
        if (r.isText()) {
            return readTextFrame(r.readText());
        }
        int at = r.position();
        H h = read(r);
        return new Frame<>(h, facts(h, r.position() - at));
    }

    /** Reads a header, checks it against {@code codec}'s schema, and decodes the body that follows. Either format. */
    public final <T> T decode(MessageCodec<T> codec, IbReader r) {
        return codec.decodeBody(readFrame(r), r);
    }

    // ---- text --------------------------------------------------------------------------------------------------

    /** A whole frame in the text format: {@code header}'s facts and table, then {@code value}. */
    public final <T> String encodeText(H header, MessageCodec<T> codec, T value) {
        if (header == null) {
            throw new IntrabufException("cannot write a null header");
        }
        if (value == null) {
            throw new IntrabufException("cannot encode a null " + codec.schema().name());
        }
        TextWriter t = new TextWriter(codec.schema().name(), facts(header, -1));
        if (!textColumns.isEmpty()) {
            t.header(textName, textColumns, () -> writeText(t, header));
        }
        codec.writeTextRoot(t, value);
        return t.finish();
    }

    public final <T> byte[] encodeTextBytes(H header, MessageCodec<T> codec, T value) {
        return encodeText(header, codec, value).getBytes(StandardCharsets.UTF_8);
    }

    /** Reads the header of a text frame: facts from the first line, the application's components from its table. */
    public final Frame<H> readTextFrame(TextDocument d) {
        Header facts = d.facts(Fingerprint.schemaId(d.schemaName()));
        TextDocument.Row row = d.header();
        if (textName == null && row != null) {
            throw new IntrabufException("text payload has an application header (#header " + row.table().name()
                    + "); read it with that header's codec");
        }
        // A header whose components are all roles has no table: the first line says everything it would.
        if (textName != null && (row != null || !textColumns.isEmpty())) {
            if (row == null) {
                throw new IntrabufException("text payload has no #header " + textName + " table");
            }
            if (!row.table().name().equals(textName)) {
                throw new IntrabufException("text payload's header is " + row.table().name() + ", not " + textName);
            }
            TextReader.checkColumns(row.table(), textColumns, false);
        }
        TextReader t = new TextReader(d, facts, row);
        H h = readText(t);
        t.endRow();
        return new Frame<>(h, facts, d);
    }

    /** The facts {@code header} carries; {@code size} is how many bytes it occupied, or -1. */
    public abstract Header facts(H header, int size);

    protected abstract void writeBody(IbWriter w, H header);

    protected abstract H readBody(IbReader r, int minor);

    /** Writes the application's components, the cells of the {@code #header} row. */
    protected abstract void writeText(TextWriter t, H header);

    /** Reads the application's components from the {@code #header} row, and the roles from {@link TextReader#facts()}. */
    protected abstract H readText(TextReader t);

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
