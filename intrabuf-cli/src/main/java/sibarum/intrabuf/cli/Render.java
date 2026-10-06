package sibarum.intrabuf.cli;

import sibarum.intrabuf.dynamic.DynValue.DynEnum;
import sibarum.intrabuf.dynamic.DynValue.DynField;
import sibarum.intrabuf.dynamic.DynValue.DynList;
import sibarum.intrabuf.dynamic.DynValue.DynMap;
import sibarum.intrabuf.dynamic.DynValue.DynMessage;
import sibarum.intrabuf.schema.Json;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/** Renders decoded values as an indented tree for people, or as JSON for tools. */
final class Render {

    private static final int INLINE_WIDTH = 72;

    private Render() {
    }

    // ---- tree --------------------------------------------------------------------------------------------------

    static String tree(Object v) {
        StringBuilder sb = new StringBuilder();
        tree(sb, v, 0);
        return sb.toString();
    }

    private static void tree(StringBuilder sb, Object v, int indent) {
        switch (v) {
            case null -> sb.append("null");
            case DynMessage m -> {
                sb.append(m.type());
                if (m.minor() >= 0) {
                    sb.append(" @minor ").append(m.minor());
                }
                sb.append(" {\n");
                for (DynField f : m.fields()) {
                    pad(sb, indent + 1).append(f.name()).append(": ");
                    tree(sb, f.value(), indent + 1);
                    sb.append('\n');
                }
                if (m.unknownBytes() > 0) {
                    pad(sb, indent + 1).append("// ").append(m.unknownBytes())
                            .append(" byte(s) of fields from a newer minor, not in the loaded schema\n");
                }
                pad(sb, indent).append('}');
            }
            case DynList l -> {
                String open = l.set() ? "{" : "[";
                String close = l.set() ? "}" : "]";
                String inline = inline(l);
                if (inline != null) {
                    sb.append(inline);
                    return;
                }
                sb.append(open).append('\n');
                for (Object e : l.elements()) {
                    pad(sb, indent + 1);
                    tree(sb, e, indent + 1);
                    sb.append('\n');
                }
                pad(sb, indent).append(close);
            }
            case DynMap m -> {
                String inline = inline(m);
                if (inline != null) {
                    sb.append(inline);
                    return;
                }
                sb.append("{\n");
                for (Map.Entry<Object, Object> e : m.entries()) {
                    pad(sb, indent + 1);
                    tree(sb, e.getKey(), indent + 1);
                    sb.append(" => ");
                    tree(sb, e.getValue(), indent + 1);
                    sb.append('\n');
                }
                pad(sb, indent).append('}');
            }
            case Optional<?> o -> {
                if (o.isEmpty()) {
                    sb.append("empty");
                } else {
                    tree(sb, o.get(), indent);
                }
            }
            default -> sb.append(scalar(v));
        }
    }

    /** A one-line rendering if the collection holds only scalars and fits, else null. */
    private static String inline(Object v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof DynList l) {
            sb.append(l.set() ? "{" : "[");
            for (int i = 0; i < l.elements().size(); i++) {
                Object e = l.elements().get(i);
                if (!isScalar(e)) {
                    return null;
                }
                sb.append(i == 0 ? "" : ", ").append(scalar(e));
            }
            sb.append(l.set() ? "}" : "]");
        } else if (v instanceof DynMap m) {
            sb.append("{");
            boolean first = true;
            for (Map.Entry<Object, Object> e : m.entries()) {
                if (!isScalar(e.getKey()) || !isScalar(e.getValue())) {
                    return null;
                }
                sb.append(first ? "" : ", ").append(scalar(e.getKey())).append(" => ").append(scalar(e.getValue()));
                first = false;
            }
            sb.append("}");
        }
        return sb.length() <= INLINE_WIDTH ? sb.toString() : null;
    }

    private static boolean isScalar(Object v) {
        return !(v instanceof DynMessage || v instanceof DynList || v instanceof DynMap || v instanceof Optional);
    }

    private static String scalar(Object v) {
        return switch (v) {
            case null -> "null";
            case String s -> Json.quote(s);
            case Character c -> c >= 0x20 && c < 0x7F && c != '\'' && c != '\\' ? "'" + c + "'"
                    : String.format("'\\u%04x'", (int) c);
            case byte[] b -> b.length == 0 ? "bytes[0]" : "bytes[" + b.length + "] " + HexFormat.of().formatHex(b, 0, Math.min(b.length, 32))
                    + (b.length > 32 ? "..." : "");
            case DynEnum e -> e.name() != null ? e.name() : "#" + e.ordinal() + " (unknown constant of " + e.type() + ")";
            case Float f -> f + "f";
            default -> String.valueOf(v);
        };
    }

    private static StringBuilder pad(StringBuilder sb, int indent) {
        return sb.repeat("  ", indent);
    }

    // ---- json --------------------------------------------------------------------------------------------------

    static void json(StringBuilder sb, Object v, int indent) {
        switch (v) {
            case null -> sb.append("null");
            case DynMessage m -> {
                sb.append("{");
                boolean first = true;
                for (DynField f : m.fields()) {
                    sb.append(first ? "\n" : ",\n");
                    first = false;
                    pad(sb, indent + 1);
                    Json.quote(sb, f.name());
                    sb.append(": ");
                    json(sb, f.value(), indent + 1);
                }
                if (m.unknownBytes() > 0) {
                    sb.append(first ? "\n" : ",\n");
                    first = false;
                    pad(sb, indent + 1).append("\"@unknownBytes\": ").append(m.unknownBytes());
                }
                if (!first) {
                    sb.append('\n');
                    pad(sb, indent);
                }
                sb.append('}');
            }
            case DynList l -> {
                sb.append('[');
                for (int i = 0; i < l.elements().size(); i++) {
                    sb.append(i == 0 ? "" : ", ");
                    json(sb, l.elements().get(i), indent);
                }
                sb.append(']');
            }
            case DynMap m -> {
                boolean stringKeys = m.entries().stream().allMatch(e -> e.getKey() instanceof String);
                if (stringKeys) {
                    sb.append('{');
                    boolean first = true;
                    for (Map.Entry<Object, Object> e : m.entries()) {
                        sb.append(first ? "" : ", ");
                        first = false;
                        Json.quote(sb, (String) e.getKey());
                        sb.append(": ");
                        json(sb, e.getValue(), indent);
                    }
                    sb.append('}');
                } else {
                    sb.append('[');
                    boolean first = true;
                    for (Map.Entry<Object, Object> e : m.entries()) {
                        sb.append(first ? "" : ", ").append("{\"key\": ");
                        first = false;
                        json(sb, e.getKey(), indent);
                        sb.append(", \"value\": ");
                        json(sb, e.getValue(), indent);
                        sb.append('}');
                    }
                    sb.append(']');
                }
            }
            case Optional<?> o -> json(sb, o.orElse(null), indent);
            case DynEnum e -> {
                if (e.name() != null) {
                    Json.quote(sb, e.name());
                } else {
                    sb.append(e.ordinal());
                }
            }
            case String s -> Json.quote(sb, s);
            case Character c -> Json.quote(sb, String.valueOf(c));
            case byte[] b -> Json.quote(sb, Base64.getEncoder().encodeToString(b));
            case Boolean b -> sb.append(b);
            case Float f -> number(sb, f.doubleValue(), f.isNaN() || f.isInfinite(), f.toString());
            case Double d -> number(sb, d, d.isNaN() || d.isInfinite(), d.toString());
            case Number n -> sb.append(n);
            default -> Json.quote(sb, v.toString()); // UUID, Instant
        }
    }

    private static void number(StringBuilder sb, double d, boolean special, String text) {
        if (special) {
            Json.quote(sb, text);
        } else {
            sb.append(text);
        }
    }
}
