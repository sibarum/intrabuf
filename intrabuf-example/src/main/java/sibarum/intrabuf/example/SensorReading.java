package sibarum.intrabuf.example;

import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * STRICT (the default): the smallest, fastest encoding, for traffic between services deployed together. Any schema
 * change must bump the version, and the payload fingerprint catches it if someone forgets.
 */
@IntrabufMessage
public record SensorReading(
        @Field(nonNull = true) UUID device,
        @Field(nonNull = true) Instant at,
        @Field(fixed = true) int sequence,
        double celsius,
        @Field(nonNull = true) List<Float> samples,
        Boolean calibrated) {
}
