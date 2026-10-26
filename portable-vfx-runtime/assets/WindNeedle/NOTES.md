# WindNeedle (바람 송곳, 기초 329, id wind_needle, 바람) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| wind_needle_cast | attached | cast (t = 0) | caster feet + facing. Fingertip gather + needle-star sigil 0 … 0.12 s, **snap/launch at 0.12 s** → spawn the projectile then. Ends by itself (0.3 s + lifetimes ≈ 0.75 s). |
| wind_needle | projectile | the mod at cast + 0.12 s | P = needle tip, start = caster frame [0.30, 1.42, 0.72] (right fingertip), +Z = travel. Straight line, range 24 blocks, very fast (preview 60 m/s ≈ 0.39 s; any speed works, the trail is emitted by distance). **Pierces: do NOT stop it on enemy contact.** Stop it at range end or on a block hit (SCHEMA §5: local layers clear, the world streak/coils finish ≈ 0.6 s). Needle grows in during its first 0.06 s. |
| wind_needle_pierce | impact | once per enemy, when the tip reaches it | hitPoint = enemy centre on the line (≈ chest), **air convention** (surfaceNormal = −travel, incoming = travel). Single hit at **+0.00 s** → apply the damage at that tick. |
| wind_needle_end | impact | projectile stop | range end = air hit (surfaceNormal = −forward, incoming = forward): the needle unravels into 3 strands and fades. Block = hitPoint/normal. |

## Features
Client-supported only. High impact: meshParticle (needle cross planes, sheath/coil helix strands, line sigil, rings), bloom, attachedFollow (cast). No orbitalVelocity. Also rateOverDistance + worldSpace (streak segments 4 m long every 1 m, coils every 1.6 m), flipbook, stretchedBillboard, noise, rotation3D.
Held needle layers: ONE particle each (life 60 s, projectile duration 60 so the t = 0 bursts happen once) — cleared on stop.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
