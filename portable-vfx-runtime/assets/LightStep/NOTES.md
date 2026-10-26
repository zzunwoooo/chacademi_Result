# LightStep (사뿐한 발걸음, id light_step, 바람 생활) — converter notes

## Systems and runtime signals
| system | role | length | started by | inputs |
|---|---|---|---|---|
| cast | attached | duration 1.2 s, loop false (all gone ≈1.35 s) | cast, once (t = 0) | anchor = caster feet, facing = body yaw; no stop signal |
| step | impact | duration 0.5 s, loop false (print lingers ≤1.1 s) | mod, per footstep while the 10-min buff is on — **at most once per 0.5 s** (server throttle) | hitPoint = foot position on the block top, surfaceNormal = up (0,1,0), incomingDirection = walk direction |

Timeline (cast): 0.00 mint wind circle (r 0.75: rings + ticks, swirl/feather/footprint rune band, hexagram, swirl-feather sigil) draws at the feet; gusts and streaks spiral in →
0.10–0.50 two feather-wind rings per ankle shrink in (wrap) and spin, wrap dots with trails orbit up → **0.50 lock**: flash at each ankle, circle flare + ring, feathers pop out,
updraft streaks run up the legs, one feather keeps orbiting each ankle → 0.95–1.2 fade, a last lift of feathers/gusts.
Step: feather-wind print (curled gust + feather glyph, 0.5 m) + curl line + thin ripple + glint + 2 floating feathers + motes + small gust puff. Light enough to play every 0.5 s.

Recommended mod timing: apply the 10-min buff at cast (or at the 0.5 s lock). No range ring — the effect is self-only.

## Features
client_compat fix: 0 changes; check OK; validate OK. No burst at/after its system duration (burstchk 0 bad).
High impact: **meshParticle** (circle line work, rune band, ankle rings, print/curl), **bloom**, **orbitalVelocity** (ankle wrap dots, orbit feathers, gathering gusts), **attachedFollow** (cast follows the caster).
Others: radialVelocity, flipbook + flipbookCycles (tumbling feathers), stretchedBillboard, trails, drag, gravity, noise, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.
Unique textures: 13, 1.91 MiB RGBA.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
