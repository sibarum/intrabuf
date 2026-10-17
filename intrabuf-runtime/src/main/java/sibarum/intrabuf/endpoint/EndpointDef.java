package sibarum.intrabuf.endpoint;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.MessageCodec;
import sibarum.intrabuf.SchemaInfo;

import java.util.List;

/**
 * An endpoint as one build declares it ({@code @IntrabufEndpoint}): the message schemas a client sends (requests)
 * and a server sends (responses), and for each, the version this build writes and the range it reads. It says
 * nothing about which side a build plays; a {@link Descriptor} adds that. Recorded in the manifest, so history knows
 * every release's endpoints.
 *
 * @param oldestPeer the oldest release this build promises to work with, or empty
 */
public record EndpointDef(String name, String oldestPeer, List<Member> members) {

    /** Which way a member travels: a client sends requests and a server sends responses. */
    public enum Side {
        REQUEST,
        RESPONSE
    }

    /**
     * One schema of the endpoint.
     *
     * @param version     the version this build writes, and the newest it reads
     * @param oldest      the oldest version this build reads
     * @param fingerprint the fingerprint of {@code version}
     * @param optional    whether a peer may lack it: then the handshake succeeds and the schema is not sent
     * @param nested      every message it holds, at any depth: what it writes ({@code written}), and what the codecs
     *                    of its past majors read
     */
    public record Member(String schema, int id, Compat compat, int fingerprint, Version version, Version oldest,
                         Side side, boolean optional, List<Nested> nested) {

        public Member {
            nested = List.copyOf(nested);
        }

        /** The member for a generated codec's schema. */
        public static Member of(MessageCodec<?> codec, Side side, boolean optional) {
            SchemaInfo s = codec.schema();
            java.util.LinkedHashSet<Nested> nested = new java.util.LinkedHashSet<>();
            closure(codec, true, nested, new java.util.HashSet<>());
            for (MessageCodec<?> p = codec.previousMajor(); p != null; p = p.previousMajor()) {
                closure(p, false, nested, new java.util.HashSet<>());
            }
            return new Member(s.name(), s.id(), s.compat(), s.fingerprint(), new Version(s.major(), s.minor()),
                    new Version(codec.oldestMajor(), codec.oldestMinor()), side, optional, List.copyOf(nested));
        }

        private static void closure(MessageCodec<?> c, boolean written, java.util.Set<Nested> out,
                                    java.util.Set<MessageCodec<?>> seen) {
            for (MessageCodec<?> n : c.nestedCodecs()) {
                if (seen.add(n)) {
                    SchemaInfo s = n.schema();
                    out.add(new Nested(s.name(), s.compat(), new Version(s.major(), s.minor()), written));
                    closure(n, written, out, seen);
                }
            }
        }

        /**
         * The nested message {@code schema} at {@code major} this member reads: through its current version if
         * {@code current}, else through a past major. Null if it holds none.
         */
        public Nested reads(String schema, int major, boolean current) {
            for (Nested n : nested) {
                if (n.schema().equals(schema) && n.version().major() == major && n.written() == current) {
                    return n;
                }
            }
            return null;
        }
    }

    /**
     * A message a member holds, at the version its build has.
     *
     * @param written whether the member writes it (it is in the current version), rather than only reading it
     *                through a past major
     */
    public record Nested(String schema, Compat compat, Version version, boolean written) {
    }

    public EndpointDef {
        members = List.copyOf(members);
    }

    /** The member for schema {@code id} on {@code side}, or null. */
    public Member member(int id, Side side) {
        for (Member m : members) {
            if (m.id() == id && m.side() == side) {
                return m;
            }
        }
        return null;
    }
}
