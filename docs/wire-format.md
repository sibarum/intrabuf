# Intrabuf wire format, version 1

A message payload is a **header** followed by the **body** of one root message. (Bundles, which hold many messages,
are written as text logs for now; their binary body is a proposal in `bundle-format.md`.) All multi-byte fixed-width values are
little-endian. "varint" is unsigned LEB128; "zigzag" is a varint of `(n << 1) ^ (n >> 63)` (or `>> 31`).

## Header

| bytes   | field        | notes                                                                    |
|---------|--------------|--------------------------------------------------------------------------|
| 1       | magic+wire   | `0xB0 \| wireVersion`. High nibble `0xB` identifies intrabuf; low is `1` |
| 1       | flags        | bit 0 snapshot, bit 1 fingerprint present, bits 2–3 compat, 4–7 zero      |
| 4       | schema id    | 32-bit hash of the schema name (FNV-1a 64, folded)                       |
| varint  | major        |                                                                          |
| varint  | minor        |                                                                          |
| 4       | fingerprint  | only if flag bit 1: STRICT schemas, and every payload from a snapshot    |

Compat: `0` STRICT, `1` BACKWARD, `2` FULL. A release BACKWARD/FULL header is 8 bytes; STRICT or snapshot is 12.

Reserved flag bits set, or a wire version other than 1, mean a newer intrabuf wrote the payload: readers reject it
rather than guess.

### Application headers

An `@IntrabufHeader` record replaces the built-in header. It is encoded as a STRICT body (bitset, then fields) with
no header of its own. Its components marked `@Role` carry the same facts: wire version, schema id, major, minor,
compat (an enum ordinal), snapshot (a bit), and fingerprint (absent when null). There is no magic. The application's
own framing identifies its frames. Readers apply the same compatibility decision below to the facts, and also reject
a negative major or minor, which signed header fields can express.

## Body

```
[FULL only: varint bitset length in bytes]
bitset
field values, in declaration order
```

### The bitset

Bits are assigned in field order, starting at bit 0 of byte 0:

* a nullable field, or an `optional<T>`, gets a **presence** bit (1 = present);
* a `bool` field (including nullable `Boolean` and `optional<bool>`) then gets a **value** bit, and writes nothing
  to the field values.

The bitset is `ceil(bits / 8)` bytes, where `bits` counts the fields that exist at the writer's minor. Because fields
are ordered by `since`, an older minor's bitset is a prefix of a newer one's, and bits past the end of a short bitset
read as zero (absent / false).

STRICT and BACKWARD readers know the bitset length from the minor. FULL writers prefix it, so readers of older
minors can skip bits they don't know.

### Field values

A field whose presence bit is 0, or that has no body (booleans), writes nothing. Otherwise its value:

| type              | encoding                                                                 |
|-------------------|--------------------------------------------------------------------------|
| `bool`            | (in collections only) one byte, 0 or 1                                    |
| `int8`            | one byte                                                                 |
| `int16`, `int32`, `int64` | zigzag varint                                                    |
| `fixed32`, `fixed64` | 4 / 8 bytes (`@Field(fixed = true)` on int / long)                    |
| `float32`, `float64` | 4 / 8 bytes, IEEE 754 raw bits                                        |
| `char`            | varint of the UTF-16 code unit                                           |
| `string`          | varint byte length, UTF-8                                                 |
| `bytes`           | varint length, raw                                                        |
| `uuid`            | 8 bytes most significant, 8 bytes least significant                       |
| `instant`         | zigzag epoch seconds, varint nanos (0–999,999,999)                        |
| `enum<E>`         | varint ordinal                                                            |
| `list<T>`, `set<T>` | varint count, then each element                                         |
| `map<K,V>`        | varint count, then key, value, key, value, …                               |
| `optional<T>`     | the value, if the presence bit is set                                     |
| `message<M>`      | a nested message, framed by **M's** compat mode (below)                    |

Collection elements, map keys and map values are never null.

### Nested messages

| M's mode | framing                                         |
|----------|-------------------------------------------------|
| STRICT   | body                                            |
| BACKWARD | varint minor, body                              |
| FULL     | varint minor, varint body length, body          |

A message may contain only messages at least as tolerant as itself: STRICT may contain anything (its fingerprint
covers the whole closure), BACKWARD may contain BACKWARD or FULL, FULL may contain only FULL. The processor enforces
this at compile time.

## Fingerprints

The fingerprint hashes a canonical text of the root message and of every message and enum reachable from it:
names, compat modes, and for each field its name, type, nullability and `since`. Version numbers are excluded. It is
32 bits (FNV-1a 64, folded), which is a drift detector, not a security property.

## Compatibility decision

Given the reader's schema and the payload header, in order:

1. Schema id, major and compat mode must match.
2. STRICT: the minor must match.
3. If the payload carries a fingerprint equal to the reader's, decode.
4. If the payload is from a snapshot build, reject (`SNAPSHOT_DRIFT`).
5. STRICT: reject (`FINGERPRINT_MISMATCH`).
6. BACKWARD: reject if the payload's minor is newer than the reader's.
7. Decode at the payload's minor. Fields newer than it take defaults; for FULL, fields newer than the reader are
   skipped (nested: by length; root: they are the trailing bytes).
