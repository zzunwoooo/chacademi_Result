# FlashBlink (섬광 도약, flash_blink) — converter notes

All four systems start at the SAME tick: the teleport moment (t = 0), after the server has validated the blink (not on a broom).

| system | role | placement | notes |
|---|---|---|---|
| depart | static | P = departure feet, basis = caster horizontal facing (server default) | sigil snap-open 0–0.1 s, starburst peak ≈ 0.05 s, shock ring reaches r = 3 m at ≈ 0.16 s, `D05 BlindEdge` = the TRUE blind radius (mesh ring, r = 3.0 m at scale 1) held to ≈ 1.35 s. ~1.6 s total. |
| streak | link | source = departure feet + (0, 1.0, 0), target = arrival feet + (0, 1.0, 0) | path-follow light streak: head reaches the target in ≈ 0.09 s, trails drain into it; afterimage beads pop source → target. Target can be fixed after the blink (no need to update every frame). |
| arrive | attached | caster (at the new position) | foot ring, falling light feathers, shimmer. ~1.8 s. |
| blind | attached | each blinded enemy (inside r 3 at t = 0), feet anchor | small eye glare at y = 1.62 m (≈ 0.7 s); `X04 Linger` emits a faint glare until the system's duration 3.0 s = blind time. Stop it early if the blind is cleansed. The debuff icon is the game's own. |

- Blind radius: `D05 BlindEdge` is authored for r = 3 m. No scale input (fixed radius); if the config radius changes, scale `depart` uniformly by radius / 3 (the whole departure flash scales with it).
- Blink distance: the link uses the real two points, so a shortened blink (wall in the way) still lines up.
- features.required includes meshParticle, bloom, orbitalVelocity, attachedFollow, pathFollow, linkTarget, trails.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
