# IceShard (얼음 조각, 기초 328, id ice_shard, 물/얼음) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| ice_shard_cast | attached (caster feet + facing) | cast, t = 0 | ≈0.6 s, one-shot. 0–0.12 s: frost gathers into the right palm (caster-local [0.3, 1.38, 0.5]); tiny hex snowflake sigil + hex frame (vertical, facing forward) spin in front of the palm; feet mini hex circle + frost decal. Muzzle snap (ring, star, spray, flakes, snow puff, navy mist, chips) at **0.12 s and 0.27 s** = the default shot times. No stop signal needed. |
| ice_shard | projectile (salvo, 2 shots) | salvo: shot k at fireStart + k·interval (default 0.12 / 0.27 s) | Slots ≈ 0.16 m ahead of the palm (slightly up-left / down-right so the two shards don't overlap), aim straight ahead. Speed / range come from the server binding. Shard = faceted crystal mesh (alpha, HDR tint) + additive rim shell + frost trail + spin wisps; world wake (frost line, snow motes, sparkles, glints, navy mist) uses rateOverDistance. Local layers clear on hit. |
| ice_shard_hit | impact | each shard hit | hitPoint / surfaceNormal / incomingDirection (air / entity hit = −incoming). Flash, frost ring + line shock ring, frost decal (0.85 m, 0.9 s), ice spike cluster pops out and crumbles (0.62 s), crystal chips + navy chips, sparks, snow puff, snow, navy mist. Same system for ground, wall and air. |
| ice_shard_frostbite | impact | **only when both shards hit the same enemy** (the tick the slow 3 s is applied) | hitPoint = that enemy's feet, surfaceNormal = up, incomingDirection = shard direction. Frost decal 1.7 m + hex circle + frost rings, crystal crown snaps up around the feet, glints, rising snow swirl (orbital), shatters at ≈0.95 s, fades by ≈1.6 s. One-shot (not looped for 3 s; the slow itself is shown by the game's debuff icon). |

## Gameplay timing
- Damage per shard at its own hit tick (impact flash peaks within 0.03 s).
- Double hit: when shard 2 hits an enemy that shard 1 already hit in this cast, apply slow 3 s and start `ice_shard_frostbite` at that enemy's feet in the same tick.
- If the server fires at a different cadence, set fireInterval (0.001–0.4 s); the cast's muzzle snaps are authored for 0.12 / 0.27 s (change F0 / DT at the top of iceshard.js to re-time them).

## Features
Only client-supported features. High impact: meshParticle (faceted crystal shards, spikes, chips, crown, sigil frame, line-work rings, trails), bloom, attachedFollow (cast), salvoSequence (2 shots), orbitalVelocity (frostbite snow swirl / streaks only).
client_compat fix: only the null 1.16 `salvo.aimMode` field removed (no visual change); check OK. Known toolkit quirk: after that removal `validate.py` reports a salvo schema mismatch (aimMode is required by the 1.21 schema); the pre-fix vfx.json validates OK. Same as SparkFlick / HydroBurst.
Texture memory: 14 unique textures ≈ 1.44 MiB RGBA (≤ 256 px).


## PortableVFX client compatibility (tools/client_compat.py fix)

- salvo.aimMode removed (shot count fixed at 2, flight speed/range come from the server binding)
