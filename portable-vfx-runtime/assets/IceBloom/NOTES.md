# IceBloom (얼음 꽃, 중급 343, ice_bloom) — converter notes

## Systems
| system | role | started by | notes |
|---|---|---|---|
| ice_bloom | static | cast (t = 0) | P = aim point on the ground, M = caster horizontal facing (burst line 0 = +Z = facing, then every 60°). Seed lands 0.16 s, flower grows 0.18–1.35 s, accelerating pulses 1.1/1.28/1.42 s, true-length telegraph lines fade in from 0.92 s. **Burst at t = 1.5 s**. Ends by itself at ~3.1 s (duration 2.6) |
| ice_bloom_hit | impact | the mod, once per enemy hit by a shard line | hitPoint = enemy feet, surfaceNormal = up, incomingDirection = that line's direction |

## Timing for the server
- Shard lines: 6 directions, length **6 m** (R), race speed **20 m/s** (VS) from the centre → a shard line reaches distance d at **t = 1.5 + d / 20 s** (reaches the end at 1.8 s). Hit an enemy at that moment, or hit all at 1.5 s if the server uses an instant line check (the visual still reads).
- Spike clusters pop on each line at d = 1.5 / 2.8 / 4.1 / 5.35 m and shatter ~0.6 s later.
- If the server's line length / burst delay differ, change `R`, `T0`, `VS` at the top of icebloom.js and rebuild.
- No runtime inputs (no scale / hold). Flower stages are 3 copies (bud → half → open) sharing rotLife 0.35 rad/s with rot3 = 0.35·t_start so they line up.

## Features
Only client-supported features (client_compat fix = 0 changes, check OK). High impact: **meshParticle** (petals, crystals, spikes, hex circle line work, burst lines), **bloom**, **orbitalVelocity** (mist, motes). No attachedFollow.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
