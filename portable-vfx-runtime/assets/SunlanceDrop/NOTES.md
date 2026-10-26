# SunlanceDrop (햇살 꽂기, 기초 335, id sunlance_drop, 빛) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| sunlance_drop_cast | attached | cast (t = 0) | caster feet + horizontal facing (+Z forward). Right hand (0.3, 1.45, 0.5): gold sun sigil + line ring spin up, **glint at +0.06 s** (star, ring, sparks, two up-threads), small feet circle. Duration 0.25 s, gone by ≈0.45 s. |
| sunlance_drop | impact | the mod, at the aimed enemy (range 16 blocks), recommended cast + 0.05 s | hitPoint = the enemy's **feet** on the ground, surfaceNormal = (0,1,0), incomingDirection = (0,−1,0). 0–0.40 s telegraph: sun sigil (line-work circle + rune band + sigil quad) 3.6 m above the feet brightens, motes/streaks gather in, hairline aim thread from 0.22 s, thin target ring (r ≈ 0.8 m) at the feet with a contracting ring → **0.40 s** beam drops from the sigil (white-gold core, amber halo, faint sheath; lance head) → **0.43 s ground hit**: flash, ray decal, 2 shock rings, scorch, gold sparks, up-rays, light feathers drifting, pale-cyan motes, umber smoke/ash accents → beam thins out by ≈0.75 s, feathers gone by ≈1.8 s. |

Damage timing: apply damage at **sunlance_drop + 0.43 s** (beam reaches the ground; = cast + 0.48 s with the recommended spawn). If the target is airborne, keep hitPoint on the ground below it (the sigil height is fixed at 3.6 m above hitPoint, authored for a 1.8 m mob). No stop / hold signals; no scale input.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK).
High impact: **meshParticle** (sky circle/rune band/sigil, foot ring, beam planes, ground decals, hand sigil/ring), **bloom**, **attachedFollow** (cast follows the caster), **orbitalVelocity** (gathering motes, rising motes; minor).
Others: rotation3D, rotationOverLifetime, stretchedBillboard, trails, noise (feathers), gravity, drag, radial/linear velocity, alpha + additive, hdrTint, neutral tonemapping.

## Difference from LightningNeedle / SolarJudgment
No zig-zag lightning or wind; not a sustained laser — one short thin sunbeam from a small sun sigil (basic tier).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
