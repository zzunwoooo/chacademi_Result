# EbbMark (썰물, #360, 물 상급) — converter notes

## Systems and runtime signals (t_hit = the moment the bolt hits)
| system | role | started by | notes |
|---|---|---|---|
| cast | attached (caster) | cast t = 0 | water gathers at the right hand; release flash at 0.22 s → launch the bolt then |
| bolt | projectile | 0.22 s | water dart with helix trail. On hit: local layers clear, world trail finishes, spawn `markHit` |
| markHit | impact | bolt hit | splash on the target (air hit: up = −incoming). Also plays on a miss (ground / wall), then nothing else starts |
| anchor | static | t_hit | P = the TARGET's feet at hit time (ground under the target), identity basis. Countdown is baked in: 3.0 s fixed (12 segments drain, one every 0.25 s; a bright head goes round once; pips at 1/2/3 s). 2.55–3.0 s anticipation (circle contracts, water drawn in). Ends by itself at 3.4 s |
| brand | attached (target) | t_hit | follows the marked target (feet + facing). Stop it at t_hit + 3.0 (onStop tails ≤ 0.12 s) |
| tether | link | t_hit | source = target chest (feet + 1.0 m) every frame, target = anchor P + (0, 1.0, 0). Thin flowing bead line target → mark. Stop at t_hit + 3.0 |
| tide | link | t_hit + 3.0 | same two ends; the pull current (≈0.3 s emission, particles ≤ 0.34 s). The mod moves the target back to the mark during it (demo: 0.32 s) |
| ebb | impact | target arrives at the mark | hitPoint = anchor P, surfaceNormal = up, incomingDirection = pull direction. **Damage at this moment** (demo: t_hit + 3.32) |

If the target dies / leaves before 3 s: stop brand + tether, let anchor finish (or remove it). Mark radius visual ≈ 1.5 m (decorative; no area damage).

## Held layers
Anchor circle layers are one particle each (life 3.4 − start) — no rotation hand-off needed (one system, fixed length). Countdown segment k fades at (k+1)·0.25 s.

## Features
Only client-supported features (client_compat check OK, fix = 0 changes). High impact: meshParticle (circle line work, countdown segments, swirls, helix),
bloom, orbitalVelocity (drops, motes, brand), attachedFollow (cast, brand), linkTarget + pathFollow (tether, tide), stopTail (brand / tether onStop).
Texture memory: 17 unique textures = 3.24 MiB RGBA (the check's WARN counts shared textures once per material).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
