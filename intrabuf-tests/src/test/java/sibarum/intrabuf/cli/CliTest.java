package sibarum.intrabuf.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.intrabuf.example.CustomerIntrabuf;
import sibarum.intrabuf.example.WriteSamples;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CLI end to end, on payloads written by generated codecs and manifests found the way a user would find them. */
class CliTest {

    @TempDir
    Path dir;

    private record Result(int code, String out, String err) {
    }

    private static Result run(byte[] stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new IntrabufCli(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new ByteArrayInputStream(stdin)).run(args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String exampleManifests() {
        return location("META-INF/intrabuf/intrabuf-example.json").toString();
    }

    private static Path location(String resource) {
        var url = CliTest.class.getClassLoader().getResource(resource);
        try {
            if (url.getProtocol().equals("jar")) {
                String s = url.toString();
                return Path.of(new java.net.URI(s.substring("jar:".length(), s.indexOf("!/"))));
            }
            return Path.of(url.toURI()).getParent().getParent().getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void decodesAPayloadFileAsATree() throws IOException {
        Path payload = dir.resolve("customer.ib");
        Files.write(payload, CustomerIntrabuf.toBytes(WriteSamples.customer()));
        Result r = run(new byte[0], "decode", "-m", exampleManifests(), payload.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("example.Customer v1.2 BACKWARD from a SNAPSHOT build"), r.out());
        assertTrue(r.out().contains("name: \"Ada Lovelace\""), r.out());
        assertTrue(r.out().contains("favoriteColor: GREEN"), r.out());
        assertTrue(r.out().contains("home: example.Address @minor 1 {"), r.out());
        assertTrue(r.out().contains("nickname: \"Ada\""), r.out());
    }

    @Test
    void decodesHexFromStdinAsJson() {
        String hex = HexFormat.of().formatHex(CustomerIntrabuf.toBytes(WriteSamples.customer()));
        Result r = run(hex.getBytes(), "decode", "--json", "--hex", "-m", exampleManifests(), "-");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("\"name\": \"Ada Lovelace\""), r.out());
        assertTrue(r.out().contains("\"loyaltyPoints\": {\"2025\": 1200}"), r.out());
        assertTrue(r.out().contains("\"snapshot\": true"), r.out());
    }

    @Test
    void headerNeedsNoManifest() {
        String b64 = java.util.Base64.getEncoder().encodeToString(CustomerIntrabuf.toBytes(WriteSamples.customer()));
        Result r = run(new byte[0], "header", "--base64", b64);
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("version   1.2 BACKWARD"), r.out());
        assertTrue(r.out().contains("SNAPSHOT"), r.out());
    }

    @Test
    void listsAndDescribesSchemas() {
        Result list = run(new byte[0], "schemas", "-m", exampleManifests());
        assertEquals(0, list.code(), list.err());
        assertTrue(list.out().contains("example.Customer"), list.out());
        assertTrue(list.out().contains("RED, GREEN, BLUE"), list.out());

        Result one = run(new byte[0], "schema", "-m", exampleManifests(), "example.Customer");
        assertEquals(0, one.code(), one.err());
        assertTrue(one.out().contains("loyaltyPoints  map<string,int32>"), one.out());
    }

    @Test
    void unknownSchemaFailsWithExitCode1() {
        Path payload = dir.resolve("x.ib");
        try {
            Files.write(payload, sibarum.intrabuf.tests.TypeSchemas_InnerIntrabuf.toBytes(new sibarum.intrabuf.tests.TypeSchemas.Inner(1, "a")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Result r = run(new byte[0], "decode", "-m", exampleManifests(), payload.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("SCHEMA_MISMATCH"), r.err());
    }

    @Test
    void decodesApplicationFrames() {
        sibarum.intrabuf.IbWriter w = new sibarum.intrabuf.IbWriter();
        var codec = sibarum.intrabuf.tests.HeaderSchemas_PongIntrabuf.CODEC;
        sibarum.intrabuf.tests.HeaderSchemas_EnvelopeIntrabuf.CODEC.write(w,
                sibarum.intrabuf.tests.HeaderSchemas_EnvelopeIntrabuf.create(codec.schema(), (short) 1, (byte) 2, false, true, 555L));
        codec.encodeBody(new sibarum.intrabuf.tests.HeaderSchemas.Pong(7, "framed"), w);
        String hex = HexFormat.of().formatHex(w.toByteArray());
        String tests = location("META-INF/intrabuf/intrabuf-tests.json").toString();

        Result r = run(new byte[0], "decode", "-m", tests, "-m", exampleManifests(), "--header", "test.Envelope", "--hex", hex);
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("correlationId: 555"), r.out());
        assertTrue(r.out().contains("note: \"framed\""), r.out());

        Result h = run(new byte[0], "header", "-m", tests, "--header", "test.Envelope", "--hex", hex);
        assertEquals(0, h.code(), h.err());
        assertTrue(h.out().contains("schema id 0x00000003"), h.out());

        Result s = run(new byte[0], "schema", "-m", tests, "test.Envelope");
        assertEquals(0, s.code(), s.err());
        assertTrue(s.out().contains("SCHEMA_ID"), s.out());

        Result missing = run(new byte[0], "decode", "-m", tests, "--header", "nope", "--hex", hex);
        assertEquals(2, missing.code());
    }

    @Test
    void convertsToTextAndReadsText() throws IOException {
        Path binary = dir.resolve("customer.ib");
        Files.write(binary, CustomerIntrabuf.toBytes(WriteSamples.customer()));
        Result t = run(new byte[0], "text", "-m", exampleManifests(), binary.toString());
        assertEquals(0, t.code(), t.err());
        // The manifest-driven writer produces exactly what the generated codec does.
        assertEquals(CustomerIntrabuf.toText(WriteSamples.customer()), t.out().replace(System.lineSeparator(), "\n"));

        Path text = dir.resolve("customer.txt");
        Files.writeString(text, t.out());
        Result d = run(new byte[0], "decode", "-m", exampleManifests(), text.toString());
        assertEquals(0, d.code(), d.err());
        assertTrue(d.out().contains("bytes of text"), d.out());
        assertTrue(d.out().contains("name: \"Ada Lovelace\""), d.out());
        assertEquals(WriteSamples.customer(), CustomerIntrabuf.fromBytes(Files.readAllBytes(text)));

        Result h = run(new byte[0], "header", text.toString());
        assertEquals(0, h.code(), h.err());
        assertTrue(h.out().contains("schema    example.Customer"), h.out());
        assertTrue(h.out().contains("version   1.2 BACKWARD"), h.out());

        // Text in, text out.
        Result again = run(Files.readAllBytes(text), "text", "-m", exampleManifests(), "-");
        assertEquals(t.out(), again.out());
    }

    @Test
    void convertsApplicationFramesToText() {
        var codec = sibarum.intrabuf.tests.HeaderSchemas_PongIntrabuf.CODEC;
        var env = sibarum.intrabuf.tests.HeaderSchemas_EnvelopeIntrabuf.create(codec.schema(), (short) 1, (byte) 2, false, true, 555L);
        var pong = new sibarum.intrabuf.tests.HeaderSchemas.Pong(7, "framed\ntwice");
        sibarum.intrabuf.IbWriter w = new sibarum.intrabuf.IbWriter();
        sibarum.intrabuf.tests.HeaderSchemas_EnvelopeIntrabuf.CODEC.write(w, env);
        codec.encodeBody(pong, w);
        String tests = location("META-INF/intrabuf/intrabuf-tests.json").toString();
        Result r = run(new byte[0], "text", "-m", tests, "-m", exampleManifests(), "--header", "test.Envelope", "--hex",
                HexFormat.of().formatHex(w.toByteArray()));
        assertEquals(0, r.code(), r.err());
        assertEquals(sibarum.intrabuf.tests.HeaderSchemas_EnvelopeIntrabuf.CODEC.encodeText(env, codec, pong),
                r.out().replace(System.lineSeparator(), "\n"));
    }

    // ---- freeze ------------------------------------------------------------------------------------------------

    /** The example's manifest as release {@code version} of module {@code module}, written to a file. */
    private Path releaseManifest(String module, String version) throws IOException {
        sibarum.intrabuf.schema.Manifest m;
        try (var in = CliTest.class.getClassLoader().getResourceAsStream("META-INF/intrabuf/intrabuf-example.json")) {
            m = sibarum.intrabuf.schema.ManifestJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        var release = new sibarum.intrabuf.schema.Manifest(m.format(), m.wireVersion(), module, version, false,
                m.messages(), m.enums(), m.headers());
        Path p = dir.resolve(module + "-" + version + "-" + System.nanoTime() + ".json");
        Files.writeString(p, sibarum.intrabuf.schema.ManifestJson.write(release), StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void freezeCopiesAReleaseIntoHistoryOnce() throws IOException {
        Path history = dir.resolve("src/main/intrabuf/history");
        Path release = releaseManifest("intrabuf-example", "1.0.0");
        Result r = run(new byte[0], "freeze", "-m", release.toString(), history.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("froze intrabuf-example 1.0.0"), r.out());
        assertEquals(Files.readString(release), Files.readString(history.resolve("1.0.0.json")));

        r = run(new byte[0], "freeze", "-m", release.toString(), history.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("already frozen"), r.out());

        Path changed = releaseManifest("intrabuf-example", "1.0.0");
        Files.writeString(changed, Files.readString(changed).replace("\"example.Customer\"", "\"example.Client\""));
        r = run(new byte[0], "freeze", "-m", changed.toString(), history.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("a release is frozen once"), r.err());

        r = run(new byte[0], "freeze", "-m", releaseManifest("other-module", "2.0.0").toString(), history.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("is the history of module 'intrabuf-example'"), r.err());
    }

    @Test
    void freezeRefusesSnapshots() {
        Result r = run(new byte[0], "freeze", "-m", exampleManifests(), dir.resolve("history").toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("is from a snapshot build"), r.err());
        assertTrue(!Files.exists(dir.resolve("history").resolve("0.1.1-SNAPSHOT.json")));
        assertEquals(2, run(new byte[0], "freeze", "-m", exampleManifests()).code());
    }

    @Test
    void usageErrorsExitWith2() {
        assertEquals(2, run(new byte[0]).code());
        assertEquals(2, run(new byte[0], "frobnicate").code());
        assertEquals(2, run(new byte[0], "decode", "--hex", "zz").code());
    }
}
