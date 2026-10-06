package sibarum.intrabuf.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a record as an application's own payload header, replacing intrabuf's built-in one. Components marked
 * {@link Role} carry the facts intrabuf checks compatibility with (each {@link sibarum.intrabuf.HeaderRole} exactly
 * once); every other component is the application's, encoded alongside like any other field.
 *
 * <p>For {@code com.acme.Envelope} the processor writes {@code com.acme.EnvelopeIntrabuf}: a
 * {@link sibarum.intrabuf.HeaderCodec} {@code CODEC}, and a {@code create(SchemaInfo, ...)} factory that fills the
 * role components from a message's schema and takes the application's components as arguments. The header is
 * encoded as a STRICT body with no header of its own, so its layout is fixed: a change to it is a change to the
 * application's protocol, versioned however that protocol versions itself.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface IntrabufHeader {

    /** Name in the manifest, for {@code intrabuf decode --header}. Defaults to the record's canonical name. */
    String name() default "";
}
