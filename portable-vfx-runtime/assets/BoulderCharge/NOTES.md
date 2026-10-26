# BoulderCharge (바위 돌진, 중급 #353, id boulder_charge, 대지) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| `boulder_charge` | attached | cast t = 0 (caster feet, forward = facing) | stomp sigil + crack + dust left at the start point (world layers), 20 rock plates (depth-writing meshes) fly in and snap onto the body bottom-up at instance t 0.05–0.37 s (+0.1 s flight), lock-in flash at 0.44 s, then held armor + amber seam shell + motes. **Loops: the mod stops it at charge end** (held plates / seams onStop 0 = removed at once; `boulder_charge_end` takes over with identical plates at the same pose) |
| `boulder_charge_dash` | attached | charge start (demo 0.48 s = armor complete) | local: pressure wedge + bow stroke + bow rings + airflow in front; world: scrape decal, dust walls, rock chips/shards off the sides, foot sparks, streaks along the path (rateOverDistance). W10 `Lane` = one world burst showing the real 6 m hit corridor (±0.6 m). **Loops: stopped at charge end** (onStop 0.03) |
| `boulder_charge_hit` | static | on the first enemy hit, P = ground point of contact (demo: caster feet + 0.55 m forward), basis = charge direction | flash, forward ray cone, vertical shock discs, rock burst (meshes) + shards, ground rings, crack decal, dust wave. Ends by itself (~1.6 s) |
| `boulder_charge_stun` | attached | on the hit enemy, P = enemy feet, 1.0 s (stun duration) | halo of line work + 4 orbiting pebbles + glints at 2.0 m (above a 1.8 m head). The stun debuff itself = the game's icon |
| `boulder_charge_end` | attached | at charge end (hit OR full 6 m), same frame the two loops are stopped | the 20 plates (world space, same pose as held) break off outward/forward and fall, seam release flash, crumbs, dust, embers, landing dust at 0.42 s |

## Timing (demo)
cast 0 → armor complete 0.46 s → charge 0.48 s, 6 m in 0.35 s (≈17 m/s) → hit: stop at 3.6 m (enemy at 4.2 m, knocked back 2.2 m) → end. Miss scenario runs the full 6 m.
Systems have no fixed timing between them: the mod's real charge start/stop and the hit event drive them. If the mod starts the charge earlier than 0.46 s, plates not yet seated are still flying in (fine visually).

## Features
Only client-supported features. High impact: meshParticle (rock plates, sigil line work, wedge, strokes, decals), depthWrite (solid rock plates/chips, alpha material depth:true), bloom, orbitalVelocity (stun pebbles), **attachedFollow** (armor + dash follow the charging caster, stun follows the enemy). Also flipbook, trails, worldSpace + rateOverDistance (world layers only), stretchedBillboard, stopTail, gravity, drag, noise, rotation3D/rotationOverLifetime.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
