package sibarum.intrabuf;

import sibarum.intrabuf.TextDocument.Atom;
import sibarum.intrabuf.TextDocument.Empty;
import sibarum.intrabuf.TextDocument.Node;
import sibarum.intrabuf.TextDocument.Row;
import sibarum.intrabuf.TextDocument.Seq;
import sibarum.intrabuf.TextDocument.Table;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Reads the values of one row of a {@link TextDocument}, in the order {@link TextWriter} wrote them. Generated
 * codecs call it the way they call {@link IbReader}: {@link #readNull()} where the binary format has a presence
 * bit, {@link #beginList()}/{@link #beginMap()} where it has a count. Every failure is an
 * {@link IntrabufException} naming the line and the column.
 */
public final class TextReader {

    private final TextDocument doc;
    private final Header facts;
    private final Row row;
    private final int depth;

    private List<Node> items;
    private int idx;
    private List<?>[] stackItems = new List<?>[4];
    private int[] stackIdx = new int[4];
    private int sp;

    /** A reader for {@code row}, or for no cells at all if it is null. */
    public TextReader(TextDocument doc, Header facts, Row row) {
        this(doc, facts, row, 0);
    }

    private TextReader(TextDocument doc, Header facts, Row row, int depth) {
        this.doc = doc;
        this.facts = facts;
        this.row = row;
        this.depth = depth;
        this.items = row == null ? List.of() : row.cells();
    }

    public TextDocument document() {
        return doc;
    }

    /** The facts on the document's first line, which an application header's role fields are read from. */
    public Header facts() {
        return facts;
    }

    public Row row() {
        return row;
    }

    // ---- rows --------------------------------------------------------------------------------------------------

    /**
     * Checks that {@code t} has the columns a reader expects: exactly {@code expected}, or, when
     * {@code extrasAllowed} (a FULL payload newer than the reader), {@code expected} followed by others.
     */
    public static void checkColumns(Table t, List<String> expected, boolean extrasAllowed) {
        List<String> got = t.columns();
        boolean ok = extrasAllowed ? got.size() >= expected.size() && got.subList(0, expected.size()).equals(expected)
                : got.equals(expected);
        if (!ok) {
            throw TextDocument.error(t.line(), t.name() + " v" + t.version() + " has columns " + String.join(",", got)
                    + "; expected " + String.join(",", expected) + (extrasAllowed ? ",..." : ""));
        }
    }

    /** Reads a reference to a row of a nested message and returns a reader for that row. */
    public TextReader readMessage(String type) {
        Atom a = atom("a reference to a " + type + " row (&n)");
        if (a.quoted() || a.text().length() < 2 || a.text().charAt(0) != '&') {
            throw error(a, "expected a reference to a " + type + " row (&n), found " + show(a));
        }
        Row r = doc.useRow(refNumber(a), a.line());
        if (!r.table().name().equals(type)) {
            throw error(a, "&" + r.ref() + " is a row of " + r.table().name() + "; this field holds " + type);
        }
        if (depth + 1 > IbReader.MAX_DEPTH) {
            throw error(a, "messages nested deeper than " + IbReader.MAX_DEPTH);
        }
        return new TextReader(doc, facts, r, depth + 1);
    }

    /**
     * Finishes the row. Cells past the ones read are fields from a newer FULL minor; the rows and blobs they refer
     * to are marked used.
     */
    public void endRow() {
        if (sp != 0) {
            throw new IllegalStateException("row ended inside a list or map");
        }
        for (; idx < items.size(); idx++) {
            doc.skip(items.get(idx));
        }
    }

    // ---- structure ---------------------------------------------------------------------------------------------

    /** Whether the next value is absent (an empty cell); consumes it if so. */
    public boolean readNull() {
        if (idx < items.size() && items.get(idx) instanceof Empty) {
            idx++;
            return true;
        }
        return false;
    }

    /** Opens a list and returns its element count; read that many elements, then {@link #endList()}. */
    public int beginList() {
        return open(false);
    }

    public void endList() {
        close();
    }

    /** Opens a map and returns its entry count; read that many keys and values, alternating, then {@link #endMap()}. */
    public int beginMap() {
        return open(true) / 2;
    }

    public void endMap() {
        close();
    }

    private int open(boolean map) {
        Node n = next(map ? "a map {k=v,...}" : "a list [...]");
        if (!(n instanceof Seq q) || q.map() != map) {
            throw error(n, "expected " + (map ? "a map {k=v,...}" : "a list [...]") + ", found " + show(n));
        }
        if (sp == stackItems.length) {
            stackItems = Arrays.copyOf(stackItems, sp * 2);
            stackIdx = Arrays.copyOf(stackIdx, sp * 2);
        }
        stackItems[sp] = items;
        stackIdx[sp] = idx;
        sp++;
        items = q.items();
        idx = 0;
        return q.items().size();
    }

    @SuppressWarnings("unchecked")
    private void close() {
        sp--;
        items = (List<Node>) stackItems[sp];
        idx = stackIdx[sp];
        stackItems[sp] = null;
    }

    // ---- scalar kinds ------------------------------------------------------------------------------------------

    public boolean readBool() {
        Atom a = atom("true or false");
        return switch (a.text()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw error(a, "expected true or false, found " + show(a));
        };
    }

    public byte readInt8() {
        return (byte) integer("an int8", Byte.MIN_VALUE, Byte.MAX_VALUE);
    }

    public short readInt16() {
        return (short) integer("an int16", Short.MIN_VALUE, Short.MAX_VALUE);
    }

    public int readInt32() {
        return (int) integer("an int32", Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public long readInt64() {
        return integer("an int64", Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public int readFixed32() {
        return readInt32();
    }

    public long readFixed64() {
        return readInt64();
    }

    private long integer(String what, long min, long max) {
        Atom a = atom(what);
        long v;
        try {
            v = Long.parseLong(a.text());
        } catch (NumberFormatException e) {
            throw error(a, "expected " + what + ", found " + show(a));
        }
        if (v < min || v > max) {
            throw error(a, a.text() + " is out of range for " + what);
        }
        return v;
    }

    public float readFloat32() {
        Atom a = atom("a float32");
        try {
            return Float.parseFloat(a.text());
        } catch (NumberFormatException e) {
            throw error(a, "expected a float32, found " + show(a));
        }
    }

    public double readFloat64() {
        Atom a = atom("a float64");
        try {
            return Double.parseDouble(a.text());
        } catch (NumberFormatException e) {
            throw error(a, "expected a float64, found " + show(a));
        }
    }

    public char readChar() {
        Atom a = text("a char");
        if (a.text().length() != 1) {
            throw error(a, "expected a single char, found " + show(a));
        }
        return a.text().charAt(0);
    }

    public String readString() {
        return text("a string").text();
    }

    public byte[] readBytes() {
        Atom a = atom("a blob reference ($n)");
        if (a.quoted() || a.text().length() < 2 || a.text().charAt(0) != '$') {
            throw error(a, "bytes are written as a reference to the #blobs section ($n), found " + show(a));
        }
        return doc.useBlob(refNumber(a), a.line()).clone();
    }

    public UUID readUuid() {
        Atom a = atom("a uuid");
        try {
            if (a.text().length() != 36) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(a.text());
        } catch (IllegalArgumentException e) {
            throw error(a, "expected a uuid (8-4-4-4-12 hex digits), found " + show(a));
        }
    }

    public Instant readInstant() {
        Atom a = atom("an instant");
        try {
            return Instant.parse(a.text());
        } catch (DateTimeParseException e) {
            throw error(a, "expected an ISO-8601 instant like 2026-10-06T12:00:00Z, found " + show(a));
        }
    }

    /** An enum constant, by name. */
    public <E extends Enum<E>> E readEnum(E[] constants, String type) {
        Atom a = atom("a constant of " + type);
        for (E e : constants) {
            if (e.name().equals(a.text())) {
                return e;
            }
        }
        throw error(a, show(a) + " is not a constant of " + type + " " + Arrays.toString(constants)
                + "; a newer writer may have added constants");
    }

    /** An enum constant's name, for readers that have no enum class. */
    public String readEnumName(String type) {
        return atom("a constant of " + type).text();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private Node next(String what) {
        if (idx >= items.size()) {
            throw TextDocument.error(row == null ? -1 : row.line(), where() + ": expected " + what + ", found nothing");
        }
        return items.get(idx++);
    }

    private Atom atom(String what) {
        Node n = next(what);
        if (n instanceof Atom a) {
            return a;
        }
        throw error(n, n instanceof Empty ? "empty, but this value is not nullable; expected " + what
                : "expected " + what + ", found " + show(n));
    }

    /** A string or char. Unquoted, it must not look like a reference. */
    private Atom text(String what) {
        Atom a = atom(what);
        if (!a.quoted() && !a.text().isEmpty() && (a.text().charAt(0) == '&' || a.text().charAt(0) == '$')) {
            throw error(a, show(a) + " looks like a reference; quote it (\"" + a.text() + "\") if it is " + what);
        }
        return a;
    }

    private int refNumber(Atom a) {
        String t = a.text();
        for (int i = 1; i < t.length(); i++) {
            if (t.charAt(i) < '0' || t.charAt(i) > '9') {
                throw error(a, "malformed reference " + show(a));
            }
        }
        return TextDocument.ref(a);
    }

    private String where() {
        if (row == null) {
            return "header";
        }
        Table t = row.table();
        // The column of the cell being read: the top-level index, wherever inside it we are.
        int cell = (sp == 0 ? idx : stackIdx[0]) - 1;
        String col = cell >= 0 && cell < t.columns().size() ? "." + t.columns().get(cell) : "";
        return (row.ref() > 0 ? "&" + row.ref() + " " : "") + t.name() + col;
    }

    private IntrabufException error(Node n, String msg) {
        return TextDocument.error(n.line(), where() + ": " + msg);
    }

    private static String show(Node n) {
        return switch (n) {
            case Atom a -> a.quoted() ? "\"" + abbreviate(a.text()) + "\"" : "'" + abbreviate(a.text()) + "'";
            case Empty e -> "an empty value";
            case Seq q -> q.map() ? "a map" : "a list";
        };
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
