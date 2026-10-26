# StormEye (폭풍의 눈, 중급 347, id storm_eye, 바람) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| storm_eye_begin | attached, phase begin | cast (t = 0) | P = caster feet, M = caster facing. Foot circle draws on, 4 tilted rings spin up, wind gathers; lock-in pulse at 0.36–0.4 s |
| storm_eye | attached, phase sustain | t = sequence.sustainStart = 0.4 s | runtime `holdDuration` = 3.6 (whole barrier 4 s). loop, duration 60 (> hold.max) so its t = 0 bursts happen once. Barrier radius 1.6 m around the chest (y 1.0) |
| storm_eye_end | attached, phase end | hold expiry or cancel (jumpToEnd) | 0 … 0.1 s rings contract, **fling at end + 0.1 s** (flash, expanding rings, radial streaks, ground gust ring) → the mod should release / throw the caught projectiles outward at end start + 0.1 s |
| storm_eye_catch | impact | the mod, once per captured projectile | hitPoint = where the projectile meets the barrier (≈ 1.6 m from the chest), surfaceNormal = outward from the caster's chest, incomingDirection = projectile direction |

The caught projectiles themselves are moved in orbit by the mod (their own visuals); this pack only draws the barrier, the catch pulse and the fling.

## Held layers (rotation hand-off)
Foot circle (4 layers) and the 4 tilted barrier rings are ONE particle each in `storm_eye` (life 59 s, `onStop` tail 0.5 s).
Their `storm_eye_begin` draw-on copies start at t_s (0 … 0.23 s) with rot3 = rot0 + ω·t_s; the sustain copies start at 0.4 s with rot3 = rot0 + ω·0.4 and the same rotLife,
so they line up exactly (overlap 0.4 … 0.48 s = short lock-in brightening).
The tail (last 0.5 s of life) holds the end: rings shrink to 0.86 by 0.1 s, then blow out to 2× and vanish by 0.3 s; foot circle flares to 1.12 and fades by 0.42 s.
Wind strokes / motes / chips in `storm_eye` use short `onStop` tails so the orbit breaks up at once when the fling starts.

## Features
Only client-supported features (client_compat check OK, `fix` = no visual change). High impact: meshParticle (foot circle line work, tilted rings, stroke ribbons, spiral),
bloom, orbitalVelocity (orbiting motes, chips, skim streaks), attachedFollow (barrier follows the caster), phasedSequence + stopTail (held layers).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
