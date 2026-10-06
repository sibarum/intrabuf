package sibarum.intrabuf.processor;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.schema.FieldDef;

import javax.lang.model.element.TypeElement;
import java.util.List;

/**
 * Everything the processor knows about one annotated record.
 *
 * @param external whether the record was read from a class file (another module) rather than compiled here
 */
record MessageModel(TypeElement element, String schemaName, String javaName, String codecName, int major, int minor,
                    Compat compat, List<FieldModel> fields, boolean external) {

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
