package sibarum.intrabuf;

import sibarum.intrabuf.schema.Fingerprint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>Every codec also writes and reads the text format ({@link #encodeText}, {@link TextDocument}), and
 * {@link #decode(byte[])} accepts either format: the compatibility rules are the same for both.
 */
public abstract class MessageCodec<T> {

    private final SchemaInfo schema;
    private final byte[] header;
    private final List<String> fieldNames;
    private final int[] fieldSince;
    private final TextWriter.Section section;
    private int sizeHint = 64;

    /**
     * @param fieldNames the record's components, in order: the text format's columns
     * @param fieldSince the minor each field was added in
     */
    protected MessageCodec(SchemaInfo schema, String[] fieldNames, int[] fieldSince) {
        this.schema = schema;
        this.header = Header.encode(schema);
        this.fieldNames = List.of(fieldNames);
        this.fieldSince = fieldSince.clone();
        this.section = new TextWriter.Section(schema.name(), schema.major(), schema.minor(), columns(schema.minor()));
    }

    public final SchemaInfo schema() {
        return schema;
    }

    /** The fields a payload written at {@code minor} has, in order: its text columns. */
    public final List<String> columns(int minor) {
        if (schema.compat() == Compat.STRICT || minor >= schema.minor()) {
            return fieldNames;
        }
        List<String> out = new ArrayList<>(fieldNames.size());
        for (int i = 0; i < fieldNames.size(); i++) {
            if (fieldSince[i] <= minor) {
                out.add(fieldNames.get(i));
            }
        }
        return out;
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

    /** Encodes a complete payload in either format; text is UTF-8. */
    public final byte[] encode(T value, Format format) {
        return format == Format.TEXT ? encodeText(value).getBytes(StandardCharsets.UTF_8) : encode(value);
    }

    /** Encodes a complete payload in the text format. */
    public final String encodeText(T value) {
        if (value == null) {
            throw new IntrabufException("cannot encode a null " + schema.name());
        }
        TextWriter t = new TextWriter(schema.name(), Header.of(schema));
        writeTextRoot(t, value);
        return t.finish();
    }

    final void writeTextRoot(TextWriter t, T value) {
        t.root(section.columns(), () -> writeText(t, value));
    }

    /** Decodes a complete payload in either format; see {@link Format#of}. */
    public final T decode(byte[] payload) {
        return decode(new IbReader(payload));
    }

    public final T decode(byte[] payload, int off, int len) {
        return decode(new IbReader(payload, off, len));
    }

    /**
     * Decodes one complete payload, in either format, which must occupy the rest of {@code r}. Trailing bytes are
     * an error unless the payload is from a newer minor of a FULL schema, in which case they are fields this reader
     * does not know.
     */
    public final T decode(IbReader r) {
        if (r.startsWith(header)) {
            // Byte-identical header: same schema, version, mode and (if present) fingerprint. Skip the checks.
            r.skip(header.length);
            return finish(r, schema.minor());
        }
        if (r.isText()) {
            return decodeText(r.readText());
        }
        return decodeBody(Header.read(r), r);
    }

    /** Decodes a complete payload in the text format. */
    public final T decodeText(String text) {
        return decodeText(TextDocument.parse(text));
    }

    public final T decodeText(TextDocument d) {
        if (d.header() != null) {
            throw new IntrabufException("text payload has an application header (#header " + d.header().table().name()
                    + "); decode it with that header's codec");
        }
        // A hand-written payload may leave out the id: it is then the one its schema name implies.
        int id = d.schemaName().equals(schema.name()) ? schema.id() : Fingerprint.schemaId(d.schemaName());
        return decodeTextBody(d.facts(id), d);
    }

    // ---- with an application's own header (see HeaderCodec) ----------------------------------------------------

    /** Appends just the body of {@code value}, for a payload whose header the application writes itself. */
    public final void encodeBody(T value, IbWriter w) {
        if (value == null) {
            throw new IntrabufException("cannot encode a null " + schema.name());
        }
        writeBody(w, value);
    }

    /**
     * Checks a header the application read itself against this codec's schema, then decodes the body, which must
     * occupy the rest of {@code r} (with the same trailing-bytes rule as {@link #decode(IbReader)}).
     */
    public final T decodeBody(Header h, IbReader r) {
        return finish(r, Compatibility.check(schema, h));
    }

    /**
     * Checks a frame the application read with {@link HeaderCodec#readFrame}, then decodes its body, in whichever
     * format the frame was in. A binary body must occupy the rest of {@code r}.
     */
    public final T decodeBody(HeaderCodec.Frame<?> frame, IbReader r) {
        return frame.text() != null ? decodeTextBody(frame.facts(), frame.text()) : decodeBody(frame.facts(), r);
    }

    /** Checks {@code h} (the document's facts) against this codec's schema, then decodes the document's root. */
    public final T decodeTextBody(Header h, TextDocument d) {
        int minor = Compatibility.check(schema, h);
        if (!d.schemaName().equals(schema.name())) {
            throw new IncompatiblePayloadException(IncompatiblePayloadException.Reason.SCHEMA_MISMATCH,
                    "text payload is " + d.schemaName() + ", not " + schema.name(), schema, h);
        }
        T value = readTextRow(new TextReader(d, h, d.root()), minor);
        d.checkAllUsed();
        return value;
    }

    private T readTextRow(TextReader t, int minor) {
        TextReader.checkColumns(t.row().table(), columns(minor), minor > schema.minor());
        T value = readText(t, minor);
        t.endRow();
        return value;
    }

    private T finish(IbReader r, int minor) {
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

    /** Writes {@code value} as a nested message: a reference here, a row in its type's table. */
    public final void writeTextNested(TextWriter t, T value) {
        t.writeMessage(section, () -> writeText(t, value));
    }

    /** Reads a reference to a nested message's row, and decodes that row. */
    public final T readTextNested(TextReader parent) {
        TextReader t = parent.readMessage(schema.name());
        TextDocument.Table table = t.row().table();
        String what = "nested " + schema.name() + " (&" + t.row().ref() + ")";
        if (table.major() != schema.major()) {
            throw new IncompatiblePayloadException(IncompatiblePayloadException.Reason.MAJOR_MISMATCH, what + " is v"
                    + table.version() + "; this reader is major " + schema.major(), schema, null);
        }
        int minor = table.minor();
        if (schema.compat() == Compat.STRICT && minor != schema.minor()) {
            throw new IncompatiblePayloadException(IncompatiblePayloadException.Reason.MINOR_MISMATCH, what
                    + " is STRICT v" + table.version() + "; this reader is v" + schema.version(), schema, null);
        }
        if (schema.compat() == Compat.BACKWARD && minor > schema.minor()) {
            throw new IncompatiblePayloadException(IncompatiblePayloadException.Reason.NEWER_MINOR, what + " is v"
                    + table.version() + ", newer than this reader's v" + schema.version(), schema, null);
        }
        return readTextRow(t, minor);
    }

    // ---- generated ---------------------------------------------------------------------------------------------

    /** Writes the body of {@code value}: bitset, then field values. */
    protected abstract void writeBody(IbWriter w, T value);

    /**
     * Reads a body written by minor version {@code minor} of this schema. Fields newer than {@code minor} take their
     * defaults; for FULL schemas {@code minor} may exceed this codec's, and unknown trailing fields are left unread.
     */
    protected abstract T readBody(IbReader r, int minor);

    /** Writes the cells of {@code value}'s row: one value per field, nulls as empty cells. */
    protected abstract void writeText(TextWriter t, T value);

    /** Reads a row written by minor version {@code minor}: its columns have been checked against {@link #columns}. */
    protected abstract T readText(TextReader t, int minor);
}
