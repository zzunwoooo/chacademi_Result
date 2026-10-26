# CropSurvey (작물 살피기, 생활 D1, id crop_survey) — converter notes

## Systems and runtime signals
| system | role | started by | duration | notes |
|---|---|---|---|---|
| wave | static | cast (t = 0), once | 1.5 s, loop false | P = caster feet, basis = caster horizontal facing. Authored for the real radius 12 m (fixed, no scale input). No stop signal. |
| ripe | static | once per fully grown crop in range (≤ 40) | 3.0 s, loop false | P = bottom centre of the ripe crop block (= top of the farmland), basis = caster facing or identity (symmetric). Marker hovers at y 1.15. No stop signal; gone at 3.0 s. Replay it if a longer marker is wanted. |

wave timeline: 0.00 tap flash + earth circle at the feet (r 1.2 m: soil base, ochre ring + runner, seed/leaf rune band, sprout sigil, sprouts) → **0.10–1.10 wave front** (crisp ochre line + moss band + glints) runs out at a constant speed: r(t) = 12·(0.08 + 0.92·(t − 0.1)/1.0) m (≈ 11 m/s); sprouts pop up and nod behind it in rings at 2/4/6/8/10/11.6 m, leaves flick up, pollen rises → **1.08 rim at the real radius 12 m**: thin crisp ring flash + sparkles, held faintly to ≈1.45 (true range shown).
ripe timeline: 0.00 a leaf badge (pin with a sprout + star) pops in 1.15 m above the crop, small moss ring draws at the crop base → glints drip from the badge to the crop at 0.3 / 1.3 / 2.3, twinkles at 0.6 / 1.6 / 2.6, base pings at 0.8 / 1.8 → 2.65–3.0 fade.

**Recommended mod timing:** start `wave` at cast; start each `ripe` when the front passes the crop: wave t + 0.1 + (d/12 − 0.08)/0.92 s (+0.1 s looks nicest), d = horizontal distance caster → crop; or all at +1.1 s. Send the growth summary (chat/UI) at +1.1 s (rim flash).

## Features
Only client-supported features (client_compat check OK, validate OK). High impact: **meshParticle** (circle line work, rune band, wave front/band rings, rim, ripe base ring), **bloom**, **orbitalVelocity** (pollen, ripe motes). Others: flipbook (sprout growth, runner wipe), radialVelocity (front glints ride the front), trails, noise, drag, gravity, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
