# BlessedRibbon (축복의 리본, 생활 #386, id blessed_ribbon) — converter notes

## System and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| blessed_ribbon | attached | cast (t = 0), once | anchor = caster feet, basis = caster horizontal facing (+Z forward, +X right). duration 2.9 s, loop false, no stop signal; everything is gone by ≈2.8 s. Gift point (right hand holding the item) = (0.32, 0.95, 0.36) caster frame; the effect assumes an invisible ≈0.2 m gift box centred there (the preview's blue box is a demo-only prop, scenario "선물 없이" hides it). |

Timeline: 0.00 hand spark, light circle (r 0.34 m, 0.17 m under the gift) draws on: line rings, octagram, heart/star/bow rune band, bow sigil → 0.05–0.75 two golden-pink satin strands spiral in (above / below), motes and sparkles swirl in, faint gold box outline → 0.38–0.81 the two ribbon bands wrap the box (cross wrap, wipe sheets) and hand off to static satin copies → **0.92 bow pops on top** (elastic overshoot) + star pop, chime rings, rays, hearts / plum hearts / stars / confetti curls; circle flares → 0.97–2.3 ribbons + bow hold with two tilted halo rings and orbiting beads, twinkles drift up → 2.0–2.35 ribbons fade into sparkles → 1.3–2.75 faint warm glow at the hand, last wink at 2.58.

**Recommended mod timing:** apply the "blessed" tag (+20 % affinity on that gift item) at cast +0.92 s (bow pop); applying at t = 0 also reads fine. If the player switches items mid-cast nothing needs to stop (local layers follow the feet/facing; drifting sparkles are world-space). Life spell: no range ring, no combat elements.

Hand-offs: wrap grow copies (life 0.32 at 0.38 / 0.50) end exactly where the hold copies start (0.69 / 0.81), same mesh, no rotation.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (circle line work, rune band, satin strands, wrap bands, bow, halo rings, box outline), **bloom**, **attachedFollow**, **orbitalVelocity** (in-swirling motes and halo beads only).
Others: flipbook (2x8 satin wipes), worldSpace, stretchedBillboard, trails, noise, drag, gravity, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.
Texture memory: 17 unique textures (≤ 512 px).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
