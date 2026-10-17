package sibarum.intrabuf.endpoint;

import sibarum.intrabuf.endpoint.EndpointDef.Member;
import sibarum.intrabuf.endpoint.EndpointDef.Side;

import java.util.List;

/**
 * What a peer tells the other before anything else: which endpoint it speaks, from which side, and what its build
 * writes and reads. Generated as {@code OrdersIntrabuf.client()} and {@code .server()}, exchanged as a
 * {@link Hello}, and compared with {@link Agreement#of}.
 *
 * @param buildVersion the version of the build, for messages
 * @param snapshot     whether the build is a snapshot, whose version numbers say nothing about its schemas
 */
public record Descriptor(EndpointDef endpoint, Role role, String buildVersion, boolean snapshot) {

    /** Which side of an endpoint a peer plays. */
    public enum Role {
        /** Sends requests, receives responses. */
        CLIENT,
        /** Sends responses, receives requests. */
        SERVER;

        public Role other() {
            return this == CLIENT ? SERVER : CLIENT;
        }

        /** The side of the endpoint this role sends. */
        public Side sends() {
            return this == CLIENT ? Side.REQUEST : Side.RESPONSE;
        }
    }

    /** The members this peer sends. */
    public List<Member> sends() {
        return endpoint.members().stream().filter(m -> m.side() == role.sends()).toList();
    }

    /** The members this peer receives. */
    public List<Member> receives() {
        return endpoint.members().stream().filter(m -> m.side() != role.sends()).toList();
    }

    @Override
    public String toString() {
        return endpoint.name() + " " + role.name().toLowerCase() + " (" + buildVersion + (snapshot ? ", snapshot" : "") + ")";
    }
}
