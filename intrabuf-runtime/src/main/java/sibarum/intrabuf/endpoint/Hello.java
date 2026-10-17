package sibarum.intrabuf.endpoint;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.IbReader;
import sibarum.intrabuf.IbWriter;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.endpoint.Descriptor.Role;
import sibarum.intrabuf.endpoint.EndpointDef.Member;
import sibarum.intrabuf.endpoint.EndpointDef.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Descriptor} on the wire: the first thing each peer sends. How it travels is the application's business,
 * like any other payload.
 *
 * <p>Its layout is intrabuf's own and only ever grows, so every intrabuf from now on reads every other one's Hello:
 *
 * <pre>
 *   'I' 'B' 'H'          magic, which no payload starts with (binary headers start 0xB_, text '#')
 *   varint               Hello format, 1
 *   string               endpoint name
 *   string               oldestPeer, or empty
 *   varint               role: 0 client, 1 server
 *   string               build version
 *   bool                 snapshot
 *   varint               member count, then each member:
 *     varint             length of what follows, so a reader skips fields added after it
 *     string             schema name
 *     fixed32            schema id
 *     varint             compat: 0 STRICT, 1 BACKWARD, 2 FULL
 *     fixed32            fingerprint of the version written
 *     varint, varint     version written (major, minor)
 *     varint, varint     oldest version read
 *     varint             side: 0 request, 1 response
 *     bool               optional
 *     varint             nested count, then each message the member holds:
 *       string           schema name
 *       varint           compat
 *       varint, varint   version
 *       bool             written (in the current version), not only read through a past major
 *   ...                  anything after the members is from a newer intrabuf and is ignored
 * </pre>
 */
public final class Hello {

    private static final byte[] MAGIC = {'I', 'B', 'H'};
    public static final int FORMAT = 1;

    private Hello() {
    }

    public static byte[] encode(Descriptor d) {
        IbWriter w = new IbWriter(256);
        w.writeRaw(MAGIC);
        w.writeVarint(FORMAT);
        w.writeString(d.endpoint().name());
        w.writeString(d.endpoint().oldestPeer());
        w.writeVarint(d.role().ordinal());
        w.writeString(d.buildVersion());
        w.writeBool(d.snapshot());
        w.writeVarint(d.endpoint().members().size());
        for (Member m : d.endpoint().members()) {
            int mark = w.beginLength();
            w.writeString(m.schema());
            w.writeFixed32(m.id());
            w.writeVarint(m.compat().ordinal());
            w.writeFixed32(m.fingerprint());
            w.writeVarint(m.version().major());
            w.writeVarint(m.version().minor());
            w.writeVarint(m.oldest().major());
            w.writeVarint(m.oldest().minor());
            w.writeVarint(m.side().ordinal());
            w.writeBool(m.optional());
            w.writeVarint(m.nested().size());
            for (EndpointDef.Nested n : m.nested()) {
                w.writeString(n.schema());
                w.writeVarint(n.compat().ordinal());
                w.writeVarint(n.version().major());
                w.writeVarint(n.version().minor());
                w.writeBool(n.written());
            }
            w.endLength(mark);
        }
        return w.toByteArray();
    }

    /** Whether {@code bytes} starts like a Hello. */
    public static boolean is(byte[] bytes) {
        return bytes.length >= MAGIC.length && bytes[0] == MAGIC[0] && bytes[1] == MAGIC[1] && bytes[2] == MAGIC[2];
    }

    public static Descriptor decode(byte[] bytes) {
        if (!is(bytes)) {
            throw new IntrabufException("not an intrabuf Hello: it doesn't start 'IBH'");
        }
        IbReader r = new IbReader(bytes);
        r.skip(MAGIC.length);
        int format = r.readCount();
        if (format < 1) {
            throw new IntrabufException("Hello format " + format + " is not one intrabuf writes");
        }
        String name = r.readString();
        String oldestPeer = r.readString();
        Role role = pick(Role.values(), r.readCount(), "role");
        String build = r.readString();
        boolean snapshot = r.readBool();
        int n = r.readCount();
        List<Member> members = new ArrayList<>(r.capacityHint(n));
        for (int i = 0; i < n; i++) {
            int len = r.readCount();
            if (len > r.remaining()) {
                throw new IntrabufException("Hello member " + i + " claims " + len + " bytes, " + r.remaining() + " remain");
            }
            int end = r.position() + len;
            String schema = r.readString();
            int id = r.readFixed32();
            Compat compat = pick(Compat.values(), r.readCount(), "compat mode");
            int fp = r.readFixed32();
            Version version = new Version(r.readCount(), r.readCount());
            Version oldest = new Version(r.readCount(), r.readCount());
            Side side = pick(Side.values(), r.readCount(), "side");
            boolean optional = r.readBool();
            int nn = r.readCount();
            List<EndpointDef.Nested> nested = new ArrayList<>(r.capacityHint(nn));
            for (int k = 0; k < nn; k++) {
                String ns = r.readString();
                Compat nc = pick(Compat.values(), r.readCount(), "compat mode");
                Version nv = new Version(r.readCount(), r.readCount());
                nested.add(new EndpointDef.Nested(ns, nc, nv, r.readBool()));
            }
            if (r.position() > end) {
                throw new IntrabufException("Hello member " + schema + " is longer than its length prefix");
            }
            r.position(end);
            members.add(new Member(schema, id, compat, fp, version, oldest, side, optional, nested));
        }
        return new Descriptor(new EndpointDef(name, oldestPeer, members), role, build, snapshot);
    }

    private static <E> E pick(E[] values, int ordinal, String what) {
        if (ordinal >= values.length) {
            throw new IntrabufException("Hello has " + what + " " + ordinal + ", which this intrabuf doesn't know");
        }
        return values[ordinal];
    }
}
