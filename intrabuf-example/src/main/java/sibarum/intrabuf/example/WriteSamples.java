package sibarum.intrabuf.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Writes one payload per example schema into a directory, for trying the CLI: {@code WriteSamples [dir]}. */
public final class WriteSamples {

    private WriteSamples() {
    }

    public static Customer customer() {
        return new Customer(42L, "Ada Lovelace", "ada@example.com", true, Color.GREEN,
                new Address("12 St James's Square", "London", "SW1Y 4JH", null),
                List.of("vip", "early-adopter"), Optional.of("Ada"), Map.of("2025", 1200));
    }

    public static SensorReading reading() {
        return new SensorReading(UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2"),
                Instant.parse("2026-10-05T12:00:00.250Z"), 1007, 21.5, List.of(21.4f, 21.5f, 21.6f), Boolean.TRUE);
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/samples");
        Files.createDirectories(dir);
        Files.write(dir.resolve("customer.ib"), CustomerIntrabuf.toBytes(customer()));
        Files.write(dir.resolve("reading.ib"), SensorReadingIntrabuf.toBytes(reading()));
        Files.write(dir.resolve("address.ib"), AddressIntrabuf.toBytes(customer().home()));
        System.out.println("wrote samples to " + dir.toAbsolutePath());
    }
}
