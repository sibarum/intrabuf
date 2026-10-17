# Intrabuf promotions and endpoints

> **Proposal.** Nothing in this document exists yet. What exists is the per-payload compatibility decision in
> [wire-format.md](wire-format.md#compatibility-decision): STRICT, BACKWARD and FULL within one major, and a major
> mismatch that is never decodable. Names, annotations and error codes here are placeholders to argue about.

Today a reader knows one version of each schema, and the compat mode says which other versions it puts up with.
Older minors are read by giving new fields their defaults. Newer minors, in FULL, are read by ignoring what comes
after the fields the reader knows. Another major is never read.

This proposal makes the upgrade explicit and the whole question decidable before a byte is sent:

* **A version is a type.** `example.Customer 1.5` and `example.Customer 2.0` are different things, the way 2/4 and
  1/2 are, until you say how to get from one to the other.
* **A promotion is that saying.** It is a declared, one-way map from a version to the next one. Promotions chain, so
  a reader at 3.1 reads a 1.2 payload by promoting 1.2 → 1.5 → 2.0 → … → 3.1. No promotion, no path, not
  compatible. Like Julia's `promote_rule`, it is a rule the program states, never one the runtime guesses.
* **A demotion is never declared.** It is implied by layout: a newer version's extra columns and parts come after
  everything an older reader knows, behind the column boundary or a [version boundary](bundle-format.md#version-boundaries),
  and the older reader stops there. Where layout can't imply it, there is no demotion.
* **Compatibility is a function of two descriptors.** Each build knows, at compile time, which versions of each
  schema it reads and which one it writes. Two peers exchange those facts and both run the same function on them,
  so they reach the same verdict: the sender knows not to send, the receiver knows to reject, and both can say what
  is missing.
* **The same function runs at compile time, over history.** Released manifests are committed. A build checks itself
  against every release it promises to work with, and the compiler names the missing promotion.

A promotion is not an equivalence. Nothing says 1.5 *is* 2.0; there is a map from one to the other, it goes one way,
and it is code you wrote. That keeps the [one promise](../README.md#design-intra) intact: everything past "the same
build reads what it wrote" is still something you declared.

## Two kinds of step

Every step from one version to the next is one of two kinds, and the version number says which.

| step  | what may change                              | promotion                                   | demotion                     |
|-------|----------------------------------------------|---------------------------------------------|------------------------------|
| minor | fields added at the end, members added       | derived from defaults, or declared per field | implied by layout, FULL only |
| major | anything: remove, reorder, retype, re-mode   | declared, always                            | none                         |

A minor step keeps the older layout as a prefix of the newer one. That is what lets an older reader stop at its
boundary, and what lets a newer reader fill in the rest. A major step keeps nothing, so there is nothing for an older
reader to stop at, and a newer reader has to be told how to get there.

### Minor promotions

A field added with `@Field(since = n)` is promoted the way it is today: collections become empty, `String` and
`byte[]` empty, nullable and `Optional` fields absent. That default *is* the declared promotion for the step; `since`
is the declaration.

A field whose value should be computed from the older fields declares how:

```java
@IntrabufMessage(name = "example.Customer", major = 2, minor = 3, compat = Compat.BACKWARD)
public record Customer(
        long id,
        String first,
        String last,
        @Field(since = 3, nonNull = true) String display) {

    @Promote(field = "display")
    static String display(Customer older) {        // older: decoded at minor 2, display not yet filled
        return older.first() + " " + older.last();
    }
}
```

This also lifts a limit: a non-null field of a type with no default (a message, an `Instant`) may be added in a
minor, as long as it has a promotion. The processor rejects such a field without one, as it rejects it today.

Minor promotions run in minor order. Each sees the fields of every earlier minor filled in, and the fields of its own
minor and later ones at their defaults.

### Major promotions

A major bump keeps the old major's record, frozen, under a new Java name, and the new record declares how to get from
it:

```java
// the last release of major 1, kept as it shipped. Read-only: it has a decoder and no encoder.
@IntrabufMessage(name = "example.Customer", major = 1, minor = 5, compat = Compat.BACKWARD)
record CustomerV1(long id, String name, @Field(since = 5) String email) { }

@IntrabufMessage(name = "example.Customer", major = 2, minor = 3, compat = Compat.BACKWARD, oldest = "1.0")
public record Customer(long id, String first, String last, @Field(since = 3, nonNull = true) String display) {

    @Promote
    static Customer from(CustomerV1 old) {
        int space = old.name().indexOf(' ');
        return CustomerIntrabuf.at2_0(old.id(), old.name().substring(0, space), old.name().substring(space + 1));
    }
}
```

The rules:

* **A past major is a record with the same schema name and a lower major.** It stands for the last released minor of
  that major. Its own minor promotions cover the minors before it, so one frozen record per major is enough.
* **Promotions are adjacent.** A `@Promote` method on major *n*'s record takes major *n − 1*'s record and nothing else.
  There are no skip edges, so the chain from any version is unique and the processor composes it.
* **A major promotion lands at minor 0.** `CustomerIntrabuf.at2_0(...)` is a generated factory that takes exactly the
  fields of 2.0 and defaults the rest. The promoted value is then treated as a decoded 2.0 payload, and the minor
  promotions 2.0 → 2.3 run on it. Adding minor 2.4 never edits the promotion from 1.x.
* **Past versions are read-only.** A build writes exactly one version of each schema, its current one. That is an
  invariant the endpoint handshake relies on: a peer is never asked to choose which version to send.
* **`oldest` is declared.** It is the lowest version the build reads. The processor proves a chain from it to the
  current version exists and fails the build otherwise, naming the link:

  ```
  error: example.Customer declares oldest = "1.0" but nothing promotes major 1 to major 2.
         add to Customer:  @Promote static Customer from(CustomerV1 old)
  ```

  Without `oldest`, a schema reads only its own major, as today. STRICT schemas have no promotions: their oldest is
  their current version.
* **Nested messages promote through their own chain.** A container's promotion that holds a past nested record calls
  the nested schema's generated `AddressIntrabuf.promote(AddressV1)`, which runs Address's chain to its current
  version. A nested major bump is still a major bump for every container, as today.

When major 3 arrives, `Customer` is renamed `CustomerV2`, keeps its `@Promote from(CustomerV1)`, and the new
`Customer` declares `@Promote from(CustomerV2)`. An IDE rename carries everything.

## History: what the build knows about the past

The manifest a release build writes is committed, unchanged, to a history directory:

```
src/main/intrabuf/history/
    0.9.0.json
    1.0.0.json
    1.4.0.json
```

`intrabuf freeze` copies the module's manifest there at release time. The processor reads it with
`-Aintrabuf.history=<dir>`, and checks:

* **A released version never changes.** If the current record, or a past one, claims a version that is in history,
  its fingerprint must equal the released one (`RELEASED_VERSION_CHANGED`). This catches an unbumped edit to a
  BACKWARD or FULL schema, which the wire can't: release headers in those modes carry no fingerprint.
* **A past record is the one that shipped.** `CustomerV1` must match the last released 1.x exactly. You can't invent a
  past, or edit one.
* **The chain reaches `oldest`.** Every version from `oldest` to now that history records has a path to the current
  version.
* **Endpoints agree with every release in their window** (below).

Snapshot builds are never in history. Between releases, history is the only past there is, which is what a
development build's moving schemas need.

## Reading a payload

A codec's `fromBytes` reads every version from `oldest` to its current one, and returns the current record:

1. The header's facts select a decoder: the current codec, or a past record's.
2. Decode at the payload's minor. Fields the writer predates take their defaults.
3. Run the chain: minor promotions to the end of the payload's major, then each major promotion and the next major's
   minor promotions, up to the current version.

A payload at the current version takes today's path, byte for byte. Only old payloads pay for a promotion.

The [compatibility decision](wire-format.md#compatibility-decision) changes in one step, a major mismatch, which
splits:

| payload major            | today            | proposed                                                         |
|--------------------------|------------------|------------------------------------------------------------------|
| below the reader's, in range | `MAJOR_MISMATCH` | decode, then promote                                            |
| below `oldest`           | `MAJOR_MISMATCH` | `BELOW_OLDEST`: names `oldest` and the promotion that would reach it |
| above the reader's       | `MAJOR_MISMATCH` | `NEWER_MAJOR`: majors never demote                               |

A minor below `oldest`'s minor is `BELOW_OLDEST` too. The text format, bundles and application headers carry the same
facts and take the same decision.

## Endpoints and the handshake

The per-payload check is a backstop. What should fail is the connection, before anything is sent, on both sides, for
the same reason. That needs a unit both peers agree on, and the unit is an **endpoint**: a declared set of message
types that go each way.

```java
@IntrabufEndpoint(name = "app.Orders", oldestPeer = "1.4.0",
        requests  = {PlaceOrder.class, CancelOrder.class},
        responses = {OrderPlaced.class, Rejected.class},
        optional  = {CancelOrder.class})
interface Orders { }
```

A client sends `requests` and receives `responses`; a server the reverse. The processor generates
`OrdersIntrabuf.client()` and `.server()`, each a **descriptor**: for every schema the role touches, its id, compat
mode and fingerprint, the version it writes, and the ranges it reads (`oldest` to current). It also writes the
descriptor into the manifest, so history has every release's.

### The exchange

Before anything else, each peer sends its descriptor as an `intrabuf.Hello`. Hello is a schema intrabuf maintains
itself: FULL, and only ever grown with version boundaries, so every intrabuf from now on reads every other one's.
How it travels is the application's business, like any other payload.

Each peer then computes `Agreement.of(mine, theirs)`. It is a pure function of the two descriptors, and the verdict
for "A sends S to B" is the same cell whether A or B computes it. That is the symmetry: there is no message in which
one side tells the other the result, so they can't disagree about it.

For each schema S that A writes at version *w*, and B reads from *oldest* to *newest*:

| case                                              | verdict                                                     |
|---------------------------------------------------|-------------------------------------------------------------|
| *w* = *newest*, same fingerprint                  | identical                                                   |
| *oldest* ≤ *w* < *newest*                         | B promotes                                                  |
| *w* > *newest*, same major, S is FULL             | B demotes by layout                                         |
| *w* > *newest*, same major, S is STRICT or BACKWARD | mismatch: B must reach *w*                                  |
| *w*'s major > *newest*'s                          | mismatch: B must reach *w*'s major; majors don't demote      |
| *w* < *oldest*                                    | mismatch: A must reach *oldest*, or B must promote from *w*  |
| B doesn't receive S                               | mismatch, unless S is optional                              |
| either peer is a snapshot and fingerprints differ | mismatch: `SNAPSHOT_DRIFT`                                  |

A mismatch fails the handshake on both sides with the full table, every schema and both directions, not the first
problem found:

```
app.Orders: this client (2.0.0) and the server (1.4.0) can't interoperate
  sends    example.PlaceOrder 2.1 → server reads 1.0–1.5: no 2.x there; deploy the server ≥ 2.0.0 first
  receives example.OrderPlaced 1.2 ← server writes 1.2: ok, promoted 1.2 → 3.0 here
  sends    example.CancelOrder 1.0 → server doesn't receive it: optional, not sent
```

An optional schema the peer can't take doesn't fail the handshake. Instead the sender knows: `agreement.accepts(S)`
is false, and encoding S for that peer throws `NOT_AGREED` before any bytes exist. Identical descriptors, the usual
case once a deploy has finished, are recognised by a descriptor fingerprint and skip the table.

### The same check at compile time

`oldestPeer` is the oldest release this build promises to work with, in either role. The processor loads the
descriptor of every release in history from `oldestPeer` on, and runs `Agreement.of` against each, both ways round.
Any mismatch is a compile error with the same table. So:

* **What this build supports is known at compile time.** It is its descriptors, and they are checked against the
  past it promised.
* **What it demands of newer builds is published.** A future build loads this release's descriptor from history and
  must agree with it for as long as this release is inside its window. A newer build that drops a promotion an
  in-window peer needs doesn't compile. Dropping that peer is a declaration in the newer build: raising
  `oldestPeer`, or `oldest`.
* **The runtime handshake can't find a problem the build didn't**, between releases in the window. It exists for what
  the build can't see: snapshot peers, releases outside the window, and peers that skipped a deploy.

## Consequences

* **Receivers deploy first across a major.** No demotion crosses a major, so a sender at major 2 can talk only to
  receivers that read major 2. The handshake says which side has to move.
* **A major bump costs a frozen record and one method.** Nothing else in the codebase changes, and the processor
  won't let the method be forgotten.
* **Old payloads cost what their promotions cost.** A decode into the past record, plus whatever the promotions
  allocate. The current version's path is unchanged.
* **The compat mode is still the declaration of what is tolerated.** STRICT has no promotions and no demotions.
  BACKWARD has promotions. FULL has both. Promotions don't make a schema more tolerant than its mode says.

## Open questions

* **Nested majors in place.** Could a BACKWARD container hold a nested message at an older major, if nested framing
  carried the major as well as the minor, and promote it without bumping its own major?
* **Skip edges.** A long chain allocates at each step. Should a schema be allowed to declare a direct 1.x → 3.0
  promotion, checked against the composed one in tests, or is that a quotient sneaking back in?
* **Opting in to derived minor promotions.** Strict equality suggests a default value is itself an identification.
  Should BACKWARD require each `since` field's promotion to be named, even when it is the default?
* **Endpoint versions.** Adding a schema to an endpoint is a change older peers don't know about. Is `optional`
  enough, or does an endpoint need its own major and minor?
* **History across modules.** An endpoint declared in a shared module, implemented in two others: whose history
  holds the descriptors, and how does `oldestPeer` name a release of the other side?
