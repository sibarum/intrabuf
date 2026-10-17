package sibarum.intrabuf.schema;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.HeaderRole;
import sibarum.intrabuf.Hex;
import sibarum.intrabuf.SchemaInfo;
import sibarum.intrabuf.endpoint.EndpointDef;
import sibarum.intrabuf.endpoint.Version;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            writeMessage(sb, m.messages().get(i), null);
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
        sb.append(m.enums().isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"headers\": [");
        for (int i = 0; i < m.headers().size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            writeMessage(sb, m.headers().get(i).def(), m.headers().get(i).roles());
        }
        sb.append(m.headers().isEmpty() ? "]" : "\n  ]");
        if (!m.endpoints().isEmpty()) {
            // Only when there are any, so a manifest without endpoints is byte for byte what it always was.
            sb.append(",\n  \"endpoints\": [");
            for (int i = 0; i < m.endpoints().size(); i++) {
                EndpointDef e = m.endpoints().get(i);
                sb.append(i == 0 ? "\n" : ",\n");
                sb.append("    {\n");
                sb.append("      \"name\": ").append(Json.quote(e.name())).append(",\n");
                sb.append("      \"oldestPeer\": ").append(Json.quote(e.oldestPeer())).append(",\n");
                sb.append("      \"members\": [");
                for (int k = 0; k < e.members().size(); k++) {
                    EndpointDef.Member mb = e.members().get(k);
                    sb.append(k == 0 ? "\n" : ",\n");
                    sb.append("        { \"schema\": ").append(Json.quote(mb.schema()))
                            .append(", \"id\": ").append(Json.quote(Hex.int32(mb.id())))
                            .append(", \"compat\": ").append(Json.quote(mb.compat().name()))
                            .append(", \"fingerprint\": ").append(Json.quote(Hex.int32(mb.fingerprint())))
                            .append(", \"version\": ").append(Json.quote(mb.version().toString()))
                            .append(", \"oldest\": ").append(Json.quote(mb.oldest().toString()))
                            .append(", \"side\": ").append(Json.quote(mb.side().name()))
                            .append(", \"optional\": ").append(mb.optional())
                            .append(", \"nested\": [");
                    for (int n = 0; n < mb.nested().size(); n++) {
                        EndpointDef.Nested ne = mb.nested().get(n);
                        sb.append(n == 0 ? "" : ", ").append("{ \"schema\": ").append(Json.quote(ne.schema()))
                                .append(", \"compat\": ").append(Json.quote(ne.compat().name()))
                                .append(", \"version\": ").append(Json.quote(ne.version().toString()))
                                .append(", \"written\": ").append(ne.written()).append(" }");
                    }
                    sb.append("] }");
                }
                sb.append(e.members().isEmpty() ? "]\n" : "\n      ]\n");
                sb.append("    }");
            }
            sb.append("\n  ]");
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    private static void writeMessage(StringBuilder sb, MessageDef d, Map<String, HeaderRole> roles) {
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
        if (roles != null) {
            sb.append("      \"roles\": {");
            boolean first = true;
            for (Map.Entry<String, HeaderRole> e : roles.entrySet()) {
                sb.append(first ? " " : ", ").append(Json.quote(e.getKey())).append(": ").append(Json.quote(e.getValue().name()));
                first = false;
            }
            sb.append(roles.isEmpty() ? "},\n" : " },\n");
        }
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
        List<HeaderDef> headers = new ArrayList<>();
        for (Object o : (List<Object>) root.getOrDefault("headers", List.of())) {
            Map<String, Object> h = (Map<String, Object>) o;
            Map<String, HeaderRole> roles = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) h.getOrDefault("roles", Map.of())).entrySet()) {
                roles.put(e.getKey(), HeaderRole.valueOf((String) e.getValue()));
            }
            headers.add(new HeaderDef(parseMessage(h), roles));
        }
        List<EndpointDef> endpoints = new ArrayList<>();
        for (Object o : (List<Object>) root.getOrDefault("endpoints", List.of())) {
            Map<String, Object> e = (Map<String, Object>) o;
            List<EndpointDef.Member> members = new ArrayList<>();
            for (Object mo : (List<Object>) e.get("members")) {
                Map<String, Object> mb = (Map<String, Object>) mo;
                List<EndpointDef.Nested> nested = new ArrayList<>();
                for (Object no : (List<Object>) mb.getOrDefault("nested", List.of())) {
                    Map<String, Object> ne = (Map<String, Object>) no;
                    nested.add(new EndpointDef.Nested(str(ne, "schema"), Compat.valueOf(str(ne, "compat")),
                            Version.parse(str(ne, "version")), Boolean.TRUE.equals(ne.get("written"))));
                }
                members.add(new EndpointDef.Member(str(mb, "schema"), Hex.parseInt32(str(mb, "id")),
                        Compat.valueOf(str(mb, "compat")), Hex.parseInt32(str(mb, "fingerprint")),
                        Version.parse(str(mb, "version")), Version.parse(str(mb, "oldest")),
                        EndpointDef.Side.valueOf(str(mb, "side")), Boolean.TRUE.equals(mb.get("optional")), nested));
            }
            endpoints.add(new EndpointDef(str(e, "name"), (String) e.getOrDefault("oldestPeer", ""), members));
        }
        return new Manifest(format, intOf(root, "wireVersion"), str(root, "module"), str(root, "buildVersion"),
                Boolean.TRUE.equals(root.get("snapshot")), messages, enums, headers, endpoints);
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
