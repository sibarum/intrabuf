package sibarum.intrabuf.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for manifests and CLI output, so neither the processor nor the native CLI needs a library.
 * Parses to {@code Map<String,Object>} (insertion-ordered), {@code List<Object>}, {@code String}, {@code Long},
 * {@code Double}, {@code Boolean} and {@code null}.
 */
public final class Json {

    private Json() {
    }

    // ---- writing -----------------------------------------------------------------------------------------------

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        quote(sb, s);
        return sb.toString();
    }

    public static void quote(StringBuilder sb, String s) {
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
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- parsing -----------------------------------------------------------------------------------------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            int line = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                }
            }
            return new IllegalArgumentException("JSON: " + msg + " at line " + line + " (offset " + i + ")");
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(i);
        }

        void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            i++;
        }

        Object value() {
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw error("unexpected token");
            }
            i += word.length();
            return v;
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                i++;
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = peek();
                i++;
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("short \\u escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw error("bad escape \\" + e);
                }
            }
        }

        Object number() {
            int from = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String n = s.substring(from, i);
            if (n.isEmpty()) {
                throw error("unexpected character '" + s.charAt(from) + "'");
            }
            try {
                if (n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0) {
                    return Double.parseDouble(n);
                }
                return Long.parseLong(n);
            } catch (NumberFormatException e) {
                throw error("bad number '" + n + "'");
            }
        }
    }
}
