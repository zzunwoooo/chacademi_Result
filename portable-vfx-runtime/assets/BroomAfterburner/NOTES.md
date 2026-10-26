# BroomAfterburner (불꽃 추진, 생활 375, id broom_afterburner) — converter notes

## Systems and runtime signals (all `attached`: rider feet = origin, +Z = horizontal facing, follows the rider every frame)
Phased sequence: `sequence.sustainStart = 0.1`, `hold.default = 2.9` (→ 3.0 s of boost from the cast), min 0.3, max 10.
| system | phase | what |
|---|---|---|
| `broom_afterburner_begin` | begin (cast t = 0) | ignition at the broom bristles: engine line-work circle flares on, white flash, 2 heat rings blown backward, back blast tongues, sparks, flame puffs, smoke, char flakes, swirl arcs |
| `broom_afterburner` | sustain (t = 0.1 … 0.1 + hold) | held engine circle (3 counter-rotating line-work layers facing backward), local flame jet (hot core + crimson outer planes, flicker sheet), swirl band, nozzle flames, shock diamonds; world-space flame trail (rateOverDistance + small rate), white-hot core streaks, embers with trails, thin smoke wisp; heat rings shed every 0.5 s |
| `broom_afterburner_end` | end (t = 0.1 + hold, or at once on cancel) | sputter (shrinking bursts 0 / 0.07 / 0.16 / 0.27 s) → pop ring + small star + smoke puff + embers at +0.3 s |

- Start the cast when the boost starts with `holdDuration` = boost time − 0.1 s (2.9 for 3 s). If the rider dismounts / the boost is cancelled, send **cancel** (§5.1 jumpToEnd): sustain stops (onStop tails 0.05–0.35 s), end plays.
- While boosting, the mod should **hide the broom's normal trail** (this effect replaces it) and restore it after the end.
- Nozzle position is baked: bristles assumed at (0, 0.5, −0.85) from the rider's feet. If the broom model differs, ask for a rebuild with the new offset.
- Held engine-circle copies: begin copies start at ts = 0 / 0.02 / 0.04 with rot3.z = ω·ts; sustain copies start at 0.1 with rot3.z = ω·0.1 (ω = 2.2 / −3.2 / 4.0 rad/s) so they line up.
- Gameplay (speed +20 %) is the mod's; the VFX has no hit/damage timing.

## Features
Only client-supported features. High impact: meshParticle (engine circle, jet planes, swirl, heat rings), bloom, **attachedFollow** (all systems follow the rider), phasedSequence. No orbitalVelocity. Also worldSpace, rateOverDistance (world layers only), trails, flipbook, stretchedBillboard, stopTail, gravity, drag, noise, linearVelocity, rotation3D.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
