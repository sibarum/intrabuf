# Intrabuf text format, version 1

Every codec writes and reads a second, human-readable format alongside the binary one. `decode` takes either: it
looks at the first bytes and parses what it finds. A peer can switch one message to text (to debug a conversation,
or to send a hand-written message) and back, and nothing else changes. The compatibility rules are the binary
format's, applied to the same facts.

A text payload is UTF-8. It is a set of CSV-like tables. Here is `example.Customer`, given a `byte[] photo` field
for illustration:

```
#intrabuf 1 example.Customer 1.2 BACKWARD id=0x313ffe3a fp=0xe95fde79 snapshot
id,name,email,active,favoriteColor,home,tags,nickname,loyaltyPoints,photo
42,Ada Lovelace,ada@example.com,true,GREEN,&1,[vip,early-adopter],Ada,{2025=1200},$1

#message example.Address 1.1
&,street,city,postcode,unit
&1,`12 St James's Square
Westminster`,London,SW1Y 4JH,

#blobs
$1,iVBORw0KGgoAAAANSUhEUg==
```

1. **The facts line.** The same facts as a binary header.
2. **The root table.** A columns line naming the root message's fields, then one row of values.
3. **A table per nested message type.** Its rows are numbered, and cells refer to them.
4. **The blobs.** Every `byte[]` value as base64, referred to from cells.

## The facts line

```
#intrabuf <format> <schema> <major>.<minor> <STRICT|BACKWARD|FULL> [id=0x<hex>] [fp=0x<hex>] [snapshot]
```

* `<format>` is `1`. Any other version is rejected (`UNSUPPORTED_WIRE_VERSION`), and so is any fact this reader
  does not know.
* `id=` is the schema id. Writers always write it. A hand-written payload may leave it out, and the id is then the
  one its schema name implies. That id is the hash of the name, unless the schema assigns its own id with
  `@IntrabufMessage(id = …)`, in which case the payload must give it.
* `fp=` is the structural fingerprint, written exactly when the binary header would carry it: STRICT schemas and
  snapshot builds.
* `snapshot` marks a development build.

The schema name must be the reader's. The checks then run as for a binary header (see `wire-format.md`): schema,
major, compat mode, minor and fingerprint.

A text payload starts with `#intrabuf ` (after an optional UTF-8 byte order mark). A binary payload with the
built-in header starts with a byte `0xB0`–`0xBF`, so the two cannot be confused.

## Tables

A **columns line** lists field names in declaration order, separated by commas. They must be exactly the fields the
payload's minor has. For BACKWARD and FULL, that means the fields with `since` up to the payload's minor. A FULL reader
of a newer payload expects its own fields followed by others, and skips the extra cells. Column names are checked, so
a row with a misplaced value fails with the column's name rather than decoding into the wrong field.

The **root table** is the columns line and the row right after the facts line, with nothing between them.

A **nested message's table** follows, after any number of blank and comment lines:

```
#message <schema> <major>.<minor>
&,<field>,<field>,...
&<n>,<value>,<value>,...
```

Each row starts with its number, `&<n>`. A cell holding a nested message refers to it by that number. Rows are
numbered across the whole document, so `&3` is the same row wherever it appears. The version is the one the nested
message was written at. It is checked by the nested message's own mode: STRICT must match, BACKWARD must not be newer,
FULL can be anything. The major must always match.

**Every row is referred to exactly once.** A row referred to twice, or one that refers to itself (directly or
through other rows), is an error, and so is a row nobody refers to. Most of the time that last one is a mistyped
reference.

Writers number rows breadth-first and group them by type. Readers accept rows in any order and references in any
direction.

**Blobs** come in a `#blobs` section, one per line: `$<n>,<base64>`. Line breaks inside the base64 are not
allowed. Every blob, like every row, is referred to exactly once.

Lines that are empty, or that start with `#` followed by a space (or nothing), are comments. They may appear between
tables and between rows. A line that starts `#` and a word is a section marker, and an unknown one is an error.

Lines end with `\n` or `\r\n`.

## Cells

Cells are separated by commas. Spaces and tabs around a cell are ignored.

| value                         | written as                                                         |
|-------------------------------|--------------------------------------------------------------------|
| null, an empty `Optional`     | nothing: an empty cell                                             |
| `bool`                        | `true`, `false`                                                    |
| integers (all widths, fixed)  | decimal: `-42`                                                     |
| `float32`, `float64`          | Java's shortest round-trip form: `21.5`, `1.0E-10`, `NaN`, `-Infinity` |
| `string`, `char`              | see below                                                          |
| `bytes`                       | `$<n>`, a reference to a blob                                      |
| `uuid`                        | `7d444840-9dc0-11d1-b245-5ffdce74fad2`                             |
| `instant`                     | ISO-8601 UTC, as `Instant.toString()`: `2026-10-05T12:00:00.250Z`  |
| enum                          | the constant's name: `GREEN`                                       |
| message                       | `&<n>`, a reference to a row                                       |
| `list<T>`, `set<T>`           | `[a,b,c]`, `[]`                                                    |
| `map<K,V>`                    | `{k=v,k=v}`, `{}`                                                  |

Collections nest freely, `{primes=[2,3,5],empty=[]}`, and their elements follow the same rules as cells. Elements,
keys and values can't be empty, since they are never null. Parsing is driven by the schema, so the reader always
knows what type it expects next.

NaN payload bits are not preserved: every NaN reads back as Java's canonical `NaN`. The binary format keeps the raw
bits.

### Strings

A string takes the first of three forms that holds it exactly:

* **Bare**: `Ada Lovelace`. Allowed when the string is non-empty, has no leading or trailing space or tab, doesn't
  start with `&`, `$` or `#`, and contains none of `` , " ` [ ] { } = \ ``, no tab, no control character, and no
  unpaired surrogate.
* **Backticks**, for text that spans lines. The content is verbatim, newlines included, and a doubled backtick
  stands for one. A `\r\n` inside reads as `\n`. Writers use backticks for strings that contain a newline and no
  other control character (tabs are fine) and no unpaired surrogate. The cell continues on the following lines:

  ```
  &1,`12 St James's Square
  Westminster`,London,SW1Y 4JH,
  ```
* **Double quotes**, otherwise: `"a, b"`, `""` (the empty string), `"say \"hi\""`. The escapes are `\"`, `\\`, `\n`,
  `\r`, `\t` and `\uXXXX`. Writers escape control characters, U+2028, U+2029, U+FEFF and unpaired surrogates as
  `\uXXXX`. Those last ones can't be carried by UTF-8, or tend to be rewritten by editors and transports.

An unquoted string that starts with `&` or `$` is rejected, because it looks like a reference. Quote it.

## Application headers

A frame with an `@IntrabufHeader` header keeps its facts on the first line. The header's own components (the ones
without a `@Role`) go in a `#header` table, between the facts line and the root table:

```
#intrabuf 1 test.Ping 1.0 STRICT id=0x00000002 fp=0xf00a24a1
#header test.Envelope
magic,protocolVersion,request,reply,correlationId
-5039,2,true,false,5
seq
9
```

The role components are filled from the facts line when the frame is read. A header whose components are all roles
has no `#header` table.

An application header has no magic number. `HeaderCodec.readFrame` tells a text frame by its first ten bytes,
`#intrabuf `. An application whose binary header could begin with those bytes should call `readTextFrame` itself when
its own framing says the frame is text.

## API

```java
String text   = CustomerIntrabuf.toText(customer);           // CODEC.encodeText
byte[] either = CustomerIntrabuf.CODEC.encode(customer, Format.TEXT);
Customer back = CustomerIntrabuf.fromBytes(either);           // binary or text
Customer hand = CustomerIntrabuf.fromText(text);              // text only
Format f      = Format.of(payload);                           // which one arrived
Header facts  = Header.peek(payload);                         // routing works on both

// application headers
String frame = EnvelopeIntrabuf.CODEC.encodeText(envelope, PingIntrabuf.CODEC, ping);
HeaderCodec.Frame<Envelope> fr = EnvelopeIntrabuf.CODEC.readFrame(reader);   // either format
Object msg = registry.get(fr.facts().schemaId()).decodeBody(fr, reader);     // pass the frame, not its facts
```

`intrabuf text -m <manifests> <payload>` converts a payload in either format to text. `decode` and `header` accept
text payloads as they are.
