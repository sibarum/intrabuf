package sibarum.intrabuf;

/**
 * The facts of a {@link Header}, as components of an application's own {@code @IntrabufHeader} record. Every role
 * must appear exactly once, so a custom header can never carry less than intrabuf needs to check compatibility.
 */
public enum HeaderRole {
    /** {@code byte}, {@code short} or {@code int}: the intrabuf wire format version of the body. */
    WIRE_VERSION,
    /** {@code int}: the schema id; for app-assigned ids use {@code @IntrabufMessage(id = ...)}. */
    SCHEMA_ID,
    /** {@code byte}, {@code short} or {@code int}. */
    MAJOR,
    /** {@code byte}, {@code short} or {@code int}. */
    MINOR,
    /** {@link Compat}. */
    COMPAT,
    /** {@code boolean}: costs one bit of the header's bitset. */
    SNAPSHOT,
    /**
     * {@code Integer}, null when the payload carries none (a release BACKWARD or FULL schema), or {@code int} to
     * always carry it. A hash, so declare it {@code @Field(fixed = true)}: as a varint it averages 5 bytes.
     */
    FINGERPRINT
}
