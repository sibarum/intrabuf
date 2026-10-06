package sibarum.intrabuf.schema;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.SchemaInfo;

import java.util.List;

/** A message schema as the manifest records it. */
public record MessageDef(SchemaInfo info, List<FieldDef> fields) {

    public MessageDef {
        fields = List.copyOf(fields);
    }

    public String name() {
        return info.name();
    }

    public Compat compat() {
        return info.compat();
    }

    public int minor() {
        return info.minor();
    }

    public int major() {
        return info.major();
    }
}
