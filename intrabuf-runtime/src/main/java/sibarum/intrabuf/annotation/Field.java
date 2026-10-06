package sibarum.intrabuf.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional per-component settings. A component without it is {@code since = 0}, varint-encoded, and nullable if it
 * is a reference type (unless it carries a type annotation named {@code NonNull}, {@code Nonnull} or
 * {@code NotNull}, from any package).
 *
 * <p>Targets FIELD as well as RECORD_COMPONENT so the annotation is also propagated to the record's private field,
 * a second place the processor can find it when reading a record from a class file.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Field {

    /**
     * The minor version this field was added in. Fields must appear in non-decreasing {@code since} order, so a
     * newer field is always after every older one. Ignored in STRICT mode.
     */
    int since() default 0;

    /** Encode {@code int}/{@code long} values (including inside collections) as 4/8 fixed bytes instead of varints. */
    boolean fixed() default false;

    /** The field is never null: no presence bit is spent on it, and encoding null throws. */
    boolean nonNull() default false;
}
