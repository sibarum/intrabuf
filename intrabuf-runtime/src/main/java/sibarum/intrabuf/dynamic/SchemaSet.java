package sibarum.intrabuf.dynamic;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.Hex;
import sibarum.intrabuf.Header;
import sibarum.intrabuf.IncompatiblePayloadException;
import sibarum.intrabuf.IncompatiblePayloadException.Reason;
import sibarum.intrabuf.schema.EnumDef;
import sibarum.intrabuf.schema.Manifest;
import sibarum.intrabuf.schema.MessageDef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Schemas from any number of manifests. Several versions of one schema may be loaded at once (say, the manifests of
 * the last few releases), and {@link #select} picks the right one for a payload.
 */
public final class SchemaSet {

    private final List<MessageDef> messages = new ArrayList<>();
    private final Map<String, EnumDef> enums = new LinkedHashMap<>();
    private final List<Manifest> manifests = new ArrayList<>();

    public SchemaSet add(Manifest m) {
        manifests.add(m);
        for (MessageDef d : m.messages()) {
            boolean dup = messages.stream().anyMatch(x -> x.name().equals(d.name()) && x.major() == d.major()
                    && x.minor() == d.minor() && x.info().fingerprint() == d.info().fingerprint());
            if (!dup) {
                messages.add(d);
            }
        }
        for (EnumDef e : m.enums()) {
            // Constants may only be appended, so the longer list knows everything the shorter one does.
            enums.merge(e.name(), e, (a, b) -> b.constants().size() > a.constants().size() ? b : a);
        }
        return this;
    }

    public List<Manifest> manifests() {
        return List.copyOf(manifests);
    }

    public List<MessageDef> messages() {
        return List.copyOf(messages);
    }

    public List<EnumDef> enums() {
        return List.copyOf(enums.values());
    }

    public EnumDef enumDef(String name) {
        return enums.get(name);
    }

    public List<MessageDef> byName(String name) {
        return messages.stream().filter(d -> d.name().equals(name)).toList();
    }

    /** The highest version of {@code name}, used for nested messages, which carry no major on the wire. */
    public MessageDef latest(String name) {
        return messages.stream().filter(d -> d.name().equals(name))
                .max(Comparator.comparingInt(MessageDef::major).thenComparingInt(MessageDef::minor))
                .orElse(null);
    }

    public record Selection(MessageDef def, List<String> warnings) {
    }

    /**
     * The definition to decode a payload with. Prefers an exact fingerprint match; otherwise applies the same rules a
     * generated reader would, except that it explains rather than refuses where it can still decode: an inspector
     * should show a drifted snapshot payload, flagged, not hide it.
     */
    public Selection select(Header h) {
        List<MessageDef> byId = messages.stream().filter(d -> d.info().id() == h.schemaId()).toList();
        if (byId.isEmpty()) {
            throw new IncompatiblePayloadException(Reason.SCHEMA_MISMATCH, "no loaded manifest has a schema with id "
                    + Hex.int32(h.schemaId()), null, h);
        }
        String name = byId.getFirst().name();
        List<MessageDef> candidates = byId.stream()
                .filter(d -> d.major() == h.major() && d.compat() == h.compat()).toList();
        if (candidates.isEmpty()) {
            throw new IncompatiblePayloadException(Reason.MAJOR_MISMATCH, name + " payload is v" + h.version() + " "
                    + h.compat() + "; loaded: " + versions(byId), null, h);
        }
        List<String> warnings = new ArrayList<>();
        if (h.hasFingerprint()) {
            for (MessageDef d : candidates) {
                if (d.info().fingerprint() == h.fingerprint() && d.minor() == h.minor()) {
                    return new Selection(d, warnings);
                }
            }
            for (MessageDef d : candidates) {
                if (d.info().fingerprint() == h.fingerprint()) {
                    return new Selection(d, warnings);
                }
            }
            warnings.add("no loaded " + name + " has the payload's fingerprint " + Hex.int32(h.fingerprint())
                    + (h.snapshot() ? " (snapshot build: its schema may differ from every loaded one)" : ""));
        }
        if (h.compat() == Compat.STRICT) {
            for (MessageDef d : candidates) {
                if (d.minor() == h.minor()) {
                    return new Selection(d, warnings);
                }
            }
            throw new IncompatiblePayloadException(Reason.MINOR_MISMATCH, name + " is STRICT and no loaded version is v"
                    + h.version() + "; loaded: " + versions(byId), null, h);
        }
        MessageDef best = candidates.stream().filter(d -> d.minor() >= h.minor())
                .max(Comparator.comparingInt(MessageDef::minor)).orElse(null);
        if (best != null) {
            return new Selection(best, warnings);
        }
        if (h.compat() == Compat.FULL) {
            MessageDef newest = candidates.stream().max(Comparator.comparingInt(MessageDef::minor)).orElseThrow();
            warnings.add("payload is v" + h.version() + ", newer than any loaded " + name + " (newest v"
                    + newest.info().version() + "); fields it added are shown as unknown bytes");
            return new Selection(newest, warnings);
        }
        throw new IncompatiblePayloadException(Reason.NEWER_MINOR, name + " payload is v" + h.version()
                + ", newer than any loaded version (" + versions(byId) + ")", null, h);
    }

    private static String versions(List<MessageDef> defs) {
        return defs.stream().map(d -> "v" + d.info().version() + " " + d.compat()).collect(Collectors.joining(", "));
    }
}
