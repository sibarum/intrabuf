package sibarum.intrabuf;

import sibarum.intrabuf.IncompatiblePayloadException.Reason;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A payload in intrabuf's text format, split into tables, rows and cells but not yet typed: what a
 * {@link TextReader} reads values from. The format is documented in {@code docs/text-format.md}; in brief:
 *
 * <pre>
 *   #intrabuf 1 example.Customer 1.2 BACKWARD id=0x5d1c6a02 fp=0xe95fde79 snapshot
 *   id,name,home,tags,photo
 *   42,Ada Lovelace,&amp;1,[vip,early-adopter],$1
 *
 *   #message example.Address 1.1
 *   &amp;,street,city
 *   &amp;1,12 St James's Square,London
 *
 *   #blobs
 *   $1,iVBORw0KGgo=
 * </pre>
 *
 * The first line carries the same facts as a binary header. Then comes the root message as a one-row table, then a
 * table per nested message type whose rows are numbered ({@code &n}) and referenced from cells, then every
 * {@code byte[]} value as base64 ({@code $n}). An application header ({@code #header}) goes before the root table.
 *
 * <p>A document is single use: decoding marks each row and blob as it is referenced, so that a row referenced twice
 * (which would make a cycle possible) or never (usually a mistyped reference) is an error.
 */
public final class TextDocument {

    /** How a text payload starts; its first byte can never begin a binary payload with the built-in header. */
    public static final String MAGIC = "#intrabuf";
    /** The text format version, the first token after {@link #MAGIC}. */
    public static final int VERSION = 1;

    private static final byte[] PREFIX = (MAGIC + " ").getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    // ---- the parse tree ----------------------------------------------------------------------------------------

    /** A cell, or an element of a list or map. */
    public sealed interface Node {
        /** The line the node starts on, for error messages. */
        int line();
    }

    /** A scalar as written: bare ({@code 42}, {@code &1}) or quoted ({@code "a, b"}, a backtick block). */
    public record Atom(String text, boolean quoted, int line) implements Node {
    }

    /** Nothing between two separators: a null, an absent optional. */
    public record Empty(int line) implements Node {
    }

    /** {@code [a,b]}, or {@code {k=v,k=v}} as keys and values alternating. */
    public record Seq(List<Node> items, boolean map, int line) implements Node {
    }

    /**
     * One table: the root message, an application header, or one nested message type at one version.
     *
     * @param kind  {@code "root"}, {@code "header"} or {@code "message"}
     * @param major -1 where the document does not say (root and header tables)
     */
    public record Table(String kind, String name, int major, int minor, List<String> columns, int line) {
        public String version() {
            return major + "." + minor;
        }
    }

    /** @param ref the row's number, 0 for the root and header rows */
    public record Row(int ref, Table table, List<Node> cells, int line) {
    }

    // ---- state -------------------------------------------------------------------------------------------------

    private final String s;
    private int pos;
    private int line = 1;

    private String schemaName;
    private int major;
    private int minor;
    private Compat compat;
    private Integer id;
    private Integer fingerprint;
    private boolean snapshot;

    private Row header;
    private Row root;
    private final Map<Integer, Row> rows = new HashMap<>();
    private final Map<Integer, byte[]> blobs = new HashMap<>();
    private final BitSet usedRows = new BitSet();
    private final BitSet usedBlobs = new BitSet();

    private TextDocument(String s) {
        this.s = s;
    }

    /** Whether {@code payload} is in the text format, judged from its first bytes. */
    public static boolean isText(byte[] payload, int off, int len) {
        int skip = startsWith(payload, off, len, BOM) ? BOM.length : 0;
        return startsWith(payload, off + skip, len - skip, PREFIX);
    }

    private static boolean startsWith(byte[] b, int off, int len, byte[] prefix) {
        if (len < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[off + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    public static TextDocument parse(byte[] payload, int off, int len) {
        return parse(new String(payload, off, len, StandardCharsets.UTF_8));
    }

    /** Parses a whole document. Malformed text throws {@link IntrabufException}, naming the line. */
    public static TextDocument parse(String text) {
        TextDocument d = new TextDocument(text);
        d.parseDocument();
        return d;
    }

    // ---- what was parsed ---------------------------------------------------------------------------------------

    /** The schema name on the first line. */
    public String schemaName() {
        return schemaName;
    }

    /** The {@code id=} on the first line, or null if the document leaves the id to be found from the name. */
    public Integer id() {
        return id;
    }

    /** The first line's facts, as a binary header would carry them; {@code idIfAbsent} if it gives no id. */
    public Header facts(int idIfAbsent) {
        return new Header(Header.WIRE_VERSION, snapshot, compat, id != null ? id : idIfAbsent, major, minor,
                fingerprint != null, fingerprint != null ? fingerprint : 0, -1);
    }

    /** The {@code #header} table's row, or null if the document has none. */
    public Row header() {
        return header;
    }

    public Row root() {
        return root;
    }

    /** Takes row {@code &ref}, which must exist and not have been taken before. */
    public Row useRow(int ref, int line) {
        Row r = rows.get(ref);
        if (r == null) {
            throw error(line, "&" + ref + " refers to no row");
        }
        if (usedRows.get(ref)) {
            throw error(line, "&" + ref + " is referenced more than once; every row belongs to exactly one place");
        }
        usedRows.set(ref);
        return r;
    }

    /** Takes blob {@code $ref}, which must exist and not have been taken before. */
    public byte[] useBlob(int ref, int line) {
        byte[] b = blobs.get(ref);
        if (b == null) {
            throw error(line, "$" + ref + " refers to no blob");
        }
        if (usedBlobs.get(ref)) {
            throw error(line, "$" + ref + " is referenced more than once");
        }
        usedBlobs.set(ref);
        return b;
    }

    /**
     * Marks every row and blob reachable from {@code n} as used, without typing it: for cells a reader skips, which
     * hold fields from a newer FULL minor.
     */
    public void skip(Node n) {
        skip(n, 0);
    }

    private void skip(Node n, int depth) {
        if (depth > IbReader.MAX_DEPTH) {
            throw error(n.line(), "messages nested deeper than " + IbReader.MAX_DEPTH);
        }
        switch (n) {
            case Atom a when !a.quoted() && a.text().length() > 1 && isDigits(a.text(), 1) -> {
                if (a.text().charAt(0) == '&') {
                    for (Node c : useRow(ref(a), a.line()).cells()) {
                        skip(c, depth + 1);
                    }
                } else if (a.text().charAt(0) == '$') {
                    useBlob(ref(a), a.line());
                }
            }
            case Seq q -> {
                for (Node c : q.items()) {
                    skip(c, depth + 1);
                }
            }
            default -> {
            }
        }
    }

    /** Throws if any row or blob was never referenced. Call after decoding the root. */
    public void checkAllUsed() {
        for (Row r : rows.values()) {
            if (!usedRows.get(r.ref())) {
                throw error(r.line(), "row &" + r.ref() + " (" + r.table().name() + ") is never referenced");
            }
        }
        for (int ref : blobs.keySet()) {
            if (!usedBlobs.get(ref)) {
                throw error(-1, "blob $" + ref + " is never referenced");
            }
        }
    }

    static int ref(Atom a) {
        try {
            return Integer.parseInt(a.text(), 1, a.text().length(), 10);
        } catch (NumberFormatException e) {
            throw error(a.line(), "reference " + a.text() + " is out of range");
        }
    }

    private static boolean isDigits(String t, int from) {
        for (int i = from; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return t.length() > from;
    }

    static IntrabufException error(int line, String msg) {
        return new IntrabufException("text payload" + (line > 0 ? " line " + line : "") + ": " + msg);
    }

    // ---- parsing: lines and tables -----------------------------------------------------------------------------

    private void parseDocument() {
        if (s.startsWith("﻿")) {
            pos = 1;
        }
        parseFacts(readLine());
        if (lineStartsWith("#header")) {
            int at = line;
            String[] marker = tokens(readLine());
            if (marker.length != 2 || !marker[0].equals("#header")) {
                throw error(at, "expected '#header <name>'");
            }
            Table t = new Table("header", marker[1], -1, 0, columns(readLine(), false, line - 1), at);
            header = row(t, false);
        }
        int at = line;
        Table t = new Table("root", schemaName, major, minor, columns(readLine(), false, at), at);
        root = row(t, false);
        while (pos < s.length()) {
            if (skipBlankOrComment()) {
                continue;
            }
            if (lineStartsWith("#message")) {
                section();
            } else if (lineStartsWith("#blobs")) {
                blobSection();
            } else {
                throw error(line, "expected a '#message' or '#blobs' section, found '" + abbreviate(peekLine()) + "'");
            }
        }
    }

    private void parseFacts(String first) {
        String[] t = tokens(first);
        if (t.length == 0 || !t[0].equals(MAGIC)) {
            throw new IncompatiblePayloadException(Reason.NOT_INTRABUF, "text payload does not start with '" + MAGIC + "'",
                    null, null);
        }
        if (t.length < 2 || !isDigits(t[1], 0) || t[1].length() > 6 || Integer.parseInt(t[1]) != VERSION) {
            throw new IncompatiblePayloadException(Reason.UNSUPPORTED_WIRE_VERSION, "text format version "
                    + (t.length < 2 ? "missing" : "'" + t[1] + "'") + "; this runtime reads version " + VERSION, null, null);
        }
        if (t.length < 5) {
            throw error(1, "expected '" + MAGIC + " " + VERSION + " <schema> <major>.<minor> <STRICT|BACKWARD|FULL>'");
        }
        schemaName = t[2];
        int[] v = version(t[3], 1);
        major = v[0];
        minor = v[1];
        compat = compat(t[4], 1);
        for (int i = 5; i < t.length; i++) {
            String k = t[i];
            if (k.equals("snapshot")) {
                snapshot = true;
            } else if (k.startsWith("id=")) {
                id = hex(k.substring(3), "id");
            } else if (k.startsWith("fp=")) {
                fingerprint = hex(k.substring(3), "fp");
            } else {
                throw new IncompatiblePayloadException(Reason.UNSUPPORTED_WIRE_VERSION, "text payload line 1: '" + k
                        + "' is not a fact this runtime knows (id=, fp=, snapshot)", null, null);
            }
        }
    }

    private static int hex(String v, String what) {
        try {
            if (!v.startsWith("0x") && !v.startsWith("0X")) {
                throw new NumberFormatException();
            }
            return Hex.parseInt32(v);
        } catch (NumberFormatException e) {
            throw error(1, what + "=" + v + " is not a 32-bit hex number like 0x0a1b2c3d");
        }
    }

    private static int[] version(String v, int line) {
        int dot = v.indexOf('.');
        if (dot < 0 || !isDigits(v.substring(0, dot), 0) || !isDigits(v.substring(dot + 1), 0)
                || dot > 9 || v.length() - dot > 10) {
            throw error(line, "version '" + v + "' is not <major>.<minor>");
        }
        return new int[]{Integer.parseInt(v, 0, dot, 10), Integer.parseInt(v, dot + 1, v.length(), 10)};
    }

    private static Compat compat(String c, int line) {
        for (Compat x : Compat.values()) {
            if (x.name().equals(c)) {
                return x;
            }
        }
        throw error(line, "'" + c + "' is not a compat mode (STRICT, BACKWARD, FULL)");
    }

    private void section() {
        int at = line;
        String[] marker = tokens(readLine());
        if (marker.length != 3) {
            throw error(at, "expected '#message <name> <major>.<minor>'");
        }
        int[] v = version(marker[2], at);
        int colLine = line;
        Table t = new Table("message", marker[1], v[0], v[1], columns(readLine(), true, colLine), at);
        while (pos < s.length()) {
            if (skipBlankOrComment()) {
                continue;
            }
            if (peek() != '&') {
                break;
            }
            Row r = row(t, true);
            if (rows.putIfAbsent(r.ref(), r) != null) {
                throw error(r.line(), "row &" + r.ref() + " is defined twice");
            }
        }
    }

    private void blobSection() {
        readLine();
        while (pos < s.length()) {
            if (skipBlankOrComment()) {
                continue;
            }
            if (peek() != '$') {
                break;
            }
            int at = line;
            String l = readLine();
            int comma = l.indexOf(',');
            if (comma < 2 || !isDigits(l.substring(1, comma), 0) || comma > 10) {
                throw error(at, "expected '$<n>,<base64>'");
            }
            int ref = Integer.parseInt(l, 1, comma, 10);
            byte[] b;
            try {
                b = Base64.getDecoder().decode(l.substring(comma + 1).strip());
            } catch (IllegalArgumentException e) {
                throw error(at, "blob $" + ref + " is not valid base64: " + e.getMessage());
            }
            if (blobs.putIfAbsent(ref, b) != null) {
                throw error(at, "blob $" + ref + " is defined twice");
            }
        }
    }

    /** A columns line. Message sections start theirs with {@code &}, the column of row numbers. */
    private static List<String> columns(String l, boolean refColumn, int at) {
        List<String> out = new ArrayList<>();
        String[] parts = l.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            String c = parts[i].strip();
            if (i == 0 && refColumn) {
                if (!c.equals("&")) {
                    throw error(at, "a #message section's columns start with '&'");
                }
                continue;
            }
            if (c.isEmpty() && parts.length == 1) {
                break; // no fields
            }
            if (c.isEmpty() || !c.chars().allMatch(ch -> Character.isJavaIdentifierPart(ch))) {
                throw error(at, "column " + (i + 1) + " ('" + c + "') is not a field name");
            }
            out.add(c);
        }
        return List.copyOf(out);
    }

    private Row row(Table t, boolean numbered) {
        if (pos >= s.length()) {
            throw error(line, "the document ends where a row of " + t.name() + " should be");
        }
        int at = line;
        int ref = 0;
        if (numbered) {
            pos++; // '&'
            int from = pos;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == from || pos - from > 9) {
                throw error(at, "a row starts with '&<n>'");
            }
            ref = Integer.parseInt(s, from, pos, 10);
            if (ref == 0) {
                throw error(at, "row numbers start at 1");
            }
        }
        List<Node> cells = new ArrayList<>(t.columns().size());
        if (numbered && atEndOfLine()) {
            endLine();
        } else {
            if (numbered) {
                skipWs();
                if (peek() != ',') {
                    throw error(line, "expected ',' after &" + ref);
                }
                pos++;
            }
            while (true) {
                cells.add(value(0));
                skipWs();
                if (atEndOfLine()) {
                    endLine();
                    break;
                }
                if (peek() != ',') {
                    throw error(line, "expected ',' between cells, found '" + peek() + "'");
                }
                pos++;
            }
        }
        if (cells.size() == 1 && cells.getFirst() instanceof Empty && t.columns().isEmpty()) {
            cells.clear(); // an empty line, for a message with no fields
        }
        if (cells.size() != t.columns().size()) {
            throw error(at, (numbered ? "row &" + ref : t.name() + " row") + " has " + cells.size()
                    + " cell(s); its table has " + t.columns().size() + " column(s): " + String.join(",", t.columns()));
        }
        return new Row(ref, t, List.copyOf(cells), at);
    }

    // ---- parsing: values ---------------------------------------------------------------------------------------

    private Node value(int depth) {
        if (depth > IbReader.MAX_DEPTH) {
            throw error(line, "lists and maps nested deeper than " + IbReader.MAX_DEPTH);
        }
        skipWs();
        int at = line;
        if (pos >= s.length()) {
            return new Empty(at);
        }
        char c = s.charAt(pos);
        switch (c) {
            case ',', ']', '}', '=', '\n', '\r' -> {
                return new Empty(at);
            }
            case '[' -> {
                pos++;
                return seq(false, ']', depth, at);
            }
            case '{' -> {
                pos++;
                return seq(true, '}', depth, at);
            }
            case '"' -> {
                return quoted(at);
            }
            case '`' -> {
                return backtick(at);
            }
            default -> {
                return bare(at);
            }
        }
    }

    private Seq seq(boolean map, char close, int depth, int at) {
        List<Node> items = new ArrayList<>();
        skipWs();
        if (pos < s.length() && s.charAt(pos) == close) {
            pos++;
            return new Seq(List.of(), map, at);
        }
        while (true) {
            items.add(value(depth + 1));
            skipWs();
            if (map) {
                if (pos >= s.length() || s.charAt(pos) != '=') {
                    throw error(line, "expected '=' after a map key");
                }
                pos++;
                items.add(value(depth + 1));
                skipWs();
            }
            if (pos >= s.length() || atEndOfLine()) {
                throw error(at, "unclosed '" + (map ? '{' : '[') + "'");
            }
            char c = s.charAt(pos++);
            if (c == close) {
                return new Seq(List.copyOf(items), map, at);
            }
            if (c != ',') {
                throw error(line, "expected ',' or '" + close + "', found '" + c + "'");
            }
        }
    }

    private Atom bare(int at) {
        int from = pos;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ',' || c == ']' || c == '}' || c == '=' || c == '\n' || c == '\r') {
                break;
            }
            if (c == '"' || c == '`' || c == '[' || c == '{' || c == '\\') {
                throw error(line, "'" + c + "' inside an unquoted value; quote the value with \"...\"");
            }
            pos++;
        }
        int to = pos;
        while (to > from && (s.charAt(to - 1) == ' ' || s.charAt(to - 1) == '\t')) {
            to--;
        }
        return new Atom(s.substring(from, to), false, at);
    }

    private Atom quoted(int at) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length() || s.charAt(pos) == '\n' || s.charAt(pos) == '\r') {
                throw error(at, "unterminated \"...\"; a value that spans lines goes in `backticks`");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return new Atom(sb.toString(), true, at);
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw error(at, "unterminated \"...\"");
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"', '\\' -> sb.append(e);
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > s.length()) {
                        throw error(line, "\\u needs four hex digits");
                    }
                    int cp = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos++), 16);
                        if (d < 0) {
                            throw error(line, "\\u needs four hex digits");
                        }
                        cp = (cp << 4) | d;
                    }
                    sb.append((char) cp);
                }
                default -> throw error(line, "unknown escape \\" + e + " (known: \\\" \\\\ \\n \\r \\t \\uXXXX)");
            }
        }
    }

    /** Verbatim text between backticks, which may span lines; a doubled backtick is a literal one. */
    private Atom backtick(int at) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error(at, "unterminated `...`");
            }
            char c = s.charAt(pos++);
            if (c == '`') {
                if (pos < s.length() && s.charAt(pos) == '`') {
                    pos++;
                    sb.append('`');
                    continue;
                }
                return new Atom(sb.toString(), true, at);
            }
            if (c == '\r' && pos < s.length() && s.charAt(pos) == '\n') {
                continue; // a CRLF document: the line break is the \n
            }
            if (c == '\n') {
                line++;
            }
            sb.append(c);
        }
    }

    // ---- parsing: characters -----------------------------------------------------------------------------------

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\n';
    }

    private void skipWs() {
        while (pos < s.length() && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) {
            pos++;
        }
    }

    private boolean atEndOfLine() {
        return pos >= s.length() || s.charAt(pos) == '\n' || s.charAt(pos) == '\r';
    }

    private void endLine() {
        if (pos < s.length() && s.charAt(pos) == '\r') {
            pos++;
        }
        if (pos < s.length() && s.charAt(pos) == '\n') {
            pos++;
        }
        line++;
    }

    private String peekLine() {
        int end = s.indexOf('\n', pos);
        String l = s.substring(pos, end < 0 ? s.length() : end);
        return l.endsWith("\r") ? l.substring(0, l.length() - 1) : l;
    }

    private String readLine() {
        if (pos >= s.length()) {
            throw error(line, "the document ends early");
        }
        String l = peekLine();
        pos = Math.min(s.length(), pos + l.length());
        endLine();
        return l;
    }

    private boolean lineStartsWith(String marker) {
        if (!s.startsWith(marker, pos)) {
            return false;
        }
        int after = pos + marker.length();
        return after >= s.length() || s.charAt(after) == ' ' || s.charAt(after) == '\t' || s.charAt(after) == '\n'
                || s.charAt(after) == '\r';
    }

    /** Skips one blank or comment line ({@code #} alone or followed by a space), returning whether it did. */
    private boolean skipBlankOrComment() {
        String l = peekLine();
        String t = l.strip();
        if (t.isEmpty() || t.equals("#") || t.startsWith("# ") || t.startsWith("#\t")) {
            readLine();
            return true;
        }
        return false;
    }

    private static String[] tokens(String l) {
        String t = l.strip();
        return t.isEmpty() ? new String[0] : t.split("[ \t]+");
    }

    private static String abbreviate(String l) {
        return l.length() > 40 ? l.substring(0, 40) + "..." : l;
    }
}
