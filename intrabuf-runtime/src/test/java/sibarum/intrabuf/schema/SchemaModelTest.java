package sibarum.intrabuf.schema;

import org.junit.jupiter.api.Test;
import sibarum.intrabuf.Compat;
import sibarum.intrabuf.SchemaInfo;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaModelTest {

    @Test
    void typeSyntaxRoundTrips() {
        for (String t : List.of("int32", "bytes", "enum<a.B>", "message<x.Y>", "list<list<string>>",
                "map<string,list<message<a.B>>>", "map<map<int64,bool>,set<uuid>>", "optional<instant>")) {
            assertEquals(t, TypeRef.parse(t).toString());
        }
        assertThrows(IllegalArgumentException.class, () -> TypeRef.parse("int31"));
        assertThrows(IllegalArgumentException.class, () -> TypeRef.parse("map<string>"));
    }

    @Test
    void layoutAssignsBitsInFieldOrder() {
        List<FieldDef> fields = List.of(
                new FieldDef("a", TypeRef.parse("int32"), false, 0),          // no bits
                new FieldDef("b", TypeRef.parse("bool"), false, 0),           // value bit 0
                new FieldDef("c", TypeRef.parse("string"), true, 0),          // presence bit 1
                new FieldDef("d", TypeRef.parse("bool"), true, 1),            // presence 2, value 3
                new FieldDef("e", TypeRef.parse("optional<int32>"), false, 2) // presence 4
        );
        Layout l = Layout.of(fields);
        assertEquals(-1, l.presenceBit(0));
        assertEquals(0, l.valueBit(1));
        assertEquals(1, l.presenceBit(2));
        assertEquals(2, l.presenceBit(3));
        assertEquals(3, l.valueBit(3));
        assertEquals(4, l.presenceBit(4));
        assertEquals(2, l.bitCount(0));
        assertEquals(4, l.bitCount(1));
        assertEquals(5, l.bitCount(2));
        assertEquals(1, l.bitsetBytes(2));
    }

    private static MessageDef msg(String name, Compat c, FieldDef... fields) {
        return new MessageDef(new SchemaInfo(name, name, Fingerprint.schemaId(name), 1, 0, c, 0, false, ""), List.of(fields));
    }

    @Test
    void fingerprintCoversTheClosureButNotVersions() {
        MessageDef inner = msg("Inner", Compat.FULL, new FieldDef("x", TypeRef.parse("enum<E>"), true, 0));
        MessageDef outer = msg("Outer", Compat.STRICT, new FieldDef("i", TypeRef.parse("list<message<Inner>>"), false, 0));
        Map<String, MessageDef> ms = Map.of("Inner", inner, "Outer", outer);
        int base = Fingerprint.of("Outer", ms::get, Map.of("E", new EnumDef("E", List.of("A", "B")))::get);

        // A change to the enum two levels down changes the root's fingerprint.
        assertNotEquals(base, Fingerprint.of("Outer", ms::get, Map.of("E", new EnumDef("E", List.of("A", "B", "C")))::get));
        // So does a nested message's mode.
        MessageDef innerBackward = msg("Inner", Compat.BACKWARD, new FieldDef("x", TypeRef.parse("enum<E>"), true, 0));
        assertNotEquals(base, Fingerprint.of("Outer", Map.of("Inner", innerBackward, "Outer", outer)::get,
                Map.of("E", new EnumDef("E", List.of("A", "B")))::get));
        // Version numbers do not.
        MessageDef outerV2 = new MessageDef(new SchemaInfo("Outer", "Outer", 0, 9, 9, Compat.STRICT, 0, true, "x"), outer.fields());
        assertEquals(base, Fingerprint.of("Outer", Map.of("Inner", inner, "Outer", outerV2)::get,
                Map.of("E", new EnumDef("E", List.of("A", "B")))::get));
    }

    @Test
    void recursiveSchemasFingerprint() {
        MessageDef node = msg("Node", Compat.STRICT, new FieldDef("kids", TypeRef.parse("list<message<Node>>"), false, 0));
        Fingerprint.of("Node", Map.of("Node", node)::get, n -> null);
    }

    @Test
    void jsonParsesEscapesAndNumbers() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse("{\"a\": \"x\\\"y\\u00e9\\n\", \"b\": [1, -2.5e3, true, null], \"c\": {}}");
        assertEquals("x\"yé\n", m.get("a"));
        assertEquals(List.of(1L, -2500.0, true), ((List<?>) m.get("b")).subList(0, 3));
        assertEquals(Map.of(), m.get("c"));
        assertEquals("\"q\\\"\\\\\\n\\u0001\"", Json.quote("q\"\\\n\u0001"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1,}"));
    }
}
