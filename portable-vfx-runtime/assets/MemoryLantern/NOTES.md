# MemoryLantern (기억의 등불, memory_lantern, 빛 생활) — converter notes

## Systems (contract: spells12.md E2)
| system | role | length | started by | inputs / notes |
|---|---|---|---|---|
| lantern | attached | duration 2.0 s, loop false | cast t = 0 | caster feet + horizontal facing. Lantern in the right hand at local (0.36, 1.02, 0.30); lights 0–0.35, flares + sends a wisp up at 0.40, fades 1.70–1.95. Small circle at the feet (r 0.6). |
| beam | static | duration 10.0 s, loop false | cast t = 0 | P = the last death point (feet / ground block top), identity basis (world up). Pillar 30 m tall (half-width 0.75 m main, 2.2 m soft outer, 0.14 m core) shoots up 0–0.35 s with a 45 m/s launch streak; rings rise every 0.75 s (0.40 … 8.65 s); motes / sparks / glints use rate; ghost lantern at y 1.5; everything fades 9.2–9.95 s. Ground circle r 1.4 m (decorative, no area effect). |
| guide | link | duration 2.0 s, loop false | cast t = 0 | source = caster chest/hand every frame (demo: feet + (0.25, 1.15, 0.2)), target = death point + (0, 1.2, 0). Gold / cyan motes stream along a bowed path (linger near the caster, then race to the target) 0.30–1.71 s; chevrons in the link's +Z (toward the death point) drift forward from the caster 0.30–1.70 s; glint at the target end 0.9–1.7 s. Works for any distance (path layers use the live linkLength). |

All three start together at cast t = 0. If the death point is in another world / missing, the cast is refused (start nothing). The direction / distance text is server UI.
The beam is meant to be seen from far away: please don't distance-cull this static system below ~64 blocks if the renderer has a cull option.

## Bursts
Every burst (including every cycle) is < its system's duration (checked with burstchk.py). No held layer needs a rotation hand-off (one particle per held layer for the whole system).

## Features
meshParticle, bloom, attachedFollow, linkTarget, pathFollow, orbitalVelocity (moths, motes), radialVelocity, flipbook (runner), stretchedBillboard, trails, noise, drag, linearVelocity, worldSpace, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping — all client-supported.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
