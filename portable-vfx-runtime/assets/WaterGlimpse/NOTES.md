# WaterGlimpse (물밑 엿보기, 생활 #377, id water_glimpse) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| water_glimpse_cast | attached | cast (t = 0), once | caster feet + horizontal facing. Small water lens sigil (vertical, facing forward) at (0.13, 1.56, 0.34) = in front of the right eye. duration 1.45 s, no stop signal. |
| water_glimpse | static | when the aimed water point is known (demo: cast + 0.35 s), once | **P = the water-surface point being looked at (top of the water block, y = surface), M = caster horizontal facing.** Local y = 0 is the water surface; the depth gauge, fish, caustics and bubbles live below it (down to y ≈ −1.3). duration 4.8 s, no stop signal; everything is gone by ≈ 4.8 s. |

Timeline (static t): 0.00 plip + 3 ripples, lens line work draws on (outer ring + runner, inner reticle, rune band, sigil; r 1.6 m) → 0.32 a glowing drop sinks the plumb line at 1.4 m/s; depth rings at −0.3/−0.6/−0.9/−1.2 m light one by one (flash at 0.53/0.75/0.96/1.18) and a bottom ping at 1.18 → **0.38–0.86 width wave runs to the rim + rim flash at 0.86** → 0.6–4.2 six cute fish silhouettes (top-down quads, 2×2 tail-swish flipbook, 3 sizes, deeper = dimmer) circle under the lens, bubbles rise, light shafts, caustic nets, surface glints, rim motes, slow ripples → 4.1–4.75 lens shrinks and fades, closing ripple + sparkles.

**Recommended mod timing:** open the info panel (depth / width / biome / fish grades) at static +0.86 s (rim flash = "measured"), or immediately — the visual reads either way. The panel itself is UI. The depth rings are a fixed gauge (not scaled to the real depth); the lens radius is fixed (1.6 m, no scale input).
If the player looks at a shallow 1-block pond, the under-water layers simply render inside the water/ground (fine in-game since water is translucent and the ground hides them).

Fish layers F00–F05: one particle each, local shape offset on its swim circle, `orbitalY` = swim rate and `rotationOverLifetime.y` = the same rate so the fish stays tangent to its path (rot3.y = start angle ± π/2). Not a combat effect: no range ring.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (lens line work, rune band, depth rings, plumb, light shafts, fish quads, hand lens), **bloom**, **attachedFollow** (cast), **orbitalVelocity** (fish, rim motes, in-drawn drops at the hand).
Others: flipbook + flipbookCycles (fish tail swish, rim runner wipe), worldSpace (cast drips), stretchedBillboard, trails, noise, drag, gravity, linearVelocity, radialVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.
Texture memory: 14 unique textures (≤ 512 px) = 1.99 MiB RGBA.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
