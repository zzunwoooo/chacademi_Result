# PrismShot (프리즘 탄, #333 빛 기초, id prism_shot) — converter notes

Gameplay: a light bolt; on its first hit it refracts into 3 ways and hits up to 2 OTHER nearby enemies.

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| prism_shot_cast | attached (caster feet + facing) | cast, t = 0 | light gathers into the right hand (caster-local [0.3, 1.38, 0.5]); a tiny prism crystal spins up inside a triangle sigil + frame; foot mini prism circle. **Release at t = 0.18 s** (3-ray flash, snap rings, spray, umber puff). Duration 0.25 s, one-shot, no stop signal. |
| prism_shot | projectile | **the mod at t = 0.18 s**, start = caster feet + facing·[0.3, 1.40, 0.66] (caster frame) | P = crystal centre, +Z = travel. Held layers = one particle each (life 30 s, system duration 30). Spinning triangular prism (additive gold facets, white rims, faint cyan/rose refraction lines) + white-gold core star + light streak + wisps + subtle cyan/rose edge glints. World wake via rateOverDistance. Speed / range from the server. |
| prism_shot_hit | impact | first hit (entity / ground / wall / air) | flash, 3-ray prism star, **3-way spectral beam burst** (mesh, random yaw), crystal chips, rays, arcs, ring + triangle shock line, sparks, cyan glints, dark shards, umber shade. **Damage at +0 s.** Entity / air: normal = −incomingDirection. |
| prism_shot_split | projectile | **the mod, same tick as prism_shot_hit: spawns 2 NEW instances FROM THE FIRST HIT POINT**, each aimed at one of the 2 nearest other enemies (chest height) | smaller copy of the bolt (scale 0.62, spectral-beam streak). If only 1 other enemy is in range spawn 1; if none, spawn none. Search radius / speed from the server. |
| prism_shot_split_hit | impact | each refracted bolt hits (or misses: block / range end) | small flash, cyan glint, ring + shock line, chips, sparks, motes. Damage of the refracted hit at +0 s. |

Flow: cast → 0.18 s launch → first hit: `prism_shot` stops (local layers cleared, world wake finishes) + `prism_shot_hit` at hitPoint + 2 × `prism_shot_split` from that hitPoint → each: `prism_shot_split_hit`.
If the first bolt hits a block (ground / wall) the mod may still refract from the hit point if enemies are near (owner's choice); the demo shows ground/wall/air misses without splits.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK). High impact: meshParticle (prism crystals, trail strips, sigil frame, foot circle, 3-way beams, shock rings, chips), bloom, attachedFollow (cast), orbitalVelocity (cast cyan motes only).
Also rateOverDistance (world layers only), worldSpace, stretchedBillboard, rotation3D, rotationOverLifetime, noise, gravity, drag, radialVelocity.
Textures: 13 unique PNGs ≤ 256 px, ≈ 0.99 MiB RGBA.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
