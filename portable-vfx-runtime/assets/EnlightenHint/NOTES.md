# EnlightenHint (깨달음의 빛, enlighten_hint, 빛 생활) — converter notes

## System (contract: spells12.md E1)
| system | role | length | started by | notes |
|---|---|---|---|---|
| cast | attached | duration 2.0 s, loop false | cast t = 0, once | anchor = caster feet, basis = caster horizontal facing (+Z forward). Nothing to stop; everything is gone by ≈1.98 s (longest particle ends ≤ 2.0 s + 0.3 s world motes). |

## Timeline (cast-relative)
- 0.00 small gold light circle (r 0.7 m) draws on at the feet (runner, octagram, rune band, book sigil); gold + pale-cyan motes spiral up to the book point (0, 2.42, −0.04).
- 0.15–0.55 a small book of light appears closed (navy cover, glowing gold pages, 0.3 m pages) and opens into a V; a rune ring under it draws on (0.30).
- 0.62 / 0.84 / 1.04 three pages flip over (leafing through); glowing text lines and gold/cyan sparkles lift off the pages.
- **1.12 hint found**: page flare, small flash, gold + cyan rays, two thin ring waves, ring flare, a star-key glyph pops out of the page and rises.
- 1.45 the glyph dives down into the head (≈4.2 m/s, world-down); **1.64 absorb**: head ring + 16 short rays flare, pale-cyan wave, star shower, ground circle flare.
- 1.70–1.95 the book shrinks and dissolves into motes.

**Recommended mod timing:** show the discovery-hint text at **cast + 1.64 s** (absorb), or at t = 0 — the visual reads either way. The hint text is server/UI, not part of this VFX. If the cast is refused (no hint left / cooldown) do not start the system.

## Held layers / hand-offs
Book pages: opening layers (rotation about local X, 0.15–0.55) hand off to held layers at 0.55 with the same final angle (front page −0.5 rad, back page +0.1 rad); the flip layer (0.3 s) rotates a page from the front pose to the back pose. Ground circle layers: outer 0.4, rune −0.55, sigil −0.5 rad/s; ground flare at 1.68 starts with rot3 = 0.4·1.68. Book ring 0.9 rad/s (born 0.30), its flare at 1.12 starts with rot3 = 0.9·0.82. Not a combat effect: no range ring.

## Features
meshParticle, bloom, attachedFollow, orbitalVelocity (rising motes), radialVelocity, flipbook (runner), stretchedBillboard, trails, noise, gravity, drag, linearVelocity, worldSpace, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping — all client-supported.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
