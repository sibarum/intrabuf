# intrabuf-nanotemplate

**[Nanotemplate](../docs/nanotemplate.md): the template format optimized for implementation compactness. Every
template names its own escape character and delimiters, and a key can be anything.**

```
nanotemplate 1 \ {{ }}
Hello, {{name}}! A literal \{{name}}.
```

You can fill one in anywhere, even where there are no libraries, because the code fits in one line. Filling in is
the format's only feature. Each placeholder becomes its value, verbatim; quoting or formatting a value for where it
lands is up to the caller.

This module is nanotemplate in one line per shell. Paste the line into a script and it can fill templates with
nothing but what the shell has built in. The lines are the resources in
`src/main/resources/sibarum/intrabuf/nanotemplate/`, and `NanotemplateLine` hands them to a generator that pastes them
into the scripts it writes. The tests run every line in its real shell, under the C locale and under UTF-8, and hold
it to a reference implementation. They cover many delimiter sets: `{{ }}`, `<% %>`, `${ }`, one character each, the
same on both sides, glob, regex and shell-quote characters, non-ASCII ones and emoji. They also cover keys and values
holding every one of those, line breaks (CR LF among them), tabs, each shell's special characters, empty keys and
values, trailing line breaks, a body with and without a final line break, an empty body, a CR LF header, repeated,
unused and dangling pairs, and every refusal.

The game is the shortest line that is still correct. A shorter line that passes the tests is a better one.

| line        | bytes | shell                                    | tested here                                |
|-------------|-------|------------------------------------------|--------------------------------------------|
| `fill.sh`   | 742   | POSIX `sh` (dash, BusyBox ash), Zsh      | dash, Git's sh; zsh **not yet**: no zsh on the dev host |
| `fill.bash` | 586   | Bash 3.2 and later                       | Bash 5.3                                   |
| `fill.ps1`  | 649   | Windows PowerShell 5.1, PowerShell 7     | 5.1                                        |

The tests skip a shell that isn't installed; point them at one with `-Dnanotemplate.bash=`, `-Dnanotemplate.sh=`,
`-Dnanotemplate.dash=`, `-Dnanotemplate.zsh=` or `-Dnanotemplate.powershell=`. From PowerShell, Git's bash isn't on
the PATH: `mvn install "-Dnanotemplate.bash=C:\Program Files\Git\usr\bin\bash.exe"`.

## Filling

Every filler is `ntf FILE KEY VALUE…`: the template, then its values as pairs.

```bash
ntf install.sh.nt app calculator dir "$HOME/.local/bin" > install.sh
```

```powershell
[IO.File]::WriteAllText("$pwd\install.ps1", (ntf install.ps1.nt app calculator dir $dir), [Text.UTF8Encoding]::new(0))
```

The shell fillers print to standard output; the PowerShell one returns the text, which `WriteAllText` writes without
a byte order mark. Redirect the shells' output to a file rather than capturing it with `$(…)`, which drops trailing
line breaks.

A key matches a placeholder only when the two are equal exactly, case included. The first pair with that key wins;
pairs nobody uses are ignored, and so is a last key without a value. A filler refuses a template whose header isn't
a nanotemplate 1 header, an unclosed placeholder, an escape at the very end, or a placeholder with no value. It then
writes nothing, says `nanotemplate: FILE is not nanotemplate 1` or `nanotemplate: FILE has no value for KEY` on
stderr, and returns status 1 (throws, in PowerShell). Shells can't hold NUL in a string, so no template or value may
contain one.

## Rules for a new line

- One line, defining one function, `ntf`, with the arguments above.
- Nothing outside the shell's own built-ins, except `cat` under `sh`.
- Locale-proof: it must give the same output under `LC_ALL=C` and under a UTF-8 locale. Working in bytes is easiest.
  Mind shells that store characters in 16 bits: Bash on Cygwin and MSYS2 splits an emoji in a UTF-8 locale.
- Byte-exact: what isn't a placeholder or an escape comes out unchanged, trailing line breaks and carriage returns
  included.
- Add it to `NanotemplateLine` and to `NanotemplateLineTest.Shell`, and it runs against everything above.
