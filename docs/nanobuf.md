# Nanobuf, version 1

*Proposed.*

**The serialization format optimized for implementation compactness, with two trade-offs you won't even notice.**

You can use nanobuf anywhere, even where there are no libraries: a reader or a writer fits in one line of the shell
you already have. Here is the whole reader for POSIX `sh`:

```sh
nbr(){ echo|cat "$1" -|awk -vBINMODE=3 -v s="$2" -v m="$3" 'BEGIN{RS="\36";FS="\37"}{sub(/^\r?\n?/,"");t=$0;$0=p;p=t}NR==2{if($1!="nanobuf"||$2!="1"||$3!=s||int($4)!=m)exit 2;h=1;next}h&&$0!=""{'"$4"'}END{if(!h)exit 2}'||{ echo "nanobuf: $1 is not $2 v$3">&2;return 1;};}
```

Bash, Zsh and PowerShell get one line each too, in [intrabuf-nanobuf](../intrabuf-nanobuf/README.md). It can be
that small because there is nothing to parse. Fields are split on one byte and records on another, with no quoting,
no escaping, and no nesting to resolve. The price is a small edge-case surface, left in on purpose:

1. **Three characters can't appear in a value:** `0x1E` and `0x1F`, the separators, and NUL. None of them is on a
   keyboard, and shells can't hold NUL in a string anyway. If one does turn up (Microsoft Word uses `0x1E` and `0x1F`
   for hyphens, and Linux allows them in file names), the writer refuses the value and names the field. It never
   writes a file a reader would misread.
2. **Line-ending conversion can change line breaks inside values.** Git on Windows, or an editor, may turn LF into
   CR LF or back. It never changes how a file parses: the separators come through untouched, and the reader accepts
   any line break between records. A value that holds a line break may come back with the other kind, which hardly
   anything downstream cares about. If yours does, `-text` in `.gitattributes` stops it.

Everything else (tabs, line breaks, quotes, non-ASCII text, any locale) goes through byte for byte.

## What it is

Nanobuf is the smallest member of the intrabuf family: the one whose reader and writer are both one-liners. To get
there it is schemaless. A file names what it holds in its header, but reading or writing one needs no schema. The
lines split records and fields, check the header's name and major version, and hand over the raw fields. What the
fields mean is up to the two ends, which agree on it the way any two ends of an intrabuf link do: both are yours.

It takes the idea of the [bundle log](bundle-format.md) (a facts line, then rows that each name their kind and stand
alone) and cuts it down to what one line of a shell can read with nothing but its built-in commands. Every feature
has to pay for itself in those lines. A schema can be laid on top, and intrabuf can generate typed readers for it;
see [Intrabuf schemas](#intrabuf-schemas) below.

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

## Records

The first record is the header:

```
nanobuf␟1␟vexelray.Manifest␟1.2␞
```

Here and below, `␟` stands for `0x1F` and `␞` for `0x1E` followed by a line feed. The fields are the format version,
a name for what the file holds, and that content's major and minor version. The name and versions are a label the two
ends agree on. A schema may stand behind them, but nothing in the format requires one.

Every other record is a row: a kind, then that kind's fields. An illustration:

```
nanobuf␟1␟vexelray.Manifest␟1.2␞
app␟calculator␟Calculator␟sibarum␞
version␟1.4.2␟2026-10-01␞
version␟1.4.1␟2026-09-12␞
file␟1.4.2␟binary␟calculator␟5e3b…␟bin/calculator␞
file␟1.4.1␟binary␟calculator␟91ac…␟bin/calculator␞
```

- **Fields are positional.** The file doesn't name them; the two ends know what each position means.
- **Rows are flat.** A field is a string. There are no lists and no nested records inside a row.
- **A list is repeated rows** of one kind, in order.
- **Nesting is by key.** A child row carries its parent's key as a field, as `file` rows carry their version above.
  There are no references to resolve.
- **A missing field reads as empty.** A row may have fewer fields than its kind defines.
- **Rows of different kinds may come in any order.** Rows of one kind keep their order.

## Reading and writing

A reader, in any language, does this:

1. Split the file on `0x1E`. Whatever follows the last one is not a record: at most a line break, or a truncated
   record to ignore.
2. Drop one line break from the start of each record: CR LF if it is there, otherwise a lone LF or CR.
3. Split each record on `0x1F`.
4. Check that the first record is `nanobuf`, `1`, the expected name, and a version whose major is the expected one.
   If it isn't, or there is no first record, refuse the file. Ignore the minor.
5. Hand over every other record, kind first.

A writer joins a record's fields with `0x1F` and ends it with `0x1E` and a line feed, the header first. It refuses a
field that contains `0x1E`, `0x1F` or NUL, naming the field (by position when it has no names), and writes nothing of
that record. It writes UTF-8 with no byte order mark and no other line endings.

[intrabuf-nanobuf](../intrabuf-nanobuf/README.md) is these two in one line per shell, `nbr` and `nbw`, for POSIX `sh`
(with awk), Bash, Zsh and PowerShell. Each line is tested in its real shell against empty fields, empty trailing
fields, tabs and line breaks in values (CR LF among them), a final record with and without its line break, a
truncated final record, CR LF and CR after every record, a file put through Git's line-ending conversion, rows with
more and fewer fields, and every refusal. A line in any other language is welcome there, held to the same cases.

Two traps every implementation has to step around:

- **Interpreters that translate line endings on their own.** gawk on Windows (Git's, Cygwin's, MSYS2's) reads in text
  mode and turns CR LF inside values into LF, unless `BINMODE=3` (set with `-v` when it reads standard input). Other
  awks ignore that variable.
- **Windows PowerShell 5.1's defaults.** `Get-Content` reads BOM-less UTF-8 as the ANSI code page, and its writers add
  a BOM and CR LF. Read and write with `[IO.File]` and an explicit no-BOM UTF-8 encoding.

Git treats a nanobuf file as text, since it has no NUL. With `* text=auto` it rewrites line endings: LF to CR LF on
a Windows checkout, CR LF to LF on commit. This never changes how a file parses. Git leaves `0x1E`, `0x1F` and lone
CRs alone, and the reader drops CR LF as readily as LF, so the same rows and fields come out. The only thing that can
change is the line breaks inside a value, which may come back as the other kind. To keep those exact, give nanobuf
files `-text` in `.gitattributes`. That stops the rewriting both ways, and `git diff` still shows them as text.
`eol=lf` is not enough: it keeps LF, but turns a value's real CR LF into LF on commit.

## Evolving what a file holds

The header's name must match, and so must its major version; otherwise the reader refuses the file. Within a major
version, the two ends stay compatible by these rules, which cost no reader code:

- **New fields are added only at the end of a kind.** A reader that expects more fields than a row has reads the
  missing ones as empty. A reader that finds more fields than it expects ignores the rest.
- **New kinds may be added.** A reader skips kinds it does not know.
- **A field that is no longer used stays in place,** written empty.

Anything else (removing a field, reordering fields, changing what a field means) is a new major version.

## Intrabuf schemas

*Proposed, not built.* A nanobuf file's content can be declared as an intrabuf schema, whose kinds and fields are
records. The schema then adds what the bare format leaves to the two ends:

- **Field names.** The file still holds positions; the schema names them.
- **Types.** A field is a string, a whole number, or a boolean (`1` or `0`).
- **Optional fields.** An empty field is allowed only where the schema says so. A field added within a major version
  has to be optional, since older files don't have it.

From a schema, intrabuf (or Koine's compiler, for Koine scripts) can generate readers that name each field and check
its type. These can be one-liners too, but each belongs to its schema rather than serving every file. Their shapes:

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

awk, the same in gawk, mawk, BusyBox and BWK awk:

```awk
BEGIN { BINMODE = 3; RS = "\036"; FS = "\037" }
{ sub(/^\r?\n?/, "") }                       # CR LF, LF or CR; also re-splits the fields
$1 == "version" { … }
```

PowerShell 5.1:

```powershell
foreach ($r in [IO.File]::ReadAllText($path, [Text.UTF8Encoding]::new($false)).Split([char]0x1e)) {
    $f = ($r -replace '^\r?\n?').Split([char]0x1f)
    switch ($f[0]) { 'version' { … } }
}
```

Generated readers are held to the same cases as the lines in intrabuf-nanobuf.

On the Java side, the processor could give such a schema a nanobuf codec, so components such as the installer
generator read and write the same files as the scripts. Nothing in Java reads or writes nanobuf yet.

## Relation to the rest of intrabuf

Nanobuf is not the [text format](text-format.md). That format is CSV with quoting, inline lists and references
between tables, which a shell cannot read without a real parser. Nanobuf keeps the facts line and the bundle log of
self-contained rows, and drops the rest.
