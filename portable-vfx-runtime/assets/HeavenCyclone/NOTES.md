# HeavenCyclone (하늘의 눈, 궁극 370) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| begin | static, phase begin | cast (t = 0) | P = caster feet, M = caster facing. Ground circle draws on, storm gathers, sky eye opens (EYE_Y = 11.5 m above P) |
| sustain | static, phase sustain | t = sequence.sustainStart = 1.2 s | runtime `holdDuration` = spell duration − 1.2 (default 8.8 → whole spell 10 s). loop, duration 60 (> hold.max 14) so its t = 0 bursts happen once |
| end | static, phase end | hold expiry (or cancel → jumpToEnd) | 0 … 0.5 s anticipation (eye collapses, wind sucked up), **slam visual at end + 0.55 s** → the server's slam damage / drop should land at end start + 0.55 s |
| lift | attached | the mod, once per lifted enemy | follows that enemy (feet, facing). loop, rate emission. Stop it (stop emitting) at end + 0.55 s, when the enemy is slammed |
| slam | impact | the mod, once per enemy | hitPoint = the enemy's landing point on the ground, surfaceNormal = up, incomingDirection = down |

Zone radius is fixed at 8 m (no `scale` input: scale would also resize the per-target lift/slam systems; `scale.systems` is not in the client yet).

## Held layers (rotation hand-off)
Ground circle (5 layers), vertex pillars, sky eye rings / eye sigils, light column: ONE particle each in `sustain` (life 59 s, `onStop` tail 1.4 s).
Their `begin` draw-on copies start at t_s with rot3 = ω·t_s and the sustain copies start at 1.2 s with rot3 = ω·1.2 and the same rotLife, so they line up exactly
(both overlap 1.2 … 1.32 s on purpose: a short lock-in brightening). The tail (last 1.4 s of life) contains the anticipation and the collapse:
circle 0 … 0.5 s contracts to 0.97, flares to 1.05 at 0.68 s, fades by 1.4 s; eye rings / sigils shrink to 0.35 by 0.48 s and vanish at 0.62 s; column dims then pulses at 0.55 s.

## Features
Only client-supported features (client_compat check OK, `fix` = 0 changes). High impact: meshParticle (ground circle line work, wind bands, cocoon, column),
bloom, orbitalVelocity (wind, debris, clouds), attachedFollow (lift), phasedSequence + stopTail (held layers).
Texture memory: 17 unique textures = 3.6 MiB RGBA (the check's WARN counts shared textures once per material).
