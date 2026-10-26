# EruptionLine (분화선, 중급 337, id eruption_line) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| `eruption_line` | static | cast t = 0 at the caster's feet, basis = caster horizontal facing (+Z = forward) | hand-slam + caster sigil (r 1.35 m) → glowing crack races z 0.75 → 10.2 m during t 0.10–0.40 s → 4 fire pillars. Ends by itself (~3.2 s incl. smoke/scorch tails) |
| `eruption_line_hit` | impact | the mod, once per enemy hit by a pillar | hitPoint = enemy feet, surfaceNormal = up, incomingDirection = forward. Short launch kick (flash, updraft tongues, ring, body flames, sparks) |

## Pillar timing / hit (must match the server)
| pillar | centre (local x, z) | telegraph ring (true radius 1.2 m) | eruption = damage + small knock-up |
|---|---|---|---|
| 1 | (≈0, 2.5) | t 0.15 | **t 0.45** |
| 2 | (≈0, 4.6) | t 0.40 | **t 0.70** |
| 3 | (≈0, 6.7) | t 0.65 | **t 0.95** |
| 4 | (≈0, 8.8) | t 0.90 | **t 1.20** |
Pillar x follows the crack's slight jitter (|x| ≤ 0.18 m). If the server uses different spacing/radius/interval, change `PZ`, `R`, `T0`, `DT` at the top of the spec and rebuild (no runtime input; scale not used).
Each eruption: crack burst/flash, 3-layer flame column (crossed-plane meshes, 2×8 flipbook), puffs, tongues, rays, sparks, 2 shock rings, ring flare, molten crack decal, scorch decal, charcoal chips, smoke column, ash, afterburn flames, embers. The caster sigil and the crack flare in sync with each eruption.

## Features
Only client-supported features (client_compat fix = 0 changes, check OK). High impact: meshParticle (line-work circles, crack ribbons, flame columns), bloom, orbitalVelocity (caster embers, hit updraft). No attachedFollow.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
