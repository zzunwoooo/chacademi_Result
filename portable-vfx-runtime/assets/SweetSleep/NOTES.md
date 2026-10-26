# SweetSleep (단잠의 주문, sweet_sleep, 어둠 생활) — converter notes

## Systems (contract: spells12.md F2)
| system | role | length | started by | notes |
|---|---|---|---|---|
| cast | attached | duration 1.5 s, loop false | cast t = 0 (charm armed) | caster feet + horizontal facing. Night circle at the feet (r 0.8, moon-phase rune band, moon sigil); a crescent moon rises over the head (0.1 s, y ≈ 2.35), little clouds, star chain around the head, cute stars orbiting, stardust falling; **1.05 blessing**: dust spirals into the chest, star + rays at the chest, moon winks, ground flare. Gone by ≈1.5 s. |
| wake | attached | duration 2.0 s, loop false | when the caster wakes up in a bed with the charm (buff starts) | the moon sets (0–0.7), a scalloped flower circle blooms at the feet, rings of light rise over the body (0.10 / 0.28 / 0.45 / 0.63 / 0.80), shafts + motes + cozy motes hug the body; a dawn star rises over the head (0.4) and **pops at 1.0** (buff applied) with a ring wave and a star sprinkle. Gone by ≈1.95 s. |

Nothing needs to be stopped. If the charm expires unused (30 min) or the cast is refused, nothing plays. Recommended: start `wake` the tick the player leaves the bed after a successful sleep; the +20 % mana-regen icon is the game's own.

## Hand-offs
Held circle layers are one particle each for the system length; the cast ground flare at 1.27 starts with rot3 = 0.45·1.27, the wake petal flare at 1.05 with rot3 = 0.35·1.05 (same spin as the held layers). No range ring (not a combat effect).

## Features
meshParticle, bloom, attachedFollow, orbitalVelocity (stars, motes), radialVelocity, flipbook (runner), stretchedBillboard, trails, noise, gravity, drag, linearVelocity, worldSpace, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping — all client-supported.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
