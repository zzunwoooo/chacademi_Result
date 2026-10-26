# VolcanicBarrage (화산탄 세례, 상급 357, id volcanic_barrage) — converter notes

Gameplay: 6 lava rocks thrown up, they rain down inside aim radius 6 (red landing telegraphs); hit enemies get slow 3 s (the game's debuff icon shows the slow).

## Systems and runtime signals
| system | role | started by the mod | stopped by the mod |
|---|---|---|---|
| launch | attached (caster feet + facing) | cast t = 0 | — (one-shot, ≈1.9 s). Eruption at +0.08 s, 6 rocks fly up and leave the view by ≈0.9 s (world space) |
| zone | static (P = aim point on the ground, M = caster facing) | cast t = 0 | stop (onStop tail 0.4 s) right after the last impact. True range ring at r = 6 m (Z01, mesh size 6) |
| mark | static (P = one landing point, world up) | once per rock, 1.2 s before that rock lands | — (life 1.32 s; covered by the impact). Red telegraph r 1.5 m that fills from the centre and reaches the rim exactly at +1.2 s |
| rock | projectile (+Z = travel) | once per rock, new instance | on hit (SCHEMA §5: clear local, stop world emission) |
| impact | impact | on each rock hit: hitPoint, surfaceNormal, incomingDirection (air = entity hit → −incoming) | — (≈2.6 s incl. scorch). Visual splash radius ≈2.6 m (rings I07/I08) |

Recommended timing (demo): mark k at 0.05 + 0.13·k s, rock k spawned at mark start + 0.55 s from landing point + (0, 14, −3.5) (high, back toward the caster) and flown in 0.65 s (≈22 m/s) → **damage + slow at mark start + 1.2 s** (the rock hit). Any flight time works; keep the mark start = hit − 1.2 s so the fill completes on the hit.
Rock look: faceted lava rock mesh ≈1.2 m across (`vbRock`, size 1.3, 376 tris) with additive crack shell (R03) — same rotation3D / rotationOverLifetime on R02 and R03, keep them identical.

## Features
Only client-supported features (client_compat check OK, `fix` = 0 changes). High impact: meshParticle (cast circle, range ring, landing marks, arcs, rock meshes), bloom, attachedFollow (launch), depthWrite (solid rock / chunks: `rock`, `rockHot` materials), rateOverDistance (rock trail, world), stopTail (zone). No orbitalVelocity.
Texture memory: 17 unique textures = 3.64 MiB RGBA (the check's WARN counts shared textures once per material).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
