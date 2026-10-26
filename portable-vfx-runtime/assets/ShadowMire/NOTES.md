# ShadowMire (그림자 늪, 중급 348, id shadow_mire, 어둠) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| shadow_mire_begin | static, phase begin | cast (t = 0) | P = aimed ground point, M = caster facing. Violet drop 0–0.08 s, ink splash + shock 0.08–0.4 s, pool spreads to r 3 by ~0.35 s, circle draws on 0.14–0.45 s, rim tendrils burst 0.24–0.4 s, lock ring 0.48 s |
| shadow_mire | static, phase sustain | t = sequence.sustainStart = 0.55 s | runtime `holdDuration` = 4 − 0.55 = 3.45 (default) → whole swamp 4 s. loop, duration 60 (> hold.max 10) so t = 0 bursts happen once |
| shadow_mire_end | static, phase end | hold expiry (or cancel → jumpToEnd) | drain into the centre ~0.9 s: held layers shrink to the centre in their `onStop` 0.9 s tail, ink gulp at +0.72 s. No damage timing |
| shadow_mire_root | attached | the mod, once per enemy when the root (3 s) starts | follows that enemy (feet, facing). Not looped, duration 2.8: grab 0–0.15 s, tendrils wrapped round the legs, release (strands snap, ink shards) at +2.75 s ≈ root end at +3.0 s. Stop + clear early if the root is cleansed / the enemy dies |

Swamp radius fixed at 3 m (no `scale` input; it would also resize the root system). The outer ring of the ground circle (unit r 1.0 × 3 m, `smOuter`) is the true range ring.
Root / slow icons are the game's own; the VFX only shows the spell.

## Held layers (rotation hand-off)
Pool, swirl and ground circle: ONE particle each in `shadow_mire` (life 59 s, `onStop` tail 0.9 s = drain: alpha fade + size → 0.05).
Begin draw-on copies start at t_s with rot3 = ω·t_s, sustain copies start at 0.55 s with rot3 = ω·0.55 and the same rotLife (overlap 0.55–0.65 s).

## Features
Only client-supported features (client_compat check OK, `fix` = 0 changes). High impact: meshParticle (circle line work, tendril / claw / leg-wrap meshes, pool decal), bloom, orbitalVelocity (mist, motes, wisps), attachedFollow (root), phasedSequence + stopTail.
Texture memory: 15 unique textures = 3.1 MiB RGBA (the check's WARN 5.0 MiB counts shared textures once per material).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
