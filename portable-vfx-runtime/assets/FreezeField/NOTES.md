# FreezeField (빙결 지대, 상급 359, id freeze_field, 물/얼음) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| freeze_field_begin | static, phase begin | cast (t = 0) | P = aimed ground point, M = caster facing. Cold snap 0–0.12 s, shock ring reaches the boundary ~0.35 s, circle draws on 0.18–0.5 s, rim crystals erupt 0.3–0.6 s, lock ring 0.72 s |
| freeze_field | static, phase sustain | t = sequence.sustainStart = 0.8 s | runtime `holdDuration` = 6 − 0.8 = 5.2 (default) → whole zone 6 s. loop, duration 60 (> hold.max 12) so its t = 0 bursts happen once. Frost front creeps rim → centre over sustain 0–3 s (cosmetic; reaches the centre at cast 3.75 s) |
| freeze_field_end | static, phase end | hold expiry (or cancel → jumpToEnd) | graceful thaw ~1.4 s (held layers use `onStop` 1.2 s tail). No damage timing |
| freeze_field_freeze | attached | the mod, ONCE per enemy that has stayed 3 s inside (stun 1 s start) | follows that enemy (feet, facing). Not looped, duration 1.0: encasing crystals at +0.14 s, **shatter at +0.9 s** (≈ stun end at +1.0 s). Do not stop it early; if the enemy dies, stop/clear is fine |

Zone radius fixed at 5 m (no `scale` input: it would also resize the per-target freeze system; `scale.systems` is not in the client). The outer ring of the ground circle (unit r 1.0 × 5 m, `ffOuter`) is the true range ring; rim crystals straddle r 5.
Stun / CC icons are the game's own; the VFX only shows the spell.

## Held layers (rotation hand-off)
Ground circle (5 layers) + rim crystals: ONE particle each in `freeze_field` (life 59 s, `onStop` tail 1.2 s = thaw fade).
Begin draw-on copies start at t_s with rot3 = ω·t_s, sustain copies start at 0.8 s with rot3 = ω·0.8 and the same rotLife (overlap 0.8–0.92 s = lock-in brightening).
Frost decals (3 nested quads, alpha-blended) fade in at sustain 0 / 1 / 2 s behind the shrinking frost front.

## Features
Only client-supported features (client_compat check OK, `fix` = 0 changes). High impact: meshParticle (circle line work, crystal meshes, encasing, frost decals), bloom, orbitalVelocity (mist, crystals, snow), attachedFollow (freeze), phasedSequence + stopTail (held layers).
Texture memory: 16 unique textures = 2.6 MiB RGBA.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
