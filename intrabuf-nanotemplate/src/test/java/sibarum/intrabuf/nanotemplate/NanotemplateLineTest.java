package sibarum.intrabuf.nanotemplate;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs every one-line filler in its real shell and holds it to {@link Reference}. A shell that isn't installed is
 * skipped. Point at a particular one with {@code -Dnanotemplate.bash=...}, {@code -Dnanotemplate.sh=...},
 * {@code -Dnanotemplate.zsh=...} or {@code -Dnanotemplate.powershell=...}; on Windows, Git's bash is not on
 * PowerShell's PATH.
 */
class NanotemplateLineTest {

    static final String H = "nanotemplate 1 \\ {{ }}\n";

    /** Text each shell treats specially, for keys, values and bodies. */
    static final String SPECIALS = "a\\b $HOME `x` $(x) ${x} %PATH% 'q' \"dq\" -n -- &|;<> * ? [a ~ !x #c";

    record Case(String name, byte[] file, List<String> pairs) {
        Case(String name, String file, String... pairs) {
            this(name, file.getBytes(StandardCharsets.UTF_8), List.of(pairs));
        }
    }

    /** Everything docs/nanotemplate.md says a filler must fill. */
    static final List<Case> FILLS = List.of(
            new Case("basic", H + "Hello, {{name}}!\n", "name", "World"),
            new Case("no final line break", H + "a{{k}}b", "k", "v"),
            new Case("empty body", H),
            new Case("body only line breaks", H + "\n\n\n"),
            new Case("verbatim body", "nanotemplate 1 ^ {{ }}\n" + "tab\there\r\ncrlf\rcr\n" + SPECIALS + "\né中🎉\n\n"),
            new Case("no placeholders, no line break", "nanotemplate 1 ^ {{ }}\n" + "just text"),
            new Case("escapes", H + "\\\\ \\{{x}} {\\{x}} \\}} }} \\a \\\n \\é \\🎉 {", "x", "no"),
            new Case("placeholders back to back", H + "{{a}}{{b}}{{a}}", "a", "1", "b", "2"),
            new Case("anything as a key", H + "[{{}}][{{ spaced key }}][{{multi\nline}}][{{tab\tkey}}][{{a\\}}b}}]"
                    + "[{{a{{b}}][{{\\\\}}][{{é中🎉}}][{{" + SPECIALS.replace("\\", "\\\\") + "}}][{{\\}}}][{{}}}]",
                    "", "empty", " spaced key ", "s", "multi\nline", "m", "tab\tkey", "t", "a}}b", "close",
                    "a{{b", "open", "\\", "esc", "é中🎉", "u", SPECIALS, "specials", "}", "brace"),
            new Case("values are verbatim", H + "[{{a}}][{{b}}][{{c}}][{{d}}][{{e}}][{{f}}]",
                    "a", "{{b}} \\{{ }} \\", "b", "", "c", "trailing\n\n", "d", "\r\ncrlf\r", "e", SPECIALS, "f", "-n"),
            new Case("the first pair wins", H + "{{k}}", "k", "first", "k", "second"),
            new Case("unused and dangling pairs", H + "{{k}}", "x", "y", "k", "v", "dangling"),
            new Case("key compared exactly", H + "{{K}}{{k}}", "k", "lower", "K", "upper"),
            new Case("header ended by CR LF", "nanotemplate 1 \\ {{ }}\r\n" + "a\r\n{{k}}\r\n", "k", "v"),
            new Case("percent", "nanotemplate 1 \\ <% %>\n" + "<%a%> %%> <\\% \\<%a%> <%%\\>%>", "a", "1", "%>", "2"),
            new Case("dollar brace", "nanotemplate 1 \\ ${ }\n" + "${user} pays \\$5 not \\${user} $$${user}}", "user", "ann"),
            new Case("one-character delimiters", "nanotemplate 1 @ [ ]\n" + "[a][b]@[c] ]x@@[@]]", "a", "1", "b", "2", "]", "3"),
            new Case("open equals close", "nanotemplate 1 \\ % %\n" + "100\\% of %who% %%", "who", "us", "", "empty"),
            new Case("glob and regex characters", "nanotemplate 1 * [[ ]]\n" + "[[a]] ** *[[ [[*]*]]] [[.+]]", "a", "1", "]]", "2", ".+", "3"),
            new Case("regex specials", "nanotemplate 1 # .* *.\n" + ".*k*. #.* .*#*.*.", "k", "1", "*.", "2"),
            new Case("brackets and caret", "nanotemplate 1 ] [ ^\n" + "[k^ ]^ ][x] []^^", "k", "1", "^", "2"),
            new Case("shell quotes", "nanotemplate 1 ' \"$( )\"\n" + "\"$(k)\" '\"$(k)\" \"$(')\")\"", "k", "1", ")\"", "2"),
            new Case("dash escape", "nanotemplate 1 - {{ }}\n" + "-- -{{k}} {{k}}", "k", "1"),
            new Case("non-ASCII delimiters", "nanotemplate 1 \\ « »\n" + "«k» \\« «»»", "k", "1", "", "2"),
            new Case("emoji delimiters", "nanotemplate 1 \\ 🎉 🎊\n" + "🎉k🎊 \\🎉 🎉🎊", "k", "1", "", "2"),
            new Case("open runs into the key", H + "{{{x}} {{x}}} {{{{x}}", "{x", "1", "x", "2", "{{x", "3"),
            new Case("close is matched whole", "nanotemplate 1 \\ << >>\n" + "<<a>b>> <<a>>>", "a>b", "1", "a", "2"),
            new Case("tab in a delimiter", "nanotemplate 1 \\ <\t >\n" + "<\tk>", "k", "1"),
            new Case("many placeholders", H + "line {{n}}: {{v}}\n".repeat(400), "n", "1", "v", "x".repeat(50)));

    /** Every refusal: the filler writes nothing and says why. */
    static final List<Case> REFUSALS = List.of(
            new Case("empty file", ""),
            new Case("header without a line feed", "nanotemplate 1 \\ {{ }}"),
            new Case("header ended by a lone CR", "nanotemplate 1 \\ {{ }}\r{{k}}", "k", "v"),
            new Case("another version", "nanotemplate 2 \\ {{ }}\n"),
            new Case("another format", "nanotemplat 1 \\ {{ }}\n"),
            new Case("case of the name", "Nanotemplate 1 \\ {{ }}\n"),
            new Case("byte order mark", "\uFEFFnanotemplate 1 \\ {{ }}\n"),
            new Case("leading space", " nanotemplate 1 \\ {{ }}\n"),
            new Case("two-character escape", "nanotemplate 1 \\\\ {{ }}\n"),
            new Case("non-ASCII escape", "nanotemplate 1 é {{ }}\n"),
            new Case("tab as the escape", "nanotemplate 1 \t {{ }}\n"),
            new Case("no close", "nanotemplate 1 \\ {{\n"),
            new Case("empty close", "nanotemplate 1 \\ {{ \n"),
            new Case("extra field", "nanotemplate 1 \\ {{ }} x\n"),
            new Case("trailing space", "nanotemplate 1 \\ {{ }} \n"),
            new Case("two spaces before open", "nanotemplate 1 \\  {{ }}\n"),
            new Case("two spaces before close", "nanotemplate 1 \\ {{  }}\n"),
            new Case("escape inside open", "nanotemplate 1 \\ \\{ }\n"),
            new Case("escape inside close", "nanotemplate 1 \\ { }\\\n"),
            new Case("escape starts open", "nanotemplate 1 $ ${ }\n"),
            new Case("unclosed placeholder", H + "a {{key", "key", "v"),
            new Case("half a close", H + "a {{key}", "key", "v"),
            new Case("escaped close only", H + "{{key\\}}", "key", "v", "key}}", "w"),
            new Case("escape at the end", H + "abc\\"),
            new Case("escape at the end of a key", H + "{{k\\", "k", "v"),
            new Case("no value", H + "ok {{a}} then {{missing}}", "a", "1"),
            new Case("no value, key differs in case", H + "{{Name}}", "name", "v"),
            new Case("no value, dangling key", H + "{{k}}", "k"),
            new Case("no value, special key", H + "{{" + SPECIALS.replace("\\", "\\\\") + "}}", "x", "y"));

    enum Shell {
        BASH("nanotemplate.bash", "bash", NanotemplateLine.BASH_FILL),
        ZSH("nanotemplate.zsh", "zsh", NanotemplateLine.SH_FILL),
        SH("nanotemplate.sh", "sh", NanotemplateLine.SH_FILL),
        DASH("nanotemplate.dash", "dash", NanotemplateLine.SH_FILL),
        POWERSHELL("nanotemplate.powershell", System.getProperty("os.name").startsWith("Windows") ? "powershell" : "pwsh",
                NanotemplateLine.POWERSHELL_FILL);

        final String exe;
        final NanotemplateLine fill;

        Shell(String property, String exe, NanotemplateLine fill) {
            this.exe = System.getProperty(property, exe);
            this.fill = fill;
        }

        /** The locales to run in, since a line must work byte by byte and character by character alike. PowerShell
         *  reads the same in any locale, so it runs once, as it is. */
        List<String> locales() {
            return this == POWERSHELL ? List.of("") : List.of("C", "C.UTF-8");
        }

        /** A literal for {@code v}. Git for Windows' bash drops carriage returns from a script's text, even inside
         *  quotes, so the shells build one with printf instead. */
        String quote(String v) {
            return this == POWERSHELL ? "'" + v.replace("'", "''") + "'"
                    : "'" + v.replace("'", "'\\''").replace("\r", "'\"$(printf '\\r')\"'") + "'";
        }

        /** Fills template {@code in<i>} with {@code pairs}: the output to out<i>, the error to err<i>, the status to rc<i>. */
        String driver(int i, List<String> pairs) {
            String args = pairs.stream().map(this::quote).map(a -> " " + a).collect(Collectors.joining());
            if (this == POWERSHELL) {
                return "try{$o=ntf in" + i + args + ";[IO.File]::WriteAllText('out" + i + "',$o,[Text.UTF8Encoding]::new(0));$c=0}"
                        + "catch{[IO.File]::WriteAllText('err" + i + "',\"$_\");$c=1};[IO.File]::WriteAllText('rc" + i + "',\"$c\")";
            }
            return "ntf in" + i + args + " >out" + i + " 2>err" + i + "; echo $? >rc" + i;
        }
    }

    private static final Map<Shell, Boolean> AVAILABLE = new ConcurrentHashMap<>();

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(Shell.class)
    void fillsWhatTheReferenceFills(Shell shell) throws Exception {
        List<String> failures = new ArrayList<>();
        for (String locale : shell.locales()) {
            run(shell, locale, FILLS);
            fills(locale, failures);
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    private void fills(String locale, List<String> failures) throws IOException {
        for (int i = 0; i < FILLS.size(); i++) {
            Case c = FILLS.get(i);
            byte[] expected;
            try {
                expected = Reference.fill(c.file(), c.pairs());
            } catch (Reference.Refused e) {
                throw new AssertionError(c.name() + ": the reference refuses it", e);
            }
            if (!status(i).equals("0")) {
                failures.add(locale + ", " + c.name() + ": refused: " + read("err" + i));
                continue;
            }
            String actual = show(Files.readAllBytes(dir.resolve("out" + i)));
            if (!actual.equals(show(expected))) {
                failures.add(locale + ", " + c.name() + "\n  expected " + show(expected) + "\n  actual   " + actual);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Shell.class)
    void refusesWhatTheReferenceRefuses(Shell shell) throws Exception {
        List<String> failures = new ArrayList<>();
        for (String locale : shell.locales()) {
            run(shell, locale, REFUSALS);
            refusals(locale, failures);
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    private void refusals(String locale, List<String> failures) throws IOException {
        for (int i = 0; i < REFUSALS.size(); i++) {
            Case c = REFUSALS.get(i);
            int n = i;
            Reference.Refused why = assertThrows(Reference.Refused.class, () -> Reference.fill(c.file(), c.pairs()), c.name());
            String err = read("err" + i);
            String expected = why.why == Reference.Refusal.NO_VALUE ? "nanotemplate: in" + n + " has no value for "
                    : "nanotemplate: in" + n + " is not nanotemplate 1";
            Path out = dir.resolve("out" + i);
            if (status(i).equals("0")) {
                failures.add(locale + ", " + c.name() + ": filled " + show(Files.readAllBytes(out)));
            } else if (Files.exists(out) && Files.size(out) > 0) {
                failures.add(locale + ", " + c.name() + ": wrote part of a refused template");
            } else if (!err.contains(expected)) {
                failures.add(locale + ", " + c.name() + ": expected \"" + expected + "\", said " + err);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(NanotemplateLine.class)
    void everyEntryIsOneLine(NanotemplateLine line) {
        String code = line.code();
        assertTrue(!code.isEmpty() && code.indexOf('\n') < 0 && code.indexOf('\r') < 0, line + " is not one line");
    }

    /** Writes each case's template to in<i> and fills them all in one run of the shell, under {@code locale}. */
    private void run(Shell shell, String locale, List<Case> cases) throws Exception {
        Assumptions.assumeTrue(AVAILABLE.computeIfAbsent(shell, NanotemplateLineTest::available), shell.exe + " is not installed");
        StringBuilder script = new StringBuilder(shell.fill.code()).append('\n');
        for (int i = 0; i < cases.size(); i++) {
            Files.write(dir.resolve("in" + i), cases.get(i).file());
            script.append(shell.driver(i, cases.get(i).pairs())).append('\n');
        }
        script.append(shell == Shell.POWERSHELL ? "[IO.File]::WriteAllText('done','')" : ": >done").append('\n');
        List<String> cmd;
        if (shell == Shell.POWERSHELL) {
            String encoded = Base64.getEncoder().encodeToString(script.toString().getBytes(StandardCharsets.UTF_16LE));
            cmd = List.of(shell.exe, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        } else {
            Files.writeString(dir.resolve("t.sh"), script);
            cmd = List.of(shell.exe, "t.sh");
        }
        Files.deleteIfExists(dir.resolve("done"));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(dir.resolve("log").toFile());
        if (!locale.isEmpty()) {
            pb.environment().put("LC_ALL", locale);
        }
        Process p = pb.start();
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "timed out");
        if (Files.notExists(dir.resolve("done"))) {
            fail("the script did not finish: " + read("log"));
        }
    }

    private String status(int i) throws IOException {
        Path rc = dir.resolve("rc" + i);
        return Files.exists(rc) ? Files.readString(rc).strip() : "(no status)";
    }

    private String read(String file) {
        try {
            return Files.readString(dir.resolve(file));
        } catch (IOException e) {
            return "(nothing)";
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

    private static String show(byte[] b) {
        return new String(b, StandardCharsets.UTF_8).replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
