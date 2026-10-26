# SparkFlick (불씨 튕기기, 기초 325, id spark_flick, 화염) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| spark_flick_cast | attached (caster feet + facing) | cast, t = 0 | ≈0.55 s total. 0–0.1 s: heat gathers into the right fingertip (caster-local [0.3, 1.38, 0.5]) with a tiny hexagram sigil. **0.1 s = the flick**: snap ring + star + spark spray + flick arcs at the hand, feet mini circle, and a ground fan line that shows the real spread (6 m, ±17°) drawing outward with the embers. No stop signal needed (all one-shot bursts). |
| spark_flick | projectile (salvo, 3 shots) | salvo: shot k at fireStart + k·interval (default 0.10 / 0.13 / 0.16 s) | Slots ≈ 0.12 m ahead of the fingertip; aim yaw −0.3 / 0 / +0.3 rad (left, centre, right), pitch 0. Speed / range (6 m) come from the server binding. Each ember = white-hot core + spinning comet tail + flames; world wake (heat line, sparks, embers, ash) uses rateOverDistance. Local layers clear on hit. |
| spark_flick_hit | impact | each ember hit | hitPoint / surfaceNormal / incomingDirection (air / entity hit = −incoming). Pop flash, fire rings, sparks, flame tongues, charcoal flakes, small scorch (0.5 m), ash. Same system for ground, wall and air. |

## Gameplay timing
- Damage per ember at its own hit tick (the pop peaks within 0.03 s). Close targets inside the fan are hit by several embers (one impact per ember).
- If the server fires all three on the same tick, set fireInterval to its minimum (0.001); the visuals still read as a fan.
- If the real fan angle / range differs, change shots[k].aim yaw and the C07/C08 fan mesh (sfFanEdge/sfFanMid, authored in metres for 6 m / ±0.3 rad).

## Features
Only client-supported features. client_compat fix: only the null 1.16 `salvo.aimMode` field removed (no visual change); check OK.
High impact: meshParticle (fingertip sigil, snap rings, flick arcs, ground fan line work, comet tails), bloom, attachedFollow (cast), salvoSequence (3 shots). orbitalVelocity is not used.


## PortableVFX client compatibility (tools/client_compat.py fix)

- salvo.aimMode removed (shot count fixed at 3, flight speed/range come from the server binding)
