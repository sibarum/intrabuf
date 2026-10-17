package sibarum.intrabuf.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A declared promotion: how a reader gets from one version of a schema to the next. Put it on a static method of an
 * {@code @IntrabufMessage} record. The generated codec calls it; nothing reflects on it.
 *
 * <p><b>A major promotion</b> ({@code field} empty) takes the record of the previous major of the same schema and
 * returns this record:
 *
 * <pre>
 *   &#64;Promote
 *   static Customer from(CustomerV1 old) {
 *       return CustomerIntrabuf.at2_0(old.id(), ...);   // the fields of minor 0; later ones are defaulted
 *   }
 * </pre>
 *
 * The parameter's record becomes a <em>past version</em>: read-only, decoded only to be promoted. The value the method
 * returns is treated as a payload at this major's minor 0, so this record's field promotions then run on it.
 *
 * <p><b>A field promotion</b> ({@code field} names a component added in a later minor) computes that component for a
 * payload that predates it, from the record as decoded at the older minor:
 *
 * <pre>
 *   &#64;Promote(field = "display")
 *   static String display(Customer older) { return older.first() + " " + older.last(); }
 * </pre>
 *
 * Without one, a field added later takes its type's default. With one, a non-null field may be added later even if
 * its type has no default.
 *
 * <p>A reader reads an older major only if its {@link IntrabufMessage#oldest()} reaches it, and the processor checks
 * that every major from there on has its promotion. STRICT schemas have no promotions.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Promote {

    /** The component this method computes for older payloads; empty for the promotion from the previous major. */
    String field() default "";
}
