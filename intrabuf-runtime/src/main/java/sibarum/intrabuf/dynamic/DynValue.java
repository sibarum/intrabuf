package sibarum.intrabuf.dynamic;

import java.util.List;
import java.util.Map;

/**
 * Values the {@link DynamicDecoder} produces where no Java type exists. Scalars decode to their boxed Java values
 * ({@code Integer}, {@code String}, {@code byte[]}, {@code UUID}, {@code Instant}, ...); absent nullable fields to
 * {@code null}; {@code optional<T>} to {@code java.util.Optional}.
 */
public sealed interface DynValue {

    /**
     * @param minor         the minor version the message was written at, or -1 for STRICT (no minor on the wire)
     * @param unknownBytes  bytes a newer FULL writer added that this schema does not describe
     */
    record DynMessage(String type, int minor, List<DynField> fields, int unknownBytes) implements DynValue {
        public Object get(String name) {
            for (DynField f : fields) {
                if (f.name().equals(name)) {
                    return f.value();
                }
            }
            throw new IllegalArgumentException(type + " has no field " + name);
        }
    }

    record DynField(String name, Object value) {
    }

    /** @param name null if the ordinal is past the constants the loaded schema knows */
    record DynEnum(String type, int ordinal, String name) implements DynValue {
    }

    record DynList(boolean set, List<Object> elements) implements DynValue {
    }

    record DynMap(List<Map.Entry<Object, Object>> entries) implements DynValue {
    }
}
