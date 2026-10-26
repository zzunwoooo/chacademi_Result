# AlchemyPreheat (연금로 예열, 생활 #374, id alchemy_preheat) — converter notes

## System and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| alchemy_preheat | attached | cast (t = 0), once | anchor = caster feet, basis = caster horizontal facing (+Z forward). duration 4.5 s, loop false, no stop signal needed; everything is gone by ≈4.4 s. Cupped-hands point = (0, 1.12, 0.38); ember mark point = (0.34, 0.98, 0.16) (right hand). |

Timeline: 0.00 spark at the hands + outer ring draws on with an ignition runner sweeping around it → 0.15–0.65 triangle traced by a runner, inner ring, rune band of alchemical glyphs, sun-furnace sigil, sulfur / mercury / salt glyph seats pop at the triangle vertices → 0.3–1.2 a small flame ring cupped between the hands grows (seat ring + sigil, flicker crown, two tilted counter-rotating rings, core flame), warm sparks/motes drawn in with growing staged bursts; the circle brightens, a low flame crown lights around the rim (r 1.35 m), small flames/tongues/motes rise, a few charcoal flakes + soot wisps as dark accents → **1.25 s "ready"**: small gold sparkle + chime ring at the hands, circle/triangle/glyph flare copies, soft ground ring wave, sparkles lift → 1.3–1.8 circle and flames fade → 1.35–4.4 tiny ember mark (fire-triangle glyph + glow + ember ticks every 0.3 s) by the right hand, winks out at 4.15.

**Recommended mod timing:** grant the buff (next alchemy craft within 10 min: −30 % XP cost) at cast +1.25 s (the "ready" sparkle), or at t = 0 if the cast is instant — the visual reads fine either way. The 10-minute buff indicator is the server UI; this VFX is only the cast + a 3 s ember mark. If the caster cancels/moves, nothing needs to be stopped (local layers follow the feet; rising motes/flames are world-space).

Rotation: circle layers rotate at fixed rates (outer 0.35, runes −0.5, triangle/glyphs 0.22, inner −0.7, sigil 0.55 rad/s); the 1.25 s flare copies start with rot3 = ω·(1.25 − born) so they overlay their layer exactly. Not a combat effect: no range ring (no enemies are affected).

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (ground circle line work, rune band, glyph seats, runners, flame crowns, hand rings, ember glyph), **bloom**, **attachedFollow**, **orbitalVelocity** (only the in-drawn motes at the hands, H03 InMotes).
Others: flipbook + flipbookCycles (flickering crowns), worldSpace, stretchedBillboard, trails, noise, drag, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.
Texture memory: 17 unique textures (≤ 512 px).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
