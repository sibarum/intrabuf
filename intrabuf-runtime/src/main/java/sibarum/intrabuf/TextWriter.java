package sibarum.intrabuf;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a payload in the text format (see {@link TextDocument}). Generated codecs call it the way they call
 * {@link IbWriter}: one {@code write<Kind>} per value, in field order, with {@link #writeNull()} for an absent value.
 * Separators are the writer's business: a value written at the top of a row becomes the next cell, one written
 * inside {@link #beginList()} the next element, and inside {@link #beginMap()} keys and values alternate.
 *
 * <p>A nested message becomes a reference ({@code &n}) in its cell and a row in its type's table, written after
 * the row that refers to it. A {@code byte[]} becomes {@code $n} and a base64 line in the {@code #blobs} section.
 */
public final class TextWriter {

    /** The table rows of one message type, at one version, go in. */
    public record Section(String name, int major, int minor, List<String> columns) {
        public Section {
            columns = List.copyOf(columns);
        }
    }

    private record Pending(Section section, int ref, Runnable body) {
    }

    private final StringBuilder doc = new StringBuilder(256);
    private StringBuilder out = doc;
    private final Map<Section, StringBuilder> sections = new LinkedHashMap<>();
    private final StringBuilder blobs = new StringBuilder();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private int nextRef = 1;
    private int nextBlob = 1;

    /** Values written at each open level: 0 is the row, then each open list or map. */
    private int[] counts = new int[8];
    private boolean[] maps = new boolean[8];
    private int depth;

    /** Starts a document for a payload of schema {@code schemaName} whose header carries {@code facts}. */
    public TextWriter(String schemaName, Header facts) {
        doc.append(TextDocument.MAGIC).append(' ').append(TextDocument.VERSION).append(' ').append(schemaName)
                .append(' ').append(facts.version()).append(' ').append(facts.compat())
                .append(" id=").append(Hex.int32(facts.schemaId()));
        if (facts.hasFingerprint()) {
            doc.append(" fp=").append(Hex.int32(facts.fingerprint()));
        }
        if (facts.snapshot()) {
            doc.append(" snapshot");
        }
        doc.append('\n');
    }

    /** Writes an application header's table: its name, its columns, and the one row {@code body} writes. */
    public void header(String name, List<String> columns, Runnable body) {
        doc.append("#header ").append(name).append('\n');
        table(columns, body);
    }

    /** Writes the root message's table: its columns, and the one row {@code body} writes. */
    public void root(List<String> columns, Runnable body) {
        table(columns, body);
    }

    private void table(List<String> columns, Runnable body) {
        doc.append(String.join(",", columns)).append('\n');
        out = doc;
        beginRow(0);
        body.run();
        endRow();
    }

    /** The finished document: everything written, then the rows of nested messages, then the blobs. */
    public String finish() {
        while (!pending.isEmpty()) {
            Pending p = pending.poll();
            out = sections.computeIfAbsent(p.section(), s -> new StringBuilder()
                    .append("#message ").append(s.name()).append(' ').append(s.major()).append('.').append(s.minor())
                    .append('\n').append('&').append(s.columns().isEmpty() ? "" : ",")
                    .append(String.join(",", s.columns())).append('\n'));
            out.append('&').append(p.ref());
            beginRow(1);
            p.body().run();
            endRow();
        }
        StringBuilder sb = new StringBuilder(doc.length() + blobs.length() + 64 * sections.size());
        sb.append(doc);
        for (StringBuilder s : sections.values()) {
            sb.append('\n').append(s);
        }
        if (!blobs.isEmpty()) {
            sb.append("\n#blobs\n").append(blobs);
        }
        return sb.toString();
    }

    public byte[] finishBytes() {
        return finish().getBytes(StandardCharsets.UTF_8);
    }

    private void beginRow(int cells) {
        depth = 0;
        counts[0] = cells;
        maps[0] = false;
    }

    private void endRow() {
        if (depth != 0) {
            throw new IllegalStateException("row ended inside a list or map");
        }
        out.append('\n');
    }

    /** Emits whatever separates the next value from the one before it. */
    private void sep() {
        int n = counts[depth]++;
        if (n > 0) {
            out.append(maps[depth] && (n & 1) == 1 ? '=' : ',');
        }
    }

    // ---- structure ---------------------------------------------------------------------------------------------

    public void writeNull() {
        sep();
    }

    public void beginList() {
        sep();
        out.append('[');
        push(false);
    }

    public void endList() {
        depth--;
        out.append(']');
    }

    /** Opens a map; then write key, value, key, value, ... */
    public void beginMap() {
        sep();
        out.append('{');
        push(true);
    }

    public void endMap() {
        depth--;
        out.append('}');
    }

    private void push(boolean map) {
        if (++depth == counts.length) {
            counts = Arrays.copyOf(counts, depth * 2);
            maps = Arrays.copyOf(maps, depth * 2);
        }
        counts[depth] = 0;
        maps[depth] = map;
    }

    /**
     * Writes a nested message: a reference here, and a row of {@code section} that {@code body} writes once the
     * current row is finished.
     */
    public void writeMessage(Section section, Runnable body) {
        sep();
        int ref = nextRef++;
        out.append('&').append(ref);
        pending.add(new Pending(section, ref, body));
    }

    // ---- scalar kinds ------------------------------------------------------------------------------------------

    public void writeBool(boolean v) {
        sep();
        out.append(v);
    }

    public void writeInt8(byte v) {
        sep();
        out.append(v);
    }

    public void writeInt16(short v) {
        sep();
        out.append(v);
    }

    public void writeInt32(int v) {
        sep();
        out.append(v);
    }

    public void writeInt64(long v) {
        sep();
        out.append(v);
    }

    public void writeFixed32(int v) {
        writeInt32(v);
    }

    public void writeFixed64(long v) {
        writeInt64(v);
    }

    public void writeFloat32(float v) {
        sep();
        out.append(Float.toString(v));
    }

    public void writeFloat64(double v) {
        sep();
        out.append(Double.toString(v));
    }

    public void writeChar(char v) {
        sep();
        appendString(out, String.valueOf(v));
    }

    public void writeString(String v) {
        sep();
        appendString(out, v);
    }

    public void writeBytes(byte[] v) {
        sep();
        int ref = nextBlob++;
        out.append('$').append(ref);
        blobs.append('$').append(ref).append(',').append(Base64.getEncoder().encodeToString(v)).append('\n');
    }

    public void writeUuid(UUID v) {
        sep();
        out.append(v);
    }

    public void writeInstant(Instant v) {
        sep();
        out.append(v);
    }

    public void writeEnum(Enum<?> v) {
        writeEnum(v.name());
    }

    /** An enum constant by name, for writers that have no enum class. */
    public void writeEnum(String constant) {
        sep();
        out.append(constant);
    }

    // ---- strings -----------------------------------------------------------------------------------------------

    /**
     * A string as the shortest form that reads back exactly: bare if nothing in it could be mistaken for syntax,
     * in backticks if it spans lines and is otherwise plain text, else in double quotes with escapes.
     */
    static void appendString(StringBuilder sb, String s) {
        int form = form(s);
        if (form == BARE) {
            sb.append(s);
        } else if (form == BACKTICK) {
            sb.append('`');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                sb.append(c);
                if (c == '`') {
                    sb.append('`');
                }
            }
            sb.append('`');
        } else {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (invisible(s, i)) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }
    }

    private static final int BARE = 0;
    private static final int BACKTICK = 1;
    private static final int QUOTED = 2;

    private static int form(String s) {
        if (s.isEmpty()) {
            return QUOTED;
        }
        boolean bare = true;
        boolean lines = false;
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if (first == ' ' || first == '\t' || last == ' ' || last == '\t' || first == '&' || first == '$' || first == '#') {
            bare = false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n' -> {
                    lines = true;
                    bare = false;
                }
                case '\t' -> bare = false;
                case ',', '"', '`', '[', ']', '{', '}', '=', '\\' -> bare = false;
                default -> {
                    if (invisible(s, i)) {
                        return QUOTED;
                    }
                    if (Character.isHighSurrogate(c)) {
                        i++; // a valid pair, or invisible() would have said so
                    }
                }
            }
        }
        return bare ? BARE : lines ? BACKTICK : QUOTED;
    }

    /**
     * Whether the char at {@code i} must be escaped: controls (bar newline and tab, which have their own escapes),
     * line and paragraph separators and the BOM (which editors and transports rewrite), and unpaired surrogates
     * (which UTF-8 cannot carry).
     */
    private static boolean invisible(String s, int i) {
        char c = s.charAt(i);
        if (c < 0x20) {
            return c != '\n' && c != '\t';
        }
        if (c == 0x7F || c == ' ' || c == ' ' || c == '﻿') {
            return true;
        }
        if (Character.isHighSurrogate(c)) {
            return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
        }
        return false;
    }
}
