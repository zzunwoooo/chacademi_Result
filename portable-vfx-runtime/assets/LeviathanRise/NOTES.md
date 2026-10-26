# LeviathanRise (레비아탄, 궁극 369, 물) — converter notes

## Systems and runtime signals
| system | role / phase | started by | notes |
|---|---|---|---|
| zoneChant | static, begin | cast (t = 0) | P = aim point on the ground (radius 10 m), M = server static basis. Water circle draws on, pool rises, serpent shadow circles, anticipation 4.2–5.0 s |
| casterChant | attached, begin | cast (t = 0) | follows the caster (feet + facing). Feet circle, water coil, orb at the hand, release flash at 4.95 s. Stops with the begin phase (sequence.sustainStart = 5.0 s) |
| strike | static, sustain | t = sustainStart = 5.0 s | runtime `holdDuration` (default 3.2, min 2.6, max 6). loop, duration 60 so its t = 0 bursts happen once |
| drain | static, end | hold expiry, or cancel → jumpToEnd | short drain; held layers play their 1.2 s onStop tails |
| struck | impact | the mod, once per enemy inside r 10 at the crash | hitPoint = enemy feet, surfaceNormal = up, incomingDirection = down |

Cancelled chant (caster interrupted, t < 5 s): send cancel → begin systems stop emitting (held layers fade in ≤ 0.5 s), drain plays, the strike never starts.

## Gameplay timing (for the server)
- strike + 0.05 s: the serpent breaks the surface at the far side of the pool (local ≈ (4.5, 0, 7.8)), arches to 13 m (apex at strike + 0.58 s).
- strike + 0.3 … 1.4 s: "swallow" — the pool closes in from the edge like a maw (inward wall + spray). Good moment to apply the slow (3 s) to enemies inside r 10.
- **strike + 1.40 s (= cast + 6.4 s): crash** on the centre (head lands at local ≈ (−1.0, 0, 0.15)) → splash crown, shock rings, tidal ring travelling out to r 10 by ≈ +2.1 s. Apply the final hit + stun 1 s here and spawn `struck` at each enemy.
- The serpent is driven by exact physics (all its layers share one trajectory: start (4.5, −11, 7.8) relative to layer origin (0, 9, 0), speed 47.25 up, gravity 7.29, orbitalY −1.519 rad/s, radial −8.438 m/s). Its body = alpha + additive particle trails (trail lifetime ratio 0.4), head = stretched billboard along the velocity.

## Range / scale
Range ring (outer circle line work) and tidal ring sit exactly at r 10 m. No `scale` input: the serpent's trajectory is authored for r 10 and `scale.systems` is not in the client, so a scale would also resize the per-enemy `struck`.

## Held layers (rotation hand-off)
Circle (outer, rune bands, wave ring, inner, sigil), spouts, water surface, caustics: begin copies start at t_s with rot3 = ω·t_s, the sustain copies start at 5.0 s with rot3 = ω·5.0 and the same rotLife (overlap 5.0–5.12 s on purpose). Sustain held particles: life 59 s, onStop tail 1.2 s.

## Features
Only client-supported features (client_compat check OK, fix = 0 changes). High impact: meshParticle (circle line work, maw, crown, tide, coil), bloom, orbitalVelocity (serpent path, motes, shed spray), attachedFollow (casterChant), trails (serpent body), phasedSequence + stopTail.
Texture memory: 23 unique textures = 3.49 MiB RGBA (the check's WARN of 6.5 MiB counts shared textures once per material).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
