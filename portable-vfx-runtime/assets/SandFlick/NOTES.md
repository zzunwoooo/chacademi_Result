# SandFlick (모래 한 줌, 기초 334, id sand_flick, 대지) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| sand_flick | attached | cast (t = 0) | anchor = caster feet, basis = caster **horizontal facing** (local +Z = forward). Right hand at release = (0.26, 1.2, 0.38). 0.00–0.12 s scoop: grains swirl up into the palm, small earth sigil under the palm, feet ring → **0.12 s fling**: release glint + ring, underhand sweep arcs, grain spray/sand streaks/grain clusters, translucent warm dust fan, 3+2 sand wavefront arcs, glints, pebble chips (dark bodies, warm rims), thin umber haze behind; ground = sand scatter fan + warm glow fan + **crisp line arc at the true reach R = 3.5 m, half-angle 30°** (side edges + end dots) → ~0.32–0.45 s kick-up dust and bouncing grains at the far end, settling motes and late glints. Duration 0.5 s, everything gone by ≈1.0 s. |
| sand_flick_eyes | impact | the mod, once per enemy hit | hitPoint = the enemy's **feet**, surfaceNormal = (0,1,0), incomingDirection = (0,−1,0) (symmetric). "Sand in the eyes" at head height (1.55 m for a 1.8 m mob): small glint flash, compact dust puff, grain burst, spinning sand swirl arcs + thin head ring, blinking glints over ~0.6 s, trickling grains, chips, umber haze, little foot dust. **Damage + blind 3 s at +0.00 s of this impact.** The blindness status itself is shown by the game's own debuff icon. |

Hit timing: the sand front leaves the hand at cast +0.12 s and travels ≈9 m/s → recommended per-enemy hit time = 0.12 + distance/9 s (≤ 0.51 s at 3.5 m); a single cone check at +0.12…0.25 s is also fine visually. Demo uses per-distance timing.
Cone geometry if server values differ: R and HA in sandflick.js (EXTRA_MESHES saArc/saFan, layer sizes 1 = metres); rebuild.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (reach arc line work, ground fans, wavefront/fling/swirl arcs, rings, palm sigil), **bloom**, **attachedFollow** (sand_flick follows caster feet + facing). orbitalVelocity is not used.
Others: worldSpace, flipbook, stretchedBillboard, noise, gravity, drag, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.

## Distinct from SmokeBomb / FairyDust
Warm ivory/ochre grains with crisp streaks and pebble chips thrown in a short directional cone (no grey smoke cloud, no pastel sparkles).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
