# Intrabuf

Compile-time binary serialization for Java records. Annotate a record; an annotation processor generates a codec of
straight-line reads and writes. Nothing reflects at runtime, so it works as-is under GraalVM native-image.

Intrabuf is for the case where both sides are your code and all you need is for the right data to arrive, quickly
and in few bytes. You don't design the binary format. You declare how a schema is allowed to evolve, and every
payload carries enough header to prove the reader can decode it.

```java
@IntrabufMessage(name = "example.Customer", minor = 2, compat = Compat.BACKWARD)
public record Customer(
        long id,
        @Field(nonNull = true) String name,
        String email,
        boolean active,
        Address home,
        @Field(nonNull = true) List<String> tags,
        @Field(since = 1) Optional<String> nickname,
        @Field(since = 2, nonNull = true) Map<String, Integer> loyaltyPoints) {
}

byte[] payload = CustomerIntrabuf.toBytes(customer);
Customer back  = CustomerIntrabuf.fromBytes(payload);
```

Every codec also speaks a human-readable [text format](#readable-the-text-format), and `fromBytes` accepts either
format, so any message can go over the wire as text when a person needs to read or write it.

## Modules

| module               | what it is                                                                                      |
|----------------------|-------------------------------------------------------------------------------------------------|
| `intrabuf-runtime`   | Wire format, header and compatibility rules, `MessageCodec`, annotations, schema model. No dependencies. |
| `intrabuf-processor` | The annotation processor. Only ever goes on the processor path.                                 |
| `intrabuf-cli`       | `intrabuf`, the payload inspector. Builds to a native executable.                               |
| `intrabuf-example`   | A small domain configured the way an application would configure it.                            |
| `intrabuf-tests`     | End-to-end tests: round trips, compatibility, fuzzing, generated-vs-dynamic agreement, CLI.      |

## Setup

```xml
<dependency>
    <groupId>sibarum.intrabuf</groupId>
    <artifactId>intrabuf-runtime</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>sibarum.intrabuf</groupId>
                <artifactId>intrabuf-processor</artifactId>
                <version>0.1.0-SNAPSHOT</version>
            </path>
        </annotationProcessorPaths>
        <compilerArgs>
            <arg>-Aintrabuf.buildVersion=${project.version}</arg>
            <arg>-Aintrabuf.module=${project.artifactId}</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

* `intrabuf.buildVersion`: a version ending in `-SNAPSHOT` makes this a **snapshot build** (see below). If it is
  unset, the build is treated as a snapshot, which is the safe failure mode.
* `intrabuf.snapshot`: `true` or `false`, overriding what the version implies.
* `intrabuf.module`: names the manifest, `META-INF/intrabuf/<module>.json`. Make it unique per artifact.

Build and test everything with `mvn install`. Build the native CLI with `mvn -Pnative -DskipTests package` (it
lands at `intrabuf-cli/target/intrabuf[.exe]`).

## Correct data: what the header guarantees

Every payload starts with a header: magic and wire version, flags, schema id, major, minor, and a structural
fingerprint where needed. A release payload in BACKWARD or FULL mode spends 8 bytes on the header. STRICT schemas
and snapshot builds spend 12. Readers decide from the header alone whether they can decode the body:

* **Wrong schema, wrong major, different compat mode** → always rejected.
* **STRICT** (default): minor and fingerprint must both match. Smallest and fastest. If someone changes a STRICT
  schema and forgets to bump its version, the fingerprint catches it (`FINGERPRINT_MISMATCH`).
* **BACKWARD**: newer readers decode older payloads. Fields the writer predates get defaults. Newer payloads are
  rejected (`NEWER_MINOR`).
* **FULL**: BACKWARD, and older readers also decode newer payloads by skipping fields they don't know.
* **Snapshot builds** always write the fingerprint. A snapshot payload whose structure differs from the reader's is
  rejected whatever its version numbers say (`SNAPSHOT_DRIFT`), because a dev build's version numbers don't move
  while its schemas do. The header also flags the payload as coming from a dev build, and the CLI shows it.

Failures throw `IncompatiblePayloadException` with a `Reason`, the reader's `SchemaInfo` and the payload's `Header`.
Malformed input of any kind throws `IntrabufException`. The fuzz tests check that nothing else escapes.

### Your own header

An application that already frames its messages can put intrabuf's facts in its own header instead of adding the
built-in one in front of it. Declare the header as a record. Components marked `@Role` carry the facts, each role
exactly once. The other components are the application's, encoded like any other field:

```java
@IntrabufHeader(name = "app.Envelope")
public record Envelope(
        short magic,
        @Role(WIRE_VERSION) byte wire,
        @Role(SCHEMA_ID) int typeId,                       // pair with @IntrabufMessage(id = 7) to route by your ids
        @Role(MAJOR) short major,
        @Role(MINOR) short minor,
        @Role(COMPAT) Compat compat,
        @Role(SNAPSHOT) boolean snapshot,                  // a bit, packed with the other booleans
        @Role(FINGERPRINT) @Field(fixed = true) Integer fingerprint,  // null when not needed: no bytes, one bit
        boolean request,
        @Field(fixed = true) long correlationId) {
}

// write
EnvelopeIntrabuf.CODEC.write(w, EnvelopeIntrabuf.create(PingIntrabuf.SCHEMA, magic, request, correlationId));
PingIntrabuf.CODEC.encodeBody(ping, w);

// read, either format: route on the facts, then decode; the compatibility checks run exactly as with the built-in header
HeaderCodec.Frame<Envelope> f = EnvelopeIntrabuf.CODEC.readFrame(r);
Object msg = registry.get(f.facts().schemaId()).decodeBody(f, r);
```

`create` fills the role components from a message's schema and takes the application's components as arguments.
It refuses a version that doesn't fit a `byte` or `short` field. The processor rejects a header that is missing a
role, carries one twice, or gives one the wrong type. The header layout goes in the manifest, so
`intrabuf decode --header app.Envelope` reads whole frames.

`@IntrabufMessage(id = …)` sets a schema's id explicitly. Without it, the id is a hash of the name. The processor
checks that no two schemas share an id and that every version of a schema keeps its id, within one compilation;
`intrabuf schemas` warns about collisions between loaded manifests.

### Evolving a schema

Fields are positional, which is why payloads are small: no tags, no field names on the wire. So:

* **Add** a field at the end with `@Field(since = n)` and bump `minor` to `n` (BACKWARD/FULL). The processor rejects
  a newer field placed before an older one.
* A non-null field added later needs a default for older payloads. Collections default to empty and `String`/`byte[]`
  to empty. Any other type must be nullable or `Optional`.
* **Removing, reordering, renaming or retyping** a field, or changing the compat mode, is a **major** bump.
* **Enums** are ordinals: only append constants.
* Changing a nested message's major is breaking for every message that contains it.
* A message may only contain messages at least as tolerant as itself: STRICT may contain anything, BACKWARD may
  contain BACKWARD or FULL, and FULL may contain only FULL. This is checked at compile time.
* To rename a record without changing its schema, pin `@IntrabufMessage(name = "...")`. The id is a hash of that name.

## Fast: what gets generated

For `com.acme.Customer` the processor writes `com.acme.CustomerIntrabuf`: a singleton `CODEC`, `toBytes`/`fromBytes`,
`toText`/`fromText`, and compile-time constants (`ID`, `MAJOR`, `MINOR`, `FINGERPRINT`, `SNAPSHOT`, `BUILD_VERSION`). Its body methods are
one local per field, a bitset built from constant masks (null flags and booleans packed together), then one
`IbWriter`/`IbReader` call per value. A decode whose header matches the codec's byte for byte skips the
compatibility checks entirely.

The annotations have CLASS retention. They stay in the class file, so a record compiled in another module can be
nested and its schema read from the jar, but they are never loaded at runtime.

Rough timings on a warm JVM, for the `intrabuf-example` types:

| message         | size  | encode | decode |
|-----------------|-------|--------|--------|
| `SensorReading` | 63 B  | ~14 ns | ~12 ns |
| `Customer`      | 116 B | ~64 ns | ~86 ns |

These come from a quick loop, not JMH, so read them as orders of magnitude.

### Supported types

Primitives and their boxes, `String`, `byte[]`, `UUID`, `Instant`, enums, other `@IntrabufMessage` records
(recursion allowed), `List`/`Set`/`Map` of any of those (nested freely), and `Optional<T>` as a field's outermost
type. Reference fields are nullable unless they are `@Field(nonNull = true)` or carry any type annotation named
`NonNull`/`Nonnull`/`NotNull`. Collection elements, map keys and map values must be non-null. `int`/`long` are zigzag
varints unless `@Field(fixed = true)`.

## Inspectable: manifests and the CLI

Every build writes `META-INF/intrabuf/<module>.json`. It lists every schema the build can write, including ones
nested from other modules, along with each schema's id, version, mode, fingerprint, the build that generated it, and
its fields. The output is deterministic, so you can commit or diff it.

```
$ intrabuf decode -m app.jar customer.ib
# example.Customer v1.2 BACKWARD from a SNAPSHOT build, fingerprint 0xe95fde79; 116 bytes (header 12)
# decoded with sibarum.intrabuf.example.Customer v1.2 from build 0.1.0-SNAPSHOT (fingerprint matches)
example.Customer @minor 2 {
  id: 42
  name: "Ada Lovelace"
  email: "ada@example.com"
  active: true
  favoriteColor: GREEN
  home: example.Address @minor 1 {
    street: "12 St James's Square"
    city: "London"
    postcode: "SW1Y 4JH"
    unit: null
  }
  tags: ["vip", "early-adopter"]
  nickname: "Ada"
  loyaltyPoints: {"2025" => 1200}
}
```

* `decode -m <manifest>... [--json] [--hex|--base64] <payload|->`: header plus contents, as a tree or as JSON.
* `text -m <manifest>... <payload|->`: the payload in the text format, a starting point for writing one by hand.
* `header <payload>`: the header alone. No manifest needed for the built-in header.
* `--header <name>` (on `decode` and `header`): the payload is a frame with an application header.
* `schemas -m ...` and `schema -m ... <name>`: what the manifests describe, header formats included.

`-m` takes a manifest `.json`, a jar, or a directory (searched for `META-INF/intrabuf/*.json`). It is repeatable, so
load several releases' manifests and the CLI will pick the version each payload was written with.
`INTRABUF_MANIFESTS` sets a default. The decoder behind it, `sibarum.intrabuf.dynamic.DynamicDecoder`, is in the
runtime for tools that want it, and the tests hold it to agreeing with the generated codecs.

The byte-level format is specified in [docs/wire-format.md](docs/wire-format.md).

## Readable: the text format

Every codec writes and reads a second format, meant for people. `decode`/`fromBytes` tells the two apart by their
first bytes, so a peer can send any one message as text (to debug a conversation, or because someone wrote it by
hand) and the receiver needs no switch, flag or second code path:

```java
String text = CustomerIntrabuf.toText(customer);          // or CODEC.encode(customer, Format.TEXT) for bytes
Customer c  = CustomerIntrabuf.fromBytes(textOrBinary);   // either format
```

```
#intrabuf 1 example.Customer 1.2 BACKWARD id=0x313ffe3a fp=0xe95fde79 snapshot
id,name,email,active,favoriteColor,home,tags,nickname,loyaltyPoints
42,Ada Lovelace,ada@example.com,true,GREEN,&1,[vip,early-adopter],Ada,{2025=1200}

#message example.Address 1.1
&,street,city,postcode,unit
&1,12 St James's Square,London,SW1Y 4JH,
```

The first line carries the binary header's facts, and the same compatibility checks run on them. Then comes the
root message as a CSV table: field names, then one row. Each nested message type gets a table after it, whose
numbered rows (`&1`) cells refer to. `byte[]` values are base64 in a trailing `#blobs` section, referred to as `$1`.
Empty cells are null. Strings that span lines go in backticks, and ones with commas or quotes in double quotes.
Column names are checked against the schema, and every row and blob must be referred to exactly once, so a
hand-edited payload fails with the line and the column at fault rather than decoding into the wrong field.
Application headers work too: their own components become a `#header` table.

The format is specified in [docs/text-format.md](docs/text-format.md).

## Bundles: many messages in one log

A bundle holds many top-level messages of the kinds its schema lists. It's declared as a record of lists, and evolves
like a message: members are added at the end with `since`.

```java
@IntrabufBundle(name = "app.Trace", minor = 1, compat = Compat.BACKWARD)
public record Trace(List<Frame> frames, List<KeyPress> keys, @Field(since = 1) List<Mark> marks) {
}

TraceIntrabuf.Writer log = TraceIntrabuf.writer(out);      // writes the facts line and declarations
log.frames(frame);                                         // then one line per row, as it happens
log.keys(key);

TraceIntrabuf.CODEC.read(text, new TraceIntrabuf.Visitor() {
    @Override public void frames(Frame f) { ... }          // each row as it is read
});
Trace whole = TraceIntrabuf.fromText(text);
```

For now a bundle is written as a text log: the facts line once, each member declared once, then rows in any order,
each standing alone, with no root and no references:

```
#intrabuf 1 app.Trace 1.1 BACKWARD id=0x38319260
#member frames app.Frame 1.0
frames,at,workMicros,idle
#member keys app.KeyPress 1.1
keys,at,key,text
frames,2026-10-11T10:00:00Z,400,true
keys,2026-10-11T10:00:00.003Z,A,a
```

Readers check compatibility from the facts line and each member's version from its declaration. Because rows hold
no references, members' messages can't contain other messages or `byte[]` values yet. The processor rejects a
bundle whose members do. Bundles are specified, along with the binary design still to come, in
[docs/bundle-format.md](docs/bundle-format.md).

## Limits and non-goals (for now)

* Records only. No polymorphism (sealed interfaces) yet.
* Fields can't be removed within a major (positional encoding). Bump the major instead.
* Each binary payload holds one root message. To stream several, slice them yourself with `decode(bytes, off, len)`
  or length-prefix them, or write a bundle as a text log.
* Bundles are text only, their members' messages are flat, and they aren't in the manifest, so the CLI can't read
  them yet.
* Manifests are per compilation. IDE incremental builds that recompile only some files may write a partial
  manifest. Maven rebuilds the whole module, so its manifests are complete.
