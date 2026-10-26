# PortableVFX protocol v1 (legacy core)

The core bytes below remain unchanged. Existing follow/orientation and the separately
negotiated seed/timing/anchor extension are specified in [EXTENDED-PLAY.md](EXTENDED-PLAY.md).
Do not send extension opcodes merely because a peer sent the ordinary v1 hello.

This module has **no Minecraft, Fabric, Paper, networking, or third-party dependencies**.
It requires Java 21. It encodes visual requests only: no damage, hitboxes, movement,
commands, scripts, class names, remote asset URLs, or arbitrary object deserialization.

## Transport and negotiation

- Client → server: `portablevfx:hello`.
- Server → client: `portablevfx:effect`.
- Payloads below are the **entire raw plugin/custom-payload data**. Do not add a
  second length prefix, Minecraft VarInt, NBT wrapper, or `writeUTF` wrapper.
- The underlying Minecraft transport supplies framing and the channel identifier.
- Every integer and floating-point field is big-endian, exactly as written by
  `java.io.DataOutputStream`. Floats and doubles are IEEE 754 binary32/binary64.
- Every accepted payload must be at most 2,048 bytes. Reject oversized payloads
  before allocating a byte array in platform adapters, then validate with this codec.
- A hello is exactly one signed 32-bit integer: `1`. It is not an effect message.
  Unsupported versions, incomplete integers, and trailing bytes are rejected.
- A server should send effects only after receiving a valid hello from that client.
  This is a capability handshake, not authentication or permission to run commands.
  Clear capability state on disconnect; send a new hello on each new connection.
- Hellos are idempotent. A client re-sends every hello (and `catalog_ready`) whenever the
  server (re)advertises `portablevfx:hello` through `minecraft:register`, and periodically
  (every 600 client ticks) as a fallback. A relay that lost its state (plugin disable/enable)
  therefore recovers without a reconnect; duplicates never reset active effects. A hello that
  reaches the server before the player is resolvable is held for up to 200 ticks and replayed.
- No handshake reply, registry transfer, or pack download is defined in v1. Installed
  effect-pack definitions determine animation frames, textures, and rendering modes.

## Effect envelope

All effect messages begin with:

| Order | Field | Wire type | Value |
| --- | --- | --- | --- |
| 1 | version | signed 32-bit integer | `1` |
| 2 | opcode | unsigned 8-bit integer | `1` PLAY, `2` STOP, `3` CLEAR (core); 4–13 negotiated, see below |

Unknown versions/opcodes and any trailing bytes are errors. No suffix is silently
ignored. Additional opcodes require explicit, separate capability negotiation.
Clients drop undecodable packets silently without disconnecting.

### Opcode sizes (complete payload, bytes)

| Opcode | Message | Capability | Size |
| --- | --- | --- | --- |
| 1 | PLAY | base hello | 83–333 |
| 2 | STOP | base hello | 21 |
| 3 | CLEAR | base hello | 5 |
| 4 | follow PLAY | base hello | 99–349 |
| 5 | ORIENT | `orient_hello` | 41 |
| 6 | extended PLAY | `play_ext_hello` | 112–362 world, 128–378 attached |
| 7 | POSE | `authority_hello` | 94–219 |
| 8 | FINISH (clearLocal) | `authority_hello` | 34–159 |
| 9 | IMPACT | `authority_hello` | 67 + nested PLAY length (see below) |
| 10 | width PLAY | `width_hello` | 125–370 world, 141–386 attached |
| 11 | FINISH intent | `stop_intent_hello` | 35–160 |
| 12 | parameterized PLAY | `stop_intent_hello` | 141–386 world, 157–402 attached |
| 13 | LINK_POSE | `stop_intent_hello` | 102–227 |

Opcode 10/12 minimums assume the shortest `claude:` effect ID (8 bytes), which both
require. IMPACT nests one complete PLAY packet (opcode 1, 4, 6, 10 or 12) behind a
16-bit length. The decoder's pre-decode window is **83–402 bytes** (402 = the largest
opcode 12 encoding). Because an IMPACT phase must use an unoffset WORLD anchor, the
largest *valid* nested phase is 386 bytes (opcode 10/12 world), so a valid IMPACT is
at most 453 bytes. The relay sends opcode 13 only to `stop_intent_hello` clients;
others receive the same pose as opcode 7 without the link length.

### PLAY (`1`)

Fields immediately after the envelope, in this exact order:

| Order | Field | Wire type | Constraint |
| --- | --- | --- | --- |
| 1 | instanceId high bits | signed 64-bit integer | UUID most-significant bits |
| 2 | instanceId low bits | signed 64-bit integer | UUID least-significant bits |
| 3 | effectId | identifier | A locally installed effect ID |
| 4 | dimensionId | identifier | For example `minecraft:overworld` |
| 5 | x | 64-bit float | Finite, absolute value ≤ 30,000,000 |
| 6 | y | 64-bit float | Finite, absolute value ≤ 30,000,000 |
| 7 | z | 64-bit float | Finite, absolute value ≤ 30,000,000 |
| 8 | yaw | 32-bit float | Finite degrees |
| 9 | pitch | 32-bit float | Finite degrees |
| 10 | roll | 32-bit float | Finite degrees |
| 11 | scale | 32-bit float | Finite, greater than 0 and ≤ 64 |
| 12 | rgb | signed 32-bit integer | `0x000000` through `0xFFFFFF`, no alpha bits |
| 13 | opacity | 32-bit float | Finite, 0 through 1 inclusive |
| 14 | durationTicks | signed 32-bit integer | 1 through 12,000 inclusive (`MAX_DURATION_TICKS`) |

Duration uses 20 Hz game ticks. The codec accepts 1–12,000 ticks (10 minutes) for every
PLAY opcode. The original v1 limit was 1,200 ticks (60 seconds) and older decoders
reject longer values, so durations above 1,200 are **only sent to, and only rendered by,
clients that announced `portablevfx:stop_intent_hello`**; legacy recipients are skipped
rather than truncated. There is **no zero, negative,
infinite, or default-duration sentinel**. Local callers resolve any effect-pack
default before constructing a message. Coordinate bounds are protocol safety
bounds for all axes, not a declaration of a dimension's playable height. Platform
adapters may use stricter world-specific bounds.

Angles may be any finite float. Clients can normalize degrees before rendering.
There is no attached-entity field or gameplay meaning. Receiving PLAY for an
existing UUID should replace that visual instance. Unknown effects or unavailable
dimensions should be safely ignored by the client; the protocol never fetches assets.
Clients must independently cap active instances, per-tick work, effect-pack size,
and render distance. A valid packet does not imply its content is cheap to render.

The minimum PLAY payload is 83 bytes (both identifiers have three bytes); the
maximum v1 PLAY payload is 333 bytes (both identifiers have 128 bytes).

### STOP (`2`)

After the envelope: UUID most-significant bits (signed 64-bit integer), then UUID
least-significant bits (signed 64-bit integer). Exactly 21 bytes total. Stopping an
unknown UUID is a client-side no-op.

### CLEAR (`3`)

No fields after the envelope. Exactly five bytes total. Clears all PortableVFX
instances on each receiving client. There is no dimension or effect-ID filter.

## Identifier encoding

Each identifier consists of:

1. An unsigned 16-bit byte length, using `writeShort` and `readUnsignedShort`.
2. Exactly that many US-ASCII bytes, with no terminator.

Lengths must be 3–128 bytes inclusive. The complete identifier matches
`[a-z0-9_.-]+:[a-z0-9/._-]+`. It contains one colon. Namespace and path are nonempty,
lowercase, and ASCII-only. Namespace `.` and `..` are rejected. Slash-delimited
path components cannot be empty, `.` or `..` (so leading/trailing/repeated slashes
and relative traversal are invalid). This deliberately safe subset is slightly
stricter than Minecraft's raw identifier parser.

This is **not modified UTF-8** and must not be read/written with `readUTF`/`writeUTF`.
Because valid characters are ASCII, normal UTF-8 bytes for a valid ID are identical.
The decoder rejects high-bit bytes, nulls, whitespace, uppercase characters, and
invalid lengths before using an identifier.

## Public Java API

Package: `dev.portablevfx.protocol`.

```java
public sealed interface EffectMessage permits PlayEffect, StopEffect, ClearEffects {}

public record PlayEffect(
    UUID instanceId, String effectId, String dimensionId,
    double x, double y, double z,
    float yaw, float pitch, float roll,
    float scale, int rgb, float opacity, int durationTicks
) implements EffectMessage {}

public record StopEffect(UUID instanceId) implements EffectMessage {}
public record ClearEffects() implements EffectMessage {}

VfxProtocol.encode(EffectMessage message); // byte[]; invalid/null input -> IllegalArgumentException
VfxProtocol.decode(byte[] payload);        // EffectMessage; invalid payload -> ProtocolException
VfxProtocol.encodeHello();                 // byte[4]
VfxProtocol.decodeHello(byte[] payload);   // int VERSION; invalid payload -> ProtocolException
VfxProtocol.validateId(String id);         // returns id; invalid -> IllegalArgumentException
```

Record constructors validate all fields, including null UUIDs/IDs. Records are
immutable. No arbitrary classes can implement the sealed message interface.
`ProtocolException` extends `IOException`. All decode failures, including invalid
field values, are reported using that checked type. Decoding takes a bounded copy
of the caller's input, never changes the caller's bytes, and allocates at most
128 bytes for each identifier. The codec is stateless and thread-safe; scheduling
render work on the Minecraft client thread is the adapter's responsibility.

Public constants: `VERSION`, `HELLO_CHANNEL`, `EFFECT_CHANNEL`, `MAX_PACKET_BYTES`,
`MAX_ID_BYTES`, `MAX_POSITION`, `MAX_SCALE`, and `MAX_DURATION_TICKS`.

## Verification

From the project root, with a Java 21 JDK on PATH (or `JAVA_HOME`):

```sh
./common/run-tests.sh
# Or through the root Gradle build:
./gradlew :common:protocolTest
```

The standalone script has no downloads or dependencies. It compiles with all
compiler warnings treated as errors and runs the `ProtocolTests` Java main. The
Gradle `:common:check` task also includes `protocolTest`.

Tests cover golden big-endian fixtures, field order, complete-record round trips,
all bounds, every truncated prefix, all unsupported byte opcodes, unknown versions,
trailing data, invalid identifier lengths/characters, NaN/infinity, mutated payloads,
random valid records, and deterministic random-byte fuzzing. Every accepted fuzz
payload must re-encode to its exact original byte sequence.


## Per-viewer budgets (client admission vs relay)

Clients admit packets before allocation in separate token buckets; the Paper relay
keeps every class strictly inside them, so no class can starve another:

| Class | Opcodes | Client bucket | Relay per viewer |
| --- | --- | --- | --- |
| PLAY | 1, 4, 6, 9, 10, 12 | 64 burst, 200/s | 48 burst, +8/tick (shared packet cap 32/tick) |
| STREAM | 5, 7, 13 | 128 burst, 640/s, then 64-burst overflow coalesced per instance | 16/tick, plus initial cast poses sent with their PLAY |
| CONTROL | 2, 3, 8, 11 | 256 burst, 256/s (exhaustion requests a local clear) | 10/tick, queued, ahead of new PLAY |

Poses are never queued or replayed by the relay. When a viewer's stream budget is
full, the skipped cast is served first on the next tick (stalest first); the client's
overflow keeps only the newest pose per instance and evicts the least recently
refreshed one.

## Additive compatibility extensions

The declarations above describe the original v1 core. Current `PlayEffect` also
preserves follow/seed/timing/anchor fields plus `double effectWidth` (0 = authored
reference). See [EXTENDED-PLAY.md](EXTENDED-PLAY.md) for exact extended opcode 6 and
separately negotiated atomic width PLAY opcode 10 layouts. Server-authoritative
POSE/FINISH/IMPACT are opcodes 7/8/9; see the Paper test guide for lifecycle rules.
Original opcode 1–9 wire fields are unchanged by width support.

## Explicit stop intent (catalog release)

Capability: `portablevfx:stop_intent_hello`, exact ordinary 4-byte version payload, accepted only after the base hello. Production catalog casts require this capability. Opcode 11 contains UUID, dimension ID, sequence (same fields as FINISH opcode 8), then one strict boolean byte `clearLocal`. A normal stop uses false: local and world particles finish naturally or use authored `onStop` tails. Impact/salvo cancellation uses true to clear local particles while world particles drain. Opcode 8 remains byte-identical and means true for older clients. Both messages share the authoritative lifecycle sequence and priority control budget. Old clients are only supported for legacy debug/API casts, not silently admitted to the production catalog.

Catalog parameters use opcode 12: the extended PLAY body, followed by effectWidth, scaleInput, linkLength as three doubles. scaleInput=0 means authored reference; linkLength=-1 means absent, and zero is a valid coincident link. Negative fractional sentinels, nonfinite values and parameters outside the bounded256m envelope are rejected. Explicit link poses use opcode 13: the opcode 7 pose body plus one nonnegative finite linkLength. Opcode 13 is sent only to stop-intent clients; other authoritative recipients get the same sample as opcode 7. Catalog start methods filter all recipients by the new capability, not only the caster. Duration bounds are now 12000 ticks (`MAX_DURATION_TICKS`) for stop-intent clients; old receivers (1200-tick decoders) are excluded from longer plays. Existing opcodes retain their original bodies.

## GPU-ready catalog inventory

`portablevfx:catalog_ready` is a distinct C2S channel, bounded to32760bytes and1024 canonical sorted unique Claude IDs. Body: version(int32), count(uint16), then for each ID its ASCII byte length(uint8) and bytes. Empty inventory withdraws readiness. It is accepted only after base and stop-intent capability hellos. The server compares the inventory with all enabled visual-plan phase IDs and filters all catalog recipients, not just the caster. This is advisory presentation readiness, never permission to cast or mutate gameplay. Cast authority/mana remains on the existing server path. No effect definitions, scripts, texture data or external URLs are accepted from clients.
