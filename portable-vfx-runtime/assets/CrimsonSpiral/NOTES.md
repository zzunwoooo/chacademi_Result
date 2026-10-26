# CrimsonSpiral (홍염 나선, 상급 356, id crimson_spiral) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| crimson_spiral_cast | attached | cast (t = 0) | caster feet + facing. Vertical sigil in front of the hands (1.35 m up, 0.95 m forward) + foot circle, charge 0 … 0.35 s, **launch flash at 0.35 s** → spawn the projectile then. Ends by itself (0.9 s + lifetimes). |
| crimson_spiral | projectile | the mod at cast + 0.35 s | P = muzzle (caster + 1.35 up, ~1.0 forward), +Z = travel. Straight line, range 18 blocks (preview speed 36 m/s ≈ 0.5 s). **Pierces: do NOT stop it on enemy contact.** Stop it at range end or on a block hit (SCHEMA §5: local layers clear, world helix corridor finishes ≈ 0.85 s). The head grows in during its first 0.11 s. |
| crimson_spiral_end | impact | projectile stop | range end = air hit (surfaceNormal = −forward, incoming = forward); block = hitPoint/normal. Strands unravel + embers. |
| crimson_spiral_pierce | impact | once per enemy, when the head reaches it | hitPoint = enemy centre on the line (≈ chest), **air convention** (surfaceNormal = −travel, incoming = travel). Visual hits at **+0.00 / +0.12 / +0.24 s** → apply the 3 damage ticks at those offsets; the 3rd is the big ember burst. |
| crimson_spiral_burn | attached | the mod, on each enemy at pierce + 0.24 s (3rd hit) | follows the burning enemy (feet, facing). Burst ignition at 0, loop flames; duration 3.0 s = burn length. Stop/remove it early if the burn is cleansed (onStop tails ≤ 0.4 s). |

## Features
Client-supported only (client_compat fix: 0 changes; check OK). High impact: meshParticle (helix strands, sigils, line circles, arcs), bloom, attachedFollow (cast, burn). Also rateOverDistance + worldSpace (helix corridor segments tile 1 per 3.2 m), stopTail, flipbook, stretchedBillboard, noise.
Held head layers: ONE particle each (life 60 s, projectile duration 60 so the t = 0 bursts happen once) — cleared on stop.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
