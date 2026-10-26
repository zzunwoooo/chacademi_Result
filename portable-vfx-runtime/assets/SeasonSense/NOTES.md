# SeasonSense (계절 예보, 생활 #381, id season_sense, 바람) — converter notes

## System and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| season_sense | attached | cast (t = 0), once | anchor = caster feet, basis = caster horizontal facing (+Z forward). duration 3.5 s, loop false, no stop signal needed; everything is gone by ≈3.4 s. |

Timeline: 0.00 mint wind circle (r 1.0 m) draws on: outer rings + 48 month ticks with a wind-stroke runner, wind/season rune band, octagram {8/3}, month-dial sigil with a 3-arm wind spiral, rotating ground swirl, slate shadow base → 0.30–0.51 four season glyph seats pop (sprout / sun / leaf / snowflake, fixed, each in its own colour) and a mint dial hand starts sweeping the wheel at constant speed → the hand passes glyph i at **0.50 / 0.85 / 1.20 / 1.55 s**: glyph ping + seat ring + glyph flare and that season's breeze ribbon spirals up the body carrying its motes: spring petals (pink, tumbling flipbook) → summer glints (gold) → autumn leaves (amber/red, tumbling flipbook) → winter snowflakes (white-blue). Mint wind ribbons, motes with trails and wind streaks run underneath the whole time; slate-indigo leaf silhouettes with mint rims are the dark accents → **1.80–2.20 gather**: converging wind helix + inward spiral strokes + swirl disc (flat and facing) above the head (y 2.3), motes/petals/leaves/snow pulled inward → **2.22 disperse**: small star, two head ripples, outward spiral, four-season scatter drifting down, ground circle/sigil/glyph flares + ground ring → 2.3–2.55 circle fades, motes fade by ≈3.4 s.

Held circle layers: one particle each, rotating at fixed ω; flare copies at 2.26–2.28 start with rot3 = ω·(tFlare − tBorn) so they line up. Dial hand: rot3 = GA(0) + ω·(0.3 − 0.5), ω = (π/2)/0.35 rad/s.

**Recommended mod timing:** show the season UI (current season, time to next season, upcoming whirlwind event) at cast +2.22 s (the disperse), or immediately if the cast is instant. The information itself is the server's UI; this VFX is only the cast. Not a combat effect: no range ring.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (circle line work, rune band, glyph seats/glyphs, dial hand, body helix ribbons, gather helix, head spirals), **bloom**, **attachedFollow**, **orbitalVelocity** (rising season motes, gather pull-in).
Others: radialVelocity (gather pull-in), flipbook + flipbookCycles (tumbling petals/leaves, stroke wipes), stretchedBillboard, trails, drag, gravity (scatter drift), linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.
Texture memory: 18 unique textures (≤ 512 px), 2.68 MiB RGBA unique (client WARN counts per material: 8.4 MiB — known toolkit issue).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
