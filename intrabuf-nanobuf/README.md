# intrabuf-nanobuf

**[Nanobuf](../docs/nanobuf.md): the serialization format optimized for implementation compactness, with two
trade-offs you won't even notice.**

You can use it anywhere, even where there are no libraries, because the code fits in one line. That is possible
because the format keeps a small edge-case surface you'll likely never meet:

1. **A value can't contain `0x1E`, `0x1F` or NUL.** None of them is on a keyboard, and the writers refuse them
   rather than write a file that would be misread.
2. **Line-ending conversion (Git, editors) can turn LF into CR LF, or back, inside a value.** The file still parses
   into the same rows and fields. `-text` in `.gitattributes` stops even that.

This module is nanobuf in one line per shell. Paste the line into a script and it can read or write nanobuf
with nothing but what the shell has built in. The lines are the resources in
`src/main/resources/sibarum/intrabuf/nanobuf/`, and `NanobufLine` hands them to a generator that pastes them into the
scripts it writes. The tests run every line in its real shell and hold it to a reference implementation. They
cover empty fields, empty trailing fields, tabs, line breaks (CR LF among them), a leading line break, a carriage
return, non-ASCII characters, each shell's special characters, a final record with and without its line break,
records ended by CR LF or a lone CR, a file put through Git's line-ending conversion, rows with more and fewer fields,
and every refusal.

The game is the shortest line that is still correct. A shorter line that passes the tests is a better one.

| line         | bytes | shell                                   | tested here                         |
|--------------|-------|-----------------------------------------|-------------------------------------|
| `read.sh`    | 241   | POSIX `sh` (dash, BusyBox ash), via awk | dash, Git's sh, bash; GNU awk       |
| `write.sh`   | 193   | POSIX `sh`                              | dash, Git's sh, bash                |
| `read.bash`  | 294   | Bash 3.2 and later                      | Bash 5.3                            |
| `write.bash` | 176   | Bash 3.2 and later, Zsh                 | Bash 5.3                            |
| `read.zsh`   | 305   | Zsh                                     | **not yet**: no zsh on the dev host |
| `read.ps1`   | 346   | Windows PowerShell 5.1, PowerShell 7    | 5.1                                 |
| `write.ps1`  | 326   | Windows PowerShell 5.1, PowerShell 7    | 5.1                                 |

Zsh writes with `write.bash`. The tests skip a shell that isn't installed; point them at one with `-Dnanobuf.bash=`,
`-Dnanobuf.sh=`, `-Dnanobuf.zsh=` or `-Dnanobuf.powershell=`. From PowerShell, Git's bash isn't on the PATH:
`mvn install "-Dnanobuf.bash=C:\Program Files\Git\usr\bin\bash.exe"`.

## Reading

Every reader is `nbr FILE SCHEMA MAJOR …`. It refuses a file whose header names another schema, another major or
another nanobuf version, or that has no header, with `nanobuf: FILE is not SCHEMA vMAJOR` on stderr and status 1. It
hands every row after the header to the caller, kind first. A row has only the fields its writer wrote: read a missing
field as empty, and ignore fields you don't expect.

```bash
row(){ case $1 in version) echo "$2 released $3";; esac; }
nbr manifest.nb vexelray.Manifest 1 row
```

The function runs in the reading shell, not a subshell, so it can set variables. Its standard input is the caller's,
not the file.

```sh
nbr manifest.nb vexelray.Manifest 1 '$1=="version"{print $2}'
```

The fourth argument is the body of an awk rule, run for every row with the kind in `$1`. Under `sh` this is the only
reader: it has no `read -d`. Print fields that may hold line breaks with care, because the output is lines.

```powershell
nbr manifest.nb vexelray.Manifest 1 | % { if ($_[0] -eq 'version') { "$($_[1]) released $($_[2])" } }
```

Each row reaches the pipeline as one `string[]`. A missing field is `$null`, which PowerShell prints as empty.

## Writing

Every writer is `nbw FIELD…` (PowerShell: `nbw FILE FIELD…`), one call per record, the header included:

```bash
{ nbw nanobuf 1 vexelray.Manifest 1.2; nbw version 1.4.2 2026-10-01; } > manifest.nb
```

```powershell
nbw manifest.nb nanobuf 1 vexelray.Manifest 1.2; nbw manifest.nb version 1.4.2 2026-10-01
```

The shell writers print to standard output. The PowerShell one appends to the file, as UTF-8 without a BOM or
carriage returns, so delete the file first to start a new one. A writer refuses a field that contains `0x1E` or
`0x1F`, naming its position (PowerShell refuses NUL too; the other shells can't hold one in a string). It writes
nothing for that record and returns status 1, or throws in PowerShell.

## Rules for a new line

- One line, defining one function, `nbr` or `nbw`, with the arguments above.
- Nothing outside the shell's own built-ins, except awk under `sh`.
- Locale-proof: it must work under `LC_ALL=C`.
- Line-break-proof: a reader drops one CR LF, LF or CR after each record separator, and nothing else. Mind
  interpreters that translate line endings on their own: gawk on Windows does unless `BINMODE=3`.
- Add it to `NanobufLine` and to `NanobufLineTest.Shell`, and it runs against everything above.
