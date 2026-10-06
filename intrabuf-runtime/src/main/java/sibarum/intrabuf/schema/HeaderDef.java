package sibarum.intrabuf.schema;

import sibarum.intrabuf.HeaderRole;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An application header format: the record's layout as a STRICT message, and which of its fields carry which
 * {@link HeaderRole}.
 *
 * @param roles field name to role, in field order
 */
public record HeaderDef(MessageDef def, Map<String, HeaderRole> roles) {

    public HeaderDef {
        roles = Collections.unmodifiableMap(new LinkedHashMap<>(roles));
    }

    public String name() {
        return def.name();
    }
}
