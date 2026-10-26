# ScaleAppraisal (비늘 감정, 생활 C2, id scale_appraisal) — converter notes

## Systems and runtime signals
| system | role | started by | duration | notes |
|---|---|---|---|---|
| cast | attached | cast (t = 0), once | 1.5 s, loop false | anchor = caster feet, basis = caster horizontal facing (+Z forward). No stop signal; gone by ≈1.5 s. |

Timeline: 0.00 glint at the right hand (0.3, 1.02, 0.42), water drawn up into the lens; small water ring at the feet (r 0.62 m) → 0.06–0.3 a vertical water lens (r 0.24 m) draws on in front of the hand at (0.28, 1.3, 0.72): rim with ticks, counter-rotating inner ring, fish-scale lens disc, navy back shade → 0.3–0.75 measuring: a sparkle runner sweeps the tick ring twice, scale glints flicker, little fish scales drift up → **0.72 / 0.86 / 1.00** three appraisal icons pop above the lens: ★ grade (caster's left), ruler = size (centre), coin = price (right) → **1.08 "appraised" ding**: lens + icons pulse, ring pulse, sparkles → 1.2–1.48 lens closes, drips, icons fade.
The lens and icons are vertical quads (normal = forward) whose UVs are mirrored so they read correctly from the caster's own view (first person, looking +Z).

**Recommended mod timing:** show the appraisal chat/UI (grade, size vs record, sell price) at cast +1.08 s (the ding), or at t = 0. Refusal (not a fish) plays nothing. No range ring (not a combat effect).

## Features
Only client-supported features (client_compat check OK, validate OK). High impact: **meshParticle** (lens rim/inner/disc, icon quads, feet ring + rune band), **bloom**, **attachedFollow**, **orbitalVelocity** (in-drawn droplets, feet motes). Others: flipbook (runner wipe), worldSpace (drips), stretchedBillboard, trails, noise, drag, gravity, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
