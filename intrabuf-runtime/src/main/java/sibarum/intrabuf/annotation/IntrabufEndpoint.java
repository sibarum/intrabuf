package sibarum.intrabuf.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an endpoint: the message types a client sends (requests) and a server sends (responses). For
 * {@code com.acme.Orders} the processor writes {@code com.acme.OrdersIntrabuf}, whose {@code client()} and
 * {@code server()} are the {@link sibarum.intrabuf.endpoint.Descriptor}s two peers exchange as a
 * {@link sibarum.intrabuf.endpoint.Hello} and compare with {@link sibarum.intrabuf.endpoint.Agreement#of}, so a
 * mismatch fails the connection on both sides before anything is sent.
 *
 * <pre>
 *   &#64;IntrabufEndpoint(name = "app.Orders", oldestPeer = "1.4.0",
 *           requests = {PlaceOrder.class, CancelOrder.class}, responses = {OrderPlaced.class},
 *           optional = CancelOrder.class)
 *   interface Orders { }
 * </pre>
 *
 * <p>The endpoint goes in the manifest. With {@link #oldestPeer()}, the processor also checks this build against the
 * endpoint of every release in history ({@code -Aintrabuf.history}) from that one on, both roles, and fails the build
 * with the same table the handshake would print.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface IntrabufEndpoint {

    /** The endpoint's name, which both peers must share. Defaults to the type's canonical name. */
    String name() default "";

    /**
     * The oldest release of this module the build promises to work with, in either role, as its build version.
     * Empty: no promise, and nothing is checked at compile time.
     */
    String oldestPeer() default "";

    /** The {@code @IntrabufMessage} records a client sends. */
    Class<?>[] requests() default {};

    /** The {@code @IntrabufMessage} records a server sends. */
    Class<?>[] responses() default {};

    /**
     * Requests or responses a peer may lack. A peer that can't take one still agrees, and the schema isn't sent to it.
     */
    Class<?>[] optional() default {};
}
