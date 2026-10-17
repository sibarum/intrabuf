package sibarum.intrabuf.processor;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.HeaderRole;
import sibarum.intrabuf.SchemaInfo;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufBundle;
import sibarum.intrabuf.annotation.IntrabufHeader;
import sibarum.intrabuf.annotation.IntrabufMessage;
import sibarum.intrabuf.annotation.Promote;
import sibarum.intrabuf.annotation.Role;
import sibarum.intrabuf.processor.MessageModel.FieldModel;
import sibarum.intrabuf.processor.MessageModel.Promotion;
import sibarum.intrabuf.processor.MessageModel.Version;
import sibarum.intrabuf.schema.EnumDef;
import sibarum.intrabuf.schema.Fingerprint;
import sibarum.intrabuf.schema.MessageDef;
import sibarum.intrabuf.schema.ScalarKind;
import sibarum.intrabuf.schema.TypeRef;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and validates {@link MessageModel}s from records. Every rule a schema must satisfy is enforced here, at
 * compile time, so that nothing about a schema can fail at runtime except the data itself.
 */
final class Models {

    private static final Set<String> NON_NULL_NAMES = Set.of("NonNull", "Nonnull", "NotNull");

    private static final Pattern VERSION = Pattern.compile("(\\d{1,9})\\.(\\d{1,9})");

    private final Elements elements;
    private final Types types;
    private final Messager messager;

    /** Models by codec class name. */
    private final Map<String, MessageModel> models = new LinkedHashMap<>();
    private final Map<String, EnumDef> enums = new LinkedHashMap<>();
    private final Map<String, Integer> fingerprints = new HashMap<>();
    /** Qualified names of records annotated in this compilation; anything else we reach came from a jar. */
    private final Set<String> locals = new HashSet<>();
    private final Map<String, String> recordByCodec = new HashMap<>();
    /** Codec names of past versions: records some {@code @Promote} method promotes from. */
    private final Set<String> past = new HashSet<>();
    private boolean errors;

    Models(ProcessingEnvironment env) {
        this.elements = env.getElementUtils();
        this.types = env.getTypeUtils();
        this.messager = env.getMessager();
    }

    boolean hasErrors() {
        return errors;
    }

    Map<String, MessageModel> all() {
        return models;
    }

    Map<String, EnumDef> enums() {
        return enums;
    }

    private void error(Element e, String msg) {
        errors = true;
        messager.printMessage(Diagnostic.Kind.ERROR, msg, e);
    }

    private void warn(Element e, String msg) {
        messager.printMessage(Diagnostic.Kind.WARNING, msg, e);
    }

    // ---- headers: what a reference to a message needs, without its fields ---------------------------------------

    static String codecName(TypeElement te) {
        Deque<String> names = new ArrayDeque<>();
        Element e = te;
        while (e != null && !(e instanceof PackageElement)) {
            names.push(e.getSimpleName().toString());
            e = e.getEnclosingElement();
        }
        String pkg = e == null ? "" : ((PackageElement) e).getQualifiedName().toString();
        String simple = String.join("_", names) + "Intrabuf";
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    static String schemaName(TypeElement te) {
        IntrabufMessage ann = te.getAnnotation(IntrabufMessage.class);
        return ann == null || ann.name().isEmpty() ? te.getQualifiedName().toString() : ann.name();
    }

    // ---- building ----------------------------------------------------------------------------------------------

    /**
     * Builds the model for {@code te} and, transitively, every message it references. Returns null if the record
     * itself is invalid; errors have been reported.
     */
    MessageModel build(TypeElement te, boolean external) {
        String codec = codecName(te);
        MessageModel existing = models.get(codec);
        if (existing != null) {
            return existing;
        }
        MessageModel m = buildOne(te, external);
        if (m == null) {
            return null;
        }
        models.put(codec, m);
        for (FieldModel f : m.fields()) {
            buildReferenced(f.type());
        }
        if (m.promotion() != null && !models.containsKey(m.promotion().fromCodec())) {
            TypeElement from = elements.getTypeElement(m.promotion().fromJavaName());
            if (from != null) {
                build(from, !locals.contains(from.getQualifiedName().toString()));
            }
        }
        return m;
    }

    private void buildReferenced(JType t) {
        if (t.ref() instanceof TypeRef.MessageRef && !models.containsKey(t.className())) {
            TypeElement te = elements.getTypeElement(recordNameOfCodec(t));
            if (te != null) {
                build(te, !locals.contains(te.getQualifiedName().toString()));
            }
        }
        for (JType a : t.args()) {
            buildReferenced(a);
        }
    }

    void markLocal(TypeElement te) {
        locals.add(te.getQualifiedName().toString());
    }

    /** Whether {@code te} is annotated in this compilation, rather than read from a jar. */
    boolean isLocal(TypeElement te) {
        return locals.contains(te.getQualifiedName().toString());
    }

    private String recordNameOfCodec(JType t) {
        return recordByCodec.get(t.className());
    }

    private MessageModel buildOne(TypeElement te, boolean external) {
        boolean ok = true;
        IntrabufMessage ann = te.getAnnotation(IntrabufMessage.class);
        IntrabufHeader headerAnn = te.getAnnotation(IntrabufHeader.class);
        IntrabufBundle bundleAnn = te.getAnnotation(IntrabufBundle.class);
        boolean header = headerAnn != null;
        boolean bundle = bundleAnn != null;
        String what = header ? "@IntrabufHeader" : bundle ? "@IntrabufBundle" : "@IntrabufMessage";
        if (te.getKind() != ElementKind.RECORD) {
            error(te, what + " applies only to records; " + te.getSimpleName() + " is a " + te.getKind().toString().toLowerCase());
            return null;
        }
        if (ann == null && !header && !bundle) {
            error(te, te.getQualifiedName() + " is used as a message but is not annotated @IntrabufMessage");
            return null;
        }
        if ((ann != null ? 1 : 0) + (header ? 1 : 0) + (bundle ? 1 : 0) > 1) {
            error(te, "a record is one of @IntrabufMessage, @IntrabufHeader or @IntrabufBundle, not several");
            return null;
        }
        if (!te.getTypeParameters().isEmpty()) {
            error(te, what + " records cannot be generic");
            ok = false;
        }
        for (Element e = te; e instanceof TypeElement; e = e.getEnclosingElement()) {
            if (e.getModifiers().contains(Modifier.PRIVATE)) {
                error(te, what + " records and their enclosing classes must not be private: the codec is a separate class in the same package");
                ok = false;
                break;
            }
            if (e.getEnclosingElement() != null && !(e.getEnclosingElement() instanceof TypeElement)
                    && !(e.getEnclosingElement() instanceof PackageElement)) {
                error(te, what + " records cannot be local to a method");
                ok = false;
                break;
            }
        }
        String schemaName = header
                ? (headerAnn.name().isEmpty() ? te.getQualifiedName().toString() : headerAnn.name())
                : bundle ? (bundleAnn.name().isEmpty() ? te.getQualifiedName().toString() : bundleAnn.name())
                : schemaName(te);
        if (schemaName.isBlank() || schemaName.chars().anyMatch(c -> c == '<' || c == '>' || c == ',' || Character.isWhitespace(c))) {
            error(te, "schema name '" + schemaName + "' must be non-empty and contain no whitespace, '<', '>' or ','");
            ok = false;
        }
        // A header is a fixed layout: STRICT and unversioned, because the application's protocol versions it.
        int major = header ? 1 : bundle ? bundleAnn.major() : ann.major();
        int minor = header ? 0 : bundle ? bundleAnn.minor() : ann.minor();
        Compat compat = header ? Compat.STRICT : bundle ? bundleAnn.compat() : ann.compat();
        if (major < 0 || minor < 0) {
            error(te, "major and minor must be non-negative");
            ok = false;
        }
        int id = Fingerprint.schemaId(schemaName);
        int[] ids = header ? new int[0] : bundle ? bundleAnn.id() : ann.id();
        if (ids.length > 1) {
            error(te, "id takes one value");
            ok = false;
        } else if (ids.length == 1) {
            id = ids[0];
        }

        Version oldest = null;
        if (ann != null && !ann.oldest().isEmpty()) {
            Matcher v = VERSION.matcher(ann.oldest());
            if (!v.matches()) {
                error(te, "oldest = \"" + ann.oldest() + "\" must be \"major.minor\"");
                ok = false;
            } else {
                oldest = new Version(Integer.parseInt(v.group(1)), Integer.parseInt(v.group(2)));
                Version self = new Version(major, minor);
                if (oldest.compareTo(self) > 0) {
                    error(te, "oldest = \"" + oldest + "\" is newer than " + te.getSimpleName() + " itself (v" + self + ")");
                    ok = false;
                } else if (compat == Compat.STRICT && !oldest.equals(self)) {
                    error(te, "a STRICT schema reads only its own version, so its oldest can only be \"" + self + "\"");
                    ok = false;
                }
            }
        }
        Promotion promotion = null;
        Map<String, String> promoted = new HashMap<>();
        for (Element e : te.getEnclosedElements()) {
            Promote p = e.getAnnotation(Promote.class);
            if (p == null || e.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) e;
            String where = te.getSimpleName() + "." + method.getSimpleName() + "()";
            if (ann == null) {
                error(method, where + ": @Promote is only meaningful on an @IntrabufMessage record");
                ok = false;
                continue;
            }
            if (compat == Compat.STRICT) {
                error(method, where + ": a STRICT schema reads only its own version, so it has no promotions");
                ok = false;
                continue;
            }
            if (!method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
                error(method, where + ": a @Promote method must be static and not private: the generated codec calls it");
                ok = false;
                continue;
            }
            if (method.getParameters().size() != 1) {
                error(method, where + ": a @Promote method takes exactly one parameter");
                ok = false;
                continue;
            }
            TypeMirror param = method.getParameters().getFirst().asType();
            if (p.field().isEmpty()) {
                TypeElement from = param.getKind() == TypeKind.DECLARED ? (TypeElement) types.asElement(param) : null;
                IntrabufMessage fromAnn = from == null ? null : from.getAnnotation(IntrabufMessage.class);
                if (from == null || from.getKind() != ElementKind.RECORD || fromAnn == null) {
                    error(method, where + ": a promotion from the previous major takes that major's @IntrabufMessage record, not " + param);
                    ok = false;
                } else if (!schemaName(from).equals(schemaName)) {
                    error(method, where + ": takes " + from.getSimpleName() + ", which is schema '" + schemaName(from)
                            + "', not '" + schemaName + "'");
                    ok = false;
                } else if (fromAnn.major() != major - 1) {
                    error(method, where + ": takes " + from.getSimpleName() + ", which is major " + fromAnn.major()
                            + "; promotions are adjacent, so it must take major " + (major - 1));
                    ok = false;
                } else if (!types.isSameType(method.getReturnType(), te.asType())) {
                    error(method, where + ": must return " + te.getSimpleName());
                    ok = false;
                } else if (promotion != null) {
                    error(method, where + ": " + te.getSimpleName() + " already promotes from major " + (major - 1)
                            + " with " + promotion.method() + "()");
                    ok = false;
                } else {
                    promotion = new Promotion(method.getSimpleName().toString(), from.getQualifiedName().toString(),
                            codecName(from), method);
                }
            } else {
                RecordComponentElement rc = te.getRecordComponents().stream()
                        .filter(c -> c.getSimpleName().contentEquals(p.field())).findFirst().orElse(null);
                if (rc == null) {
                    error(method, where + ": " + te.getSimpleName() + " has no component named '" + p.field() + "'");
                    ok = false;
                } else if (!types.isSameType(param, te.asType())) {
                    error(method, where + ": takes the record as decoded at an older minor, so its parameter must be "
                            + te.getSimpleName());
                    ok = false;
                } else if (!types.isSameType(method.getReturnType(), rc.asType())) {
                    error(method, where + ": must return " + rc.asType() + ", the type of " + p.field());
                    ok = false;
                } else if (promoted.containsKey(p.field())) {
                    error(method, where + ": " + p.field() + " is already promoted by " + promoted.get(p.field()) + "()");
                    ok = false;
                } else {
                    promoted.put(p.field(), method.getSimpleName().toString());
                }
            }
        }

        List<FieldModel> fields = new ArrayList<>();
        Map<String, HeaderRole> roles = new LinkedHashMap<>();
        Map<String, String> fieldPromotions = new LinkedHashMap<>();
        int lastSince = 0;
        boolean warnedStrictSince = false;
        for (RecordComponentElement rc : te.getRecordComponents()) {
            Field fa = annotation(te, rc, Field.class);
            Role role = annotation(te, rc, Role.class);
            int since = fa == null ? 0 : fa.since();
            boolean fixed = fa != null && fa.fixed();
            boolean nonNullDeclared = (fa != null && fa.nonNull()) || hasNonNullAnnotation(rc);
            String where = te.getSimpleName() + "." + rc.getSimpleName();

            JType jt = map(rc.asType(), fixed, true, rc, where);
            if (jt == null) {
                ok = false;
                continue;
            }
            if (bundle) {
                if (!(jt.ref() instanceof TypeRef.ListOf l && l.element() instanceof TypeRef.MessageRef)) {
                    error(rc, where + ": a bundle's members are lists of @IntrabufMessage records, not " + jt.javaName());
                    ok = false;
                    continue;
                }
                // A member is never null: a bundle with no rows of it has an empty list.
                nonNullDeclared = true;
            }
            boolean primitive = rc.asType().getKind().isPrimitive();
            boolean optional = jt.ref() instanceof TypeRef.OptionalOf;
            if (fixed && !containsInt(jt.ref())) {
                error(rc, where + ": @Field(fixed = true) applies only to int and long values");
                ok = false;
            }
            if (role != null) {
                if (!header) {
                    error(rc, where + ": @Role is only meaningful in an @IntrabufHeader record");
                    ok = false;
                } else if (roles.containsValue(role.value())) {
                    error(rc, where + ": role " + role.value() + " is already carried by another component");
                    ok = false;
                } else if (!checkRole(rc, where, role.value(), jt)) {
                    ok = false;
                } else {
                    roles.put(rc.getSimpleName().toString(), role.value());
                    // Role components are always present, except a fingerprint, which is absent when not needed.
                    if (role.value() != HeaderRole.FINGERPRINT && !primitive) {
                        nonNullDeclared = true;
                    }
                }
            }
            if (nonNullDeclared && (primitive || optional)) {
                warn(rc, where + ": nonNull has no effect on a " + (primitive ? "primitive" : "Optional"));
                nonNullDeclared = false;
            }
            boolean nullable = !primitive && !optional && !nonNullDeclared;

            if (since < 0 || since > minor) {
                error(rc, where + ": since = " + since + " must be between 0 and the message's minor (" + minor + ")");
                ok = false;
            }
            if (compat == Compat.STRICT) {
                if (since != 0 && !warnedStrictSince) {
                    warn(rc, where + ": since has no effect on a STRICT message; every field is always present");
                    warnedStrictSince = true;
                }
            } else {
                if (since < lastSince) {
                    error(rc, where + ": since = " + since + " follows a field with since = " + lastSince
                            + "; fields are positional, so newer fields must come after older ones");
                    ok = false;
                }
                lastSince = Math.max(lastSince, since);
                String promoter = promoted.get(rc.getSimpleName().toString());
                if (promoter != null && since == 0) {
                    error(rc, where + ": is promoted by " + promoter + "(), but it is in minor 0, which every payload has");
                    ok = false;
                } else if (promoter != null) {
                    fieldPromotions.put(rc.getSimpleName().toString(), promoter);
                } else if (since > 0 && nonNullDeclared && !hasDefault(jt.ref())) {
                    error(rc, where + ": a non-null " + jt.ref() + " added in minor " + since
                            + " has no default to give readers of older payloads; make it nullable or Optional,"
                            + " or compute it with @Promote(field = \"" + rc.getSimpleName() + "\")");
                    ok = false;
                }
            }
            String accessor = rc.getAccessor() == null ? rc.getSimpleName().toString() : rc.getAccessor().getSimpleName().toString();
            fields.add(new FieldModel(rc.getSimpleName().toString(), accessor, jt, nullable, nonNullDeclared, since));
        }
        if (header && ok) {
            for (HeaderRole r : HeaderRole.values()) {
                if (!roles.containsValue(r)) {
                    error(te, "@IntrabufHeader " + te.getSimpleName() + " has no component with @Role(" + r
                            + "); every role must be carried so readers can check compatibility");
                    ok = false;
                }
            }
        }
        if (!ok) {
            return null;
        }
        String codec = codecName(te);
        recordByCodec.put(codec, te.getQualifiedName().toString());
        return new MessageModel(te, schemaName, te.getQualifiedName().toString(), codec, id, major, minor,
                compat, fields, external, header, roles, bundle, oldest, promotion, fieldPromotions);
    }

    /** Whether a component's type can carry {@code role}; reports why not. */
    private boolean checkRole(Element rc, String where, HeaderRole role, JType t) {
        String j = t.javaName();
        boolean ok = switch (role) {
            case WIRE_VERSION, MAJOR, MINOR -> j.equals("byte") || j.equals("short") || j.equals("int");
            case SCHEMA_ID -> j.equals("int");
            case COMPAT -> t.ref() instanceof TypeRef.EnumRef e && e.name().equals(Compat.class.getCanonicalName());
            case SNAPSHOT -> j.equals("boolean");
            case FINGERPRINT -> j.equals("int") || j.equals("java.lang.Integer");
        };
        if (!ok) {
            String want = switch (role) {
                case WIRE_VERSION, MAJOR, MINOR -> "byte, short or int";
                case SCHEMA_ID -> "int";
                case COMPAT -> Compat.class.getCanonicalName();
                case SNAPSHOT -> "boolean";
                case FINGERPRINT -> "Integer (null when absent) or int";
            };
            error(rc, where + ": @Role(" + role + ") must be " + want + ", not " + j);
        } else if (role == HeaderRole.FINGERPRINT && !t.ref().isScalar(ScalarKind.FIXED32)) {
            warn(rc, where + ": the fingerprint is a hash, so as a varint it averages 5 bytes; @Field(fixed = true) makes it 4");
        }
        return ok;
    }

    /**
     * {@code @Field} or {@code @Role} on the record component, or failing that on the private field javac
     * propagates it to; the second is a fallback for records read from class files.
     */
    private static <A extends java.lang.annotation.Annotation> A annotation(TypeElement te, RecordComponentElement rc, Class<A> type) {
        A a = rc.getAnnotation(type);
        if (a != null) {
            return a;
        }
        for (Element e : te.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && e.getSimpleName().equals(rc.getSimpleName())) {
                return e.getAnnotation(type);
            }
        }
        return null;
    }

    private static boolean hasNonNullAnnotation(RecordComponentElement rc) {
        for (AnnotationMirror am : rc.asType().getAnnotationMirrors()) {
            if (NON_NULL_NAMES.contains(am.getAnnotationType().asElement().getSimpleName().toString())) {
                return true;
            }
        }
        for (AnnotationMirror am : rc.getAnnotationMirrors()) {
            if (NON_NULL_NAMES.contains(am.getAnnotationType().asElement().getSimpleName().toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInt(TypeRef t) {
        return switch (t) {
            case TypeRef.Scalar s -> s.kind() == ScalarKind.FIXED32 || s.kind() == ScalarKind.FIXED64;
            case TypeRef.ListOf l -> containsInt(l.element());
            case TypeRef.SetOf s -> containsInt(s.element());
            case TypeRef.MapOf m -> containsInt(m.key()) || containsInt(m.value());
            case TypeRef.OptionalOf o -> containsInt(o.value());
            default -> false;
        };
    }

    /** Whether a non-null field of this type has a natural empty value for payloads that predate it. */
    static boolean hasDefault(TypeRef t) {
        return t instanceof TypeRef.ListOf || t instanceof TypeRef.SetOf || t instanceof TypeRef.MapOf
                || t.isScalar(ScalarKind.STRING) || t.isScalar(ScalarKind.BYTES);
    }

    // ---- types -------------------------------------------------------------------------------------------------

    private JType map(TypeMirror tm, boolean fixed, boolean topLevel, Element where, String path) {
        switch (tm.getKind()) {
            case BOOLEAN:
                return JType.leaf(TypeRef.scalar(ScalarKind.BOOL), "boolean");
            case BYTE:
                return JType.leaf(TypeRef.scalar(ScalarKind.INT8), "byte");
            case SHORT:
                return JType.leaf(TypeRef.scalar(ScalarKind.INT16), "short");
            case INT:
                return JType.leaf(TypeRef.scalar(fixed ? ScalarKind.FIXED32 : ScalarKind.INT32), "int");
            case LONG:
                return JType.leaf(TypeRef.scalar(fixed ? ScalarKind.FIXED64 : ScalarKind.INT64), "long");
            case FLOAT:
                return JType.leaf(TypeRef.scalar(ScalarKind.FLOAT32), "float");
            case DOUBLE:
                return JType.leaf(TypeRef.scalar(ScalarKind.FLOAT64), "double");
            case CHAR:
                return JType.leaf(TypeRef.scalar(ScalarKind.CHAR), "char");
            case ARRAY:
                if (((ArrayType) tm).getComponentType().getKind() == TypeKind.BYTE) {
                    return JType.leaf(TypeRef.scalar(ScalarKind.BYTES), "byte[]");
                }
                error(where, path + ": arrays other than byte[] are not supported; use a List");
                return null;
            case DECLARED:
                return mapDeclared((DeclaredType) tm, fixed, topLevel, where, path);
            default:
                error(where, path + ": type " + tm + " is not supported");
                return null;
        }
    }

    private JType mapDeclared(DeclaredType dt, boolean fixed, boolean topLevel, Element where, String path) {
        TypeElement te = (TypeElement) dt.asElement();
        String qn = te.getQualifiedName().toString();
        List<? extends TypeMirror> targs = dt.getTypeArguments();
        switch (qn) {
            case "java.lang.Boolean":
                return JType.leaf(TypeRef.scalar(ScalarKind.BOOL), qn);
            case "java.lang.Byte":
                return JType.leaf(TypeRef.scalar(ScalarKind.INT8), qn);
            case "java.lang.Short":
                return JType.leaf(TypeRef.scalar(ScalarKind.INT16), qn);
            case "java.lang.Integer":
                return JType.leaf(TypeRef.scalar(fixed ? ScalarKind.FIXED32 : ScalarKind.INT32), qn);
            case "java.lang.Long":
                return JType.leaf(TypeRef.scalar(fixed ? ScalarKind.FIXED64 : ScalarKind.INT64), qn);
            case "java.lang.Float":
                return JType.leaf(TypeRef.scalar(ScalarKind.FLOAT32), qn);
            case "java.lang.Double":
                return JType.leaf(TypeRef.scalar(ScalarKind.FLOAT64), qn);
            case "java.lang.Character":
                return JType.leaf(TypeRef.scalar(ScalarKind.CHAR), qn);
            case "java.lang.String":
                return JType.leaf(TypeRef.scalar(ScalarKind.STRING), qn);
            case "java.util.UUID":
                return JType.leaf(TypeRef.scalar(ScalarKind.UUID), qn);
            case "java.time.Instant":
                return JType.leaf(TypeRef.scalar(ScalarKind.INSTANT), qn);
            case "java.util.List", "java.util.Set", "java.util.Optional": {
                if (targs.size() != 1) {
                    error(where, path + ": raw " + qn + " is not supported; give it a type argument");
                    return null;
                }
                if (qn.equals("java.util.Optional") && !topLevel) {
                    error(where, path + ": Optional is only supported as a field's outermost type");
                    return null;
                }
                JType e = mapArg(targs.get(0), fixed, where, path);
                if (e == null) {
                    return null;
                }
                TypeRef ref = switch (qn) {
                    case "java.util.List" -> new TypeRef.ListOf(e.ref());
                    case "java.util.Set" -> new TypeRef.SetOf(e.ref());
                    default -> new TypeRef.OptionalOf(e.ref());
                };
                return new JType(ref, qn + "<" + e.javaName() + ">", List.of(e), null);
            }
            case "java.util.Map": {
                if (targs.size() != 2) {
                    error(where, path + ": raw java.util.Map is not supported; give it type arguments");
                    return null;
                }
                JType k = mapArg(targs.get(0), fixed, where, path + "[key]");
                JType v = mapArg(targs.get(1), fixed, where, path + "[value]");
                if (k == null || v == null) {
                    return null;
                }
                return new JType(new TypeRef.MapOf(k.ref(), v.ref()), qn + "<" + k.javaName() + ", " + v.javaName() + ">",
                        List.of(k, v), null);
            }
            default:
                break;
        }
        if (te.getKind() == ElementKind.ENUM) {
            enums.computeIfAbsent(qn, n -> new EnumDef(n, te.getEnclosedElements().stream()
                    .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                    .map(e -> e.getSimpleName().toString()).toList()));
            return new JType(new TypeRef.EnumRef(qn), qn, List.of(), qn);
        }
        if (te.getKind() == ElementKind.RECORD && te.getAnnotation(IntrabufMessage.class) != null) {
            String codec = codecName(te);
            recordByCodec.put(codec, qn);
            return new JType(new TypeRef.MessageRef(schemaName(te)), qn, List.of(), codec);
        }
        if (te.getAnnotation(IntrabufBundle.class) != null) {
            error(where, path + ": " + qn + " is a bundle, which is always a payload of its own; it cannot be held by a"
                    + " message or another bundle");
            return null;
        }
        error(where, path + ": type " + qn + " is not supported; supported are primitives and their boxes, String, byte[], "
                + "UUID, Instant, enums, @IntrabufMessage records, and List, Set, Map and Optional of those");
        return null;
    }

    private JType mapArg(TypeMirror arg, boolean fixed, Element where, String path) {
        if (arg.getKind() != TypeKind.DECLARED && arg.getKind() != TypeKind.ARRAY) {
            error(where, path + ": type argument " + arg + " is not supported (no wildcards or type variables)");
            return null;
        }
        return map(arg, fixed, false, where, path + "[]");
    }

    // ---- whole-compilation checks ------------------------------------------------------------------------------

    /**
     * Checks that need every model: nesting compat rules, schema id collisions, and that each message's reachable
     * closure names each schema once. Computes fingerprints. Returns false if anything failed.
     */
    boolean link() {
        boolean ok = true;
        Map<Integer, String> nameById = new HashMap<>();
        Map<String, MessageModel> byName = new HashMap<>();
        for (MessageModel m : models.values()) {
            if (m.header()) {
                // A header has no id on the wire; it is the thing that carries ids.
                for (FieldModel f : m.fields()) {
                    ok &= checkNesting(m, f, f.type());
                }
                continue;
            }
            String other = nameById.putIfAbsent(m.id(), m.schemaName());
            if (other != null && !other.equals(m.schemaName())) {
                error(m.element(), "schemas '" + other + "' and '" + m.schemaName() + "' have the same id "
                        + sibarum.intrabuf.Hex.int32(m.id()) + "; give one a different @IntrabufMessage(id = ...)"
                        + " or, for derived ids, a different name");
                ok = false;
            }
            MessageModel sameName = byName.putIfAbsent(m.schemaName(), m);
            if (sameName != null && sameName.id() != m.id()) {
                error(m.element(), m.javaName() + " and " + sameName.javaName() + " are both schema '" + m.schemaName()
                        + "' but have different ids; every version of a schema must keep its id");
                ok = false;
            }
            for (FieldModel f : m.fields()) {
                ok &= checkNesting(m, f, f.type());
            }
            if (m.bundle()) {
                ok &= checkStandalone(m);
            }
        }
        if (!ok || !linkPromotions()) {
            return false;
        }
        for (MessageModel m : models.values()) {
            Integer fp = fingerprintOf(m);
            if (fp == null) {
                ok = false;
            } else {
                fingerprints.put(m.codecName(), fp);
            }
        }
        return ok;
    }

    /**
     * Marks every record a promotion starts from as a past version, then checks that each declared oldest is
     * reached by a chain of promotions, that no promotion is unreachable, and that only past versions hold past
     * versions.
     */
    private boolean linkPromotions() {
        boolean ok = true;
        for (MessageModel m : models.values()) {
            if (m.promotion() != null) {
                if (!models.containsKey(m.promotion().fromCodec())) {
                    return false; // already reported while building it
                }
                past.add(m.promotion().fromCodec());
            }
        }
        for (MessageModel m : models.values()) {
            if (m.header() || m.bundle()) {
                continue;
            }
            Version oldest = m.effectiveOldest();
            if (m.promotion() != null && !past.contains(m.codecName()) && oldest.major() == m.major()) {
                error(m.promotion().where(), m.element().getSimpleName() + "." + m.promotion().method()
                        + "() promotes from major " + (m.major() - 1) + ", but " + m.element().getSimpleName()
                        + " reads only from v" + oldest + ", so it never runs; declare oldest = \"" + (m.major() - 1)
                        + ".0\", or the version it should read from");
                ok = false;
            }
            MessageModel at = m;
            while (at.major() > oldest.major()) {
                if (at.promotion() == null) {
                    error(m.element(), m.schemaName() + ": " + m.element().getSimpleName() + " declares oldest = \""
                            + oldest + "\", but nothing promotes major " + (at.major() - 1) + " to major " + at.major()
                            + ". Add to " + at.javaName() + ": @Promote static " + at.element().getSimpleName()
                            + " from(<the major " + (at.major() - 1) + " record> old)");
                    ok = false;
                    break;
                }
                at = models.get(at.promotion().fromCodec());
            }
            if (at.major() == oldest.major() && at != m) {
                if (oldest.minor() > at.minor()) {
                    error(m.element(), m.element().getSimpleName() + " declares oldest = \"" + oldest + "\", newer than "
                            + at.javaName() + " (v" + at.major() + "." + at.minor() + "), the major " + at.major()
                            + " record that reads it");
                    ok = false;
                } else if (at.compat() == Compat.STRICT && oldest.minor() != at.minor()) {
                    error(m.element(), m.element().getSimpleName() + " declares oldest = \"" + oldest + "\", but "
                            + at.javaName() + " is STRICT and reads only v" + at.major() + "." + at.minor());
                    ok = false;
                }
            }
        }
        for (MessageModel m : models.values()) {
            if (past.contains(m.codecName())) {
                continue;
            }
            for (FieldModel f : m.fields()) {
                for (String codec : messageCodecs(f.type(), new ArrayList<>())) {
                    if (past.contains(codec)) {
                        MessageModel p = models.get(codec);
                        error(m.element(), m.element().getSimpleName() + "." + f.name() + " holds " + p.javaName()
                                + ", a past version of " + p.schemaName() + ". Past versions are only read, to be"
                                + " promoted, so only another past version may hold one");
                        ok = false;
                    }
                }
            }
        }
        return ok;
    }

    /** Whether {@code m} is a past version: a record some promotion starts from. Valid after {@link #link()}. */
    boolean isPast(MessageModel m) {
        return past.contains(m.codecName());
    }

    private boolean checkNesting(MessageModel m, FieldModel f, JType t) {
        boolean ok = true;
        if (t.ref() instanceof TypeRef.MessageRef) {
            MessageModel nested = models.get(t.className());
            if (nested == null) {
                return false; // already reported while building it
            }
            if (!m.compat().canContain(nested.compat())) {
                error(m.element(), m.element().getSimpleName() + "." + f.name() + ": a " + m.compat() + " message cannot contain "
                        + nested.schemaName() + ", which is " + nested.compat() + ". "
                        + (m.compat() == Compat.FULL ? "FULL messages may only contain FULL messages."
                        : "BACKWARD messages may only contain BACKWARD or FULL messages."));
                ok = false;
            }
        }
        for (JType a : t.args()) {
            ok &= checkNesting(m, f, a);
        }
        return ok;
    }

    /**
     * A bundle is written as a log whose rows stand alone, so a member's message may hold no reference to another
     * row (a nested message) or to a blob (a {@code byte[]}).
     */
    private boolean checkStandalone(MessageModel bundle) {
        boolean ok = true;
        for (FieldModel member : bundle.fields()) {
            MessageModel m = models.get(member.type().arg(0).className());
            if (m == null) {
                return false; // already reported while building it
            }
            for (FieldModel f : m.fields()) {
                String held = refersOut(f.type().ref());
                if (held != null) {
                    error(bundle.element(), bundle.element().getSimpleName() + "." + member.name() + ": "
                            + m.javaName() + "." + f.name() + " holds " + held + ", but a bundle's rows stand alone"
                            + " in its log, so its members' messages cannot hold messages or byte[] yet");
                    ok = false;
                }
            }
        }
        return ok;
    }

    /** What in {@code t} a text row would have to refer to another row or a blob for, or null if nothing. */
    private static String refersOut(TypeRef t) {
        return switch (t) {
            case TypeRef.MessageRef m -> "a message (" + m.name() + ")";
            case TypeRef.Scalar s -> s.kind() == ScalarKind.BYTES ? "byte[]" : null;
            case TypeRef.ListOf l -> refersOut(l.element());
            case TypeRef.SetOf s -> refersOut(s.element());
            case TypeRef.MapOf mp -> refersOut(mp.key()) != null ? refersOut(mp.key()) : refersOut(mp.value());
            case TypeRef.OptionalOf o -> refersOut(o.value());
            case TypeRef.EnumRef e -> null;
        };
    }

    private Integer fingerprintOf(MessageModel root) {
        Map<String, MessageModel> byName = new LinkedHashMap<>();
        Deque<MessageModel> todo = new ArrayDeque<>();
        todo.add(root);
        byName.put(root.schemaName(), root);
        while (!todo.isEmpty()) {
            MessageModel m = todo.poll();
            for (FieldModel f : m.fields()) {
                for (String codec : messageCodecs(f.type(), new ArrayList<>())) {
                    MessageModel n = models.get(codec);
                    MessageModel prev = byName.putIfAbsent(n.schemaName(), n);
                    if (prev == null) {
                        todo.add(n);
                    } else if (prev != n) {
                        error(root.element(), root.schemaName() + " reaches two different records named '" + n.schemaName()
                                + "': " + prev.javaName() + " and " + n.javaName());
                        return null;
                    }
                }
            }
        }
        return Fingerprint.of(root.schemaName(), name -> {
            MessageModel m = byName.get(name);
            return m == null ? null : new MessageDef(placeholder(m), m.defs());
        }, enums::get);
    }

    /** The codecs of the messages {@code m} holds directly, each once, in field order. */
    static List<String> nestedCodecs(MessageModel m) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (FieldModel f : m.fields()) {
            out.addAll(messageCodecs(f.type(), new ArrayList<>()));
        }
        return List.copyOf(out);
    }

    private static List<String> messageCodecs(JType t, List<String> out) {
        if (t.ref() instanceof TypeRef.MessageRef) {
            out.add(t.className());
        }
        for (JType a : t.args()) {
            messageCodecs(a, out);
        }
        return out;
    }

    private static SchemaInfo placeholder(MessageModel m) {
        return new SchemaInfo(m.schemaName(), m.javaName(), 0, m.major(), m.minor(), m.compat(), 0, false, "");
    }

    int fingerprint(MessageModel m) {
        return fingerprints.get(m.codecName());
    }
}
