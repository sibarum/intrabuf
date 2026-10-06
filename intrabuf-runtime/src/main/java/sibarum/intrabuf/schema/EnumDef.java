package sibarum.intrabuf.schema;

import java.util.List;

/** An enum, encoded on the wire as the varint ordinal of its constant. */
public record EnumDef(String name, List<String> constants) {

    public EnumDef {
        constants = List.copyOf(constants);
    }
}
