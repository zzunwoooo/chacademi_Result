# WardCandle (결계 향초, id ward_candle, 화염 생활) — converter notes

## Systems and runtime signals
| system | role | length | started by | inputs |
|---|---|---|---|---|
| candle | static | duration 4.0 s, loop false (all gone ≈4.3 s) | cast, once (t = 0); no stop signal | P = the ward point (caster feet at cast); identity / caster-facing basis (symmetric) |

The 10-min spawn ward itself has no persistent VFX (contract: one 4 s system). The ring shows the **real radius 24**.

Timeline: 0.00 warding circle (r 1.1: rings + ticks, eye / shield / no-claw / flame rune band, octagram {8/3} + {8/2}, candle sigil) draws; brass dish (0.05) and ivory candle (0.08) rise
(solid depthWrite lathe meshes) → **0.45 the wick lights**: spark, small ring, flickering candle flame (flipbook cycles), hot core, halo glow, warm wax glow; circle flare → 0.55–1.15 incense smoke
(warm additive + charcoal alpha) and ember motes curl up → 0.95–1.5 they gather into a smoke ring 1.45 m above the candle → **1.50 the ring sinks** (0.25 s) → **1.75–3.25 it spreads over the ground
to r 24** (smoke band + crisp line front + thin rune band, glints/embers riding the front, dark smoke and incense kicked up behind; radius = 1.2 + 22.8·(1 − e^(−2.2 t))/(1 − e^(−3.3))) →
**3.20–4.25 a thin crisp boundary ring (+ ticks, thin rune band) holds at r 24**, incense wisps and glints rise along it → 3.6–4.25 everything fades, last smoke curl from the wick.

Recommended mod timing: register the ward at cast (or at 3.2 s when the ring reaches r 24, purely cosmetic). One per player: on re-cast elsewhere just play a new instance.

## Features
client_compat fix: 0 changes; check OK; validate OK; burstchk 0 bad.
High impact: **meshParticle** (circle, candle/dish lathes, smoke ring/band, wave + boundary rings), **depthWrite** (candle + dish), **bloom**, **orbitalVelocity** (incense curl). Static: no attachedFollow.
Others: flipbook + flipbookCycles (candle flame flicker), radialVelocity, stretchedBillboard, trails, drag, gravity, noise, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha, hdrTint, neutral tonemapping.
Unique textures: 16, 2.21 MiB RGBA.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
