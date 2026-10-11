# Intrabuf bundles

> **Status: partly implemented.** What exists is `@IntrabufBundle` and the [text log](#text-body-the-log): a bundle written
> and read one row at a time, whose members' messages are flat (no nested messages, no `byte[]`), and which a reader
> stops reading at `#end` or at a [version boundary](#version-boundaries) newer than itself. Everything else
> here is still a design proposal: the binary body, ordering profiles, hints, size limits, shared blobs, nested
> messages in bundles, archives, [collected bundles](#collected-bundles), the [streaming parser](#streaming-parser)
> and the writer's end line, the rest of [forward compatibility](#forward-compatibility) (recorded as requirements only),
> and bundles in the manifest and the CLI.
> Names, chunk tags and error codes in those parts are placeholders to argue about.

A message payload holds one root message. A **bundle** holds many top-level messages of several kinds in one
self-contained payload, under one header. The motivating case is a keyframe, or scene re-sync: loading the whole
world in one frame, streamed as one large payload rather than a million separate messages.

Bundles follow the rest of intrabuf: both ends are trusted, and everything past "the same build reads what it wrote"
is something the schema asks for. A sender that only wants to spray rows at a receiver, and a receiver that needs
them in a particular order to stream-parse them, should both be served without the format getting in the way.

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

## Collected bundles

> **Proposal.** Nothing in this section exists yet.

Some bundles can't be listed by whoever declares them. The motivating case is a trace: every layer of a stack
reports events, the probe that writes them sits at the bottom and can name none of them, and each library adds kinds
as it pleases. A collected bundle has its members found by the processor instead of written out. It is still planned
ahead, by the build: the application's compilation sees every module it is made of, so it can list every kind there
is, and the one promise (the same build reads what it wrote) is exactly what such a bundle needs.

```java
// atchung-probe, at the bottom: only the marker
public interface ProbeEvent { }

// vexelray-gui, a library: declares an event and knows nothing about any bundle
@IntrabufMessage
public record LoopPark(long budgetNanos) implements ProbeEvent { }

// the application: the processor writes the rest
@IntrabufBundle(collect = ProbeEvent.class)
interface Trace { }
```

The processor collects every `@IntrabufMessage` that implements the marker, in this compilation and in the manifests
of its dependencies, and generates:

* the bundle record, `TraceBundle`, one list per member, and its codec, writer and visitor as for a declared bundle;
* `write(ProbeEvent)` on the writer, which hands an event to its member by type. This is what lets code that knows
  only the marker (the probe) write any member.

The rules:

* **Members are named by simple class name** in lower camel case: `LoopPark` is the member `loopPark`, its rows start
  `loopPark,` and its writer method is `loopPark(LoopPark)`. Two collected messages with the same simple name are a compile error,
  never a silent rename.
* **The fingerprint covers the collected list.** Adding an event anywhere in the build changes it, so a reader from
  another build notices.
* **STRICT by default.** A generated list has no place for `since`. A FULL collected bundle is allowed when every
  member is FULL: a reader skips the members it doesn't know, which is what yesterday's trace read by today's tools
  wants. BACKWARD is not offered, since nothing records which minor a member arrived at.
* **Text only, until binary tags are settled.** A text row names its member, so order doesn't matter there. The
  binary body tags members by declaration order, and a collected order changes whenever an event is added. A binary
  collected bundle needs tags from a hash of the name, or ids its messages assign themselves with
  `@IntrabufMessage(id = …)`.
* **Nothing is inferred that a message doesn't already declare.** Members are ordinary `@IntrabufMessage` records,
  with their codecs as they are. Collecting finds them; it doesn't guess how to serialize anything.

What it needs that doesn't exist: manifests recording each message's supertypes, so a dependency's messages can be
found by marker, and the processor reading dependencies' manifests.

## What a bundle is

* **Self-contained.** Row ids and blob ids are scoped to the bundle. Every reference resolves inside it, and the
  checks that need the whole payload run when it ends.
* **Order within a member is kept.** `players()` lists players in the order they were written.
* **Order across members is not data.** Whether player 3 came before tile 7 carries no meaning, and writers may
  interleave rows however suits them. Anything that needs time ordering carries a timestamp as a field. In this stack
  those come from kronometer's synchronized clocks, which are trusted like everything else.
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
* **The end line**, `#end`, ends the log: a reader reads nothing past it. A FULL log may also hold
  [version boundaries](#version-boundaries), `#end <major>.<minor>`. Readers understand both. Nothing writes either
  yet, and a log may still end without one (see [The end of a payload](#the-end-of-a-payload)).
* Blank lines and comments (`#` followed by a space) may appear anywhere after the facts line. Lines end with `\n` or
  `\r\n`.

Evolution follows the bundle's mode. A newer BACKWARD reader reads an older log, and members the log predates are
empty lists. A FULL reader of a newer log skips the rows of members it doesn't know, and cells past the fields it
knows. A declaration of a member the log's minor shouldn't have is an error. What else FULL will have to tolerate is
in [Forward compatibility](#forward-compatibility).

The writer sends each row to its output in a single `append`, newline included, and writing is synchronized. Rows
from several threads therefore never interleave. A reader of a live log never sees part of a row, as long as the
output writes each append whole: a `BufferedWriter` doesn't, since it writes out what it holds when its buffer fills,
mid-row or not (see [Open questions](#open-questions)).

## Streaming parser

> **Proposal.** Today's parser hands each row to the visitor as it reads it, but it reads from a `String` or bytes
> already in memory. Nothing in this section exists yet.

A bundle is the stream between two components: unbounded, not infinite. Its reader has to work while the stream is
still arriving, in the memory of what it hasn't finished. So the parser takes its input in pieces, as the input
arrives, and calls the visitor each time a row is complete. There is one parser, and every way of reading a bundle
is built on it.

```java
LogParser p = TraceIntrabuf.CODEC.parser(visitor);
p.feed(chunk, off, len);   // any amount, split anywhere; complete rows go to the visitor before feed returns
p.feed(more, 0, n);
Header facts = p.end();    // the input is over: checks that the payload ended cleanly
```

* **Pushed, not pulled.** The caller hands the parser whatever it has. The parser never waits for input, so it serves
  blocking and non-blocking code alike: a socket callback, a pipe, a file read loop.
* **No threads of its own.** The visitor runs on the thread that calls `feed`, before `feed` returns. There are no
  futures and no queues. A slow visitor slows the feeder, which is the only backpressure there is, and the right
  kind between trusted peers. A parser is fed by one thread at a time.
* **A chunk may end anywhere**: inside a UTF-8 character, a quoted cell, a backtick block, the facts line, a varint.
  The parser keeps the unfinished tail and picks up where it stopped.
* **A complete row is a parser state, not a newline.** A backtick value may span lines, so a text row ends at the
  newline that closes it, not at the first one.
* **Memory is the declarations plus the unfinished row.** In the binary body, add the blobs the ordering profile
  makes it hold. A row that never ends grows the tail, so the reader's size cap counts it, and a tail over the cap
  fails with `TOO_LARGE`.
* **Errors arrive where they happen.** A malformed row fails in the `feed` that completes it, naming its line, after
  the rows before it have gone to the visitor. That is what streaming means. `fromText` and `fromBytes` still return
  a whole bundle or throw; they never return part of one.

Everything else is a loop over it:

```java
Header facts = TraceIntrabuf.CODEC.read(in, visitor);   // InputStream: feed what read() returns, then end()
Header facts = TraceIntrabuf.CODEC.read(text, visitor); // String or bytes: one feed, then end()
Trace trace  = TraceIntrabuf.fromText(text);            // the visitor that appends to lists
```

The binary body suits this better still. Every chunk's extent is known from its tag and the schema, so the parser
needs no lookahead past a varint, and the [ordering profiles](#ordering-profiles) already say what it must hold.

### The end of a payload

Running out of input isn't the same as the payload ending. A binary bundle ends with its END chunk. A text log ends
with an end line:

```
frames,2026-10-11T10:00:00.016Z,401,false
#end
```

The writer writes `#end` when it is closed. A reader reads nothing past it, so whatever follows can't fail it.

Today's reader stops at `#end`, but doesn't yet require one, and the writer doesn't write it. The rest of this
section is proposal. `end()` checks the payload ended: a log without `#end`, or one cut off inside a row, fails with `TRUNCATED`. That is
what a log written by a crashed process, or one cut off in transit, looks like. A reader that wants such a log
anyway asks for it:

```java
Header facts = p.endUnfinished();   // accepts a payload with no end; a partial last row is still an error
```

That is how a live tail, or a post-mortem of a crashed process's log, reads what it has. Logs written before the end
line existed read only this way.

## Forward compatibility

> **Requirements, not design.** This records what forward compatibility has to mean for a bundle, so nothing built
> before it rules it out. What exists today is FULL's part of it: an older reader skips cells past the fields it
> knows, and the rows of members it doesn't know. The text log's reader also understands
> [version boundaries](#version-boundaries), though nothing writes one yet. Removed columns, unknown parts and unknown
> blobs don't exist.

Forward compatibility is an older reader reading a newer payload. It is something a schema declares, never a
default: everything in this section applies only to a FULL bundle. In a STRICT or BACKWARD payload, a boundary or
anything else an older reader would have to ignore is an error, exactly as it is today.

A FULL bundle is read by builds that predate it, so it carries its whole history. A future version must remember
every change ever made to the schema, and what a change looks like on the wire is chosen so that older readers can
pass over it:

* **Columns are only added, at the end.** An older reader's last field is its **column boundary**: cells past it are
  ignored. No marker is needed, because a new column can only ever land past every column an older reader knows.
* **A removed column isn't removed.** It keeps its place, and every row leaves its cell empty. The schema keeps the
  field as a tombstone, so its position stays taken and the fingerprint still covers it. An older reader that still
  has the field reads the empty cell as the field's default, so a FULL field that may one day be removed needs a
  default, as a field added with `since` does.
* **Unknown schema types are ignored.** A member, or a nested message's table, whose schema the reader doesn't know is
  skipped, with every row of it.
* **Unsupported blobs are ignored.** A blob referred to only by what the reader skipped, or one it can't make use of,
  is passed over, not an error.

### Version boundaries

The rules above cover the changes that can be foreseen. A **version boundary** is the catch-all for the rest: new
syntax, new kinds of parts, anything an older parser can't even read past. It is an end line that carries a version:

```
#intrabuf 1 app.Trace 1.3 FULL id=0x38319260
#member frames app.Frame 1.0
frames,at,workMicros,idle
frames,2026-10-11T10:00:00Z,400,true
#end 1.3
(parts only a 1.3 reader understands)
#end
```

A reader whose schema version is at or above the boundary's keeps parsing. Any other reader treats the boundary as
`#end`: what it read is a complete payload, and `end()` succeeds. It reads nothing past the boundary, so what a
newer writer put there can't fail it, however foreign.

The text log's reader does this today, and checks the rules below. In a STRICT or BACKWARD log a boundary is an
error.

* **The version is the bundle schema's**, `major.minor`, not the text or wire format's. The major must be the facts
  line's, so it is the minor that is compared.
* **What follows a boundary is parts**: records, attachments, or kinds of part that don't exist yet. Whatever a
  future version adds to the bundle's syntax goes after a boundary at that version, so a reader older than it stops
  before it.
* **Boundaries rise.** Each one's version is above the one before it, and none is above the facts line's.
* **None is at minor 0**, since every reader would pass it.
* **Plain `#end` is the end no reader passes.** In the binary body a boundary is a kind `0` chunk with tag
  `minor << 2`. Minor 0 can't be a boundary, which leaves `0x00` for END.
* **A boundary splits by position, not by kind.** An older reader sees nothing after it, including rows of members it
  knows. A writer puts everything older readers should see before the first boundary.

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
memory until the [streaming parser](#streaming-parser) exists.

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
* **Whole rows under buffering.** Should `LogWriter` buffer rows itself and hand its output only whole rows? That
  makes the live-log promise true on any output, and is likely faster than a `BufferedWriter` behind a lock.
* **Feeding characters.** The parser takes bytes, since every payload is bytes and text is UTF-8. Should a text log
  also be fed `char`s, for input that is already decoded (a `Reader`)?
* **A cap on one row.** The reader's size cap bounds the whole payload. Should a streaming reader also cap a single
  row, so a log that is fine overall but has one runaway row fails early?
