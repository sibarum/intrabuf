package sibarum.intrabuf.cli;

import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.TextWriter;
import sibarum.intrabuf.dynamic.DynValue.DynEnum;
import sibarum.intrabuf.dynamic.DynValue.DynField;
import sibarum.intrabuf.dynamic.DynValue.DynList;
import sibarum.intrabuf.dynamic.DynValue.DynMap;
import sibarum.intrabuf.dynamic.DynValue.DynMessage;
import sibarum.intrabuf.dynamic.DynamicDecoder;
import sibarum.intrabuf.dynamic.SchemaSet;
import sibarum.intrabuf.schema.HeaderDef;
import sibarum.intrabuf.schema.Json;
import sibarum.intrabuf.schema.MessageDef;

import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    // ---- the text format ---------------------------------------------------------------------------------------

    /**
     * A decoded payload in the text format. Each message's columns are the fields it was decoded with, which are the
     * fields of the minor it was written at. Refuses a payload with fields no loaded schema describes, since its text
     * could not say what they hold.
     */
    static String text(DynamicDecoder.Decoded d, SchemaSet set, HeaderDef format) {
        if (d.trailingBytes() > 0 || d.header().minor() > d.schema().minor()) {
            throw unknown(d.schema().name());
        }
        TextWriter t = new TextWriter(d.schema().name(), d.header());
        if (format != null) {
            List<DynField> own = d.frame().fields().stream().filter(f -> !format.roles().containsKey(f.name())).toList();
            if (!own.isEmpty()) {
                t.header(format.name(), own.stream().map(DynField::name).toList(), () -> cells(t, own, set));
            }
        }
        t.root(names(d.value()), () -> cells(t, d.value().fields(), set));
        return t.finish();
    }

    private static List<String> names(DynMessage m) {
        return m.fields().stream().map(DynField::name).toList();
    }

    private static void cells(TextWriter t, List<DynField> fields, SchemaSet set) {
        for (DynField f : fields) {
            text(t, f.value(), set);
        }
    }

    private static void text(TextWriter t, Object v, SchemaSet set) {
        switch (v) {
            case null -> t.writeNull();
            case Optional<?> o -> text(t, o.orElse(null), set);
            case DynMessage m -> {
                MessageDef def = set.latest(m.type());
                if (m.unknownBytes() > 0 || m.minor() > def.minor()) {
                    throw unknown(m.type());
                }
                TextWriter.Section s = new TextWriter.Section(m.type(), def.major(), m.minor() >= 0 ? m.minor() : def.minor(),
                        names(m));
                t.writeMessage(s, () -> cells(t, m.fields(), set));
            }
            case DynList l -> {
                t.beginList();
                for (Object e : l.elements()) {
                    text(t, e, set);
                }
                t.endList();
            }
            case DynMap m -> {
                t.beginMap();
                for (Map.Entry<Object, Object> e : m.entries()) {
                    text(t, e.getKey(), set);
                    text(t, e.getValue(), set);
                }
                t.endMap();
            }
            case DynEnum e -> {
                if (e.name() == null) {
                    throw new IntrabufException("ordinal " + e.ordinal() + " is not a known constant of " + e.type()
                            + "; the text format names constants");
                }
                t.writeEnum(e.name());
            }
            case Boolean b -> t.writeBool(b);
            case Byte b -> t.writeInt8(b);
            case Short s -> t.writeInt16(s);
            case Integer i -> t.writeInt32(i);
            case Long l -> t.writeInt64(l);
            case Float f -> t.writeFloat32(f);
            case Double x -> t.writeFloat64(x);
            case Character c -> t.writeChar(c);
            case String s -> t.writeString(s);
            case byte[] b -> t.writeBytes(b);
            case UUID u -> t.writeUuid(u);
            case Instant i -> t.writeInstant(i);
            default -> throw new IllegalStateException("no text form for " + v.getClass());
        }
    }

    private static IntrabufException unknown(String type) {
        return new IntrabufException(type + " has fields from a newer minor that no loaded manifest describes;"
                + " load a newer manifest to convert it to text");
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
