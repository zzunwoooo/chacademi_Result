# EmberPuff (작은 불숨, 기초 326, id ember_puff) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| ember_puff | attached | cast (t = 0) | anchor = caster feet, basis = caster **horizontal look direction** (local +Z = forward). Mouth/hands point = (0, 1.38, 0.32). 0.00–0.10 s inhale (sparks sucked in, hot mouth glow) → **0.10 s puff**: flash, flames/tongues/heat-shimmer streaks/sparks fan out in a 30° half-angle cone that reaches the ground at ≈3 m, three fire wavefront arcs expand through the cone, a heat fan + scorch on the ground, and a **crisp line arc at the true reach R = 3.0 m** (with side edges and end dots) → ~0.40–0.95 s little ash puff, char flakes and sparkles at the front. Duration 0.5 s (no bursts after it), everything gone by ≈1.0 s. Mostly local layers (follow the caster for the short breath); embers, char flakes, ash puff are world-space. |
| ember_puff_burn | impact | the mod, once per enemy hit | hitPoint = the enemy's **feet**, surfaceNormal = (0,1,0), incomingDirection = (0,−1,0) (impact is symmetric). Small burn tick: flash, flames + licks rising, spinning fire brand arcs around the body, foot ring, embers, char, a little smoke (~0.7 s). **Damage + burn (3 s) at +0.00 s of this impact.** Size authored for a 1.8 m mob. The 3 s burn status itself is the game's own fire/debuff display. |

Hit timing: the visible breath front leaves the mouth at cast +0.10 s and travels ≈9 m/s → recommended hit time per enemy = 0.10 + distance/9 s (≤ 0.43 s at 3 m); a single hit check at +0.10…0.2 s for the whole cone is also fine visually. Demo uses the per-distance timing.
Cone geometry if the server values differ: reach = EXTRA_MESHES epArc/epFan (R), half-angle HA = 30°; change R/HA in emberpuff.js and rebuild (layer sizes are 1 = metres).

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (reach arc line work, ground heat fan/scorch, wavefront arcs, burn brand arcs, foot ring), **bloom**, **attachedFollow** (ember_puff follows the caster's feet + facing). orbitalVelocity is not used.
Others: worldSpace, flipbook, stretchedBillboard, trails, noise, gravity, drag, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.

## Difference from DragonBreath
Small and snappy: one 0.3 s puff from the mouth, 3 m reach, no sustained stream, no large flames or screen-filling glow.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
