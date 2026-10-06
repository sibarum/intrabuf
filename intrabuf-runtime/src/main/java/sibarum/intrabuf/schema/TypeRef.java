package sibarum.intrabuf.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * A field's wire type. Prints as, and parses from, the manifest's type syntax:
 * {@code int32}, {@code string}, {@code enum<com.acme.Color>}, {@code message<com.acme.Address>},
 * {@code list<T>}, {@code set<T>}, {@code map<K,V>}, {@code optional<T>}.
 */
public sealed interface TypeRef {

    record Scalar(ScalarKind kind) implements TypeRef {
        @Override
        public String toString() {
            return kind.wireName();
        }
    }

    record EnumRef(String name) implements TypeRef {
        @Override
        public String toString() {
            return "enum<" + name + ">";
        }
    }

    record MessageRef(String name) implements TypeRef {
        @Override
        public String toString() {
            return "message<" + name + ">";
        }
    }

    record ListOf(TypeRef element) implements TypeRef {
        @Override
        public String toString() {
            return "list<" + element + ">";
        }
    }

    record SetOf(TypeRef element) implements TypeRef {
        @Override
        public String toString() {
            return "set<" + element + ">";
        }
    }

    record MapOf(TypeRef key, TypeRef value) implements TypeRef {
        @Override
        public String toString() {
            return "map<" + key + "," + value + ">";
        }
    }

    /** Only ever a field's outermost type. */
    record OptionalOf(TypeRef value) implements TypeRef {
        @Override
        public String toString() {
            return "optional<" + value + ">";
        }
    }

    static TypeRef scalar(ScalarKind k) {
        return new Scalar(k);
    }

    default boolean isScalar(ScalarKind k) {
        return this instanceof Scalar s && s.kind() == k;
    }

    static TypeRef parse(String s) {
        String t = s.strip();
        int lt = t.indexOf('<');
        if (lt < 0) {
            ScalarKind k = ScalarKind.byWireName(t);
            if (k == null) {
                throw new IllegalArgumentException("unknown type '" + t + "'");
            }
            return new Scalar(k);
        }
        if (!t.endsWith(">")) {
            throw new IllegalArgumentException("unbalanced type '" + t + "'");
        }
        String head = t.substring(0, lt);
        String inner = t.substring(lt + 1, t.length() - 1);
        return switch (head) {
            case "enum" -> new EnumRef(inner.strip());
            case "message" -> new MessageRef(inner.strip());
            case "list" -> new ListOf(parse(inner));
            case "set" -> new SetOf(parse(inner));
            case "optional" -> new OptionalOf(parse(inner));
            case "map" -> {
                List<String> args = splitTopLevel(inner);
                if (args.size() != 2) {
                    throw new IllegalArgumentException("map needs two type arguments: '" + t + "'");
                }
                yield new MapOf(parse(args.get(0)), parse(args.get(1)));
            }
            default -> throw new IllegalArgumentException("unknown type constructor '" + head + "'");
        };
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(from, i));
                from = i + 1;
            }
        }
        out.add(s.substring(from));
        return out;
    }
}
