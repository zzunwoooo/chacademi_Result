# BottleFill (물병 가득, 생활 C1, id bottle_fill) — converter notes

## Systems and runtime signals
| system | role | started by | duration | notes |
|---|---|---|---|---|
| cast | attached | cast (t = 0), once | 1.2 s, loop false | anchor = caster feet, basis = caster horizontal facing (+Z forward). No stop signal; everything is gone by ≈1.2 s. |

Timeline: 0.00 small water circle at the feet (r 0.85 m: ring + runner, rune band, droplet sigil, caustics, navy base) + sea-blue ribbons spiral up the body → 0.02–0.36 droplets are drawn in and gather into a water orb above the hands (0.05, 1.28, 0.62), two water rings orbit it, the orb wobbles → **0.42 split**: splash flash, crown droplets, the orb divides into three little round flasks (left / up / right) that fill up (water level flipbook) with drips and twinkles → 0.86 / 0.90 / 0.94 "ting" (each full flask) → 0.98–1.2 flasks shrink and three glints zip into the caster's right hip (= inventory), small pop at the hip.

**Recommended mod timing:** convert the bottles at cast t = 0 (or at +0.42 s, the split). The refusal case (no empty bottles, no mana) plays nothing. Not a combat effect: no range ring.

## Features
Only client-supported features (client_compat check OK, validate OK). High impact: **meshParticle** (circle line work, rune band, body helix ribbons, orb rings), **bloom**, **attachedFollow**, **orbitalVelocity** (in-drawn droplets, rising motes, orb bubbles). Others: flipbook (flask fill, stroke wipe), worldSpace (drips), stretchedBillboard, trails, drag, gravity, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
