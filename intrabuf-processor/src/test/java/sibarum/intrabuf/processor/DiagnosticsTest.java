package sibarum.intrabuf.processor;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every schema rule is a compile error, not a runtime surprise. Each case compiles one source with the processor and
 * checks the diagnostics.
 */
class DiagnosticsTest {

    private record Result(boolean success, String diagnostics, Path out) {
    }

    private static Result compile(String source) {
        try {
            Path out = Files.createTempDirectory("intrabuf-proc");
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(out.toFile()));
            JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///t/T.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            JavaCompiler.CompilationTask task = javac.getTask(null, fm, diags,
                    List.of("-classpath", System.getProperty("java.class.path"), "-proc:full",
                            "-Aintrabuf.buildVersion=1.0.0", "-Aintrabuf.module=t"),
                    null, List.of(file));
            task.setProcessors(List.of(new IntrabufProcessor()));
            boolean ok = task.call();
            String text = diags.getDiagnostics().stream()
                    .map(d -> d.getKind() + ": " + d.getMessage(Locale.ROOT))
                    .collect(Collectors.joining("\n"));
            return new Result(ok, text, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String HEADER = """
            package t;
            import sibarum.intrabuf.Compat;
            import sibarum.intrabuf.annotation.*;
            import java.util.*;
            """;

    private static void fails(String body, String expected) {
        Result r = compile(HEADER + body);
        assertTrue(!r.success(), "expected compilation to fail:\n" + r.diagnostics());
        assertTrue(r.diagnostics().contains(expected), "expected '" + expected + "' in:\n" + r.diagnostics());
    }

    @Test
    void validSchemaCompilesWithItsManifest() {
        Result r = compile(HEADER + """
                @IntrabufMessage(compat = Compat.BACKWARD, minor = 1)
                record T(int a, List<Map<String, Integer>> c, @Field(since = 1) Optional<String> b) {}
                """);
        assertTrue(r.success(), r.diagnostics());
        assertTrue(Files.exists(r.out().resolve("t/TIntrabuf.class")), "codec compiled");
        assertTrue(Files.exists(r.out().resolve("META-INF/intrabuf/t.json")), "manifest written");
    }

    @Test
    void onlyRecords() {
        fails("@IntrabufMessage class T {}", "applies only to records");
    }

    @Test
    void sinceBeyondMinor() {
        fails("@IntrabufMessage(compat = Compat.BACKWARD) record T(@Field(since = 1) int a) {}", "must be between 0 and the message's minor");
    }

    @Test
    void sinceOutOfOrder() {
        fails("@IntrabufMessage(compat = Compat.FULL, minor = 2) record T(@Field(since = 2) int a, @Field(since = 1) int b) {}",
                "newer fields must come after older ones");
    }

    @Test
    void nonNullAddedLaterNeedsADefault() {
        fails("@IntrabufMessage(name = \"t.U\") record U(int x) {}\n"
                        + "@IntrabufMessage(compat = Compat.BACKWARD, minor = 1) record T(int a, @Field(since = 1, nonNull = true) java.util.UUID u) {}",
                "has no default");
    }

    @Test
    void unsupportedTypes() {
        fails("@IntrabufMessage record T(Object o) {}", "type java.lang.Object is not supported");
        fails("@IntrabufMessage record T(int[] o) {}", "arrays other than byte[]");
        fails("@IntrabufMessage record T(List<? extends Number> o) {}", "no wildcards");
        fails("@IntrabufMessage record T(List<Optional<String>> o) {}", "Optional is only supported as a field's outermost type");
        fails("record N(int x) {}\n@IntrabufMessage record T(N n) {}", "type t.N is not supported");
    }

    @Test
    void compatNesting() {
        fails("@IntrabufMessage(name = \"t.S\") record S(int x) {}\n"
                + "@IntrabufMessage(compat = Compat.BACKWARD) record T(S s) {}", "cannot contain t.S, which is STRICT");
        fails("@IntrabufMessage(name = \"t.B\", compat = Compat.BACKWARD) record B(int x) {}\n"
                + "@IntrabufMessage(compat = Compat.FULL) record T(List<B> s) {}", "FULL messages may only contain FULL messages");
    }

    @Test
    void privateRecords() {
        fails("class Outer { @IntrabufMessage private record T(int a) {} }", "must not be private");
    }

    @Test
    void genericRecords() {
        fails("@IntrabufMessage record T<X>(int a) {}", "cannot be generic");
    }

    @Test
    void badSchemaName() {
        fails("@IntrabufMessage(name = \"a<b\") record T(int a) {}", "must be non-empty");
    }

    private static final String ROLES = """
            @Role(HeaderRole.WIRE_VERSION) byte w, @Role(HeaderRole.SCHEMA_ID) int id,
            @Role(HeaderRole.MAJOR) int major, @Role(HeaderRole.MINOR) int minor,
            @Role(HeaderRole.COMPAT) Compat compat, @Role(HeaderRole.SNAPSHOT) boolean snap
            """;

    @Test
    void validHeaderCompiles() {
        Result r = compile(HEADER + "import sibarum.intrabuf.HeaderRole;\n@IntrabufHeader record H(long corr, " + ROLES
                + ", @Role(HeaderRole.FINGERPRINT) @Field(fixed = true) Integer fp) {}");
        assertTrue(r.success(), r.diagnostics());
        assertTrue(Files.exists(r.out().resolve("t/HIntrabuf.class")), "header codec compiled");
    }

    @Test
    void headerRoles() {
        String h = "import sibarum.intrabuf.HeaderRole;\n"; // fails() prepends HEADER
        fails(h + "@IntrabufHeader record H(" + ROLES + ") {}", "no component with @Role(FINGERPRINT)");
        fails(h + "@IntrabufHeader record H(" + ROLES + ", @Role(HeaderRole.FINGERPRINT) Integer fp, @Role(HeaderRole.MAJOR) int m2) {}",
                "role MAJOR is already carried");
        fails(h + "@IntrabufHeader record H(" + ROLES.replace("int id", "long id") + ", @Role(HeaderRole.FINGERPRINT) Integer fp) {}",
                "@Role(SCHEMA_ID) must be int, not long");
        fails(h + "@IntrabufMessage record M(@Role(HeaderRole.MAJOR) int m) {}", "@Role is only meaningful in an @IntrabufHeader");
        fails(h + "@IntrabufMessage @IntrabufHeader record M(int m) {}", "not both");
    }

    @Test
    void fingerprintAsVarintWarns() {
        Result r = compile(HEADER + "import sibarum.intrabuf.HeaderRole;\n@IntrabufHeader record H(" + ROLES
                + ", @Role(HeaderRole.FINGERPRINT) Integer fp) {}");
        assertTrue(r.success(), r.diagnostics());
        assertTrue(r.diagnostics().contains("averages 5 bytes"), r.diagnostics());
    }

    @Test
    void explicitIds() {
        fails("@IntrabufMessage(name = \"t.A\", id = 5) record A(int x) {}\n"
                + "@IntrabufMessage(name = \"t.B\", id = 5) record B(int x) {}", "have the same id 0x00000005");
        fails("@IntrabufMessage(name = \"t.A\", id = 5) record A(int x) {}\n"
                + "@IntrabufMessage(name = \"t.A\", id = 6, minor = 1) record A1(int x) {}", "every version of a schema must keep its id");
        fails("@IntrabufMessage(id = {1, 2}) record A(int x) {}", "id takes one value");
    }

    @Test
    void sinceOnStrictWarns() {
        Result r = compile(HEADER + "@IntrabufMessage(minor = 1) record T(@Field(since = 1) int a) {}");
        assertTrue(r.success(), r.diagnostics());
        assertTrue(r.diagnostics().contains(Diagnostic.Kind.WARNING + ": T.a: since has no effect"), r.diagnostics());
    }
}
