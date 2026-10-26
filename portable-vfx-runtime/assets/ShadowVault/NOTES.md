# ShadowVault (그림자 금고, shadow_vault, 어둠 생활) — converter notes

## System (contract: spells12.md F1)
| system | role | length | started by | notes |
|---|---|---|---|---|
| cast | attached | duration 1.2 s, loop false | cast t = 0, once | anchor = caster feet, basis = caster horizontal facing (+Z forward). The vault is centred 0.3 m in front of the feet (frame r 0.95 m, door r 0.62 m) so the body does not hide it. Nothing to stop; gone by ≈1.19 s (world wisps finish by ≈1.3 s). |

## Timeline (cast-relative)
- 0.00 ink shadow pool spreads, violet vault frame (rings, ticks, rune band, runner) draws on; dark ink wisps curl up at the rim.
- 0.04 the dark vault door (ink body, violet engraving, bolts, seam) surfaces; 0.08–0.46 two dial rings spin against each other, clicks at 0.20 / 0.31 / 0.41.
- **0.46 unlock**: lock-seal sigil flashes, small lilac flash + rays, two thin ring waves, sparks spit from the seam.
- 0.48 the two door halves slide apart (±X, drag) and fade by 0.86; under them the pocket space: ink disc + two counter-rotating violet/magenta swirls, light shafts and a soft violet column, stars / motes spiral up, rising rings (0.55 / 0.70 / 0.85), a few violet-rimmed ink shards, ink wisps.
- 0.95–1.15 the portal shrinks closed, the frame flares inward, a wink + puff at 1.07.

**Recommended mod timing:** open the ender-chest screen at **cast + 0.48 s** (door opens) or immediately at t = 0 — the effect also reads as "the vault opened" if the screen covers it. Opening the screen is the mod's job.

## Hand-offs
The closed door (one particle 0.04–0.48) hands off to the two half-door particles at 0.48 (same rotation 0, same size). The frame flare at 0.95 starts with rot3 = 0.5·0.95 (frame spins 0.5 rad/s from t = 0). No range ring (not a combat effect).

## Features
meshParticle, bloom, attachedFollow, orbitalVelocity (stars, motes), radialVelocity, flipbook (runner), stretchedBillboard, trails, gravity, drag, linearVelocity, worldSpace, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping — all client-supported.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
