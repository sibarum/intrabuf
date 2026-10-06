package sibarum.intrabuf.cli;

import sibarum.intrabuf.schema.Manifest;
import sibarum.intrabuf.schema.ManifestJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds manifests wherever a build leaves them: a {@code .json} file, a jar (every
 * {@code META-INF/intrabuf/*.json} inside), or a directory such as {@code target/classes} or a whole project
 * (searched for {@code META-INF/intrabuf/*.json}).
 */
final class ManifestLoader {

    private ManifestLoader() {
    }

    record Loaded(String source, Manifest manifest) {
    }

    static List<Loaded> load(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            return loadDirectory(p);
        }
        if (!Files.isRegularFile(p)) {
            throw new IOException("no such manifest, jar or directory: " + p);
        }
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".jar") || name.endsWith(".zip")) {
            return loadJar(p);
        }
        return List.of(parse(p.toString(), Files.readString(p, StandardCharsets.UTF_8)));
    }

    private static List<Loaded> loadDirectory(Path dir) throws IOException {
        List<Loaded> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir, 12)) {
            for (Path f : files.filter(ManifestLoader::isManifestPath).sorted().toList()) {
                out.add(parse(f.toString(), Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        if (out.isEmpty()) {
            throw new IOException("no META-INF/intrabuf/*.json under " + dir);
        }
        return out;
    }

    private static boolean isManifestPath(Path f) {
        int n = f.getNameCount();
        return n >= 3 && f.getFileName().toString().endsWith(".json")
                && f.getName(n - 2).toString().equals("intrabuf")
                && f.getName(n - 3).toString().equals("META-INF")
                && Files.isRegularFile(f);
    }

    private static List<Loaded> loadJar(Path jar) throws IOException {
        List<Loaded> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.isDirectory() && e.getName().startsWith(Manifest.RESOURCE_DIR) && e.getName().endsWith(".json")
                        && e.getName().indexOf('/', Manifest.RESOURCE_DIR.length()) < 0) {
                    try (InputStream in = zip.getInputStream(e)) {
                        out.add(parse(jar + "!/" + e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                    }
                }
            }
        }
        if (out.isEmpty()) {
            throw new IOException("no " + Manifest.RESOURCE_DIR + "*.json in " + jar);
        }
        return out;
    }

    private static Loaded parse(String source, String json) throws IOException {
        try {
            return new Loaded(source, ManifestJson.parse(json));
        } catch (RuntimeException e) {
            throw new IOException(source + ": " + e.getMessage(), e);
        }
    }
}
