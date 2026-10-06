package sibarum.intrabuf.example;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BACKWARD compatibility: newer services read older customers. Contains a FULL message, which is allowed (a BACKWARD
 * message may contain BACKWARD or FULL ones).
 */
@IntrabufMessage(name = "example.Customer", minor = 2, compat = Compat.BACKWARD)
public record Customer(
        long id,
        @Field(nonNull = true) String name,
        String email,
        boolean active,
        Color favoriteColor,
        Address home,
        @Field(nonNull = true) List<String> tags,
        @Field(since = 1) Optional<String> nickname,
        @Field(since = 2, nonNull = true) Map<String, Integer> loyaltyPoints) {
}
