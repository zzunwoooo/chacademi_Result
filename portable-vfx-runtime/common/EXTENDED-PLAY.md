# Negotiated PLAY extension: seed, game time and named anchors

Java 21; visual-only, with no server gameplay authority or downloaded executable code.
The ordinary `portablevfx:hello` remains exactly the big-endian integer `1`.
Existing PLAY (opcode 1), STOP (2), CLEAR (3), follow PLAY (4), and orientation (5)
retain their exact bytes and constructors. Opcode 4 is opcode 1's fields followed
by an entity UUID (two big-endian longs). Opcode 5 is UUID, nonnegative sequence
(long), yaw/pitch/roll (three finite floats in +/-360); it is gated independently by
`portablevfx:orient_hello`. Both extensions retain envelope version `1`.

## Safe capability negotiation

A new client sends the exact four-byte version `1` on
`portablevfx:play_ext_hello` only after its ordinary hello and only if the server
advertises this channel. The relay accepts this capability only after a valid base
hello. Truncation, extra bytes and unsupported versions revoke only this capability.
Disconnect, invalid base hello and effect-channel unregister discard capability state.

The relay sends opcode 6 only to recipients that announced this capability. Ordinary
play/follow commands still reach legacy recipients with their original opcode 1/4.
For a capable recipient the same ordinary request is stamped with the world's
absolute game tick and upgraded to opcode 6. An explicitly seeded, timed, named or
offset request is delivered only to capable recipients: there is no silent placement
or timing approximation for old clients. Orientation capability does not imply
extended-play capability. The existing packet budgets/control priority still apply.

## Opcode 6 layout

Envelope: version `1` (int), opcode `6` (unsigned byte). Then all the opcode 1 fields
through durationTicks, unchanged and in the same order. Then:

1. seed: signed 64-bit integer (all bit patterns valid).
2. startTick: signed 64-bit integer, `-1` for receipt time or nonnegative absolute
   world game time. This is not time-of-day and not the server process tick count.
3. anchor: unsigned byte: `0 world`, `1 entity`, `2 head`, `3 left_shoulder`,
   `4 right_shoulder`. Unknown values are rejected.
4. offsetX, offsetY, offsetZ: three finite 32-bit floats, each within +/-64 blocks.
5. For every non-world anchor only: target entity UUID (two signed 64-bit integers).

World cannot have a target UUID; every other anchor requires one. Trailing bytes are
always rejected. Minimal-size world/attached packets are 112/128 bytes; the maximum
is 378 bytes, still below the 2048-byte admission cap. An opcode 6 request with only
legacy defaults is rejected as redundant, preserving canonical decode/re-encode.
Negative zero offsets retain their IEEE representation and select opcode 6.

## Java API compatibility

`PlayEffect` appends these fields after the existing `followEntity` field:

```java
long seed, long startTick, EffectAnchor anchor,
float offsetX, float offsetY, float offsetZ
```

Both legacy constructors remain. They use `startTick = -1`, zero offsets, WORLD
without a follow target or ENTITY with one. `seed = PlayEffect.defaultSeed(instanceId)`
is a documented stable 64-bit UUID mix, not a process-dependent hash. The old constructors
therefore still encode as opcode 1/4. The full constructor permits seed zero for exact
repeatable scene proofs. `withStartTick(tick)` produces an immutable timestamped copy.
The existing same-UUID replacement rule remains.

Paper's existing `play(EffectRequest)`/`follow(EffectRequest, UUID)` remain unchanged.
For explicit options use `play(EffectRequest, PlaybackOptions)`, e.g.:

```java
service.play(request, PlaybackOptions.attached(entityId, EffectAnchor.HEAD, 42L));
service.play(request, new PlaybackOptions(entityId, EffectAnchor.LEFT_SHOULDER,
        .1f, .2f, 0, 42L, -1));
service.play(request, PlaybackOptions.world(0L));
```

Paper validates that attachment targets are loaded/alive in the requested world and
uses their position for recipient selection. `/pvfx follow <id> <entityUUID> [scale]
[ticks] [anchor] [offsetX] [offsetY] [offsetZ]` exposes the same named attachments.
The ordinary `/pvfx play` command is unchanged; it does not select a render backend.

The client accepts the full PlayEffect through its existing local API, or uses
`playAttached(effectId, target, anchor, localOffset, scale, durationTicks, seed)`.
`/pvfxclient playproof <id> ...` has the same placement/options as `play` but uses
explicit seed zero. It is client-only and sends no gameplay/server packets.

## Runtime semantics and limits

- Clients compute initial age as max(0, client world game time - startTick), reject
  already-expired requests, then advance by client simulation ticks. Timestamping
  provides bounded initial catch-up; it is not a continuous clock-sync protocol.
  Future timestamps are clamped to age zero, not delayed scheduling.
- TTL is at most 1200 ticks for clients without `stop_intent_hello` and at most 12000
  (`MAX_DURATION_TICKS`) for stop-intent clients; old/delayed packets cannot allocate unbounded catch-up
  work. The relay reduces its handle TTL for already-aged explicit requests.
- Deterministic scene particles consume request.seed. Native Effekseer timing/random
  behavior is backend-specific; a wire seed alone does not guarantee equivalent native
  simulations or synchronization across unlike backends.
- `position(active, delta)` is shared by both backends. Entity/head/shoulder anchors
  follow only the actual, locally tracked entity captured at play time. Removed/dead
  entities, world changes and out-of-range positions stop playback. The client never
  spawns an entity or looks up remote assets to satisfy a target.
- Local coordinates use +X left, +Y up, +Z forward at heading zero. User offsets are
  transformed by the same Rx(pitch) Ry(-yaw) Rz(roll) matrix as the Unity scene
  renderer (applied local roll, yaw, pitch), then entity heading. WORLD uses the
  same offset transform without an entity heading. Offsets are blocks, not multiplied
  by effect scale. Named anatomical offsets rotate with entity heading only. The asset
  itself keeps explicit request/world Euler rotation; attachment does not add body yaw
  to the asset rotation.
- Head uses the current pose eye height. Shoulder height is eye height minus
  min(.35, entity height * .18), lateral displacement is entity width * .6. These are
  bounded pose/dimension approximations; they do not bind to animated skeleton bones.
  Heights/lateral distances are capped at 32/16 blocks. Generic entity heading is used
  even for head anchors, rather than a model's independently animated head yaw.
- Resolved coordinates must remain finite, within protocol position bounds and within
  256 blocks of the viewer. Active-instance and rendering budgets remain in place.
- Runtime tint remains white/opacity one for both backends; author colors in the asset.

## Verification

`bash common/run-tests.sh` compiles dependency-free Java with warnings as errors and
covers legacy golden bytes, all anchors, seed/tick extremes, exact extended field
order, every truncated prefix, bad offsets/anchors, trailing data, canonical encoding,
rotation math and deterministic mutation fuzzing. Paper JUnit tests additionally
cover old/new audience splitting, independent exact capability negotiation, expired
requests and shortened TTL, disconnect reset and existing budget/control regressions.
Actual Fabric/Paper connection, body-pose accuracy and rendered in-game alignment
remain acceptance tests, not results established by these unit tests.


## Negotiated width PLAY (opcode 10)

`portablevfx:width_hello` is an independent exact four-byte version-1 capability,
accepted only after base hello. An explicit positive width requires both extended
PLAY and width capabilities; authoritative casts also require authority capability.
Unsupported clients receive no approximate PLAY. Existing opcodes 1–9 retain their
wire format, and all existing `PlayEffect` constructors still default to width 0.

`PlayEffect` appends `double effectWidth`; `withEffectWidth(width)` preserves every
other field and `withStartTick(tick)` preserves width. Width 0 means the authored
reference, and retains opcode 1/4/6. Finite positive values through 64 metres use
opcode 10, require a `claude:` effect and uniform scale 1, and cannot be downgraded
with `legacy()`. Negative zero, negative values, NaN and infinity are rejected.

Opcode 10 is version 1 + opcode 10 + the full opcode-6 body (including optional
follow UUID) + one big-endian IEEE-754 double `effectWidth`. Width must be positive
in this encoding; encoding zero as opcode 10 is noncanonical and rejected. Packet
size is 125–386 bytes (the effect ID must be `claude:`). Width is initialized in the PLAY itself, before the first
emission, rather than racing a later update packet. The renderer applies the
asset's own width rule (e.g. `clamp(effectWidth / reference, min, max)`), independent
of the existing uniform scale; billboard size remains authored.

The existing atomic IMPACT envelope may embed opcode 10 (or 12). The decoder's nested
length window is 83–402 bytes; because IMPACT phases must be unoffset WORLD anchors,
a valid nested opcode 10/12 phase is at most 386 bytes (see PROTOCOL.md size table). The server only sends width-bearing IMPACT to width-capable recipients;
others get the existing graceful FINISH fallback. A collapse can thus use the
same width as its wave without terminating or resizing independent splash effects.
Disconnect, invalid base hello and effect-channel unregister clear width capability.
The normal PLAY budgets, two-packet authoritative-start reservation and control
priority are unchanged.
