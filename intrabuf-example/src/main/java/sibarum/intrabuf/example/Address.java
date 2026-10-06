package sibarum.intrabuf.example;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

/**
 * FULL compatibility: readers on either side of a minor bump can exchange addresses. {@code unit} arrived in minor 1;
 * a v1.0 reader skips it, a v1.1 reader of a v1.0 payload sees null.
 */
@IntrabufMessage(name = "example.Address", minor = 1, compat = Compat.FULL)
public record Address(
        @Field(nonNull = true) String street,
        @Field(nonNull = true) String city,
        String postcode,
        @Field(since = 1) String unit) {
}
