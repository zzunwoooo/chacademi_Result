# Thundercloud (뇌운, #363, 바람 상급) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| cloud | attached (to the TARGET) | the mod, at cast (t = 0) | P = target feet, +Y up, follows the target every tick (attachedFollow). Scaled by `targetHeight` (reference 1.8, clamp 0.6–2.0). Cloud forms 0–0.9 s at ~4.7 m (cloud underside 4.15 m, rune ring 3.75 m). Charge build-up starts 0.5 s before each strike; **bolt visuals baked in at t = 1.5 / 3.0 / 4.5 / 6.0 s**; cloud disperses 6.1–7.2 s. duration 6.2 s, not looping. If the spell is cancelled / target dies: stop the system (held layers have `onStop` tails 0.3–0.6 s). |
| strike | impact | the mod, at each strike (t = 1.5 / 3.0 / 4.5 / 6.0 after cast) | hitPoint = the target's feet (ground under the target), surfaceNormal = up, incomingDirection = down. Apply the damage on the same tick. Scorch decal lingers 2.6 s in the world. |

The strike times are fixed (owner plan: 6 s, a bolt every 1.5 s, 4 bolts). If the server timing ever changes, the bursts in `cloud` (layers Q01–Q06, S01–S10) must be re-timed.

## Held layers
Rune ring (H01–H03) + sigil (H04) = one particle each, life 6.9 s, draw-on 0–0.55 s, flare at 6.0–6.12 s, fade by 6.6 s.
Cloud body = rate-emitted alpha cloud flipbook puffs (C01 dark top, C02 lit belly, C03 dark scud) — dark accents behind the additive lightning.

## Features
Only client-supported features (`client_compat.py check` OK, `fix` = 0 changes). High impact: meshParticle (rune ring line work, bolt planes, wind band, ground decals), bloom, orbitalVelocity (cloud swirl, gather motes), attachedFollow (cloud follows the target), instanceScale (targetHeight), stopTail.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
