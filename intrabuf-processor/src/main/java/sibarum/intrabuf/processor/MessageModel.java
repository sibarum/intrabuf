package sibarum.intrabuf.processor;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.HeaderRole;
import sibarum.intrabuf.schema.FieldDef;

import javax.lang.model.element.TypeElement;
import java.util.List;
import java.util.Map;

/**
 * Everything the processor knows about one annotated record: a message, an application header format, or a bundle.
 * A bundle's fields are its members, each a {@code List} of a message, so the rules for fields (positions,
 * {@code since}, nesting, fingerprints) apply to members unchanged.
 *
 * @param id       the schema id on the wire: explicit, or the hash of {@code schemaName}
 * @param external whether the record was read from a class file (another module) rather than compiled here
 * @param roles    for a header, field name to role, in field order; empty for a message
 */
record MessageModel(TypeElement element, String schemaName, String javaName, String codecName, int id, int major,
                    int minor, Compat compat, List<FieldModel> fields, boolean external, boolean header,
                    Map<String, HeaderRole> roles, boolean bundle) {

    record FieldModel(String name, String accessor, JType type, boolean nullable, boolean nonNullDeclared, int since) {
        FieldDef def() {
            return new FieldDef(name, type.ref(), nullable, since);
        }
    }

    List<FieldDef> defs() {
        return fields.stream().map(FieldModel::def).toList();
    }

    String codecSimpleName() {
        return codecName.substring(codecName.lastIndexOf('.') + 1);
    }

    String packageName() {
        int dot = codecName.lastIndexOf('.');
        return dot < 0 ? "" : codecName.substring(0, dot);
    }
}
