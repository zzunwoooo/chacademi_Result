# HeatHaze (아지랑이, 중급 339, id heat_haze) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| heat_haze_dash | attached | cast (t = 0) | anchor = caster feet every frame, **facing = the SLIDE direction** (horizontal, fixed at cast; local +Z = slide, like GaleDash), not the look direction. Visual timing assumes 2 m in 0.2 s: launch burst at t = 0 (old spot), wake layers are world-space with rateOverDistance along the slide, arrival burst at t = 0.2. Ends by itself (duration 0.22 s, ~0.7 s visible). |
| heat_haze_decoy | static | cast (t = 0), placed at the caster's **old feet position** (before the slide), basis = caster facing | The flame/heat-shimmer silhouette (1.83 m tall, billboard centre 0.97 m) + ground heat circle (r ≈ 0.95 m) for exactly **3.0 s**. All layers stop by themselves at 3.0 s (held circle layers fade out over the last 0.25 s; repeating flicker layers have their last burst timed so nothing outlives 3.0 s). Anticipation 2.55–3.0 s: ring contracts, sparks sucked in, silhouette over-brightens. If the decoy is removed early (destroyed/cancelled), send the stop signal: `onStop` tails (0.1–0.25 s) break it up at once, then start heat_haze_pop as usual (or not, if the server cancels the explosion). |
| heat_haze_pop | impact | the mod when the decoy expires (cast + 3.0 s) | hitPoint = decoy feet position, surfaceNormal = (0,1,0), incomingDirection = (0,−1,0). **Damage + burn (3 s) at +0.00 s.** The thin crisp line ring (P05 Edge) marks the explosion radius: authored **2.5 m** (layer size = radius). If the server radius differs, change P05/P06/P07 size (or use one fixed radius). |

Monster targeting of the decoy is server logic; the VFX has no input for it.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (ground circle line work, rune band, sigil, pop edge ring, spinning fire arcs, slide scorch strip, heat strands), **bloom**, **attachedFollow** (dash: local body shimmer follows the caster, world wake drops along the path). orbitalVelocity is not used.
Others: rateOverDistance + worldSpace (dash wake), flipbook (+ flipbookCycles on the ghost), stretchedBillboard, trails, noise, gravity, drag, radialVelocity, stopTail, rotation3D.

## Approximation (reported)
- The afterimage is a camera-facing billboard flipbook (T_HHGhost_Sheet, 4×2 frames) of a generic standing human made of flame strokes, not a copy of the player's model/pose. A real translucent player-model ghost is outside the schema; if the mod can render one, it may be added on top — decide explicitly.
- Billboard ghost rotates to face every viewer (it reads as a figure from all sides, but has no back/side view).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
