# SkyDive (천공 강하, 상급 362, id sky_dive) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| `launch` | static | cast t = 0 at the caster's feet (basis = caster horizontal facing) | launch circle + ground ring burst + spiral updraft column (7 m) + dust/chips. Ends by itself (~1.3 s) |
| `rise` | attached | cast t = 0 (caster feet, follows the caster) | wind cocoon + vertical speed lines + sonic rings during the 7 m rise; apex flare at instance t ≈ 0.42–0.5 s. Has rate layers: **stop it when the dive starts** (stop signal, onStop 0.1 s) |
| `dive` | attached | when the dive starts (demo +0.58 s) | streamlined wind cone + world speed lines/sonic rings. **Loops; the mod stops it at landing** (stop + onStop 0.05 s). Forward = caster horizontal facing; the cone is baked pitched 32° down (dive line 7 m drop over ~12 m) |
| `mark` | static | cast t = 0 at the aim point (landing point, ground) | true landing range r 3 (line work, contracting rings) during the flight. **Stop at landing** (onStop 0.1 s); otherwise ends at ~1.4 s by itself |
| `land` | impact | at landing: hitPoint = landing point, up = surfaceNormal, incomingDirection = dive direction | **damage at instance t = 0**, r 3 shock (first wave stops exactly at r 3), down-slam funnel, crack, dust, shards. Held 3 s "heavy air" range ring (r 3) + pulses + sinking motes = gravity debuff duration; fades at 3.0 s |

## Timing (demo)
cast 0 → rise 0–0.52 s (7 m) → hang → dive 0.58–0.95 s → landing / damage / gravity 3 s at +0.95 s. The mod's real flight times drive the start of `dive` and `land`; the VFX has no fixed timing between them.
Radius is fixed at 3 m in the VFX (range rings sized for r 3). If the server radius differs, ask for a rebuild.

## Features
Only client-supported features. High impact: meshParticle (circles, columns, cone, funnel, rings), bloom, orbitalVelocity, **attachedFollow** (rise + dive follow the caster). Also trails, rateOverDistance (world layers only), flipbook, stretchedBillboard, stopTail, gravity, drag, noise.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
