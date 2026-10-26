# HydroBurst (수류 탄환, 중급 341, 물) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| channel | attached (caster feet + facing) | cast, t = 0 | 1.5 s channel: feet water circle, hand sigil (vertical, 0.62 m ahead at hand height), spinning water barrel, pressure rings, muzzle spray/mist. Held layers have life 1.58 s + onStop tail 0.25 s: if the channel is interrupted, **stop** the system → everything fades within 0.25 s |
| bolt | projectile (salvo, 8 shots) | salvo: shot k at fireStart + k·interval (default 0.20 + k·0.17 s → last shot at 1.39 s) | P = caster + M·slot (muzzle, ≈ 0.95 m ahead of the hands), forward = facing. The server drives speed/range (demo flight ≈ 0.24 s for 6 m). Local layers clear on hit, world trail layers finish |
| splash | impact | each bolt hit | hitPoint / surfaceNormal / incomingDirection (air hit = −incoming). Ground, wall and air (entity) all use the same system |

## Gameplay timing
- Damage + small knockback per bolt at its hit (the splash appears at the hit tick; the flash peaks within 0.03 s).
- Slow on the caster during the channel: 0 … 1.5 s (the channel's own visuals cover exactly that window).
- If the server fires more/less often, change `fireInterval` (0.05–0.4 s); the shot count is fixed at 8 (client has no salvo count input).

## Features
Only client-supported features (client_compat fix: only the null 1.16 salvo fields removed — no visual change; check OK).
High impact: meshParticle (line-work circles, vertical sigil, coil barrel, water shell, crown), bloom, attachedFollow (channel), salvoSequence (8 shots), orbitalVelocity (feet motes only).
Texture memory: 16 unique textures ≈ 2.6 MiB RGBA (the check's WARN counts shared textures once per material).


## PortableVFX client compatibility (tools/client_compat.py fix)

- salvo.aimMode removed (shot count fixed at 8, flight speed/range come from the server binding)
