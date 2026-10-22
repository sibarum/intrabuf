# Nanobuf, version 1

*Proposed.*

Nanobuf is intrabuf for shell scripts. It takes the idea of the [bundle log](bundle-format.md) (a facts line, then
rows that each name their kind and stand alone) and cuts it down to what a shell can read in a few lines with nothing
but its built-in commands. Every feature has to pay for itself in reader code on Bash, Zsh, PowerShell 5.1 and awk.
Like intrabuf, it is for data that stays inside your own system: both ends are yours.

Its first user is [Koine](https://github.com/sibarum/koine-shell), whose scripts keep their install records,
manifests, journals and stamps in it.

## The bytes

- **UTF-8, with no byte order mark.**
- **`0x1F` (unit separator) separates fields.**
- **`0x1E` (record separator) ends every record,** including the last. The writer puts a line feed after each one, so
  a file still shows one record per line in an editor or `git diff`. The reader drops one line break from the start of
  each record, whichever kind it is: CR LF, matched first, or a lone LF or a lone CR. A file whose line endings an
  editor or Git has converted still reads the same rows. Only the record separator ends a record, so this costs
  nothing: the first field is a kind, which never starts with a line break.
- **Nothing is quoted or escaped.** A value may contain anything except `0x1E`, `0x1F` and NUL, including tabs and
  line breaks.

These separators are single bytes, so splitting on them works the same in every locale, including the C locale that
installers often run in. A multi-byte separator would not: Bash splits on one only under a UTF-8 locale, and POSIX awk
uses only the first character of `RS`.

The three forbidden characters are rare, not impossible. Microsoft Word uses `0x1E` and `0x1F` internally for
hyphens, and Linux allows both in file names. So **the writer checks every value**, and refuses one that contains a
forbidden character, naming the field: `installDir contains a control character (0x1F)`. It never writes a file a
reader would misread.

## Records

The first record is the header:

```
nanobuf␟1␟vexelray.Manifest␟1.2␞
```

Here and below, `␟` stands for `0x1F` and `␞` for `0x1E` followed by a line feed. The fields are the format version,
the schema name, and the schema's major and minor version.

Every other record is a row. Its first field is its kind; the rest are that kind's fields, in the order the schema
declares them. An illustration, not the real manifest schema:

```
nanobuf␟1␟vexelray.Manifest␟1.2␞
app␟calculator␟Calculator␟sibarum␞
version␟1.4.2␟2026-10-01␞
version␟1.4.1␟2026-09-12␞
file␟1.4.2␟binary␟calculator␟5e3b…␟bin/calculator␞
file␟1.4.1␟binary␟calculator␟91ac…␟bin/calculator␞
```

- **Fields are positional.** The schema gives their names; the file does not repeat them.
- **Rows are flat.** A field is a string, a whole number, or a boolean (`1` or `0`). There are no lists and no nested
  records inside a row.
- **A list is repeated rows** of one kind, in order.
- **Nesting is by key.** A child row carries its parent's key as a field, as `file` rows carry their version above.
  There are no references to resolve.
- **An empty field is missing.** It is allowed only for fields the schema marks optional.
- **Rows of different kinds may come in any order.** Rows of one kind keep their order.

## Compatibility

The header's schema name must match, and so must its major version; otherwise the reader refuses the file. Within a
major version, compatibility costs no reader code:

- **New fields are added only at the end of a kind.** A reader that expects more fields than a row has reads the
  missing ones as empty. A reader that finds more fields than it expects ignores the rest.
- **New kinds may be added.** A reader skips kinds it does not know.
- **A field that is no longer used stays in place,** written empty.

Anything else (removing a field, reordering fields, changing a type) is a new major version.

## Reading and writing

The code is generated from the declared schema (by Koine's compiler, for Koine scripts), so nobody writes it by hand. These are its shapes.

Bash and Zsh. The last variable, `_rest`, catches fields from a newer writer, so they never end up inside the last
expected field:

```bash
while IFS=$'\x1f' read -r -d $'\x1e' kind version date _rest; do
    kind=${kind#$'\r'}; kind=${kind#$'\n'}     # CR LF, LF or CR
    case $kind in
        version) … ;;
    esac
done < "$file"
```

awk, the same in gawk, mawk, BusyBox and BWK awk. `BINMODE = 3` matters only to gawk on Windows (Git's, Cygwin's
or MSYS2's), which otherwise reads in text mode and turns CR LF inside values into LF; the other awks see it as an
ordinary variable:

```awk
BEGIN { BINMODE = 3; RS = "\036"; FS = "\037" }
{ sub(/^\r?\n?/, "") }                       # CR LF, LF or CR; also re-splits the fields
$1 == "version" { … }
```

PowerShell 5.1. `ReadAllText` with an explicit encoding, because `Get-Content` reads the ANSI code page:

```powershell
foreach ($r in [IO.File]::ReadAllText($path, [Text.UTF8Encoding]::new($false)).Split([char]0x1e)) {
    $f = ($r -replace '^\r?\n?').Split([char]0x1f)
    switch ($f[0]) { 'version' { … } }
}
```

Writing is `printf '%s\037%s\036\n'` in Bash and Zsh. In PowerShell it is `-join [char]0x1f`, written with
`[IO.File]::WriteAllText` and a no-BOM UTF-8 encoding, so that 5.1 adds neither a BOM nor carriage returns.

The generated readers must be tested against empty fields, empty trailing fields, values containing tabs and line
breaks (CR LF among them), a final record with and without its line break, CR LF and CR after every record, and
rows with more and fewer fields than expected.

For a script with no generator behind it, [intrabuf-nanobuf](../intrabuf-nanobuf/README.md) has a one-line reader
and a one-line writer for POSIX `sh` (with awk), Bash, Zsh and PowerShell, each tested in its real shell against
those cases.

Git treats a nanobuf file as text, since it has no NUL. With `* text=auto` it rewrites line endings: LF to CR LF on
a Windows checkout, CR LF to LF on commit. This never changes how a file parses. Git leaves `0x1E`, `0x1F` and lone
CRs alone, and the reader drops CR LF as readily as LF, so the same rows and fields come out. The only thing that can
change is the line breaks inside a value, which may come back as the other kind. To keep those exact, give nanobuf
files `-text` in `.gitattributes`. That stops the rewriting both ways, and `git diff` still shows them as text.
`eol=lf` is not enough: it keeps LF, but turns a value's real CR LF into LF on commit.

## Relation to the rest of intrabuf

Nanobuf is not the [text format](text-format.md). That format is CSV with quoting, inline lists and references
between tables, which a shell cannot read without a real parser. Nanobuf keeps the facts line and the bundle log of
self-contained rows, and drops the rest.

Nothing in the Java side reads or writes nanobuf yet. A schema whose rows are all flat could be given a nanobuf codec
by the processor, so Java components such as the installer generator could read and write the same files as shell
scripts. That is proposed, not built.
