package sibarum.intrabuf.processor;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.Hex;
import sibarum.intrabuf.schema.FieldDef;
import sibarum.intrabuf.schema.Manifest;
import sibarum.intrabuf.schema.ManifestJson;
import sibarum.intrabuf.schema.MessageDef;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The module's released manifests, committed to a history directory ({@code -Aintrabuf.history}), and the checks a
 * build makes against them: a released version never changes, and a past version is the one that shipped.
 */
final class History {

    /** A version of a schema as some release shipped it. */
    private record Released(MessageDef def, String build) {
    }

    private final Messager messager;
    /** By {@code name major.minor}; the first release that shipped each. */
    private final Map<String, Released> released = new LinkedHashMap<>();
    /** By {@code name major}: the highest minor released. */
    private final Map<String, Released> lastOfMajor = new LinkedHashMap<>();
    private final Set<String> checked = new HashSet<>();
    private boolean errors;

    private History(Messager messager) {
        this.messager = messager;
    }

    /**
     * Loads every {@code *.json} directly in {@code dir}: release manifests of {@code module}, each named after its
     * build version. Problems with the directory itself are reported as errors.
     */
    static History load(Path dir, String module, Messager messager) {
        History h = new History(messager);
        if (!Files.isDirectory(dir)) {
            h.error(null, "intrabuf: history directory " + dir + " does not exist; create it, or freeze a release"
                    + " into it with 'intrabuf freeze'");
            return h;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p)).sorted().toList();
        } catch (IOException e) {
            h.error(null, "intrabuf: cannot list history directory " + dir + ": " + e.getMessage());
            return h;
        }
        for (Path f : files) {
            Manifest m;
            try {
                m = ManifestJson.parse(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                h.error(null, "intrabuf: history file " + f + " is not a manifest: " + e.getMessage());
                continue;
            }
            String expected = m.buildVersion() + ".json";
            if (m.snapshot()) {
                h.error(null, "intrabuf: history file " + f + " is from a snapshot build; only releases are history");
            } else if (!m.module().equals(module)) {
                h.error(null, "intrabuf: history file " + f + " is module '" + m.module() + "'; this is module '"
                        + module + "', and each module keeps its own history");
            } else if (!f.getFileName().toString().equals(expected)) {
                h.error(null, "intrabuf: history file " + f + " holds release " + m.buildVersion() + "; name it " + expected);
            } else {
                h.add(m, f);
            }
        }
        return h;
    }

    private void add(Manifest m, Path file) {
        for (MessageDef d : m.messages()) {
            String key = d.name() + " " + d.major() + "." + d.minor();
            Released prev = released.get(key);
            if (prev == null) {
                released.put(key, new Released(d, m.buildVersion()));
            } else {
                String change = change(prev.def(), d.info().fingerprint(), d.compat(), d.fields());
                if (change != null) {
                    error(null, "intrabuf: history is inconsistent: " + d.name() + " v" + d.major() + "." + d.minor()
                            + " was released in " + prev.build() + " and differently in " + m.buildVersion() + " ("
                            + file.getFileName() + "): " + change);
                }
            }
            Released last = lastOfMajor.get(d.name() + " " + d.major());
            if (last == null || d.minor() > last.def().minor()) {
                lastOfMajor.put(d.name() + " " + d.major(), new Released(d, m.buildVersion()));
            }
        }
    }

    boolean hasErrors() {
        return errors;
    }

    /** Checks every message model not yet checked. Returns false if any check failed. */
    boolean check(Models models) {
        boolean ok = !errors;
        for (MessageModel m : models.all().values()) {
            if (m.header() || m.bundle() || !checked.add(m.codecName())) {
                continue;
            }
            Released r = released.get(m.schemaName() + " " + m.major() + "." + m.minor());
            if (r != null) {
                String change = change(r.def(), models.fingerprint(m), m.compat(), m.defs());
                if (change != null) {
                    error(m.element(), m.schemaName() + " v" + m.major() + "." + m.minor() + " was released in " + r.build()
                            + ", and a released version never changes, but " + m.javaName() + " differs: " + change
                            + ". Bump the minor for an added field, or the major for anything else");
                    ok = false;
                }
            }
            if (models.isPast(m)) {
                Released last = lastOfMajor.get(m.schemaName() + " " + m.major());
                if (last == null) {
                    error(m.element(), m.javaName() + " is a past version of " + m.schemaName() + ", but no release in"
                            + " history has major " + m.major() + ": a past version is one that shipped");
                    ok = false;
                } else if (m.minor() != last.def().minor()) {
                    error(m.element(), m.javaName() + " is v" + m.major() + "." + m.minor() + ", but the last release of "
                            + m.schemaName() + " major " + m.major() + " is v" + m.major() + "." + last.def().minor()
                            + " (in " + last.build() + "): a past version is the last release of its major, so it"
                            + " reads every " + m.major() + ".x payload");
                    ok = false;
                }
            }
        }
        return ok;
    }

    /**
     * How a version now differs from what was released, or null if it doesn't. A STRICT schema's identity is its
     * whole closure, so its fingerprint is compared. A BACKWARD or FULL message describes only its own fields:
     * nested messages carry their own versions and are checked on their own.
     */
    private static String change(MessageDef was, int fingerprint, Compat compat, List<FieldDef> fields) {
        if (compat != was.compat()) {
            return "it was " + was.compat() + " and is now " + compat;
        }
        if (compat == Compat.STRICT) {
            return fingerprint == was.info().fingerprint() ? null : "its fingerprint was " + Hex.int32(was.info().fingerprint())
                    + " and is now " + Hex.int32(fingerprint) + ", so it or something it holds changed";
        }
        List<FieldDef> old = was.fields();
        for (int i = 0; i < Math.max(old.size(), fields.size()); i++) {
            FieldDef a = i < old.size() ? old.get(i) : null;
            FieldDef b = i < fields.size() ? fields.get(i) : null;
            if (a == null || b == null || !a.equals(b)) {
                return "field " + i + " was " + describe(a) + " and is now " + describe(b);
            }
        }
        return null;
    }

    private static String describe(FieldDef f) {
        if (f == null) {
            return "absent";
        }
        return "'" + f.name() + " " + f.type() + (f.nullable() ? "?" : "") + (f.since() > 0 ? " since " + f.since() : "") + "'";
    }

    private void error(Element e, String msg) {
        errors = true;
        if (e == null) {
            messager.printMessage(Diagnostic.Kind.ERROR, msg);
        } else {
            messager.printMessage(Diagnostic.Kind.ERROR, msg, e);
        }
    }
}
