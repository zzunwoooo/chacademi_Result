# ReplantTouch (되심기 손길, 생활 D2, id replant_touch) — converter notes

## Systems and runtime signals
| system | role | started by | duration | notes |
|---|---|---|---|---|
| cast | attached | cast (t = 0), once | 1.0 s, loop false | anchor = caster feet, basis = caster horizontal facing (+Z forward). No stop signal. |
| sprout | impact | each automatic replant (the tick the seed is planted), any number during the 60 s buff | 0.8 s, loop false | hitPoint = top centre of the farmland under the replanted crop (= bottom of the broken crop block), surfaceNormal = up. ≈35 particles, cheap for fast farming. |

cast timeline: 0.00 glint between the cupped hands (0, 1.05, 0.42); small earth circle at the feet (r 0.8 m: soil base, ochre ring + runner, rune band, sprout sigil) → 0.04–0.6 moss + gold leaf ribbons spiral up the body, leaves rise; soil grains drawn in, four seeds orbit the hands → 0.1–0.95 a sprout of light grows in the hands (flipbook), moss glow, dark olive back shade → **0.55 blessing pop**: star + leaf burst at the hands, circle flare + ring pulse + little sprouts on the ground, three seed glints drop to the ground in front → 0.6–1.0 fade.
sprout timeline: 0.00 a seed (with gold trail) drops from 0.62 m → **0.14 plop** into the soil (star, soil puff, grains, ring ping) → 0.17 a sprout springs up and nods (flipbook), small moss ring at the base held to 0.78, star glint at 0.3, sparkles + leaves.

**Recommended mod timing:** grant the 60 s buff at cast t = 0 (or +0.55 s, the pop). Start `sprout` the same tick the crop is replanted (the seed visually lands at +0.14 s). Not a combat effect: no range ring.

## Features
Only client-supported features (client_compat check OK, validate OK). High impact: **meshParticle** (circle line work, rune band, body helix ribbons, sprout base ring), **bloom**, **attachedFollow**, **orbitalVelocity** (seeds / motes / grains around the hands, rising leaves). Others: flipbook (sprout growth, ribbon wipe), trails, drag, gravity, linearVelocity (world-space rise on the impact), rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
