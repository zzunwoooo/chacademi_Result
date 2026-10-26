# GaleCombo (질풍 연격 #345, gale_combo, 바람 중급) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| gale_combo_slash1 | attached | 1st cast (combo step 1) | P = caster feet, M = caster horizontal facing (+Z forward, +X right). Flat crescent slash RIGHT → LEFT at chest height. Blade crosses the front at **+0.08 s** → apply step-1 damage then. ~0.75 s of particles, no loop. |
| gale_combo_slash2 | attached | re-cast within 2 s (step 2) | same frame. Rising slash LEFT → RIGHT (≈41° roll). Front crossing **+0.08 s** → step-2 damage. |
| gale_combo_push3 | attached | re-cast within 2 s of step 2 (step 3) | 0 … 0.12 s wind-up (hand seal, inflow, ground seal), **release at +0.12 s** → push / knockback at +0.12 … +0.15 s. Thin ground arc shows the push reach (radius 4.5 m, 130° front cone). If the server reach differs, change C24 RangeLine `start.size` (= radius m). |
| gale_combo_hit | impact | the mod, once per enemy hit by slash 1/2 | hitPoint = enemy chest, air-hit convention: surfaceNormal = −incomingDirection (incoming = caster → enemy). |
| gale_combo_push_hit | impact | the mod, once per enemy hit by the push | same convention, incoming = push direction. |

Each step is a separate instance; steps may overlap (slash particles still fading when the next starts) — intended.
If the combo window (2 s) lapses nothing needs stopping (all systems are one-shot).

## Features
Only client-supported features (no height/depth/orientation/follow/lights/attachPoint/rollWithTravel). High impact: meshParticle (all slash arcs, crescents, wind cone, seals, rings), bloom, attachedFollow (3 step systems follow the caster while they play). orbitalVelocity is used on slash streaks / leaves / motes (follow the sweep direction).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
