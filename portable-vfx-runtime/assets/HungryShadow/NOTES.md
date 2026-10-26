# HungryShadow (굶주린 그림자 #177, hungry_shadow, 어둠 패시브) — converter notes

Rebuilt 2026-10-25 at higher quality. **Same systems, roles and timing** as the bound pack (`claude:hungryshadow/maw`, `claude:hungryshadow/devour`).

## Systems (unchanged contract — spell-catalog phases)
| system | role | trigger | link ends | timing |
|---|---|---|---|---|
| maw | link | cast (the cast key only plays the look), 24 ticks | source = caster-hand, target = event-point (aim) | thread reaches the point at +0.20 s, maw opens, **snaps at +0.62 s**, gone by ~1.1 s (emission 0.8 s) |
| devour | link | external:devour (each accepted kill), 18 ticks | source = slain monster feet (fixed), target = caster feet (every frame) | ground maw bites at the corpse at **+0.24 s**, 3 soul shards fly and **arrive at the caster at +0.62 s**, absorb ring until ~0.9 s (emission 0.7 s) |

Gameplay (mod side, unchanged): +1 hunger (food 2) per accepted kill while registered; never skipped for visual reasons. Overlapping kills = overlapping instances.
No runtime inputs. Link length any (shards and the thread use path progress, so their timing does not depend on distance).

## What changed visually
- Old: one flat ink mouth decal + 3 small glints — thin, almost invisible in the preview.
- maw: hand spark (mini bite + crescents + gather streaks + ink) → white-core thread with violet haze, double-helix fibres, ink wisps and a star head with trail → at the point a shadow maw (8-frame ink jaw flipbook with crescent fangs, violet rim) opens inside a spinning fang-ring line, glow and ink mist, snaps shut with a flash, line shock ring, ink splash crown, spinning arcs, flung crescents and motes.
- devour: ink pool + fang circle + rune band on the ground at the corpse, rising violet streaks, the maw rises and bites (flash, line shock, ink shards, arcs), then 3 ink crescent soul shards (violet rim, trails, glow) arc to the caster with a helix stream, motes and an ink trail; at the feet a contracting absorb ring, a small circle flare, glow and spinning arcs.

## Features
Client-supported only (client_compat fix = 0 changes, check OK). meshParticle (line circles, fang rings, arcs), bloom, linkTarget + pathFollow, flipbook, trails, stretchedBillboard, drag, gravity, radialVelocity, orbitalVelocity, rotation3D.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
