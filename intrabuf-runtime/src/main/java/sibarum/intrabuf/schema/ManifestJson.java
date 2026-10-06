package sibarum.intrabuf.schema;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.Hex;
import sibarum.intrabuf.SchemaInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manifest to and from JSON. The output is stable (no timestamps, fixed ordering) so a manifest only changes when a
 * schema does, and can be diffed or committed as a record of what a release wrote.
 */
public final class ManifestJson {

    private ManifestJson() {
    }

    public static String write(Manifest m) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": ").append(m.format()).append(",\n");
        sb.append("  \"wireVersion\": ").append(m.wireVersion()).append(",\n");
        sb.append("  \"module\": ").append(Json.quote(m.module())).append(",\n");
        sb.append("  \"buildVersion\": ").append(Json.quote(m.buildVersion())).append(",\n");
        sb.append("  \"snapshot\": ").append(m.snapshot()).append(",\n");
        sb.append("  \"messages\": [");
        for (int i = 0; i < m.messages().size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            writeMessage(sb, m.messages().get(i));
        }
        sb.append(m.messages().isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"enums\": [");
        for (int i = 0; i < m.enums().size(); i++) {
            EnumDef e = m.enums().get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    { \"name\": ").append(Json.quote(e.name())).append(", \"constants\": [");
            for (int k = 0; k < e.constants().size(); k++) {
                sb.append(k == 0 ? "" : ", ").append(Json.quote(e.constants().get(k)));
            }
            sb.append("] }");
        }
        sb.append(m.enums().isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void writeMessage(StringBuilder sb, MessageDef d) {
        SchemaInfo s = d.info();
        sb.append("    {\n");
        sb.append("      \"name\": ").append(Json.quote(s.name())).append(",\n");
        sb.append("      \"javaType\": ").append(Json.quote(s.javaType())).append(",\n");
        sb.append("      \"id\": ").append(Json.quote(Hex.int32(s.id()))).append(",\n");
        sb.append("      \"major\": ").append(s.major()).append(",\n");
        sb.append("      \"minor\": ").append(s.minor()).append(",\n");
        sb.append("      \"compat\": ").append(Json.quote(s.compat().name())).append(",\n");
        sb.append("      \"fingerprint\": ").append(Json.quote(Hex.int32(s.fingerprint()))).append(",\n");
        sb.append("      \"snapshot\": ").append(s.snapshot()).append(",\n");
        sb.append("      \"buildVersion\": ").append(Json.quote(s.buildVersion())).append(",\n");
        sb.append("      \"fields\": [");
        for (int i = 0; i < d.fields().size(); i++) {
            FieldDef f = d.fields().get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("        { \"name\": ").append(Json.quote(f.name()))
                    .append(", \"type\": ").append(Json.quote(f.type().toString()))
                    .append(", \"nullable\": ").append(f.nullable())
                    .append(", \"since\": ").append(f.since()).append(" }");
        }
        sb.append(d.fields().isEmpty() ? "]\n" : "\n      ]\n");
        sb.append("    }");
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Manifest parse(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        int format = intOf(root, "format");
        if (format > Manifest.FORMAT) {
            throw new IllegalArgumentException("manifest format " + format + " is newer than this reader (" + Manifest.FORMAT + ")");
        }
        List<MessageDef> messages = new ArrayList<>();
        for (Object o : (List<Object>) root.getOrDefault("messages", List.of())) {
            messages.add(parseMessage((Map<String, Object>) o));
        }
        List<EnumDef> enums = new ArrayList<>();
        for (Object o : (List<Object>) root.getOrDefault("enums", List.of())) {
            Map<String, Object> e = (Map<String, Object>) o;
            List<String> constants = new ArrayList<>();
            for (Object c : (List<Object>) e.get("constants")) {
                constants.add((String) c);
            }
            enums.add(new EnumDef(str(e, "name"), constants));
        }
        return new Manifest(format, intOf(root, "wireVersion"), str(root, "module"), str(root, "buildVersion"),
                Boolean.TRUE.equals(root.get("snapshot")), messages, enums);
    }

    @SuppressWarnings("unchecked")
    private static MessageDef parseMessage(Map<String, Object> m) {
        String name = str(m, "name");
        SchemaInfo info = new SchemaInfo(name, (String) m.getOrDefault("javaType", name), Hex.parseInt32(str(m, "id")),
                intOf(m, "major"), intOf(m, "minor"), Compat.valueOf(str(m, "compat")),
                Hex.parseInt32(str(m, "fingerprint")), Boolean.TRUE.equals(m.get("snapshot")),
                (String) m.getOrDefault("buildVersion", ""));
        List<FieldDef> fields = new ArrayList<>();
        for (Object o : (List<Object>) m.get("fields")) {
            Map<String, Object> f = (Map<String, Object>) o;
            fields.add(new FieldDef(str(f, "name"), TypeRef.parse(str(f, "type")),
                    Boolean.TRUE.equals(f.get("nullable")), intOf(f, "since")));
        }
        return new MessageDef(info, fields);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("manifest: '" + key + "' missing or not a string");
        }
        return s;
    }

    private static int intOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Long l)) {
            throw new IllegalArgumentException("manifest: '" + key + "' missing or not an integer");
        }
        return Math.toIntExact(l);
    }
}
