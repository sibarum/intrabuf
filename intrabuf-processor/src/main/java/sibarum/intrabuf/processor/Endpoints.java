package sibarum.intrabuf.processor;

import sibarum.intrabuf.annotation.IntrabufEndpoint;
import sibarum.intrabuf.annotation.IntrabufMessage;
import sibarum.intrabuf.endpoint.Agreement;
import sibarum.intrabuf.endpoint.Descriptor;
import sibarum.intrabuf.endpoint.Descriptor.Role;
import sibarum.intrabuf.endpoint.EndpointDef;
import sibarum.intrabuf.endpoint.EndpointDef.Side;
import sibarum.intrabuf.endpoint.Version;
import sibarum.intrabuf.schema.Manifest;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static sibarum.intrabuf.schema.Json.quote;

/**
 * {@code @IntrabufEndpoint} types: what they declare, the checks on it, the generated descriptor class, and the
 * check of this build against the endpoint of every release in history from {@code oldestPeer} on.
 */
final class Endpoints {

    /** A request or response: the record's codec, and whether a peer may lack it. */
    record MemberModel(String codecName, String javaName, Side side, boolean optional, Element where) {
    }

    record EndpointModel(TypeElement element, String name, String codecName, String oldestPeer, List<MemberModel> members) {
        String packageName() {
            int dot = codecName.lastIndexOf('.');
            return dot < 0 ? "" : codecName.substring(0, dot);
        }

        String simpleName() {
            return codecName.substring(codecName.lastIndexOf('.') + 1);
        }
    }

    private final Elements elements;
    private final Messager messager;
    private final Models models;
    private boolean errors;

    Endpoints(ProcessingEnvironment env, Models models) {
        this.elements = env.getElementUtils();
        this.messager = env.getMessager();
        this.models = models;
    }

    private void error(Element e, String msg) {
        errors = true;
        messager.printMessage(Diagnostic.Kind.ERROR, msg, e);
    }

    /** Reads an endpoint declaration and builds the models of its messages. Returns null if it is invalid. */
    EndpointModel parse(TypeElement te) {
        boolean ok = true;
        if (te.getAnnotation(IntrabufMessage.class) != null || te.getKind() == ElementKind.RECORD) {
            error(te, "@IntrabufEndpoint goes on a type of its own (an interface), not on a record");
            return null;
        }
        IntrabufEndpoint ann = te.getAnnotation(IntrabufEndpoint.class);
        String name = ann.name().isEmpty() ? te.getQualifiedName().toString() : ann.name();
        List<TypeElement> requests = types(te, "requests");
        List<TypeElement> responses = types(te, "responses");
        List<TypeElement> optional = types(te, "optional");
        if (requests == null || responses == null || optional == null) {
            return null;
        }
        Set<String> optionalNames = new HashSet<>();
        for (TypeElement o : optional) {
            optionalNames.add(o.getQualifiedName().toString());
            if (!requests.contains(o) && !responses.contains(o)) {
                error(te, name + ": optional " + o.getSimpleName() + " is neither a request nor a response");
                ok = false;
            }
        }
        List<MemberModel> members = new ArrayList<>();
        for (Side side : Side.values()) {
            Set<String> seen = new LinkedHashSet<>();
            for (TypeElement rec : side == Side.REQUEST ? requests : responses) {
                String qn = rec.getQualifiedName().toString();
                if (!seen.add(qn)) {
                    error(te, name + ": " + rec.getSimpleName() + " is listed twice in " + plural(side));
                    ok = false;
                    continue;
                }
                MessageModel m = models.build(rec, !models.isLocal(rec));
                if (m == null) {
                    ok = false;
                    continue;
                }
                members.add(new MemberModel(m.codecName(), m.javaName(), side, optionalNames.contains(qn), te));
            }
        }
        return ok ? new EndpointModel(te, name, Models.codecName(te), ann.oldestPeer(), members) : null;
    }

    private static String plural(Side side) {
        return side == Side.REQUEST ? "requests" : "responses";
    }

    /** The records named by one of the annotation's class arrays, or null after reporting what isn't one. */
    private List<TypeElement> types(TypeElement te, String attribute) {
        for (AnnotationMirror am : te.getAnnotationMirrors()) {
            if (!((TypeElement) am.getAnnotationType().asElement()).getQualifiedName()
                    .contentEquals(IntrabufEndpoint.class.getCanonicalName())) {
                continue;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e
                    : elements.getElementValuesWithDefaults(am).entrySet()) {
                if (!e.getKey().getSimpleName().contentEquals(attribute)) {
                    continue;
                }
                List<TypeElement> out = new ArrayList<>();
                boolean ok = true;
                for (Object v : (List<?>) e.getValue().getValue()) {
                    TypeMirror tm = (TypeMirror) ((AnnotationValue) v).getValue();
                    TypeElement rec = tm instanceof DeclaredType dt ? (TypeElement) dt.asElement() : null;
                    if (rec == null || rec.getKind() != ElementKind.RECORD || rec.getAnnotation(IntrabufMessage.class) == null) {
                        error(te, te.getSimpleName() + ": " + attribute + " lists " + tm + ", which is not an @IntrabufMessage record");
                        ok = false;
                    } else {
                        out.add(rec);
                    }
                }
                return ok ? out : null;
            }
        }
        return List.of();
    }

    /** Checks that need linked models: only current versions are sent. */
    boolean check(EndpointModel e) {
        boolean ok = true;
        for (MemberModel mm : e.members()) {
            MessageModel m = models.all().get(mm.codecName());
            if (models.isPast(m)) {
                error(e.element(), e.name() + ": " + m.javaName() + " is a past version of " + m.schemaName()
                        + ", which is only read; an endpoint sends and receives the current version");
                ok = false;
            }
        }
        return ok;
    }

    /** The endpoint as the manifest and the compile-time agreement see it. */
    EndpointDef def(EndpointModel e) {
        List<EndpointDef.Member> members = new ArrayList<>();
        for (MemberModel mm : e.members()) {
            MessageModel m = models.all().get(mm.codecName());
            MessageModel.Version oldest = m.effectiveOldest();
            Set<EndpointDef.Nested> nested = new LinkedHashSet<>();
            closure(m, true, nested, new HashSet<>());
            for (MessageModel p = previous(m); p != null; p = previous(p)) {
                closure(p, false, nested, new HashSet<>());
            }
            members.add(new EndpointDef.Member(m.schemaName(), m.id(), m.compat(), models.fingerprint(m),
                    new Version(m.major(), m.minor()), new Version(oldest.major(), oldest.minor()), mm.side(), mm.optional(),
                    List.copyOf(nested)));
        }
        return new EndpointDef(e.name(), e.oldestPeer(), members);
    }

    private MessageModel previous(MessageModel m) {
        return m.promotion() == null ? null : models.all().get(m.promotion().fromCodec());
    }

    /** What {@code EndpointDef.Member.of} finds at runtime: every message held, depth first, in field order. */
    private void closure(MessageModel m, boolean written, Set<EndpointDef.Nested> out, Set<String> seen) {
        for (String codec : Models.nestedCodecs(m)) {
            if (seen.add(codec)) {
                MessageModel n = models.all().get(codec);
                out.add(new EndpointDef.Nested(n.schemaName(), n.compat(), new Version(n.major(), n.minor()), written));
                closure(n, written, out, seen);
            }
        }
    }

    /**
     * Runs the handshake against the endpoint of every release in history from {@code oldestPeer} on, in both roles,
     * as if this build were already released. Returns false after reporting each one that fails.
     */
    boolean checkAgainst(EndpointModel e, History history, String buildVersion) {
        if (e.oldestPeer().isEmpty()) {
            return true;
        }
        if (history == null) {
            error(e.element(), e.name() + ": oldestPeer = \"" + e.oldestPeer() + "\" is checked against the module's"
                    + " released manifests, so it needs -Aintrabuf.history");
            return false;
        }
        boolean ok = true;
        EndpointDef mine = def(e);
        for (Manifest release : history.releases()) {
            if (compareBuilds(release.buildVersion(), e.oldestPeer()) < 0) {
                continue;
            }
            EndpointDef theirs = release.endpoints().stream().filter(d -> d.name().equals(e.name())).findFirst().orElse(null);
            if (theirs == null) {
                error(e.element(), e.name() + ": release " + release.buildVersion() + " is at or after oldestPeer = \""
                        + e.oldestPeer() + "\" but has no endpoint " + e.name() + "; raise oldestPeer above it");
                ok = false;
                continue;
            }
            for (Role role : Role.values()) {
                Agreement a = Agreement.of(new Descriptor(mine, role, buildVersion, false),
                        new Descriptor(theirs, role.other(), release.buildVersion(), false));
                if (!a.ok()) {
                    error(e.element(), "this build doesn't work with release " + release.buildVersion() + ", which is at or"
                            + " after oldestPeer = \"" + e.oldestPeer() + "\":\n" + a.table() + "\nFix the schemas, or raise"
                            + " oldestPeer above " + release.buildVersion() + " to stop supporting it");
                    ok = false;
                }
            }
        }
        return ok;
    }

    /**
     * Orders build versions by their numeric parts ({@code 1.4.0 < 1.10.0}); a part that isn't a number compares as
     * text. {@code 1.0.0-rc1} sorts after {@code 1.0.0}, which is close enough for a window of releases.
     */
    static int compareBuilds(String a, String b) {
        String[] x = a.split("[.\\-+]");
        String[] y = b.split("[.\\-+]");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c;
            if (x[i].matches("\\d{1,9}") && y[i].matches("\\d{1,9}")) {
                c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            } else {
                c = x[i].compareTo(y[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    boolean hasErrors() {
        return errors;
    }

    /** The source of {@code <Endpoint>Intrabuf}: the endpoint, and a descriptor for each role. */
    String generate(EndpointModel e, String buildVersion, boolean snapshot) {
        String rt = "sibarum.intrabuf.endpoint.";
        StringBuilder sb = new StringBuilder();
        if (!e.packageName().isEmpty()) {
            sb.append("package ").append(e.packageName()).append(";\n\n");
        }
        sb.append("/**\n");
        sb.append(" * Endpoint {@code ").append(e.name()).append("}, declared by {@link ")
                .append(e.element().getQualifiedName()).append("}.\n");
        sb.append(" * Generated by intrabuf; do not edit.\n");
        sb.append(" */\n");
        sb.append("@javax.annotation.processing.Generated(\"sibarum.intrabuf.processor.IntrabufProcessor\")\n");
        sb.append("public final class ").append(e.simpleName()).append(" {\n\n");
        sb.append("    public static final String NAME = ").append(quote(e.name())).append(";\n");
        sb.append("    public static final String BUILD_VERSION = ").append(quote(buildVersion)).append(";\n");
        sb.append("    public static final boolean SNAPSHOT = ").append(snapshot).append(";\n\n");
        sb.append("    /** What this build writes and reads of each request and response. */\n");
        sb.append("    public static final ").append(rt).append("EndpointDef ENDPOINT = new ").append(rt)
                .append("EndpointDef(NAME, ").append(quote(e.oldestPeer())).append(", java.util.List.of(");
        for (int i = 0; i < e.members().size(); i++) {
            MemberModel m = e.members().get(i);
            sb.append(i == 0 ? "\n" : ",\n").append("            ").append(rt).append("EndpointDef.Member.of(")
                    .append(m.codecName()).append(".CODEC, ").append(rt).append("EndpointDef.Side.").append(m.side())
                    .append(", ").append(m.optional()).append(")");
        }
        sb.append("));\n\n");
        sb.append("    private ").append(e.simpleName()).append("() {\n    }\n\n");
        for (Role role : Role.values()) {
            String r = role.name().toLowerCase();
            sb.append("    /** This build as a ").append(r).append(" of the endpoint: send it as a {@code Hello}. */\n");
            sb.append("    public static ").append(rt).append("Descriptor ").append(r).append("() {\n");
            sb.append("        return new ").append(rt).append("Descriptor(ENDPOINT, ").append(rt).append("Descriptor.Role.")
                    .append(role).append(", BUILD_VERSION, SNAPSHOT);\n");
            sb.append("    }\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
