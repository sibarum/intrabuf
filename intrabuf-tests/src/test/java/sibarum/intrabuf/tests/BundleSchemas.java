package sibarum.intrabuf.tests;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufBundle;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bundles written as logs: a trace in two versions, a FULL bundle in two versions. */
public final class BundleSchemas {

    private BundleSchemas() {
    }

    @IntrabufMessage(name = "test.Frame", compat = Compat.BACKWARD)
    public record Frame(Instant at, int workMicros, boolean idle) {
    }

    @IntrabufMessage(name = "test.KeyPress", compat = Compat.BACKWARD)
    public record KeyPressV0(Instant at, String key) {
    }

    @IntrabufMessage(name = "test.KeyPress", compat = Compat.BACKWARD, minor = 1)
    public record KeyPress(Instant at, String key, @Field(since = 1) Optional<String> text) {
    }

    /** No fields: its rows are just the member's name. */
    @IntrabufMessage(name = "test.Mark", compat = Compat.BACKWARD)
    public record Mark() {
    }

    @IntrabufMessage(name = "test.Note", compat = Compat.BACKWARD)
    public record Note(String text, List<String> tags, Map<String, Integer> counts) {
    }

    @IntrabufBundle(name = "test.Trace", compat = Compat.BACKWARD)
    public record TraceV0(List<Frame> frames, List<KeyPressV0> keys) {
    }

    @IntrabufBundle(name = "test.Trace", compat = Compat.BACKWARD, minor = 1)
    public record Trace(List<Frame> frames, List<KeyPress> keys, @Field(since = 1) List<Mark> marks,
                        @Field(since = 1) List<Note> notes) {
    }

    @IntrabufMessage(name = "test.Tick", compat = Compat.FULL)
    public record TickV0(long n) {
    }

    @IntrabufMessage(name = "test.Tick", compat = Compat.FULL, minor = 1)
    public record Tick(long n, @Field(since = 1) String label) {
    }

    @IntrabufMessage(name = "test.Flag", compat = Compat.FULL)
    public record Flag(String name) {
    }

    @IntrabufBundle(name = "test.Ticks", compat = Compat.FULL)
    public record TicksV0(List<TickV0> ticks) {
    }

    @IntrabufBundle(name = "test.Ticks", compat = Compat.FULL, minor = 1)
    public record Ticks(List<Tick> ticks, @Field(since = 1) List<Flag> flags) {
    }
}
