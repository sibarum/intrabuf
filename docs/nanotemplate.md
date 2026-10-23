# Nanotemplate, version 1

*Proposed.*

**The template format optimized for implementation compactness: every template names its own escape character and
delimiters, and a placeholder's key can be anything at all.**

Like [nanobuf](nanobuf.md), nanotemplate works even where there are no libraries, because filling one in takes a single
line of the shell you already have. Here is the whole filler for Bash:

```bash
ntf()(LC_ALL=C;f=$1;b(){ echo "nanotemplate: $f ${1:-is not nanotemplate 1}">&2;exit 1;};s(){ a=;while q=${t%%"$e"*};q=${q%%"$1"*};a+=$q;t=${t#"$q"};[[ $t = "$e"?* ]];do a+=${t:1:1};t=${t:2};done;};IFS= read -r h <"$f"||b;IFS= read -rd '' t <"$f";t=${t#"$h"?};h=${h%$'\r'};[[ $h =~ ^nanotemplate\ 1\ [[:graph:]]\ [^\ ]+\ [^\ ]+$ ]]||b;IFS=\  read -r n n e o c <<<$h;[[ $o$c = *"$e"* ]]&&b;r=;while s "$o";r+=$a;[ "$t" ];do t=${t#"$o"};s "$c";[[ $t = "$c"* ]]||b;t=${t#"$c"};i=0;while((i+=2,i<$#))||b "has no value for $a";[[ ${!i} != "$a" ]];do :;done;r+=${@:i+1:1};done;printf %s "$r")
```

POSIX `sh` and PowerShell get one line each too, in [intrabuf-nanotemplate](../intrabuf-nanotemplate/README.md). The
format does one thing: it replaces each placeholder with its value, verbatim. It has no conditionals, no loops, no
includes and no filters. A value that needs quoting, escaping or formatting for where it lands is prepared by the
caller before it is passed in. That is the caller's business, not the template's.

## The bytes

A template is UTF-8 text with no byte order mark and no NUL. Its first line is the header, ended by a line feed:

```
nanotemplate 1 \ {{ }}
```

The header has five fields, separated by single spaces:

1. `nanotemplate`, the format.
2. `1`, its version.
3. **The escape:** one printable ASCII character (`!` to `~`).
4. **The opening delimiter:** one or more characters.
5. **The closing delimiter:** one or more characters.

No field contains a space or a line feed, and neither delimiter contains the escape. The delimiters may be anything
else: one character or several, ASCII or not, the same as each other or different. A carriage return just before
the line feed is dropped, so a header with CR LF reads the same.

Everything after the header's line feed is the body. It is copied to the output byte for byte, except for:

- **The escape followed by any one character,** which writes that character. `\{{` writes `{{`, and `\\` writes `\`.
  The escaped character may be a line break, a delimiter's first character or the escape itself.
- **A placeholder:** the opening delimiter, a key, and the closing delimiter. It writes the key's value.

A key runs from the opening delimiter to the first closing delimiter that isn't escaped. The escape works inside it
exactly as in the body, so a key can hold any text, including the closing delimiter (`{{a\}}b}}` is the key `a}}b`).
The key is the text with its escapes resolved. It may be empty, and it may contain spaces, line breaks and the
opening delimiter. It is never trimmed or normalized in any way.

A closing delimiter outside a placeholder is ordinary text. So is anything that doesn't start with the escape or the
opening delimiter. At each position, the escape is checked before the opening delimiter.

## Filling it in

A filler takes a template and its values as pairs of key and value. It does this, in any language:

1. Read the header up to the first line feed and drop one carriage return from its end. Refuse the template if there
   is no line feed, or if the header isn't five fields as above, or if a delimiter contains the escape.
2. Scan the body from the start. Copy ordinary text. Where the escape is, copy the character after it; if nothing
   follows it, refuse the template. Where the opening delimiter is, read the key up to the closing delimiter,
   resolving escapes; if the body ends first, refuse the template.
3. For each key, write the value of **the first pair whose key equals it exactly**, compared character for character
   with no change of case, no trimming and no normalization. If no pair has that key, refuse the template. A value is
   written verbatim. It is never scanned for escapes or placeholders, so it can hold anything.
4. Pairs whose key is never used are ignored. So is a last key with no value after it: it is not a pair.

A filler writes nothing for a template it refuses. It says why on standard error, either
`nanotemplate: FILE is not nanotemplate 1` (a bad header, an unclosed placeholder or an escape at the very end) or
`nanotemplate: FILE has no value for KEY`, and fails.

## Examples

```
nanotemplate 1 \ {{ }}
Hello, {{name}}! A literal \{{name}} and a backslash \\.
```

With `name` = `World` this fills to `Hello, World! A literal {{name}} and a backslash \.` followed by a line feed.

A template picks the delimiters that collide least with its body. PowerShell source, where `{` `}` and `$` are
everywhere, can use `«` and `»`:

```
nanotemplate 1 ` « »
$config = @{ Name = '«name»'; Root = '«root»' }
```

A shell script can use `%` for both delimiters, with `\` to escape:

```
nanotemplate 1 \ % %
echo "100\% installed to %dir%"
```

## Reading notes for implementers

- **Work in bytes or in characters, not in a mix.** Every delimiter and the escape is valid UTF-8, so matching bytes
  gives the same result as matching characters: the escape is one byte, and no delimiter can start partway through a
  character. The shell lines work in bytes under `LC_ALL=C`. Bash on Cygwin and MSYS2, Git for Windows's `sh` among
  them, stores characters in 16 bits and splits a character outside the Basic Multilingual Plane (an emoji, say)
  when it takes one character in a UTF-8 locale; working in bytes avoids that.
- **Check the escape is ASCII without trusting the locale.** dash reads its locale once at startup and ignores
  `LC_ALL` set later, so `[[:graph:]]` there may accept `é`. The `sh` line also checks the character's code with
  `printf %d "'$e"`, which is under 128 only for ASCII in any locale.
- **Don't let the shell strip trailing line breaks.** `$(cat file)` drops them; read with a marker after
  (`$(cat file; echo .)`, then remove the `.`) or with `read -d ''`.
- **Windows PowerShell 5.1 strips a byte order mark** when it reads text with `ReadAllText` or `Get-Content`, which
  would make it accept a template the other fillers refuse. Read the bytes and decode them.
- **PowerShell's `-match` and `-replace` ignore case,** and so do its hashtables. Use `-cmatch`, `-creplace` and
  `-ceq`, and don't put the values in a hashtable.

## Line endings and Git

Git and editors may turn LF into CR LF, or back, in a template as in any text file. The header reads the same either
way. The body is copied verbatim, so the filled output gets whichever line endings the template has. If that matters,
give templates `-text` in `.gitattributes`, as for [nanobuf](nanobuf.md#reading-and-writing) files.

## Relation to nanobuf

Nanotemplate is nanobuf's sibling: the format for what a generator or an installer writes out, where nanobuf is the
format for what it reads and records. They share the same aim, an implementation that fits in one line, and the
same strictness: a file is either read exactly as specified or refused, never guessed at.
