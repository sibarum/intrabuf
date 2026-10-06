package sibarum.intrabuf.tests;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;

/**
 * Successive versions of the same schemas, declared side by side under one schema name each, the way two
 * deployments of a service would see them.
 */
public final class CompatSchemas {

    private CompatSchemas() {
    }

    // ---- BACKWARD ----------------------------------------------------------------------------------------------

    @IntrabufMessage(name = "test.Profile", compat = Compat.BACKWARD)
    public record ProfileV0(String name, int age) {
    }

    @IntrabufMessage(name = "test.Profile", minor = 1, compat = Compat.BACKWARD)
    public record ProfileV1(String name, int age,
                            @Field(since = 1) String email,
                            @Field(since = 1) boolean verified,
                            @Field(since = 1, nonNull = true) List<String> tags) {
    }

    @IntrabufMessage(name = "test.Order", compat = Compat.BACKWARD)
    public record OrderV0(long id, ProfileV0 owner) {
    }

    @IntrabufMessage(name = "test.Order", compat = Compat.BACKWARD)
    public record OrderV0WithProfileV1(long id, ProfileV1 owner) {
    }

    // ---- FULL --------------------------------------------------------------------------------------------------

    @IntrabufMessage(name = "test.Point", compat = Compat.FULL)
    public record PointV0(int x, int y) {
    }

    @IntrabufMessage(name = "test.Point", minor = 1, compat = Compat.FULL)
    public record PointV1(int x, int y, @Field(since = 1) Integer z, @Field(since = 1) boolean flagged) {
    }

    @IntrabufMessage(name = "test.Shape", compat = Compat.FULL)
    public record ShapeV0(String name, List<PointV0> points) {
    }

    @IntrabufMessage(name = "test.Shape", compat = Compat.FULL)
    public record ShapeWithPointV1(String name, List<PointV1> points) {
    }

    // ---- STRICT ------------------------------------------------------------------------------------------------

    @IntrabufMessage(name = "test.Token")
    public record TokenV0(String value) {
    }

    /** Same version numbers as TokenV0, different structure: the edit someone forgot to version. */
    @IntrabufMessage(name = "test.Token")
    public record TokenV0Edited(String value, int ttl) {
    }

    @IntrabufMessage(name = "test.Token", minor = 1)
    public record TokenV1(String value, int ttl) {
    }

    @IntrabufMessage(name = "test.Token", major = 2)
    public record TokenMajor2(String value) {
    }

    @IntrabufMessage(name = "test.Token", compat = Compat.BACKWARD)
    public record TokenBackward(String value) {
    }
}
