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
 * @param oldest   the declared oldest version the codec reads, or null (minor 0 of its own major)
 * @param promotion        the {@code @Promote} method from the previous major, or null
 * @param fieldPromotions  field name to the {@code @Promote(field = ...)} method computing it, in field order
 */
record MessageModel(TypeElement element, String schemaName, String javaName, String codecName, int id, int major,
                    int minor, Compat compat, List<FieldModel> fields, boolean external, boolean header,
                    Map<String, HeaderRole> roles, boolean bundle, Version oldest, Promotion promotion,
                    Map<String, String> fieldPromotions) {

    /** A {@code major.minor} pair, ordered. */
    record Version(int major, int minor) implements Comparable<Version> {
        @Override
        public int compareTo(Version o) {
            return major != o.major ? Integer.compare(major, o.major) : Integer.compare(minor, o.minor);
        }

        @Override
        public String toString() {
            return major + "." + minor;
        }
    }

    /**
     * A major promotion: {@code method} on this record takes the record {@code fromJavaName}, whose codec is
     * {@code fromCodec}, the previous major of the same schema.
     */
    record Promotion(String method, String fromJavaName, String fromCodec, javax.lang.model.element.Element where) {
    }

    /** The oldest version the codec reads: declared, or minor 0 of its own major. */
    Version effectiveOldest() {
        return oldest != null ? oldest : new Version(major, 0);
    }

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
