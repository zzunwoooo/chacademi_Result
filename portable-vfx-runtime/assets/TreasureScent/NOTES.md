# TreasureScent (보물 냄새, 생활 #379, id treasure_scent) — converter notes

## System and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| treasure_scent | attached | cast (t = 0), once | anchor = caster feet, basis = caster horizontal facing (+Z forward). duration 5.0 s, loop false, no stop signal needed; everything is gone by ≈5.0 s. |

Timeline: 0.00 sniff glint + in-drawn scent streaks at the nose (0, 1.62, 0.2); ground treasure-compass circle (r 1.15 m) draws on: gold outer ring with a sparkle runner, sea-blue treasure-glyph rune band, blue octagram {8/3}, four glyph seats (X-mark / coin / gem / chest) that pop at 0.42–0.66, gold compass-rose sigil, rotating water caustics, navy shadow base → 0.2–1.45 the compass needle (gold north / blue south) spins fast (16 rad/s) → slows (8 → 3) → wobbles → **settles pointing forward (+Z) at 1.45 s**; five gold/blue sparkle ribbons spiral up the body (staggered 0.2–1.1), glints, trail motes, spinning coins, gems rise and orbit in growing staged bursts, dark navy gem shards as accents → **1.45 "found it"**: ping at the needle tip, gold + blue sparkle trail runs forward ~2 m along the ground → **1.60 "lucky pop"** (buff moment): star flash at the chest, coin + gem fountain, gold/blue glint bursts, circle/octagram/glyph/rose flare copies, two ring waves → 1.7–2.3 circle fades → 1.7–5.0 two tiny glints (gold, blue) orbit the waist with short trails + sparse gold dust, wink at 4.75.

Needle: one particle per spin segment; each copy starts with rot3 = accumulated angle so the rotation is continuous (segments 0.2–0.75 ω16, 0.75–1.05 ω8, 1.05–1.25 ω3, 1.25–1.45 ω chosen to land exactly on 6π = forward); held copy 1.45–2.3 at rot3 0. Circle flare copies at 1.62–1.64 start with rot3 = ω·(tFlare − tBorn).

**Recommended mod timing:** grant the 10-minute buff (fishing treasure-chest chance ×2) at cast +1.6 s (the lucky pop), or at t = 0 if the cast is instant. The 10-minute indicator is the server's buff UI; this VFX is the cast + ~3 s of orbit glints. Not a combat effect: no range ring.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (circle line work, rune band, glyph seats, needle, body helix ribbons, ground scent trail), **bloom**, **attachedFollow**, **orbitalVelocity** (rising glints/motes/coins/gems and the lingering orbit glints).
Others: flipbook + flipbookCycles (spinning coin), worldSpace, stretchedBillboard, trails, drag, gravity (coin fountain), linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.
Texture memory: 19 unique textures (≤ 512 px), 3.04 MiB RGBA unique (client WARN counts per material: 4.7 MiB).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
