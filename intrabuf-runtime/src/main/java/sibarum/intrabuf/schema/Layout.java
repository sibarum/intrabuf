package sibarum.intrabuf.schema;

import java.util.List;

/**
 * Where each field's bits live in a message body's bitset. The one definition both the generated codecs (via the
 * processor) and the schema-driven decoder use, so they cannot disagree.
 *
 * <p>Bits are assigned in field order: a nullable field or an {@code optional<T>} gets a presence bit; a boolean
 * (including a nullable {@code Boolean} or {@code optional<bool>}) then gets a value bit and writes nothing to the
 * body. Because {@code since} is non-decreasing in field order, the bitset of an older minor is a prefix of the
 * current one, and bits past the end of a shorter bitset read as zero: absent, or false.
 */
public final class Layout {

    private final List<FieldDef> fields;
    private final int[] presenceBit;
    private final int[] valueBit;
    private final int[] endBit;

    private Layout(List<FieldDef> fields) {
        this.fields = fields;
        int n = fields.size();
        presenceBit = new int[n];
        valueBit = new int[n];
        endBit = new int[n];
        int bit = 0;
        for (int i = 0; i < n; i++) {
            FieldDef f = fields.get(i);
            presenceBit[i] = hasPresenceBit(f) ? bit++ : -1;
            valueBit[i] = base(f.type()).isScalar(ScalarKind.BOOL) ? bit++ : -1;
            endBit[i] = bit;
        }
    }

    public static Layout of(List<FieldDef> fields) {
        return new Layout(List.copyOf(fields));
    }

    public static boolean hasPresenceBit(FieldDef f) {
        return f.nullable() || f.type() instanceof TypeRef.OptionalOf;
    }

    /** The type with any {@code optional<>} removed. */
    public static TypeRef base(TypeRef t) {
        return t instanceof TypeRef.OptionalOf o ? o.value() : t;
    }

    /** Presence bit of field {@code i}, or -1. */
    public int presenceBit(int i) {
        return presenceBit[i];
    }

    /** Value bit of boolean field {@code i}, or -1 if it is not a boolean. */
    public int valueBit(int i) {
        return valueBit[i];
    }

    /** Whether field {@code i} writes anything to the body after the bitset. Booleans live entirely in it. */
    public boolean hasBody(int i) {
        return valueBit[i] < 0;
    }

    /** Bits used by the fields that existed at {@code minor}. */
    public int bitCount(int minor) {
        int bits = 0;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).since() <= minor) {
                bits = Math.max(bits, endBit[i]);
            }
        }
        return bits;
    }

    public int bitsetBytes(int minor) {
        return (bitCount(minor) + 7) >>> 3;
    }
}
