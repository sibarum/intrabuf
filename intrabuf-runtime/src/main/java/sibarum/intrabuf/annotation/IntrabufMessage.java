package sibarum.intrabuf.annotation;

import sibarum.intrabuf.Compat;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a codec for a record. For {@code com.acme.Order} the processor writes {@code com.acme.OrderIntrabuf},
 * whose {@code CODEC} encodes and decodes it, and adds the schema to the build's manifest.
 *
 * <p>CLASS retention: the annotation is in the class file, so the processor can read the schema of a record compiled
 * in another module, but it is never loaded at runtime and nothing reflects on it.
 *
 * <p>Supported component types: primitives and their boxes, {@code String}, {@code byte[]}, {@code UUID},
 * {@code Instant}, enums, other {@code @IntrabufMessage} records, {@code List}, {@code Set}, {@code Map} of those
 * (nested freely), and {@code Optional} of any of them as a field's outermost type.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface IntrabufMessage {

    /** Schema name, hashed into the wire id. Defaults to the record's canonical name; set it to survive a rename. */
    String name() default "";

    /**
     * The schema id on the wire. Empty (the default) derives it by hashing {@link #name()}; set it ({@code id = 7})
     * when the application assigns ids itself, e.g. to route by a type id in its own header. Every version of a
     * schema must keep the same id, and no two schemas may share one; the processor checks both within a
     * compilation, and {@code intrabuf schemas} reports collisions between manifests.
     */
    int[] id() default {};

    /** Breaking-change version. Readers reject every major but their own. */
    int major() default 1;

    /** Additive-change version: the highest {@link Field#since()} of any field. */
    int minor() default 0;

    Compat compat() default Compat.STRICT;
}
