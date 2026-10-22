package sibarum.intrabuf.nanobuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Nanobuf as docs/nanobuf.md specifies it, written for clarity rather than length: what every one-line reader and
 * writer is held to.
 */
final class Reference {

    static final char US = 0x1F;
    static final char RS = 0x1E;

    private Reference() {
    }

    /** A file: the header for {@code schema} at {@code major.minor}, then {@code rows}, each record ended by RS and LF. */
    static byte[] write(String schema, int major, int minor, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        record(sb, List.of("nanobuf", "1", schema, major + "." + minor));
        for (List<String> row : rows) {
            record(sb, row);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void record(StringBuilder sb, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            String v = fields.get(i);
            if (v.indexOf(US) >= 0 || v.indexOf(RS) >= 0 || v.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("field " + i + " of " + fields.get(0) + " contains a control character");
            }
            if (i > 0) {
                sb.append(US);
            }
            sb.append(v);
        }
        sb.append(RS).append('\n');
    }

    /** The rows after the header, each as its fields; refuses another schema or major, or a file with no header. */
    static List<List<String>> read(byte[] file, String schema, int major) {
        String text = new String(file, StandardCharsets.UTF_8);
        int end = text.lastIndexOf(RS);
        String tail = dropLineBreak(text.substring(end + 1));
        if (!tail.isEmpty()) {
            throw new IllegalArgumentException("truncated: the last record has no record separator");
        }
        if (end < 0) {
            throw new IllegalArgumentException("no header");
        }
        List<List<String>> rows = new ArrayList<>();
        for (String r : text.substring(0, end).split(String.valueOf(RS), -1)) {
            rows.add(Arrays.asList(dropLineBreak(r).split(String.valueOf(US), -1)));
        }
        List<String> h = rows.removeFirst();
        if (h.size() < 4 || !h.get(0).equals("nanobuf") || !h.get(1).equals("1") || !h.get(2).equals(schema)
                || !h.get(3).split("\\.", -1)[0].equals(String.valueOf(major))) {
            throw new IllegalArgumentException("not " + schema + " v" + major + ": " + h);
        }
        return rows;
    }

    /** One line break off the front of a record: CR LF first, else a lone LF or CR. */
    private static String dropLineBreak(String r) {
        return r.startsWith("\r\n") ? r.substring(2) : r.startsWith("\n") || r.startsWith("\r") ? r.substring(1) : r;
    }
}
