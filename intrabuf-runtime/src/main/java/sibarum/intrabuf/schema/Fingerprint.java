package sibarum.intrabuf.schema;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Schema ids and structural fingerprints.
 *
 * <p>A message's fingerprint hashes a canonical text of the message and of every message and enum it reaches,
 * directly or not: names, compat modes, field names, types, nullability and {@code since}. It deliberately excludes
 * version numbers, so it answers exactly one question: would these two schemas lay out the same bytes?
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static int schemaId(String name) {
        return fold(fnv1a64(name));
    }

    /**
     * @param messages resolves a message name to its definition (its own fingerprint is ignored)
     * @param enums    resolves an enum name to its definition
     */
    public static int of(String root, Function<String, MessageDef> messages, Function<String, EnumDef> enums) {
        return fold(fnv1a64(canonical(root, messages, enums)));
    }

    public static String canonical(String root, Function<String, MessageDef> messages, Function<String, EnumDef> enums) {
        Set<String> seenMessages = new LinkedHashSet<>();
        Set<String> seenEnums = new TreeSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(root);
        seenMessages.add(root);
        while (!todo.isEmpty()) {
            MessageDef m = require(messages, todo.poll());
            for (FieldDef f : m.fields()) {
                collect(f.type(), seenMessages, seenEnums, todo);
            }
        }
        List<String> others = new ArrayList<>(seenMessages);
        others.remove(root);
        others.sort(null);

        StringBuilder sb = new StringBuilder();
        appendMessage(sb, require(messages, root));
        for (String name : others) {
            appendMessage(sb, require(messages, name));
        }
        for (String name : seenEnums) {
            EnumDef e = enums.apply(name);
            if (e == null) {
                throw new IllegalArgumentException("unknown enum " + name);
            }
            sb.append("E ").append(name).append(' ').append(String.join(",", e.constants())).append('\n');
        }
        return sb.toString();
    }

    private static MessageDef require(Function<String, MessageDef> messages, String name) {
        MessageDef m = messages.apply(name);
        if (m == null) {
            throw new IllegalArgumentException("unknown message " + name);
        }
        return m;
    }

    private static void collect(TypeRef t, Set<String> msgs, Set<String> enums, Deque<String> todo) {
        switch (t) {
            case TypeRef.MessageRef m -> {
                if (msgs.add(m.name())) {
                    todo.add(m.name());
                }
            }
            case TypeRef.EnumRef e -> enums.add(e.name());
            case TypeRef.ListOf l -> collect(l.element(), msgs, enums, todo);
            case TypeRef.SetOf s -> collect(s.element(), msgs, enums, todo);
            case TypeRef.MapOf m -> {
                collect(m.key(), msgs, enums, todo);
                collect(m.value(), msgs, enums, todo);
            }
            case TypeRef.OptionalOf o -> collect(o.value(), msgs, enums, todo);
            case TypeRef.Scalar s -> {
            }
        }
    }

    private static void appendMessage(StringBuilder sb, MessageDef m) {
        sb.append("M ").append(m.name()).append(' ').append(m.compat());
        for (FieldDef f : m.fields()) {
            sb.append(' ').append(f.name()).append(':').append(f.type()).append(f.nullable() ? "?" : "!")
                    .append('@').append(f.since());
        }
        sb.append('\n');
    }

    private static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xFF;
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static int fold(long h) {
        return (int) (h ^ (h >>> 32));
    }
}
