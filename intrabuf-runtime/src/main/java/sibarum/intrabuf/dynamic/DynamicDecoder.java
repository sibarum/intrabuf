package sibarum.intrabuf.dynamic;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.Header;
import sibarum.intrabuf.HeaderRole;
import sibarum.intrabuf.IbReader;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.dynamic.DynValue.DynEnum;
import sibarum.intrabuf.dynamic.DynValue.DynField;
import sibarum.intrabuf.dynamic.DynValue.DynList;
import sibarum.intrabuf.dynamic.DynValue.DynMap;
import sibarum.intrabuf.dynamic.DynValue.DynMessage;
import sibarum.intrabuf.schema.EnumDef;
import sibarum.intrabuf.schema.FieldDef;
import sibarum.intrabuf.schema.HeaderDef;
import sibarum.intrabuf.schema.Layout;
import sibarum.intrabuf.schema.MessageDef;
import sibarum.intrabuf.schema.TypeRef;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decodes a payload from its schema alone, for tools that have a manifest but not the generated codecs. Implements
 * the same body layout the processor generates code for, from the same {@link Layout}.
 */
public final class DynamicDecoder {

    private final SchemaSet schemas;
    private final Map<MessageDef, Layout> layouts = new HashMap<>();

    public DynamicDecoder(SchemaSet schemas) {
        this.schemas = schemas;
    }

    /**
     * @param trailingBytes bytes after the root message: unknown newer fields if the payload is from a newer FULL
     *                      minor, corruption otherwise
     */
    public record Decoded(Header header, DynMessage frame, MessageDef schema, DynMessage value, List<String> warnings,
                          int trailingBytes) {
    }

    /** Decodes a payload with intrabuf's built-in header. */
    public Decoded decode(byte[] payload) {
        return decode(payload, null);
    }

    /**
     * Decodes a payload whose header is the application format {@code format}, or the built-in one if null. The
     * header record is returned as {@link Decoded#frame()}, application fields and all.
     */
    public Decoded decode(byte[] payload, HeaderDef format) {
        IbReader r = new IbReader(payload);
        Header h;
        DynMessage frame = null;
        if (format == null) {
            h = Header.read(r);
        } else {
            frame = readBody(r, format.def(), format.def().minor(), new ArrayList<>());
            h = facts(frame, format, r.position());
        }
        SchemaSet.Selection sel = schemas.select(h);
        List<String> warnings = new ArrayList<>(sel.warnings());
        DynMessage value = readBody(r, sel.def(), h.minor(), warnings);
        int trailing = r.remaining();
        if (trailing > 0) {
            if (h.minor() > sel.def().minor()) {
                value = new DynMessage(value.type(), value.minor(), value.fields(), trailing);
            } else {
                warnings.add(trailing + " trailing byte(s) after the message; the payload may be corrupt");
            }
        }
        return new Decoded(h, frame, sel.def(), value, warnings, trailing);
    }

    /** An application header as read: its record, and the facts its role fields carry. */
    public record Frame(DynMessage record, Header facts) {
    }

    /** Reads only the application header at the start of {@code payload}; no message schema is needed. */
    public Frame readFrame(byte[] payload, HeaderDef format) {
        IbReader r = new IbReader(payload);
        DynMessage frame = readBody(r, format.def(), format.def().minor(), new ArrayList<>());
        return new Frame(frame, facts(frame, format, r.position()));
    }

    /** The header facts an application header carries, read from its role fields. */
    static Header facts(DynMessage frame, HeaderDef format, int size) {
        int wire = 0, id = 0, major = 0, minor = 0, fp = 0;
        Compat compat = null;
        boolean snapshot = false, hasFp = false;
        for (Map.Entry<String, HeaderRole> e : format.roles().entrySet()) {
            Object v = frame.get(e.getKey());
            switch (e.getValue()) {
                case WIRE_VERSION -> wire = number(v, e);
                case SCHEMA_ID -> id = number(v, e);
                case MAJOR -> major = number(v, e);
                case MINOR -> minor = number(v, e);
                case SNAPSHOT -> snapshot = Boolean.TRUE.equals(v);
                case FINGERPRINT -> {
                    hasFp = v != null;
                    fp = hasFp ? number(v, e) : 0;
                }
                case COMPAT -> {
                    if (!(v instanceof DynEnum de) || de.name() == null) {
                        throw new IntrabufException("header " + format.name() + "." + e.getKey() + " is not a known compat mode: " + v);
                    }
                    compat = Compat.valueOf(de.name());
                }
            }
        }
        return new Header(wire, snapshot, compat, id, major, minor, hasFp, fp, size);
    }

    private static int number(Object v, Map.Entry<String, HeaderRole> e) {
        if (!(v instanceof Number n)) {
            throw new IntrabufException("header field " + e.getKey() + " (" + e.getValue() + ") is not a number: " + v);
        }
        return n.intValue();
    }

    private Layout layout(MessageDef d) {
        return layouts.computeIfAbsent(d, x -> Layout.of(x.fields()));
    }

    private DynMessage readBody(IbReader r, MessageDef d, int minor, List<String> warnings) {
        Layout layout = layout(d);
        int nb = switch (d.compat()) {
            case STRICT -> layout.bitsetBytes(d.minor());
            case BACKWARD -> layout.bitsetBytes(minor);
            case FULL -> r.readCount();
        };
        int at = r.beginBits(nb);
        List<DynField> fields = new ArrayList<>(d.fields().size());
        for (int i = 0; i < d.fields().size(); i++) {
            FieldDef f = d.fields().get(i);
            if (d.compat() != Compat.STRICT && f.since() > minor) {
                continue; // not in the payload: the writer predates it
            }
            boolean optional = f.type() instanceof TypeRef.OptionalOf;
            TypeRef base = Layout.base(f.type());
            int pb = layout.presenceBit(i);
            boolean present = pb < 0 || bit(r, at, nb, pb);
            Object value;
            if (!present) {
                value = null;
            } else if (layout.valueBit(i) >= 0) {
                value = bit(r, at, nb, layout.valueBit(i));
            } else {
                value = read(r, base, f.name(), warnings);
            }
            fields.add(new DynField(f.name(), optional ? Optional.ofNullable(value) : value));
        }
        return new DynMessage(d.name(), d.compat() == Compat.STRICT ? -1 : minor, fields, 0);
    }

    private static boolean bit(IbReader r, int at, int nb, int bit) {
        return (r.bitsWord(at, nb, bit >>> 6) & (1L << (bit & 63))) != 0;
    }

    private Object read(IbReader r, TypeRef t, String where, List<String> warnings) {
        return switch (t) {
            case TypeRef.Scalar s -> switch (s.kind()) {
                case BOOL -> r.readBool();
                case INT8 -> r.readInt8();
                case INT16 -> r.readInt16();
                case INT32 -> r.readInt32();
                case INT64 -> r.readInt64();
                case FIXED32 -> r.readFixed32();
                case FIXED64 -> r.readFixed64();
                case FLOAT32 -> r.readFloat32();
                case FLOAT64 -> r.readFloat64();
                case CHAR -> r.readChar();
                case STRING -> r.readString();
                case BYTES -> r.readBytes();
                case UUID -> r.readUuid();
                case INSTANT -> r.readInstant();
            };
            case TypeRef.EnumRef e -> {
                int ord = r.readCount();
                EnumDef def = schemas.enumDef(e.name());
                if (def == null) {
                    throw new IntrabufException("no loaded manifest defines enum " + e.name());
                }
                String name = ord < def.constants().size() ? def.constants().get(ord) : null;
                if (name == null) {
                    warnings.add(where + ": ordinal " + ord + " is past the " + def.constants().size()
                            + " known constants of " + e.name());
                }
                yield new DynEnum(e.name(), ord, name);
            }
            case TypeRef.MessageRef m -> readNested(r, m.name(), warnings);
            case TypeRef.ListOf l -> readList(r, l.element(), false, where, warnings);
            case TypeRef.SetOf s -> readList(r, s.element(), true, where, warnings);
            case TypeRef.MapOf m -> {
                int n = r.readCount();
                List<Map.Entry<Object, Object>> entries = new ArrayList<>(r.capacityHint(n));
                for (int i = 0; i < n; i++) {
                    Object k = read(r, m.key(), where + "[key]", warnings);
                    Object v = read(r, m.value(), where + "[" + k + "]", warnings);
                    entries.add(new AbstractMap.SimpleImmutableEntry<>(k, v));
                }
                yield new DynMap(entries);
            }
            case TypeRef.OptionalOf o -> throw new IntrabufException(where + ": optional<> is only valid as a field's outermost type");
        };
    }

    private DynList readList(IbReader r, TypeRef element, boolean set, String where, List<String> warnings) {
        int n = r.readCount();
        List<Object> out = new ArrayList<>(r.capacityHint(n));
        for (int i = 0; i < n; i++) {
            out.add(read(r, element, where + "[" + i + "]", warnings));
        }
        return new DynList(set, out);
    }

    private DynMessage readNested(IbReader r, String name, List<String> warnings) {
        MessageDef d = schemas.latest(name);
        if (d == null) {
            throw new IntrabufException("no loaded manifest defines message " + name);
        }
        r.enter();
        DynMessage value = switch (d.compat()) {
            case STRICT -> readBody(r, d, d.minor(), warnings);
            case BACKWARD -> {
                int minor = r.readCount();
                if (minor > d.minor()) {
                    throw new IntrabufException("nested " + name + " is minor " + minor + ", newer than any loaded version (v"
                            + d.info().version() + "); a BACKWARD body cannot be skipped without its schema");
                }
                yield readBody(r, d, minor, warnings);
            }
            case FULL -> {
                int minor = r.readCount();
                int len = r.readCount();
                if (len > r.remaining()) {
                    throw new IntrabufException("nested " + name + " claims " + len + " bytes, " + r.remaining() + " remain");
                }
                int end = r.position() + len;
                DynMessage m = readBody(r, d, minor, warnings);
                if (r.position() > end) {
                    throw new IntrabufException("nested " + name + " overran its length prefix");
                }
                int unknown = end - r.position();
                r.position(end);
                yield unknown == 0 ? m : new DynMessage(m.type(), m.minor(), m.fields(), unknown);
            }
        };
        r.exit();
        return value;
    }
}
