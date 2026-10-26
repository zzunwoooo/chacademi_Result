# HarvestBell (수확제의 종, 생활 #385, id harvest_bell, 빛) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| harvest_bell | static | cast (t = 0), once | P = caster feet, basis = caster horizontal facing (server-sent static basis). duration 3.05 s, loop false, no stop signal; everything is gone by ≈3.3 s. |
| reveal | impact | mod, once per crop mob that comes alive (during the 1-minute buff) | hitPoint = crop mob feet (spawn block top), surfaceNormal = up (0,1,0), incomingDirection any (unused). duration 0.8 s; gone by ≈1.6 s. |

Timeline (harvest_bell): 0.00 gold festival circle (r 1.4 m) draws at the feet: rings + 36 ticks, harvest rune band (wheat / bell / sun / sickle), octagram {8/3} + inner {8/2}, bell-in-wheat-wreath sigil, soft dark-amber ground shadow; light pillar + motes rise → 0.25 the bell pops in 3.6 m above the caster (solid lathe mesh with wheat relief, alpha + depthWrite; additive rim/band layer on the same mesh), ribbon bow with wheat sprigs on its crown, halo ring + rune halo → 0.55 the bell starts swinging about its pivot (pendulum, amplitude 0.36 rad, period 0.9 s) → **rings at 0.775 / 1.225 / 1.675 s** (clapper strike at each swing extreme; the 3rd is the biggest): strike flash at the lip, 2 sound rings + line rings round the bell, rays, music notes, glints, ground circle/sigil flare, and a **golden sound wave on the ground** (thin double line ring + scalloped vibration ring + wheat-ear band, plus a dim echo ring 0.09 s later) that runs out to **r 8 m in 1.15 s** with wheat-gold glints/streaks riding the front and wheat ears/leaves kicked up behind it → wheat ears, leaves, gold dust and dark olive leaf accents drift down; field twinkles → 2.35 swing settles → 2.65–3.05 bell dissolves (small flash, ring, sparkle scatter), circle fades.

Swing hand-off: the bell is split into 15 consecutive one-particle layers (SW00–SW14, bell + rim pairs), each a linear rotation about local Z from θ(tₐ) to θ(t_b) over its segment, so each segment starts exactly where the previous one ends. Sound-wave radius curve = R0 + (8 − R0)(1 − e^(−2.6 t)) (Hermite keys with exact tangents); the front glints use speed v0 = 2.6·(8 − R0)/(1 − e^(−3.0)) with drag 2.6, so they follow the same curve.

Recommended mod timing: start the 1-minute buff (2× chance that a harvested crop comes alive) at cast +0.775 s (first ring) or immediately. Play `reveal` at each crop mob the moment it comes alive (its "location shown" marker: tiny gold ground ring + ring wave + 1.6 s light pillar + upward sparkles + small bell icon at 1.35 m with glint). Not a combat effect, so there is no range ring; r 8 m is only the visual wave reach.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (bell lathe mesh, ground circle line work, rune band, sound-wave rings, wheat band, pillars, reveal ring), **depthWrite** (solid bell mesh, alpha material with texture alpha 1), **bloom**, **orbitalVelocity** (rising motes, bell sparkles, reveal icon orbit). Not attached: no attachedFollow.
Others: radialVelocity (motes gathered into the bell), flipbook + flipbookCycles (tumbling wheat ears / leaves), stretchedBillboard, trails, drag, gravity, noise, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
