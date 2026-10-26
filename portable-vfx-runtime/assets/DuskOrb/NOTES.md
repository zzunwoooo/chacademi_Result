# DuskOrb (어둠 구슬, 기초 #332, id dusk_orb, 어둠) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| dusk_orb_cast | attached | cast (t = 0) | caster feet + facing. Palm gather + dusk sigil + heptagram frame 0 … 0.15 s, **release at +0.15 s** → spawn the projectile then. Ends by itself (≈ 0.75 s total). |
| dusk_orb | projectile | the mod at cast + 0.15 s | P = orb centre, start = caster frame [0.30, 1.35, 0.78] (right hand), +Z = travel. **The mod steers it toward the target every tick (homing), slow (preview ≈ 3 m/s)**; forward = current velocity. Loops until stopped. Orb grows in during its first 0.18 s. Trail layers are world-space + rateOverDistance, so any speed/turn works. |
| dusk_orb_hit | impact | contact with the target (or a block) | hitPoint = contact point on the target (≈ chest), entity = **air convention** (surfaceNormal = −travel, incoming = travel); block = hitPoint + normal. **Apply weakened 3 s at +0.00 s.** Splash/rings 0 … 0.5 s, then the "weakened" wilt (motes, crescents, drips, sagging sigil drifting world-down) to ≈ 1.2 s. The weakened state itself is shown by the game's debuff icon. |
| dusk_orb_fizzle | impact | 6 s elapsed without contact | at the orb's last position, air convention (surfaceNormal = −forward). Quiet: sphere shrinks, satellites scatter, ink dissolves (≈ 0.9 s). No damage, no splash. |

On stop (hit or expiry): clear the projectile's local layers (SCHEMA §5); world tail/wisps finish their lifetimes (≤ 1 s).

## Held layers
Orb body, swirls, aura, the 3 orbiting crescents, 2 orbiting motes and the dashed orbit ring are ONE particle each (life 60 s, system duration 60 so the t = 0 bursts happen once). The 6 s homing limit is well inside that.
Orbiting satellites use `velocityOverLifetime.orbitalY` (local layers) — **orbitalVelocity is required**; the satellites' short trails use `trail` (feature `trails`).

## Features
Client-supported only. High impact: **meshParticle** (line frame/heptagram rings, orbit track, ink/violet wisp arcs, tail lines), **bloom**, **orbitalVelocity** (orb satellites, cast crescents, fizzle scatter), **attachedFollow** (cast). Also trails, worldSpace, rateOverDistance (world layers only), flipbook, stretchedBillboard, noise, gravity, drag, radial/linear velocity, rotation3D.
Dark bodies (orb, ink crescent, ink blobs, splash crown, shards) are alpha-blended with HDR tint so the violet rim blooms while the ink body stays dark.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
