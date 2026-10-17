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
        fails(h + "@IntrabufMessage @IntrabufHeader record M(int m) {}", "not several");
    }

    private static final String MEMBER = "@IntrabufMessage(name = \"t.F\", compat = Compat.BACKWARD) record F(long at) {}\n";

    @Test
    void validBundleCompilesOutsideTheManifest() {
        Result r = compile(HEADER + MEMBER
                + "@IntrabufBundle(compat = Compat.BACKWARD, minor = 1) record B(List<F> frames, @Field(since = 1) List<F> more) {}");
        assertTrue(r.success(), r.diagnostics());
        assertTrue(Files.exists(r.out().resolve("t/BIntrabuf.class")), "bundle codec compiled");
        try {
            String manifest = Files.readString(r.out().resolve("META-INF/intrabuf/t.json"));
            assertTrue(manifest.contains("\"t.F\"") && !manifest.contains("\"t.B\""), manifest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void bundleMembers() {
        fails(MEMBER + "@IntrabufBundle record B(F frame) {}", "a bundle's members are lists of @IntrabufMessage records");
        fails(MEMBER + "@IntrabufBundle record B(List<String> names) {}", "a bundle's members are lists of @IntrabufMessage records");
        fails(MEMBER + "@IntrabufBundle(compat = Compat.FULL) record B(List<F> frames) {}",
                "FULL messages may only contain FULL messages");
        fails(MEMBER + "@IntrabufBundle(compat = Compat.BACKWARD) record B(@Field(since = 1) List<F> frames) {}",
                "must be between 0 and the message's minor");
        fails(MEMBER + "@IntrabufBundle @IntrabufMessage record B(List<F> frames) {}", "not several");
    }

    @Test
    void bundleRowsStandAlone() {
        fails(MEMBER + "@IntrabufMessage(name = \"t.H\", compat = Compat.BACKWARD) record H(F inner) {}\n"
                + "@IntrabufBundle(compat = Compat.BACKWARD) record B(List<H> hs) {}", "H.inner holds a message (t.F)");
        fails("@IntrabufMessage(name = \"t.P\") record P(Map<String, byte[]> blobs) {}\n"
                + "@IntrabufBundle record B(List<P> ps) {}", "P.blobs holds byte[]");
    }

    @Test
    void bundlesAreNotNested() {
        fails(MEMBER + "@IntrabufBundle(name = \"t.B\", compat = Compat.BACKWARD) record B(List<F> frames) {}\n"
                + "@IntrabufMessage record M(B b) {}", "t.B is a bundle");
        fails(MEMBER + "@IntrabufBundle(name = \"t.B\", compat = Compat.BACKWARD) record B(List<F> frames) {}\n"
                + "@IntrabufBundle(name = \"t.C\", compat = Compat.BACKWARD) record C(List<B> bs) {}", "t.B is a bundle");
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

    private static final String ACCOUNT_V1 =
            "@IntrabufMessage(name = \"t.A\", major = 1, compat = Compat.BACKWARD) record A1(String name) {}\n";
    private static final String ACCOUNT_V2 =
            "@IntrabufMessage(name = \"t.A\", major = 2, compat = Compat.BACKWARD) record A2(String name) {\n"
                    + "  @Promote static A2 from(A1 old) { return new A2(old.name()); } }\n";

    @Test
    void validPromotionChainCompiles() {
        Result r = compile(HEADER + ACCOUNT_V1 + ACCOUNT_V2
                + "@IntrabufMessage(name = \"t.A\", major = 3, minor = 1, compat = Compat.BACKWARD, oldest = \"1.0\")\n"
                + "record A3(String name, @Field(since = 1, nonNull = true) java.time.Instant at) {\n"
                + "  @Promote static A3 from(A2 old) { return A3Intrabuf.at3_0(old.name()); }\n"
                + "  @Promote(field = \"at\") static java.time.Instant at(A3 older) { return java.time.Instant.EPOCH; } }\n");
        assertTrue(r.success(), r.diagnostics());
    }

    @Test
    void theChainMustReachTheOldest() {
        fails(ACCOUNT_V1 + "@IntrabufMessage(name = \"t.A\", major = 2, compat = Compat.BACKWARD) record A2(String name) {}\n"
                        + "@IntrabufMessage(name = \"t.A\", major = 3, compat = Compat.BACKWARD, oldest = \"1.0\") record A3(String name) {\n"
                        + "  @Promote static A3 from(A2 old) { return new A3(old.name()); } }\n",
                "declares oldest = \"1.0\", but nothing promotes major 1 to major 2. Add to t.A2: @Promote static A2 from(");
        fails("@IntrabufMessage(name = \"t.A\", major = 2, compat = Compat.BACKWARD, oldest = \"1.0\") record A2(String name) {}",
                "nothing promotes major 1 to major 2");
        fails(ACCOUNT_V1 + "@IntrabufMessage(name = \"t.A\", major = 2, compat = Compat.BACKWARD, oldest = \"1.3\") record A2(String name) {\n"
                + "  @Promote static A2 from(A1 old) { return new A2(old.name()); } }\n", "newer than t.A1 (v1.0)");
    }

    @Test
    void aPromotionMustBeReachable() {
        fails(ACCOUNT_V1 + ACCOUNT_V2, "so it never runs; declare oldest = \"1.0\"");
    }

    @Test
    void promotionSignatures() {
        fails(ACCOUNT_V1 + "@IntrabufMessage(name = \"t.A\", major = 3, compat = Compat.BACKWARD) record A3(String name) {\n"
                + "  @Promote static A3 from(A1 old) { return null; } }", "promotions are adjacent, so it must take major 2");
        fails("@IntrabufMessage(name = \"t.B\", compat = Compat.BACKWARD) record B(String name) {}\n"
                + "@IntrabufMessage(name = \"t.A\", major = 2, compat = Compat.BACKWARD) record A2(String name) {\n"
                + "  @Promote static A2 from(B old) { return null; } }", "which is schema 't.B', not 't.A'");
        fails(ACCOUNT_V1 + "@IntrabufMessage(name = \"t.A\", major = 2, compat = Compat.BACKWARD) record A2(String name) {\n"
                + "  @Promote A2 from(A1 old) { return null; } }", "must be static and not private");
        fails(ACCOUNT_V1 + "@IntrabufMessage(name = \"t.A\", major = 2) record A2(String name) {\n"
                + "  @Promote static A2 from(A1 old) { return null; } }", "a STRICT schema reads only its own version");
        fails("@IntrabufMessage(compat = Compat.BACKWARD, minor = 1) record T(int a, @Field(since = 1) int b) {\n"
                + "  @Promote(field = \"b\") static long b(T older) { return 0; } }", "must return int, the type of b");
        fails("@IntrabufMessage(compat = Compat.BACKWARD, minor = 1) record T(int a, @Field(since = 1) int b) {\n"
                + "  @Promote(field = \"a\") static int a(T older) { return 0; } }", "it is in minor 0, which every payload has");
        fails("@IntrabufMessage(compat = Compat.BACKWARD, minor = 1) record T(int a) {\n"
                + "  @Promote(field = \"c\") static int c(T older) { return 0; } }", "has no component named 'c'");
    }

    @Test
    void oldestIsAVersionNoNewerThanTheRecord() {
        fails("@IntrabufMessage(compat = Compat.BACKWARD, oldest = \"1\") record T(int a) {}", "must be \"major.minor\"");
        fails("@IntrabufMessage(compat = Compat.BACKWARD, oldest = \"1.2\") record T(int a) {}", "is newer than T itself (v1.0)");
        fails("@IntrabufMessage(minor = 2, oldest = \"1.1\") record T(int a) {}", "its oldest can only be \"1.2\"");
    }

    @Test
    void aNonNullFieldWithoutADefaultNeedsAPromotion() {
        fails("@IntrabufMessage(name = \"t.L\", compat = Compat.FULL) record L(String s) {}\n"
                        + "@IntrabufMessage(compat = Compat.FULL, minor = 1) record T(int a, @Field(since = 1, nonNull = true) L l) {}",
                "or compute it with @Promote(field = \"l\")");
    }

    @Test
    void onlyPastVersionsHoldPastVersions() {
        fails(ACCOUNT_V1 + ACCOUNT_V2.replace("major = 2,", "major = 2, oldest = \"1.0\",")
                        + "@IntrabufMessage(name = \"t.H\", compat = Compat.BACKWARD) record H(A1 a) {}",
                "H.a holds t.A1, a past version of t.A");
    }

    @Test
    void sinceOnStrictWarns() {
        Result r = compile(HEADER + "@IntrabufMessage(minor = 1) record T(@Field(since = 1) int a) {}");
        assertTrue(r.success(), r.diagnostics());
        assertTrue(r.diagnostics().contains(Diagnostic.Kind.WARNING + ": T.a: since has no effect"), r.diagnostics());
    }
}
