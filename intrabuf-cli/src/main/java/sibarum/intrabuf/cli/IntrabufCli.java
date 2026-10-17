package sibarum.intrabuf.cli;

import sibarum.intrabuf.Format;
import sibarum.intrabuf.Header;
import sibarum.intrabuf.TextDocument;
import sibarum.intrabuf.HeaderRole;
import sibarum.intrabuf.Hex;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.SchemaInfo;
import sibarum.intrabuf.dynamic.DynValue;
import sibarum.intrabuf.dynamic.DynamicDecoder;
import sibarum.intrabuf.dynamic.SchemaSet;
import sibarum.intrabuf.schema.EnumDef;
import sibarum.intrabuf.schema.FieldDef;
import sibarum.intrabuf.schema.HeaderDef;
import sibarum.intrabuf.schema.Json;
import sibarum.intrabuf.schema.Manifest;
import sibarum.intrabuf.schema.ManifestJson;
import sibarum.intrabuf.schema.MessageDef;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code intrabuf}: inspect payloads using the manifests builds emit. See {@link #USAGE}.
 */
public final class IntrabufCli {

    static final String USAGE = """
            usage: intrabuf <command> [options]

            commands:
              decode  -m <manifest>... [--header <name>] [--json] [--hex|--base64] <payload>
                      Print a payload's header and contents.
              text    -m <manifest>... [--header <name>] [--hex|--base64] <payload>
                      Print a payload in the text format, which every codec also reads: a starting
                      point for writing one by hand.
              header  [-m <manifest>... --header <name>] [--json] [--hex|--base64] <payload>
                      Print a payload's header; needs no manifest for the built-in header.
              schemas -m <manifest>...
                      List the schemas and header formats the manifests describe.
              schema  -m <manifest>... <name>
                      Show every loaded version of one schema (or a header format), field by field.
              freeze  -m <manifest> <history-dir>
                      Copy a release build's manifest into the module's history as <version>.json, for
                      -Aintrabuf.history to check later builds against. Refuses snapshots, and a
                      different manifest under a version already frozen.

            <payload> is a file, or '-' for stdin, in either format (binary, or text starting '#intrabuf').
            With --hex or --base64 it is a binary payload itself, as text.

            --header <name>         The payload starts with an application header (@IntrabufHeader) rather than
                                    intrabuf's built-in one: a frame as the application writes it.

            -m, --manifest <path>   A manifest .json, a jar holding META-INF/intrabuf/*.json, or a directory
                                    searched for them (target/classes, or a whole project). Repeatable; load
                                    several releases' manifests to decode payloads from any of them.
                                    INTRABUF_MANIFESTS (separated by '%s') is used when no -m is given.
            """.formatted(File.pathSeparator);

    private final PrintStream out;
    private final PrintStream err;
    private final InputStream in;

    IntrabufCli(PrintStream out, PrintStream err, InputStream in) {
        this.out = out;
        this.err = err;
        this.in = in;
    }

    public static void main(String[] args) {
        System.exit(new IntrabufCli(System.out, System.err, System.in).run(args));
    }

    private static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    private static final class Options {
        final List<Path> manifests = new ArrayList<>();
        final List<String> positional = new ArrayList<>();
        boolean json;
        String encoding; // null, "hex" or "base64"
        String header;   // application header format name, or null for the built-in header
    }

    int run(String[] args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("-h") || args[0].equals("--help")) {
            out.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        try {
            Options o = parse(args);
            return switch (args[0]) {
                case "decode" -> decode(o);
                case "text" -> text(o);
                case "header" -> header(o);
                case "schemas" -> schemas(o);
                case "schema" -> schema(o);
                case "freeze" -> freeze(o);
                default -> throw new UsageException("unknown command '" + args[0] + "'");
            };
        } catch (UsageException e) {
            err.println("intrabuf: " + e.getMessage());
            err.print(USAGE);
            return 2;
        } catch (IntrabufException e) {
            err.println("intrabuf: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("intrabuf: " + e.getMessage());
            return 1;
        }
    }

    private static Options parse(String[] args) throws UsageException {
        Options o = new Options();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-m", "--manifest" -> {
                    if (++i >= args.length) {
                        throw new UsageException(a + " needs a path");
                    }
                    o.manifests.add(Path.of(args[i]));
                }
                case "--header" -> {
                    if (++i >= args.length) {
                        throw new UsageException("--header needs a header format name");
                    }
                    o.header = args[i];
                }
                case "--json" -> o.json = true;
                case "--hex" -> o.encoding = "hex";
                case "--base64" -> o.encoding = "base64";
                default -> {
                    if (a.startsWith("-") && !a.equals("-")) {
                        throw new UsageException("unknown option '" + a + "'");
                    }
                    o.positional.add(a);
                }
            }
        }
        if (o.manifests.isEmpty()) {
            String env = System.getenv("INTRABUF_MANIFESTS");
            if (env != null && !env.isBlank()) {
                for (String p : env.split(File.pathSeparator)) {
                    if (!p.isBlank()) {
                        o.manifests.add(Path.of(p.strip()));
                    }
                }
            }
        }
        return o;
    }

    private SchemaSet schemaSet(Options o) throws IOException, UsageException {
        if (o.manifests.isEmpty()) {
            throw new UsageException("no manifests: pass -m <manifest|jar|dir> or set INTRABUF_MANIFESTS");
        }
        SchemaSet set = new SchemaSet();
        for (Path p : o.manifests) {
            for (ManifestLoader.Loaded l : ManifestLoader.load(p)) {
                set.add(l.manifest());
            }
        }
        return set;
    }

    private byte[] payload(Options o) throws IOException, UsageException {
        if (o.positional.size() != 1) {
            throw new UsageException("expected one payload, got " + o.positional.size());
        }
        String p = o.positional.getFirst();
        if (o.encoding != null) {
            String text = p.equals("-") ? new String(in.readAllBytes()).strip() : p;
            try {
                return o.encoding.equals("hex")
                        ? HexFormat.of().parseHex(text.replaceAll("[\\s:]", "").replaceFirst("^0[xX]", ""))
                        : Base64.getDecoder().decode(text.replaceAll("\\s", ""));
            } catch (IllegalArgumentException e) {
                throw new UsageException("payload is not valid " + o.encoding + ": " + e.getMessage());
            }
        }
        return p.equals("-") ? in.readAllBytes() : Files.readAllBytes(Path.of(p));
    }

    // ---- commands ----------------------------------------------------------------------------------------------

    private HeaderDef headerFormat(Options o, SchemaSet set) throws UsageException {
        if (o.header == null) {
            return null;
        }
        HeaderDef hd = set.header(o.header);
        if (hd == null) {
            hd = set.headers().stream().filter(x -> x.def().info().javaType().equals(o.header)).findFirst().orElse(null);
        }
        if (hd == null) {
            throw new UsageException("no loaded manifest defines header format '" + o.header + "'; known: "
                    + set.headers().stream().map(HeaderDef::name).toList());
        }
        return hd;
    }

    private int header(Options o) throws IOException, UsageException {
        SchemaSet set = o.header == null ? null : schemaSet(o);
        HeaderDef format = o.header == null ? null : headerFormat(o, set);
        byte[] payload = payload(o);
        Header h;
        DynValue.DynMessage frame = null;
        if (format == null) {
            h = Header.peek(payload);
        } else {
            DynamicDecoder.Frame f = new DynamicDecoder(set).readFrame(payload, format);
            h = f.facts();
            frame = f.record();
        }
        if (o.json) {
            StringBuilder sb = new StringBuilder(headerJson(h, payload.length));
            if (frame != null) {
                sb.setLength(sb.length() - 1);
                sb.append(", \"frame\": ");
                Render.json(sb, frame, 0);
                sb.append('}');
            }
            out.println(sb);
        } else {
            printHeader(h, payload);
            if (frame != null) {
                out.println("frame     " + Render.tree(frame).replace("\n", "\n          "));
            }
        }
        return 0;
    }

    private void printHeader(Header h, byte[] payload) {
        if (Format.of(payload) == Format.TEXT) {
            out.println("payload   " + payload.length + " bytes of text");
            out.println("schema    " + TextDocument.parse(payload, 0, payload.length).schemaName());
        } else {
            out.println("payload   " + payload.length + " bytes: header " + h.size() + ", body " + (payload.length - h.size()));
            out.println("header    " + Hex.bytes(payload, 0, h.size()));
        }
        out.println("schema id " + Hex.int32(h.schemaId()));
        out.println("version   " + h.version() + " " + h.compat());
        out.println("build     " + (h.snapshot() ? "SNAPSHOT (development build)" : "release"));
        out.println("structure " + (h.hasFingerprint() ? "fingerprint " + Hex.int32(h.fingerprint()) : "no fingerprint (release " + h.compat() + ")"));
        out.println("wire      format " + h.wireVersion());
    }

    private int decode(Options o) throws IOException, UsageException {
        SchemaSet set = schemaSet(o);
        HeaderDef format = headerFormat(o, set);
        byte[] payload = payload(o);
        DynamicDecoder.Decoded d = new DynamicDecoder(set).decode(payload, format);
        SchemaInfo s = d.schema().info();
        if (o.json) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"header\": ").append(headerJson(d.header(), payload.length));
            if (d.frame() != null) {
                sb.append(",\n  \"frame\": ");
                Render.json(sb, d.frame(), 1);
            }
            sb.append(",\n  \"schema\": {\"name\": ").append(Json.quote(s.name()))
                    .append(", \"javaType\": ").append(Json.quote(s.javaType()))
                    .append(", \"version\": ").append(Json.quote(s.version()))
                    .append(", \"buildVersion\": ").append(Json.quote(s.buildVersion())).append("}");
            sb.append(",\n  \"warnings\": [");
            for (int i = 0; i < d.warnings().size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(Json.quote(d.warnings().get(i)));
            }
            sb.append("],\n  \"value\": ");
            Render.json(sb, d.value(), 1);
            sb.append("\n}");
            out.println(sb);
            return 0;
        }
        Header h = d.header();
        out.println("# " + s.name() + " v" + h.version() + " " + h.compat()
                + (h.snapshot() ? " from a SNAPSHOT build" : "")
                + (h.hasFingerprint() ? ", fingerprint " + Hex.int32(h.fingerprint()) : "")
                + "; " + payload.length + (Format.of(payload) == Format.TEXT ? " bytes of text"
                : " bytes (header " + h.size() + ")"));
        out.println("# decoded with " + s.javaType() + " v" + s.version() + " from build " + s.buildVersion()
                + (h.hasFingerprint() && h.fingerprint() == s.fingerprint() ? " (fingerprint matches)" : ""));
        for (String w : d.warnings()) {
            out.println("# warning: " + w);
        }
        if (d.frame() != null) {
            out.println("# frame: " + Render.tree(d.frame()).replace("\n", "\n# "));
        }
        out.println(Render.tree(d.value()));
        return 0;
    }

    private int text(Options o) throws IOException, UsageException {
        SchemaSet set = schemaSet(o);
        HeaderDef format = headerFormat(o, set);
        DynamicDecoder.Decoded d = new DynamicDecoder(set).decode(payload(o), format);
        out.print(Render.text(d, set, format));
        return 0;
    }

    /**
     * Copies one release's manifest into a module's history directory as {@code <buildVersion>.json}. A release is
     * frozen once: the same manifest again is a no-op, a different one under the same version is an error.
     */
    private int freeze(Options o) throws IOException, UsageException {
        if (o.positional.size() != 1) {
            throw new UsageException("freeze takes one history directory, got " + o.positional.size());
        }
        if (o.manifests.isEmpty()) {
            throw new UsageException("freeze needs the release's manifest: -m <manifest|jar|dir>");
        }
        List<ManifestLoader.Loaded> loaded = new ArrayList<>();
        for (Path p : o.manifests) {
            loaded.addAll(ManifestLoader.load(p));
        }
        if (loaded.size() != 1) {
            throw new UsageException("freeze takes one module's manifest; found " + loaded.size() + ": "
                    + loaded.stream().map(ManifestLoader.Loaded::source).collect(Collectors.joining(", ")));
        }
        Manifest m = loaded.getFirst().manifest();
        if (m.snapshot()) {
            throw new IntrabufException(loaded.getFirst().source() + " is from a snapshot build (" + m.buildVersion()
                    + "); only releases go in history");
        }
        Path dir = Path.of(o.positional.getFirst());
        Files.createDirectories(dir);
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                String module;
                try {
                    module = ManifestJson.parse(Files.readString(f, StandardCharsets.UTF_8)).module();
                } catch (RuntimeException e) {
                    throw new IntrabufException(f + " is in the history directory but is not a manifest: " + e.getMessage());
                }
                if (!module.equals(m.module())) {
                    throw new IntrabufException(dir + " is the history of module '" + module + "' (" + f.getFileName()
                            + "); this manifest is module '" + m.module() + "'");
                }
            }
        }
        Path target = dir.resolve(m.buildVersion() + ".json");
        String json = ManifestJson.write(m);
        if (Files.exists(target)) {
            if (Files.readString(target, StandardCharsets.UTF_8).equals(json)) {
                out.println(m.module() + " " + m.buildVersion() + " is already frozen in " + target);
                return 0;
            }
            throw new IntrabufException(target + " already holds a different " + m.module() + " " + m.buildVersion()
                    + "; a release is frozen once");
        }
        Files.writeString(target, json, StandardCharsets.UTF_8);
        out.println("froze " + m.module() + " " + m.buildVersion() + " (" + m.messages().size() + " message schemas) into "
                + target);
        return 0;
    }

    private int schemas(Options o) throws IOException, UsageException {
        SchemaSet set = schemaSet(o);
        List<MessageDef> defs = new ArrayList<>(set.messages());
        defs.sort(Comparator.comparing(MessageDef::name).thenComparingInt(MessageDef::major).thenComparingInt(MessageDef::minor));
        int width = defs.stream().mapToInt(d -> d.name().length()).max().orElse(0);
        for (MessageDef d : defs) {
            SchemaInfo s = d.info();
            out.println(pad(s.name(), width + 1) + " v" + pad(s.version(), 6) + pad(s.compat().name(), 9)
                    + "id " + Hex.int32(s.id()) + "  fp " + Hex.int32(s.fingerprint()) + "  "
                    + d.fields().size() + " field(s)  " + s.buildVersion() + (s.snapshot() ? " (snapshot)" : ""));
        }
        for (EnumDef e : set.enums()) {
            out.println(pad(e.name(), width + 1) + " enum   " + String.join(", ", e.constants()));
        }
        for (HeaderDef h : set.headers()) {
            out.println(pad(h.name(), width + 1) + " header fp " + Hex.int32(h.def().info().fingerprint()) + "  "
                    + h.def().fields().size() + " field(s)  roles: " + h.roles().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")));
        }
        for (String c : set.idCollisions()) {
            out.println("warning: " + c);
        }
        return 0;
    }

    private int schema(Options o) throws IOException, UsageException {
        if (o.positional.size() != 1) {
            throw new UsageException("expected one schema name");
        }
        SchemaSet set = schemaSet(o);
        String name = o.positional.getFirst();
        List<MessageDef> defs = new ArrayList<>(set.byName(name));
        if (defs.isEmpty()) {
            defs.addAll(set.messages().stream().filter(d -> d.info().javaType().equals(name)).toList());
        }
        Map<String, HeaderRole> roles = Map.of();
        if (defs.isEmpty()) {
            HeaderDef h = set.header(name);
            if (h != null) {
                defs.add(h.def());
                roles = h.roles();
            }
        }
        if (defs.isEmpty()) {
            EnumDef e = set.enumDef(name);
            if (e != null) {
                out.println(e.name() + " enum");
                for (int i = 0; i < e.constants().size(); i++) {
                    out.println("  " + i + "  " + e.constants().get(i));
                }
                return 0;
            }
            err.println("intrabuf: no schema named " + name);
            return 1;
        }
        defs.sort(Comparator.comparingInt(MessageDef::major).thenComparingInt(MessageDef::minor));
        for (MessageDef d : defs) {
            SchemaInfo s = d.info();
            boolean isHeader = !roles.isEmpty();
            if (isHeader) {
                out.println(s.name() + " header format  (" + s.javaType() + ")");
                out.println("  fingerprint " + Hex.int32(s.fingerprint()) + "  build " + s.buildVersion());
            } else {
                out.println(s.name() + " v" + s.version() + " " + s.compat() + "  (" + s.javaType() + ")");
                out.println("  id " + Hex.int32(s.id()) + "  fingerprint " + Hex.int32(s.fingerprint()) + "  build "
                        + s.buildVersion() + (s.snapshot() ? " (snapshot)" : ""));
            }
            int nw = Math.max(5, d.fields().stream().mapToInt(f -> f.name().length()).max().orElse(0));
            int tw = Math.max(4, d.fields().stream().mapToInt(f -> f.type().toString().length()).max().orElse(0));
            out.println("  " + pad("#", 3) + pad("field", nw + 2) + pad("type", tw + 2) + pad("null", 6)
                    + (isHeader ? "role" : "since"));
            for (int i = 0; i < d.fields().size(); i++) {
                FieldDef f = d.fields().get(i);
                HeaderRole role = roles.get(f.name());
                out.println("  " + pad(String.valueOf(i), 3) + pad(f.name(), nw + 2) + pad(f.type().toString(), tw + 2)
                        + pad(f.nullable() ? "yes" : "no", 6) + (isHeader ? (role == null ? "-" : role.name()) : f.since()));
            }
            out.println();
        }
        return 0;
    }

    private static String headerJson(Header h, int payloadSize) {
        return "{\"wireVersion\": " + h.wireVersion() + ", \"schemaId\": " + Json.quote(Hex.int32(h.schemaId()))
                + ", \"major\": " + h.major() + ", \"minor\": " + h.minor()
                + ", \"compat\": " + (h.compat() == null ? "null" : Json.quote(h.compat().name())) + ", \"snapshot\": " + h.snapshot()
                + ", \"fingerprint\": " + (h.hasFingerprint() ? Json.quote(Hex.int32(h.fingerprint())) : "null")
                + ", \"headerBytes\": " + h.size() + ", \"payloadBytes\": " + payloadSize + "}";
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}
