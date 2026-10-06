package sibarum.intrabuf.schema;

/**
 * The leaf types of the wire format. Each has a lowercase manifest name ({@code int32}) and a method suffix
 * ({@code Int32}) naming its {@code IbWriter.write*} / {@code IbReader.read*} pair.
 */
public enum ScalarKind {
    BOOL, INT8, INT16, INT32, INT64, FIXED32, FIXED64, FLOAT32, FLOAT64, CHAR, STRING, BYTES, UUID, INSTANT;

    private final String wireName = name().toLowerCase(java.util.Locale.ROOT);
    private final String methodSuffix = name().charAt(0) + wireName.substring(1);

    public String wireName() {
        return wireName;
    }

    public String methodSuffix() {
        return methodSuffix;
    }

    public static ScalarKind byWireName(String s) {
        for (ScalarKind k : values()) {
            if (k.wireName.equals(s)) {
                return k;
            }
        }
        return null;
    }
}
