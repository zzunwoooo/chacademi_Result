# HaloThrow (광륜 투척, #351 빛 중급, id halo_throw) — converter notes

Gameplay: throw a ring of light; the FIRST enemy hit is slowed for 3 s, then the ring bounces ONCE to the next enemy.

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| halo_throw_cast | attached (caster feet + facing) | cast, t = 0 | Foot line-work circle draws on; a golden halo forms above the right hand (caster-local [0.38, 1.82, 0.36]) with rune ticks lighting in stages (0.1 / 0.2 / 0.28 / 0.35 s) and staged inflow. **Release at t = 0.45 s** (flash, push rings, swing arc, foot pulse, feathers). |
| halo_throw | projectile | **(1) the mod at t = 0.45 s**: start = caster feet + facing·[0.36, 1.6, 0.8] (caster frame). **(2) the bounce: the mod spawns a NEW halo_throw instance at the FIRST hit point** (same tick as the hit), aimed at the next enemy (chest height). | P = ring centre, +Z = travel. Held ring = one particle per layer (life 60 s, system duration 60), spinning banked chakram. World trail (wake, rim streaks, light feathers, motes, sparks, cyan glints) uses rateOverDistance. Speed / range / bounce search radius come from the server. |
| halo_throw_hit | impact | first enemy hit | flash, rings, band burst, rays, sparks, dark-rim shards, feathers. **Damage + slow at +0 s.** Entity hit → surfaceNormal = −incomingDirection. |
| halo_throw_slow | attached to the FIRST enemy (its feet + facing) | same tick as halo_throw_hit | the ring clamps around the waist (y = 1.0 m, r = 0.62 m): arrives wide, snaps tight at ≈ 0.12–0.3 s (flash, contracting ring, tangential sparks), then a steady binding band + sinking motes/feathers + faint foot ring until **3.0 s** (built-in duration). If the slow is cleansed early, the mod stops the system → onStop tail 0.25 s. Scale with the target if desired (authored for a 1.8 m body). |
| halo_throw_end | impact | the BOUNCE instance hits the next enemy, OR any instance misses (block / range end / no bounce target) | the halo shatters into light feathers (band/rune wall/edge expand and fade, ring, sparks, shards, motes). Damage of the 2nd hit at +0 s. Ground / wall: normal = face normal; air / entity: −incoming. |

Flow: cast → 0.45 s launch → first enemy: `halo_throw` stops (local layers cleared, world trail finishes) + `halo_throw_hit` + `halo_throw_slow` (enemy 1) + new `halo_throw` from the first hit point → enemy 2: `halo_throw_end`.
If no second enemy is in range, no bounce instance is spawned (optionally play `halo_throw_end` at the first hit point for a clean fade, otherwise the hit impact alone is fine).
If the first throw misses: `halo_throw_end` at the miss point (no slow, no bounce).

## Orientation detail
Ring meshes are authored in the XY plane (axis local +Z); each layer uses rot3 [-1.13, 1.25, z] (Euler Z, X, then Y) so the spin (rotationOverLifetime Z) stays about the ring axis while the ring flies banked ~25°.
Cast halo uses [0.95, 0.55, z].

## Features
Only client-supported features (client_compat fix: 0 changes; check OK). High impact: meshParticle (ring band/edge/rune wall/ticks, wake strips, foot circle), bloom, attachedFollow (cast, slow), orbitalVelocity (slow clamp sparks, cast cyan motes, end sparks).
Also rateOverDistance + worldSpace (trail), stopTail (slow), flipbook (spin smears), stretchedBillboard, rotation3D, rotationOverLifetime, noise, gravity, drag, radialVelocity.
Textures: 14 unique PNGs ≤ 512 px.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
