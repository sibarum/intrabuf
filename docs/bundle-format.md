# Intrabuf bundles

> **Status: partly implemented.** What exists is `@IntrabufBundle` and the [text log](#text-body-the-log): a bundle written
> and read one row at a time, whose members' messages are flat (no nested messages, no `byte[]`). Everything else
> here is still a design proposal: the binary body, ordering profiles, hints, size limits, shared blobs, nested
> messages in bundles, archives, and bundles in the manifest and the CLI. Names, chunk tags and error codes in those
> parts are placeholders to argue about.

A payload today holds one root message. A **bundle** holds many top-level messages of several kinds in one
self-contained payload, under one header. The motivating case is a keyframe, or scene re-sync: loading the whole
world in one frame, streamed as one large payload rather than a million separate messages.

A bundle is planned ahead. Its schema lists every kind of message it may contain, and nothing else can go in. You
can't combine arbitrary payloads.

## Declaring a bundle

A bundle is a record whose components are lists of message types:

```java
@IntrabufBundle(name = "world.Snapshot", minor = 1, compat = Compat.BACKWARD, order = Order.NEXT_ROW)
public record WorldSnapshot(
        List<Player> players,
        List<Tile> tiles,
        @Field(since = 1) List<Prop> props) {
}
```

Each component is a **member**. Members:

* are `List<M>` where `M` is an `@IntrabufMessage`. The lists are never null (an absent member is an empty list),
  and their elements are never null;
* are numbered in declaration order, starting at 0. The number is the member's **tag** on the wire;
* evolve like fields. Add a member at the end with `since` and bump the minor. Removing, reordering or retyping one
  is a major bump;
* follow the nesting rule: a bundle may contain only members at least as tolerant as itself. A FULL bundle has only
  FULL members, so a reader can skip a member it doesn't know by its length.

The same message type may be the element of more than one member. A bundle can't be a member, can't be nested in a
message, and can't be the element of a collection. There are no bundles inside bundles.

The bundle's schema has an id, a version, a compat mode and a fingerprint, like a message's. They go in the header,
which doesn't change: a bundle payload starts with the built-in header or an application header, exactly as a
message payload does. Whether a schema is a bundle is a property of the schema, recorded in the manifest. The
fingerprint covers the member list, the ordering profile and any mandated hints.

## What a bundle guarantees

* **Self-contained.** Row ids and blob ids are scoped to the bundle. Every reference resolves inside it, and the
  checks that need the whole payload run when it ends.
* **Order within a member is kept.** `players()` lists players in the order they were written.
* **Order across members is not data.** Whether player 3 came before tile 7 carries no meaning, and writers may
  interleave rows however suits them. Anything that needs time ordering carries a timestamp as a field.
* **Blobs can be shared.** Any number of rows, of any members, may refer to the same blob.

## Ordering profiles

The format accepts rows and blobs in any order. A bundle's schema may promise more, so that readers can stream:

| profile           | promise                                                                  | reader buffering            |
|-------------------|--------------------------------------------------------------------------|-----------------------------|
| `ANY` (default)   | none                                                                     | rows wait for their blobs    |
| `NEXT_ROW`        | every blob a row refers to arrives before the next row (or the end)      | at most one row              |
| `BEFORE_USE`      | every blob arrives before the first row that refers to it               | none                        |

`BEFORE_USE` is a special case of `NEXT_ROW`. Under `NEXT_ROW` a writer may send a row and then its blobs, which
suits a receiver that wants to see the row first and decide where its blobs go. Under `BEFORE_USE` a reader can build
each record the moment its row arrives.

The streaming writer enforces the profile. It holds a row or a blob back until writing it keeps the promise. A writer
given whole records already has every blob a row refers to, so it can write a row's new blobs straight before it
(`BEFORE_USE`) or straight after it (`NEXT_ROW`) and holds nothing back. Holding back only happens when blobs are
supplied separately from the rows that refer to them, for example streamed from disk after the rows. Under `ANY`, the
writer writes everything as soon as it is given.

A payload that breaks its schema's profile is undefined behaviour. Snapshot builds check the profile and fail with
`ORDER_VIOLATION`. Release builds trust it.

Nested messages don't need a profile. They stay inside their parent's row, as they do in a standalone message, so
they always resolve with it.

## Binary body

The header is followed by a sequence of **chunks**, ended by an END chunk. Each chunk starts with a varint tag. Its
low two bits are the kind:

| kind | chunk | layout after the tag                                    |
|------|-------|---------------------------------------------------------|
| `0`  | END   | nothing. The tag is the single byte `0x00`              |
| `1`  | ROW   | the member's framing (below). The tag is `member << 2 \| 1` |
| `2`  | BLOB  | varint blob id, varint length, raw bytes. The tag is `0x02` |
| `3`  | HINT  | varint length, then the hint. The tag is `hintType << 2 \| 3` |

A ROW is framed by its member's compat mode, as a nested message is:

| member's mode | framing                                         |
|---------------|-------------------------------------------------|
| STRICT        | body                                            |
| BACKWARD      | varint minor, body                              |
| FULL          | varint minor, varint body length, body          |

A reader reads a tag only at a chunk boundary, and every chunk's extent is known from its tag and the schema. So
hints never appear inside a row or a blob, and no byte of a payload is ever mistaken for a hint.

### `bytes` inside a bundle

Inside a bundle, a `bytes` value is written as one varint `v`, then:

* `v` even: an inline value of `v >> 1` bytes follows;
* `v` odd: a reference to blob `v >> 1`.

Writers choose per value. Small values go inline. Large or shared ones go in blobs. The writer shares a blob between
values that are the same `byte[]` instance, so the reader gives back one array to every record that referred to it.
Those arrays alias: a receiver that mutates one sees it in every record.

### Blobs

Blob ids are unique within the bundle. Writers number them from 0 in the order they write the BLOB chunks. Every
blob is referred to at least once, and every reference names a blob that is in the bundle. A FULL reader that skips
rows of a member it doesn't know also drops blobs that only those rows referred to.

### Hints

A hint is information the reader could do without, sent so it doesn't have to. Hints are **advisory** by default: a
writer may leave them out, a reader may ignore them, and a payload must parse without them. A reader skips a hint
type it doesn't know by its length.

| type | hint          | content                         | lets the reader                                    |
|------|---------------|---------------------------------|----------------------------------------------------|
| 1    | TOTAL_LENGTH  | varint bytes left in the body   | reject an oversized bundle at once, size one buffer |
| 2    | MEMBER_COUNT  | varint member, varint rows      | size a member's list                               |
| 3    | BLOB_LENGTH   | varint blob id, varint length   | allocate a blob before it arrives                  |
| 4    | BLOB_REFS     | varint blob id, varint count    | free a blob after its last use                     |
| 5    | ROW_LENGTH    | varint length of the next row   | skip or hand off a row without parsing it          |

A hint may appear anywhere a chunk can, and applies to what comes after it.

A schema can **mandate** a hint. The writer must then send it, and the reader may rely on it instead of working the
fact out itself. That's what lets generated code do a single bulk read of a blob, or free blobs as it goes:

```java
@IntrabufBundle(name = "world.Snapshot", hints = { Hint.BLOB_LENGTH, Hint.BLOB_REFS })
```

A mandated hint that is missing or wrong is undefined behaviour. Bundles only travel between trusted peers. Snapshot
builds check mandated hints and fail with `HINT_MISMATCH`.

`BLOB_REFS` is what bounds memory. Without it, a backward reference may name any earlier blob, so a reader keeps
every blob until the bundle ends.

### Size limits

`@IntrabufBundle(maxBytes = …)` caps a bundle's body, and a reader may set its own cap. Counting bytes is cheap, so
this is checked even in release builds: a bundle over either cap fails with `TOO_LARGE`, as soon as a TOTAL_LENGTH
hint or the bytes read so far show it.

## Text body: the log

In text, a bundle is a **log**. The facts line comes once, each member is declared once, and then rows follow in any
order, each naming its member. A writer can append each row as it happens, and a reader can hand each one on as it
reads it:

```
#intrabuf 1 app.Trace 1.1 BACKWARD id=0x38319260
#member frames app.Frame 1.0
frames,at,workMicros,idle
#member keys app.KeyPress 1.1
keys,at,key,text
#member marks app.Mark 1.0
marks
frames,2026-10-11T10:00:00Z,400,true
keys,2026-10-11T10:00:00.003Z,A,a
marks
frames,2026-10-11T10:00:00.016Z,401,false
```

* **The facts line** is the bundle's, and is checked exactly as for a standalone payload: schema, major, compat mode,
  minor, fingerprint. A hand-written log may leave out `id=`.
* **A declaration** is `#member <name> <schema> <major>.<minor>`, then a columns line that starts with the member's
  name. The version is the one the member's rows were written at. It is checked by that message's own mode, as a
  nested message's is (STRICT must match, BACKWARD must not be newer, FULL can be anything, the major must match), and
  the columns are checked against the fields of that version. A member is declared at most once. Writers declare
  every member before the first row. Readers accept a declaration anywhere above the member's first row.
* **A row** is the member's name, then its cells, exactly as the cells of any text row (see `text-format.md`). Rows
  aren't numbered and hold no references, so each one stands alone. Order within a member is kept. A member with no
  fields has rows that are just its name.
* **There is no root table, no `#message` table and no `#blobs`**, which is why members' messages can't yet hold
  nested messages or `byte[]` values. The processor rejects a bundle whose members could.
* Blank lines and comments (`#` followed by a space) may appear anywhere after the facts line. Lines end with `\n` or
  `\r\n`.

Evolution follows the bundle's mode. A newer BACKWARD reader reads an older log, and members the log predates are
empty lists. A FULL reader of a newer log skips the rows of members it doesn't know, and cells past the fields it
knows. A declaration of a member the log's minor shouldn't have is an error.

The writer sends each row to its output in a single `append`, newline included, and writing is synchronized. Rows
from several threads therefore never interleave, and a reader of a live log never sees part of a row the writer
finished.

## Archives

Base64 makes a text payload a third larger than its blobs. For use as a debug export, any payload, bundle or not, can
instead be written as an **archive**: a zip file with the extension `.intrabuf`.

```
payload.txt        the text payload, with no #blobs section, or only small blobs
blobs/1            blob $1, raw
blobs/2            blob $2, raw
```

A reference `$n` resolves to the `#blobs` line or to `blobs/<n>`, and exactly one of them must exist. Zip rather than
tar.gz because a zip has a central directory: a tool can list a large archive and open one blob without reading the
rest. Writers store blobs uncompressed when they are already compressed, as images usually are.

`decode` recognises an archive by its first bytes, `PK\x03\x04`, which can't be confused with a binary header
(`0xB0`–`0xBF`) or a text payload (`#intrabuf `). `Format.of` reports it as `ARCHIVE`. The intrabuf analyzer, when
it exists, registers the `.intrabuf` extension.

## API

What exists, for the text log:

```java
// streaming write: the facts line and declarations go out when the writer is made, then one line per call
try (TraceIntrabuf.Writer log = TraceIntrabuf.writer(Files.newBufferedWriter(path))) {
    log.frames(frame);
    log.keys(keyPress);
    log.flush();                                   // buffering is the output's business
}

// streaming read: each row goes to the visitor as soon as it is read; nothing else is kept
Header facts = TraceIntrabuf.CODEC.read(text, new TraceIntrabuf.Visitor() {
    @Override public void frames(Frame f)  { ... }
    @Override public void keys(KeyPress k) { ... }  // members not overridden are ignored
});

// whole bundle
Trace trace = TraceIntrabuf.fromText(text);        // or fromBytes(utf8)
String text = TraceIntrabuf.toText(trace);
```

The visitor and the writer have one method per member, named after it. Materialising a `Trace` is the visitor that
appends to lists. A reader takes the log as a `String` or UTF-8 bytes. The parse is streamed, but the text is in
memory.

The proposal, once there is a binary body:

```java
WorldSnapshot w = WorldSnapshotIntrabuf.fromBytes(payload);          // binary, text or archive
byte[] bytes    = WorldSnapshotIntrabuf.toBytes(w);
```

## Open questions

* **Separately supplied blobs.** What does the writer API look like for a blob fed in apart from its rows (a handle
  the row refers to, filled later)? How much may such a writer hold back before it fails rather than buffers?
* **Blob ids.** Numbering in BLOB chunk order means a `NEXT_ROW` writer refers to a blob before giving it an id it
  hasn't written yet. That works, but a reader indexing blobs by id must allow for gaps until the bundle ends.
* **Archives of binary payloads.** Should an archive be able to carry a binary payload too, or is it text only?
