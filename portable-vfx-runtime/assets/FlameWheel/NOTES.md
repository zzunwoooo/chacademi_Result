# FlameWheel (화염 원반, 중급 336, id flame_wheel) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| flame_wheel_cast | attached | cast (t = 0) | caster feet + facing. The foot circle draws on and the wheel forms 1.0 m in front of the caster with its hub at 0.7 m up. **Launch flash at 0.30 s**: spawn the projectile then. The system ends by itself after 0.8 s plus lifetimes. |
| flame_wheel | projectile | the mod at cast + 0.30 s | **P = wheel hub = ground + 0.70 m** (wheel radius 0.7, the authored ground is local y = −0.7). +Z = travel, kept horizontal and following the ground (the mod moves P along the terrain). Range 12 blocks (preview 15 m/s, about 0.8 s). **Pierces: do NOT stop it on enemy contact.** The wheel spin is a fixed rotationOverLifetime of 15 rad/s, not tied to speed (travelRoll is not in the client). The held wheel layers are ONE particle each (life 60 s, duration 60, so the t = 0 bursts happen once) and they pop in over 0.14 s. The world trail (scorch decals about 2.5 s, burning strip, flames, embers, ash) uses rateOverDistance, so it only appears while the wheel moves. |
| flame_wheel_pierce | impact | once per enemy, when the hub reaches it | hitPoint = hub point at the enemy (ground + 0.7), surfaceNormal = up (0,1,0), incomingDirection = travel. **Damage at +0.00 s** (single hit). The cut arcs spin in the wheel plane and sparks spray forward. |
| flame_wheel_bounce | impact | first block hit (wall) | hitPoint = the rim's contact point on the wall (hub + forward·0.7), surfaceNormal = wall normal, incomingDirection = travel. It plays a flash, rings on the wall, a scorch mark, sparks, tongues and embers. If the wheel hits a block that is NOT a wall (a step, for example), still treat it as the one bounce. |
| flame_wheel_end | impact | range end, or the second block hit | hitPoint = hub at the stop, **surfaceNormal = up**, incomingDirection = travel (use up even for a second wall hit, because the wheel collapses onto the ground). The wheel shrinks and drops, a ring of fire spreads on the ground, then scorch, embers and smoke. |

## Bounce (how the mod should handle it)
1. When the wheel first hits a wall, **stop the projectile instance** using the normal hit rule. Local layers are cleared, and world trail layers stop emitting and finish their lifetimes. Do **not** play flame_wheel_end here.
2. Play **flame_wheel_bounce** at the rim contact point (hitPoint = wall point, surfaceNormal = wall normal, incomingDirection = old travel direction).
3. In the same tick, **start a NEW flame_wheel projectile instance** at the hub position where the first one stopped, with forward = the old travel reflected about the wall normal and flattened to horizontal: `d' = d − 2(d·n)n`, then y = 0, then normalise. For a head-on hit this means it rolls straight back.
4. The new instance rolls the **remaining range** (12 − distance already travelled). It pierces enemies again; an enemy can be hit once on the way out and once on the way back, as the server's damage rules allow. It must not bounce a second time: at the next block hit or at range end, stop it and play flame_wheel_end.
The new instance pops in again over 0.14 s, which matches the visible rebound in the bounce flash. The preview's "벽 튕김" scenario shows exactly this: two flame_wheel instances, a bounce impact, and pierce hits in both directions.

## Features
This bundle uses only client-supported features (client_compat fix: 0 changes; check OK). High-impact features: meshParticle (the wheel rim, tongues, spokes, rune band, hub sigil, spin arcs, track strip, foot circle) and bloom. It also uses attachedFollow (the cast system). Other features: rateOverDistance + worldSpace (the trail), flipbook, stretchedBillboard, rotation3D, noise and gravity. orbitalVelocity and stopTail are not used.
Textures: 15 unique PNGs, ≤ 512 px, about 2.6 MiB RGBA. The check's WARN figure (4.3 MiB) counts shared textures once per material.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
