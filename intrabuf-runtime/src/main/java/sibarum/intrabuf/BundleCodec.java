package sibarum.intrabuf;

import sibarum.intrabuf.schema.Fingerprint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class of every generated bundle codec. A bundle record {@code Trace} gets {@code TraceIntrabuf}, with a
 * singleton {@code CODEC}, a {@code Visitor} with one method per member, and a {@code Writer} with one method per
 * member.
 *
 * <p>A bundle is written as a text log (see {@code docs/bundle-format.md}):
 *
 * <pre>
 *   #intrabuf 1 app.Trace 1.0 BACKWARD id=0x1c0ffee5
 *   #member frames app.Frame 1.0
 *   frames,at,workMicros
 *   #member keys app.KeyPress 2.1
 *   keys,at,key
 *   frames,2026-10-11T10:00:00Z,412
 *   keys,2026-10-11T10:00:00.004Z,A
 * </pre>
 *
 * The facts line comes first and is checked as for any payload. Each member is declared once, with its message's
 * version and columns. Rows follow in any order, each naming its member, each standing alone: a row refers to no
 * other row and no blob. A writer can therefore append each row as it happens, and a reader can deliver each one as
 * it reads it.
 *
 * <p>A log may end with {@code #end}. A FULL log may also hold version boundaries, {@code #end 1.2}: a reader at
 * that minor or newer reads on, and any other reader stops there, at what is for it the end of the log. Nothing past
 * an end a reader stops at is read, so whatever a newer writer put there can't fail an older reader.
 *
 * <p>Binary bundles are not implemented yet.
 *
 * @param <B> the bundle record
 * @param <V> the generated visitor
 */
public abstract class BundleCodec<B, V> {

    /**
     * A member: a component of the bundle record.
     *
     * @param since the minor the member was added in
     */
    public record Member(String name, MessageCodec<?> codec, int since) {
    }

    private final SchemaInfo schema;
    private final List<Member> members;

    protected BundleCodec(SchemaInfo schema, Member[] members) {
        this.schema = schema;
        this.members = List.of(members);
    }

    public final SchemaInfo schema() {
        return schema;
    }

    /** The members, in declaration order: the order of the record's components. */
    public final List<Member> members() {
        return members;
    }

    // ---- writing -----------------------------------------------------------------------------------------------

    /** The whole bundle as a text log: every member's rows, member by member, in list order. */
    public final String encodeText(B bundle) {
        if (bundle == null) {
            throw new IntrabufException("cannot encode a null " + schema.name());
        }
        StringBuilder sb = new StringBuilder(256);
        LogWriter w = new LogWriter(this, sb);
        for (int i = 0; i < members.size(); i++) {
            List<?> rows = list(bundle, i);
            if (rows == null) {
                throw IntrabufException.nullField(schema.javaType() + "." + members.get(i).name());
            }
            for (Object row : rows) {
                w.write(i, row);
            }
        }
        return sb.toString();
    }

    public final byte[] encode(B bundle) {
        return encodeText(bundle).getBytes(StandardCharsets.UTF_8);
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    /** Decodes a whole log into the bundle record. Each member's list holds its rows in the order they were written. */
    public final B decodeText(String log) {
        List<?>[] lists = new List<?>[members.size()];
        for (int i = 0; i < lists.length; i++) {
            lists[i] = new ArrayList<>();
        }
        readLog(log, (member, row) -> add(lists[member], row));
        return create(lists);
    }

    /** Decodes a whole log, given as UTF-8. */
    public final B decode(byte[] payload) {
        return decodeText(text(payload));
    }

    /**
     * Reads a log, handing each row to {@code visitor} as soon as it is read; nothing else is kept. Returns the
     * facts on the log's first line.
     */
    public final Header read(String log, V visitor) {
        if (visitor == null) {
            throw new IllegalArgumentException("visitor is null");
        }
        return readLog(log, (member, row) -> dispatch(visitor, member, row));
    }

    /** Reads a log given as UTF-8; see {@link #read(String, Object)}. */
    public final Header read(byte[] payload, V visitor) {
        return read(text(payload), visitor);
    }

    private static String text(byte[] payload) {
        if (!TextDocument.isText(payload, 0, payload.length)) {
            throw new IntrabufException("not a text log; binary bundles are not implemented yet");
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void add(List<?> list, Object row) {
        ((List<Object>) list).add(row);
    }

    private interface RowHandler {
        void row(int member, Object value);
    }

    private Header readLog(String log, RowHandler handler) {
        LogReader r = new LogReader(handler);
        TextDocument.parseLog(log, r);
        return r.facts;
    }

    /** Checks the facts and each declaration as they are read, then decodes rows with their member's codec. */
    private final class LogReader implements TextDocument.LogSink {
        private final RowHandler handler;
        private TextDocument doc;
        private Header facts;
        private int minor;
        /** Per declared member name: its index and the minor its rows are at, or null to skip its rows. */
        private final java.util.Map<String, int[]> declared = new java.util.HashMap<>();

        LogReader(RowHandler handler) {
            this.handler = handler;
        }

        @Override
        public void facts(TextDocument d) {
            doc = d;
            // A hand-written log may leave out the id: it is then the one its schema name implies.
            int id = d.schemaName().equals(schema.name()) ? schema.id() : Fingerprint.schemaId(d.schemaName());
            facts = d.facts(id);
            minor = Compatibility.check(schema, facts);
            if (!d.schemaName().equals(schema.name())) {
                throw new IncompatiblePayloadException(IncompatiblePayloadException.Reason.SCHEMA_MISMATCH,
                        "text log is " + d.schemaName() + ", not " + schema.name(), schema, facts);
            }
        }

        @Override
        public void member(String name, TextDocument.Table table) {
            int index = indexOf(name);
            if (index < 0) {
                if (schema.compat() == Compat.FULL && minor > schema.minor()) {
                    declared.put(name, null); // a member from a newer minor: its rows are skipped
                    return;
                }
                throw TextDocument.error(table.line(), schema.name() + " has no member " + name);
            }
            Member m = members.get(index);
            if (schema.compat() != Compat.STRICT && m.since() > minor) {
                throw TextDocument.error(table.line(), "member " + name + " was added in minor " + m.since()
                        + ", but the log is minor " + minor);
            }
            if (!table.name().equals(m.codec().schema().name())) {
                throw TextDocument.error(table.line(), "member " + name + " holds " + m.codec().schema().name()
                        + ", not " + table.name());
            }
            int rowMinor = m.codec().checkTable(table, "member " + name + " (" + table.name() + " v" + table.version() + ")");
            TextReader.checkColumns(table, m.codec().columns(rowMinor), rowMinor > m.codec().schema().minor());
            declared.put(name, new int[]{index, rowMinor});
        }

        @Override
        public void row(String member, TextDocument.Row row) {
            int[] d = declared.get(member);
            if (d == null) {
                return;
            }
            MessageCodec<?> codec = members.get(d[0]).codec();
            TextReader t = new TextReader(doc, facts, row);
            Object value = codec.readTextAt(t, d[1]);
            t.endRow();
            handler.row(d[0], value);
        }

        @Override
        public boolean readsPast(int boundaryMinor) {
            return schema.minor() >= boundaryMinor;
        }

        private int indexOf(String name) {
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).name().equals(name)) {
                    return i;
                }
            }
            return -1;
        }
    }

    // ---- generated ---------------------------------------------------------------------------------------------

    /** The bundle record, from one list per member. */
    protected abstract B create(List<?>[] lists);

    /** Member {@code member}'s list in {@code bundle}. */
    protected abstract List<?> list(B bundle, int member);

    /** Hands {@code row}, a row of member {@code member}, to the visitor method for that member. */
    protected abstract void dispatch(V visitor, int member, Object row);
}
