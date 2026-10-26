# AirCannon (공기 포탄, 중급 354, id air_cannon) — converter notes

Gameplay: 3 compressed air shells fired in a row; each hit pushes the target a little; all 3 hit → gravity 3 s (the game's debuff icon shows the state).

## Systems and runtime signals
| system | role | started by the mod | stopped by the mod |
|---|---|---|---|
| cast | attached (caster feet + facing) | cast t = 0 | — (one-shot ≈1.55 s). Wind barrel at the right hand (muzzle = salvo slot [0.3, 1.38, 0.95]) held until t ≈ 1.25 s, release ring at 1.28 s |
| projectile | projectile (+Z = travel) | salvo shot k at fireStart + k·fireInterval (defaults 0.4 s / 0.25 s → 0.40, 0.65, 0.90 s), new instance per shot at caster + M·slot | on hit (SCHEMA §5: clear local, stop world emission). Muzzle ring / recoil puff are world-space layers of this system (stay at the muzzle) |
| impact | impact | each shell hit: hitPoint, surfaceNormal, incomingDirection (air = entity hit → −incoming) | — (≈0.6 s). Apply the small push here |
| heavy | impact | ONLY when all 3 shells of one cast hit the same target: at that target's feet, surfaceNormal = world up, incoming = shot direction, right after the 3rd hit | — (life 3.0 s = gravity duration). If the gravity is shorter/longer, the visual still ends at 3 s |

Salvo: `count` 3 (fixed), shots all from the same slot with aim [0,0] (caster forward); the mod aims each shot (look direction). The salvo `stop` lists are empty (the barrel is one held cast layer, not per-slot idles).
The cast barrel is timed for the default fireStart/interval (last shot at 0.9 s); with a much slower interval the barrel fades at ≈1.25–1.45 s.

## Features
Only client-supported features (client_compat check OK). High impact: meshParticle (barrel line work, muzzle rings, helix wake, ground circles), bloom, attachedFollow (cast), salvoSequence. No orbitalVelocity. rateOverDistance only on world layers (wake rings, tail, motes).


## PortableVFX client compatibility (tools/client_compat.py fix)

- salvo.aimMode removed (shot count fixed at 3, flight speed/range come from the server binding)

(export artifact only: aimMode "slot" is the default, no visual change.)

Texture memory: 12 unique textures = 2.13 MiB RGBA (the check WARN 5.1 MiB counts shared textures once per material).
