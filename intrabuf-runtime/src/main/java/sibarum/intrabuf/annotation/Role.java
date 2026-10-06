package sibarum.intrabuf.annotation;

import sibarum.intrabuf.HeaderRole;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component of an {@link IntrabufHeader} record as carrying one of intrabuf's header facts. Role components
 * other than {@link HeaderRole#FINGERPRINT} are implicitly non-null.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Role {

    HeaderRole value();
}
