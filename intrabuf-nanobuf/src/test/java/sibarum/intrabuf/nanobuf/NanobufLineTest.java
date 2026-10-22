package sibarum.intrabuf.nanobuf;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every one-line reader and writer in its real shell and holds it to {@link Reference}. A shell that isn't
 * installed is skipped. Point at a particular one with {@code -Dnanobuf.bash=...}, {@code -Dnanobuf.sh=...},
 * {@code -Dnanobuf.zsh=...} or {@code -Dnanobuf.powershell=...}; on Windows, Git's bash is not on PowerShell's PATH.
 */
class NanobufLineTest {

    static final String SCHEMA = "test.Schema";

    /** Everything docs/nanobuf.md says the readers must survive, and the characters each shell treats specially. */
    static final List<List<String>> ROWS = List.of(
            List.of("app", "calculator", "Calculator", "sibarum"),
            List.of("version", "1.4.2", "2026-10-01"),
            List.of("empty", "", "mid", "", ""),
            List.of("ws", "a\tb", "line1\nline2", "\nleading", " spaced ", "trailing\n", "crlf\r\ninside", "\r\nleading crlf", "cr\r"),
            List.of("unicode", "é", "中文", "🎉"),
            List.of("one"),
            List.of("specials", "a\\b", "$HOME", "`x`", "$(x)", "%PATH%", "'q'", "\"dq\"", "-n", "--", "&|;<>"),
            List.of("globs", "*", "?", "[a", "~"),
            List.of("wide", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"));

    enum Shell {
        BASH("nanobuf.bash", "bash", NanobufLine.BASH_READ, NanobufLine.BASH_WRITE),
        ZSH("nanobuf.zsh", "zsh", NanobufLine.ZSH_READ, NanobufLine.BASH_WRITE),
        SH("nanobuf.sh", "sh", NanobufLine.SH_READ, NanobufLine.SH_WRITE),
        POWERSHELL("nanobuf.powershell", System.getProperty("os.name").startsWith("Windows") ? "powershell" : "pwsh",
                NanobufLine.POWERSHELL_READ, NanobufLine.POWERSHELL_WRITE);

        final String exe;
        final NanobufLine read;
        final NanobufLine write;

        Shell(String property, String exe, NanobufLine read, NanobufLine write) {
            this.exe = System.getProperty(property, exe);
            this.read = read;
            this.write = write;
        }

        /** A literal for {@code v}. Git for Windows' bash drops carriage returns from a script's text, even inside
         *  quotes, so the shells build one with printf instead. */
        String quote(String v) {
            return this == POWERSHELL ? "'" + v.replace("'", "''") + "'"
                    : "'" + v.replace("'", "'\\''").replace("\r", "'\"$(printf '\\r')\"'") + "'";
        }

        /** Reads {@code in} and writes each row as {@code [field]...} plus a line feed to {@code out}, the status to rc. */
        String readDriver(String schema, int major) {
            return switch (this) {
                case BASH, ZSH -> "d(){ local x;for x;do printf '[%s]' \"$x\";done;echo;};nbr in "
                        + quote(schema) + " " + major + " d >out;echo $? >rc";
                case SH -> "nbr in " + quote(schema) + " " + major
                        + " 'for(i=1;i<=NF;i++)printf \"[%s]\",$i;print \"\"' >out;echo $? >rc";
                case POWERSHELL -> "try{$o=@(nbr in " + quote(schema) + " " + major + "|%{($_|%{\"[$_]\"})-join''});"
                        + "[IO.File]::WriteAllText('out',(($o|%{\"$_`n\"})-join''),[Text.UTF8Encoding]::new(0));$c=0}"
                        + "catch{$c=1};[IO.File]::WriteAllText('rc',\"$c\")";
            };
        }

        /** Writes {@code rows} to out with the shell's writer, stopping at the first refusal; the status to rc. */
        String writeDriver(List<List<String>> rows) {
            if (this == POWERSHELL) {
                String calls = rows.stream().map(r -> "nbw out " + r.stream().map(this::quote).collect(Collectors.joining(" ")))
                        .collect(Collectors.joining(";"));
                return "try{" + calls + ";$c=0}catch{$c=1};[IO.File]::WriteAllText('rc',\"$c\")";
            }
            String calls = rows.stream().map(r -> "nbw " + r.stream().map(this::quote).collect(Collectors.joining(" ")) + " >>out")
                    .collect(Collectors.joining(" && "));
            return ": >out; " + calls + "; echo $? >rc";
        }

        /** A writer call with one field holding {@code c}, built in the shell since a script can't hold it literally. */
        String refuseDriver(char c) {
            String code = Integer.toOctalString(c);
            if (this == POWERSHELL) {
                return "try{nbw out k ok \"a$([char]" + (int) c + ")b\";$c=0}catch{$c=1};[IO.File]::WriteAllText('rc',\"$c\")";
            }
            return ": >out; nbw k ok \"$(printf 'a\\" + code + "b')\" >>out; echo $? >rc";
        }
    }

    private static final Map<Shell, Boolean> AVAILABLE = new ConcurrentHashMap<>();

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(Shell.class)
    void readsWhatTheReferenceWrites(Shell shell) throws Exception {
        byte[] file = Reference.write(SCHEMA, 1, 2, ROWS);
        assertEquals(dump(ROWS), read(shell, file, SCHEMA, 1));
    }

    @ParameterizedTest
    @EnumSource(Shell.class)
    void readsAFinalRecordWithoutItsLineFeed(Shell shell) throws Exception {
        byte[] file = Reference.write(SCHEMA, 1, 0, ROWS);
        byte[] cut = java.util.Arrays.copyOf(file, file.length - 1);
        assertEquals(dump(ROWS), read(shell, cut, SCHEMA, 1));
    }

    @ParameterizedTest
    @EnumSource(Shell.class)
    void readsAnyLineBreakAfterARecord(Shell shell) throws Exception {
        String lf = new String(Reference.write(SCHEMA, 1, 2, ROWS), StandardCharsets.UTF_8);
        for (String br : new String[]{"\r\n", "\r", ""}) {
            byte[] file = lf.replace("\u001e\n", "\u001e" + br).getBytes(StandardCharsets.UTF_8);
            assertEquals(ROWS, Reference.read(file, SCHEMA, 1));
            assertEquals(dump(ROWS), read(shell, file, SCHEMA, 1), () -> "after " + show(br.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** What a Windows checkout with {@code text=auto} does: every bare LF becomes CR LF, inside values too. The rows
     *  survive; line breaks inside values come back as CR LF, which only {@code -text} prevents. */
    @ParameterizedTest
    @EnumSource(Shell.class)
    void survivesGitsLineEndingConversion(Shell shell) throws Exception {
        byte[] file = crlf(new String(Reference.write(SCHEMA, 1, 2, ROWS), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        List<List<String>> converted = ROWS.stream().map(r -> r.stream().map(NanobufLineTest::crlf).toList()).toList();
        assertEquals(converted, Reference.read(file, SCHEMA, 1));
        assertEquals(dump(converted), read(shell, file, SCHEMA, 1));
    }

    @ParameterizedTest
    @EnumSource(Shell.class)
    void readsAHeaderWithNoRows(Shell shell) throws Exception {
        assertEquals("", read(shell, Reference.write(SCHEMA, 3, 7, List.of()), SCHEMA, 3));
    }

    @ParameterizedTest
    @EnumSource(Shell.class)
    void refusesAnotherSchemaMajorOrFormat(Shell shell) throws Exception {
        byte[] file = Reference.write(SCHEMA, 1, 2, ROWS);
        assertEquals(1, readStatus(shell, file, "test.Other", 1));
        assertEquals(1, readStatus(shell, file, "test", 1));
        assertEquals(1, readStatus(shell, file, SCHEMA, 2));
        assertEquals(1, readStatus(shell, Reference.write(SCHEMA, 11, 0, ROWS), SCHEMA, 1));
        assertEquals(1, readStatus(shell, new byte[0], SCHEMA, 1));
        byte[] v2 = new String(file, StandardCharsets.UTF_8).replaceFirst("nanobuf\u001f1", "nanobuf\u001f2")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(1, readStatus(shell, v2, SCHEMA, 1));
    }

    @ParameterizedTest
    @EnumSource(Shell.class)
    void writesWhatTheReferenceWrites(Shell shell) throws Exception {
        List<List<String>> all = new ArrayList<>();
        all.add(List.of("nanobuf", "1", SCHEMA, "1.2"));
        all.addAll(ROWS);
        run(shell, shell.write.code() + "\n" + shell.writeDriver(all));
        assertEquals("0", Files.readString(dir.resolve("rc")).strip());
        byte[] expected = Reference.write(SCHEMA, 1, 2, ROWS);
        byte[] actual = Files.readAllBytes(dir.resolve("out"));
        assertArrayEquals(expected, actual, () -> "\nexpected " + show(expected) + "\nactual   " + show(actual));
        assertEquals(ROWS, Reference.read(actual, SCHEMA, 1));
    }

    @ParameterizedTest
    @EnumSource(Shell.class)
    void writerRefusesSeparatorsAndWritesNothing(Shell shell) throws Exception {
        for (char c : new char[]{0x1E, 0x1F}) {
            run(shell, shell.write.code() + "\n" + shell.refuseDriver(c));
            assertNotEquals("0", Files.readString(dir.resolve("rc")).strip(), "accepted 0x" + Integer.toHexString(c));
            Path out = dir.resolve("out");
            assertTrue(Files.notExists(out) || Files.size(out) == 0, "wrote part of a refused row");
            Files.deleteIfExists(out);
        }
    }

    @ParameterizedTest
    @EnumSource(NanobufLine.class)
    void everyEntryIsOneLine(NanobufLine line) {
        String code = line.code();
        assertTrue(!code.isEmpty() && code.indexOf('\n') < 0 && code.indexOf('\r') < 0, line + " is not one line");
    }

    private String read(Shell shell, byte[] file, String schema, int major) throws Exception {
        assertEquals(0, readStatus(shell, file, schema, major), () -> "refused: " + output());
        return canonical(Files.readString(dir.resolve("out")));
    }

    private int readStatus(Shell shell, byte[] file, String schema, int major) throws Exception {
        Files.write(dir.resolve("in"), file);
        Files.deleteIfExists(dir.resolve("out"));
        run(shell, shell.read.code() + "\n" + shell.readDriver(schema, major));
        return Integer.parseInt(Files.readString(dir.resolve("rc")).strip());
    }

    private void run(Shell shell, String script) throws Exception {
        Assumptions.assumeTrue(AVAILABLE.computeIfAbsent(shell, NanobufLineTest::available), shell.exe + " is not installed");
        List<String> cmd;
        if (shell == Shell.POWERSHELL) {
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            cmd = List.of(shell.exe, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        } else {
            Files.writeString(dir.resolve("t.sh"), script + "\n");
            cmd = List.of(shell.exe, "t.sh");
        }
        Files.deleteIfExists(dir.resolve("rc"));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(dir.resolve("log").toFile()).start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "timed out");
        assertTrue(Files.exists(dir.resolve("rc")), () -> "the script did not finish: " + output());
    }

    private String output() {
        try {
            return Files.readString(dir.resolve("log"));
        } catch (IOException e) {
            return "(no output)";
        }
    }

    private static boolean available(Shell shell) {
        List<String> cmd = shell == Shell.POWERSHELL
                ? List.of(shell.exe, "-NoProfile", "-NonInteractive", "-Command", "exit 0")
                : List.of(shell.exe, "-c", "exit 0");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** How the drivers print rows. */
    private static String dump(List<List<String>> rows) {
        return canonical(rows.stream().map(r -> r.stream().map(f -> "[" + f + "]").collect(Collectors.joining()) + "\n")
                .collect(Collectors.joining()));
    }

    /** Missing trailing fields read as empty, so shells may pass them or not: drop them before comparing. */
    private static String canonical(String dump) {
        return dump.replaceAll("(\\[\\])+\n", "\n");
    }

    private static String crlf(String s) {
        return s.replaceAll("(?<!\r)\n", "\r\n");
    }

    private static String show(byte[] b) {
        return new String(b, StandardCharsets.UTF_8).replace("\u001f", "␟").replace("\u001e", "␞").replace("\n", "\\n");
    }
}
