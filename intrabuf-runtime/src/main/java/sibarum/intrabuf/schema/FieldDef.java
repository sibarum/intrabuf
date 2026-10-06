package sibarum.intrabuf.schema;

/**
 * One field of a message.
 *
 * @param nullable whether a presence bit is spent on it. Always false for primitives and for {@code optional<T>},
 *                 whose emptiness has its own presence bit.
 * @param since    the minor version the field was added in
 */
public record FieldDef(String name, TypeRef type, boolean nullable, int since) {
}
