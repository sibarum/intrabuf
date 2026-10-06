package sibarum.intrabuf.processor;

import sibarum.intrabuf.schema.TypeRef;

import java.util.List;

/**
 * A field type as the generator needs it: the wire type, plus the Java spelling of the type and, for messages and
 * enums, the codec or enum class to call.
 *
 * @param javaName  fully qualified Java type, with type arguments ({@code java.util.List<java.lang.String>})
 * @param args      element / key-value / optional-content types, in order
 * @param className codec class for a message, enum class for an enum, otherwise null
 */
record JType(TypeRef ref, String javaName, List<JType> args, String className) {

    static JType leaf(TypeRef ref, String javaName) {
        return new JType(ref, javaName, List.of(), null);
    }

    JType arg(int i) {
        return args.get(i);
    }
}
