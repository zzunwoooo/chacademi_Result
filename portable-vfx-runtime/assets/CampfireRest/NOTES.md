# CampfireRest (모닥불 쉼터, id campfire_rest, 화염 생활) — converter notes

## Systems and runtime signals
| system | role | length | started by | inputs |
|---|---|---|---|---|
| campfire | static | **loop true, duration 60 s**, rate emission; held layers live 60 s | cast, once; **the mod stops it at 60 s** (or earlier if the fire is removed) | P = caster feet at cast (identity / caster-facing basis, symmetric) |
| warm | attached | duration 1.2 s, loop false (gone ≈1.3 s) | mod, on each healed party member at every 2 s heal tick | anchor = that member's feet + facing |

Stop: every held / flame / smoke layer has `onStop jumpToTail` (logs, stones, scorch, ground glow, range ring/band: 1.0 s; flames 0.2–0.3 s; smoke 0.6 s; range motes 0.5 s). Held layers also fade
during their own last 1.0 s, so a stop exactly at 60 s and a stop earlier both end in ≤1 s; embers/sparks just finish (≤2 s). Bursts exist only at t ≤ 0.9 s (ignition), so a loop wrap never re-fires them
before the stop (keep the stop at 60 s).

Timeline (campfire): 0.00 ignition flash + sparks; stones and a teepee of glowing logs pop in (solid depthWrite meshes + additive ember-crack copy) → 0.10 flames (two flame-tongue flipbook
layers + rising tongue streaks + hot core glow), embers with short trails, pop sparks, charcoal smoke curl and ash flakes start (rate) → 0.15 a warm line wave runs to **r 5 in 0.9 s** and the soft
**range ring + hearth rune band (heart / flame / logs / moon)** settle at the real radius 5 (alpha ≈0.5, slowly turning), warm motes rise along it all the time → hold to 60 s → stop tail.
Warm: feet ring + small hearth circle, warm steam wisps rising off the body, ember-heart icon popping above the head (0.1 s), "+" glints, orange motes spiralling up, ash flakes → 1.2 s.

## Features
client_compat fix: 0 changes; check OK; validate OK; burstchk 0 bad.
High impact: **meshParticle** (logs, stones, line rings, rune band), **depthWrite** (logs + stones: alpha material, texture alpha 1), **bloom**, **orbitalVelocity** (warm motes), **attachedFollow** (warm).
Others: stopTail, flipbook (flames, smoke, steam), stretchedBillboard, trails, drag, gravity, noise, linearVelocity (world-up flames/smoke), rotation3D, rotationOverLifetime, additive + alpha, hdrTint, neutral tonemapping.
Unique textures: 16, 2.60 MiB RGBA.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
