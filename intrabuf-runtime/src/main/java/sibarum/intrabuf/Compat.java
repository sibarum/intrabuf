package sibarum.intrabuf;

/**
 * How a message schema may evolve without a major version bump. Chosen per message, written into every payload's
 * header, and part of the schema: changing it is itself a breaking change.
 *
 * <p>Fields are encoded positionally (no per-field tags), so evolution is expressed through {@code since}: a field
 * added in minor {@code n} is declared with {@code @Field(since = n)} after every field older than it.
 */
public enum Compat {

    /**
     * Writer and reader must have the same major, the same minor, and the same structural fingerprint. The smallest
     * and fastest encoding: nothing in the body describes itself. Payloads carry the fingerprint, so an edit that
     * forgot to bump the version is still caught.
     */
    STRICT,

    /**
     * A reader accepts payloads written at its own minor or any older one; fields the writer did not know get
     * their defaults. A payload from a newer minor is rejected. Costs one varint (the minor) per nested message.
     */
    BACKWARD,

    /**
     * BACKWARD, plus older readers accept newer payloads and skip fields they do not know. Costs a minor, a body
     * length and a bitset length per nested message, and a bitset length for the root.
     */
    FULL;

    /**
     * Whether a message in this mode may contain a message in {@code nested}'s mode. A STRICT message's fingerprint
     * covers everything it contains, so it may contain anything. A BACKWARD or FULL message relies on nested
     * messages to describe their own versions, so they must be at least as tolerant as it is.
     */
    public boolean canContain(Compat nested) {
        return switch (this) {
            case STRICT -> true;
            case BACKWARD -> nested != STRICT;
            case FULL -> nested == FULL;
        };
    }
}
