# CropSnare (작물 붙잡기, 생활 #387, id crop_snare) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| crop_snare | attached | cast (t = 0), once | caster feet + horizontal facing. duration 4.5 s, loop false, no stop signal. Root circle r 0.95 m (0–1.6 s), "armed" pulse at 1.15 s, two tiny ankle leaves orbit 1.3–4.4 s. |
| crop_snare_bind | impact | the tick a crop mob spawns from the caster's harvest (while the 2-min buff is active) | hitPoint = crop mob feet (block top), surfaceNormal = up (0,1,0), incoming = (0,-1,0). duration 3.9 s. Burst 0–0.3, roots coiled 0.3–3.0, loosen + sink 3.0–3.6, gone ≈3.9. One instance per crop mob. |

Bind timeline: 0.00 soil burst decal, ochre shock ring, dirt chips + dust, flash → 0.00–0.14 five cartoony roots (olive-moss body, ochre rim, little leaves) start bursting out (staggered), each grows in 0.3 s (flipbook) and coils up to 0.3–0.74 m around the mob (body radius ≈ 0.15–0.2 m at the top) → 0.3–3.0 held, with small squeeze tugs at 0.95 / 1.75 / 2.45 s (chips + glint + leaf flick) → 3.0 roots loosen (widen, spin a little) and sink 0.45 m into the soil, dirt puff, leaves drop, pollen lifts → ≈3.9 empty.
Authored for a 0.6–1.0 m crop mob (no scale input). The mod applies the actual 3 s root; the client's own root debuff icon shows the CC state.

**Recommended mod timing:** start `crop_snare_bind` at the same tick the crop mob is spawned (it should appear inside the roots); release movement at +3.0 s (roots loosen exactly then). Start `crop_snare` at cast; the 2-minute buff indicator is the server's buff UI.
Not a combat spell: no range ring.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (circle line work, rune band, ground vines, leg vines, root coils), **bloom**, **attachedFollow** (cast), **orbitalVelocity** (rising leaves/pollen, ankle leaves).
Others: flipbook + flipbookCycles (root/vine growth, spinning leaves, soil chips), radialVelocity, noise, gravity, drag, worldSpace, stretchedBillboard, trails, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.
Texture memory: 19 unique textures (≤ 512 px), 3.05 MiB RGBA unique (client WARN counts per material: 4.5 MiB).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
