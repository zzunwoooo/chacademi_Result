# ShadowFang (그림자 송곳니, 기초 331, id shadow_fang, 어둠) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| shadow_fang_cast | attached | cast (t = 0) | caster feet + horizontal facing (+Z forward). Right hand (0.3, 1.42, 0.5): violet sigil + line ring spin up, **flick at +0.07 s** (star, ring, sparks, ink flecks), small feet circle. Duration 0.3 s, gone by ≈0.5 s. |
| shadow_fang | impact | the mod, at the aimed enemy (range 12 blocks), recommended cast + 0.08 s | hitPoint = the enemy's **feet** on the ground, surfaceNormal = (0,1,0), incomingDirection = (0,−1,0). 0–0.20 s ink pool + fang circle spread, ring contracts, wisps converge → **0.20 s SNAP**: 3 big + 3 small curved ink fangs (dark bodies, violet rims) erupt out of the pool leaning outward, flash, shock rings, crack, ink crown → **0.28–0.35 s BITE**: tips snap inward and meet at chest height (bite flash, clamp ring, burst) → hold → 0.58–0.88 s fangs sink back, pool closes. Gone by ≈1.0 s. Authored for a 1.8 m mob, pool Ø ≈ 3.6 m. |

Damage timing: apply damage at **shadow_fang + 0.20 s** (eruption) or + 0.31 s (the visual bite); recommended 0.20 s so the hit feels instant. If the target is in the air, keep hitPoint on the ground below it (the fangs rise from the ground). No stop / hold signals.

## Fang hand-off
Each fang is three consecutive mesh particles (rise 0.08 s, bite 0.07 s, hold+sink) with the tilt (rotation3D z + rotationOverLifetime z) chosen so the end angle of one equals the start angle of the next; no gaps or jumps.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (fangs, ground circle line work, rune band, hand sigil/ring), **bloom**, **attachedFollow** (cast follows the caster), **orbitalVelocity** (converging wisps, motes; minor).
Others: rotation3D, rotationOverLifetime, stretchedBillboard, trails, gravity, drag, radial/linear velocity, alpha + additive, hdrTint, neutral tonemapping.

## Difference from BoneGrasp / VoidMaw
No hands, no maw: thin curved ink fangs biting inward around one target, small and quick (basic tier).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
