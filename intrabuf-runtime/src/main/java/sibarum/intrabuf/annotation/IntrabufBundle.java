package sibarum.intrabuf.annotation;

import sibarum.intrabuf.Compat;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a codec for a bundle: one payload holding many top-level messages of the kinds the record lists. Every
 * component is a {@code List} of an {@code @IntrabufMessage} record, and is called a member:
 *
 * <pre>
 * &#64;IntrabufBundle(name = "app.Trace", compat = Compat.BACKWARD)
 * record Trace(List&lt;Frame&gt; frames, List&lt;KeyPress&gt; keys) {}
 * </pre>
 *
 * For {@code com.acme.Trace} the processor writes {@code com.acme.TraceIntrabuf}, whose {@code CODEC} writes and
 * reads the bundle as a text log: the facts line and one declaration per member, then rows appended one at a time
 * in any order. See {@code docs/bundle-format.md}.
 *
 * <p>Members evolve like fields: add one at the end with {@link Field#since()} and bump {@link #minor()}. They follow
 * the nesting rule (a bundle may contain only members at least as tolerant as itself). A log row stands alone, so for
 * now a member's message may not contain other messages or {@code byte[]} values.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface IntrabufBundle {

    /** Schema name, hashed into the id. Defaults to the record's canonical name. */
    String name() default "";

    /** The schema id. Empty (the default) derives it by hashing {@link #name()}. */
    int[] id() default {};

    /** Breaking-change version. Readers reject every major but their own. */
    int major() default 1;

    /** Additive-change version: the highest {@link Field#since()} of any member. */
    int minor() default 0;

    Compat compat() default Compat.STRICT;
}
