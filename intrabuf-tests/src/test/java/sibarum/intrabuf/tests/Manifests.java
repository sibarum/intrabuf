package sibarum.intrabuf.tests;

import sibarum.intrabuf.dynamic.SchemaSet;
import sibarum.intrabuf.schema.Manifest;
import sibarum.intrabuf.schema.ManifestJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** The manifests this module and intrabuf-example generated, as the classpath holds them. */
final class Manifests {

    static final String TESTS = "META-INF/intrabuf/intrabuf-tests.json";
    static final String EXAMPLE = "META-INF/intrabuf/intrabuf-example.json";

    private Manifests() {
    }

    static Manifest load(String resource) {
        try (InputStream in = Manifests.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return ManifestJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static SchemaSet all() {
        return new SchemaSet().add(load(TESTS)).add(load(EXAMPLE));
    }

    /** Where a manifest lives on disk: its classes directory, or its jar. What the CLI's -m takes. */
    static Path location(String resource) {
        URL url = Manifests.class.getClassLoader().getResource(resource);
        try {
            if (url.getProtocol().equals("jar")) {
                String s = url.toString();
                return Path.of(new URL(s.substring("jar:".length(), s.indexOf("!/"))).toURI());
            }
            Path file = Path.of(url.toURI());
            return file.getParent().getParent().getParent();
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
