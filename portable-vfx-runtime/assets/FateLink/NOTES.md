# FateLink (운명의 실, #364, fate_link) — converter notes

Two enemies tied by a violet fate thread for 6 s. If they separate beyond 8 m the thread snaps (damage + 1 s stun on both); otherwise it unravels quietly at 6 s.
Visual only: distance checks, damage, stun and timing are the mod's job. The mod also drives the tension (taut / strain systems) and the snap (trigger signal).

## Systems and runtime inputs
| system | role | started by | placement |
|---|---|---|---|
| form | link, phase begin (0–0.45 s) | cast | source = enemy A chest, target = enemy B chest (both every frame) |
| bind | link, phase sustain | sequence t = 0.45 s | same; `holdDuration` = 5.55 s (6 s total). Stop → thread `onStop` 0.12 s, end knots 0.35 s |
| snap | link, phase end, `endBranch: "trigger"` | **trigger signal** when distance > 8 m | same two points (last positions) |
| fade | link, phase end, `endBranch: "expire"` | hold expiry (6 s) or cancel (death / dispel) | same |
| taut | link (no phase) | the mod while distance ≥ 6 m (stop below ~5.8 m, and at any end) | same |
| strain | link (no phase) | the mod while distance ≥ 7.2 m (stop below ~7.0 m, and at any end) | same |
| mark | attached | cast, once per enemy (A and B) | enemy feet + facing; stop at the end (snap or fade) → 0.4 s tail |
| snapHit | attached | at the snap, once per enemy | enemy feet; body burst at +0.1 s = **damage + stun moment** |
| snapMid | impact | at the snap, once | hitPoint = midpoint of the two chest points, normal = up, incoming = A→B |
| cast | attached | cast (t = 0), caster | caster feet + facing |

- Link direction: z runs A → B. Chest point (suggested): feet + 0.7·height (preview feet + 1.25 m).
- Snap timing: snap / snapMid / snapHit all start at the same tick (trigger). Whips recoil from the middle to both bodies in ~0.25 s; snapHit's flash at +0.1 s.
- Tension is NOT computed in the VFX: the base thread (bind) is calm; taut adds a hotter core, quivering fibres, pull pulses to the middle, sparks;
  strain adds a white-hot core, fraying fibres, crackle and ink flecks. Use hysteresis so they do not flicker on/off.
- Send `trigger` only while the sustain is running; after expiry `fade` plays instead (two-way end, feature triggeredEnd).

## Held layers
Knot sigils + cuffs at both ends: ONE particle each in `bind` (life 59 s, onStop 0.35 s); the `form` pop-in copies start at t0 (A 0.02 s, B 0.36 s) with
rot3 = ω·t0 and hand over at 0.45 s (cuff ω = 1.4, cuff runes ω = −0.9 rad/s about the thread axis). Mark (attached): circle/rune/wrap held 59 s, onStop 0.4 s.
Particles pinned to B use path progress = 1; the break point uses progress = 0.5.

## Features
Client-supported only. High impact: meshParticle (ground knot circle, cuffs, body wrap, whip arcs), bloom, orbitalVelocity (mark needles/motes), attachedFollow (mark, snapHit, cast),
plus linkTarget + pathFollow (the thread itself — a static straight beam is not equivalent), phasedSequence, triggeredEnd, stopTail, trails, flipbook, stretchedBillboard.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
