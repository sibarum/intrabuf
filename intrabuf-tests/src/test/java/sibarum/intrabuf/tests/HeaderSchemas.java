package sibarum.intrabuf.tests;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufHeader;
import sibarum.intrabuf.annotation.IntrabufMessage;
import sibarum.intrabuf.annotation.Role;

import static sibarum.intrabuf.HeaderRole.COMPAT;
import static sibarum.intrabuf.HeaderRole.FINGERPRINT;
import static sibarum.intrabuf.HeaderRole.MAJOR;
import static sibarum.intrabuf.HeaderRole.MINOR;
import static sibarum.intrabuf.HeaderRole.SCHEMA_ID;
import static sibarum.intrabuf.HeaderRole.SNAPSHOT;
import static sibarum.intrabuf.HeaderRole.WIRE_VERSION;

/** An application frame header in the shape of elektro-Q's envelope, and messages with app-assigned ids. */
public final class HeaderSchemas {

    private HeaderSchemas() {
    }

    @IntrabufHeader(name = "test.Envelope")
    public record Envelope(
            short magic,
            byte protocolVersion,
            @Role(WIRE_VERSION) byte wire,
            @Role(SCHEMA_ID) int typeId,
            @Role(MAJOR) short major,
            @Role(MINOR) short minor,
            @Role(COMPAT) Compat compat,
            @Role(SNAPSHOT) boolean snapshot,
            @Role(FINGERPRINT) @Field(fixed = true) Integer fingerprint,
            boolean request,
            boolean reply,
            @Field(fixed = true) long correlationId) {
    }

    /** Byte-sized versions and an always-present fingerprint. */
    @IntrabufHeader(name = "test.Tight")
    public record Tight(
            @Role(WIRE_VERSION) byte wire,
            @Role(SCHEMA_ID) int id,
            @Role(MAJOR) byte major,
            @Role(MINOR) byte minor,
            @Role(COMPAT) Compat compat,
            @Role(SNAPSHOT) boolean snapshot,
            @Role(FINGERPRINT) @Field(fixed = true) int fingerprint) {
    }

    @IntrabufMessage(name = "test.Ping", id = 2)
    public record Ping(long seq) {
    }

    @IntrabufMessage(name = "test.Pong", id = 3, compat = Compat.BACKWARD)
    public record PongV0(long seq) {
    }

    @IntrabufMessage(name = "test.Pong", id = 3, minor = 1, compat = Compat.BACKWARD)
    public record Pong(long seq, @Field(since = 1) String note) {
    }
}
