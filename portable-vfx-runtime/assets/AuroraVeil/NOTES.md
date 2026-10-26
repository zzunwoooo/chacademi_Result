# AuroraVeil (오로라, 궁극 372, id aurora_veil, 빛 · 지원형) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| aurora_veil_begin | static, phase begin | cast (t = 0) | P = caster feet, M = caster facing. Dawn opening 0–1.5 s: circle r 10 draws on, horizon band + dawn rays, curtains unfurl, crown opens at 14.5 m; sunrise flash + first ripple at 1.15–1.3 s |
| aurora_veil | static, phase sustain | t = sequence.sustainStart = 1.5 s | runtime `holdDuration` = spell time − 1.5 (default 10.5 → whole spell 12 s). loop, duration 60 (> hold.max 15) so its t = 0 bursts happen once. **Heal pulse visual at sustain t = 0.8 + 2k s** (cast t = 2.3, 4.3, 6.3 …) → apply the periodic heal on that 2 s rhythm if you want them in sync |
| aurora_veil_end | static, phase end | hold expiry (or cancel → jumpToEnd) | graceful fade ~1.5 s: crown flare, last ripple, curtains rise and dissolve, motes ascend. No damage timing |
| aurora_veil_ally | attached | the mod, once per ally (party member) inside the zone, when it enters / at the lock-in | follows that ally (feet, facing). loop; heal sparkle every 2 s from its own t = 0.3 s. Stop it (stop emitting) when the ally leaves the zone or the zone ends — held foot seal uses `onStop` 0.35 s |
| aurora_veil_weaken | attached | the mod, once per enemy inside the zone | violet dusk shaft + press rings + dark mist on the weakened enemy. Stop it when the enemy leaves or the zone ends. Debuff/immunity icons are the game's own |

Zone radius fixed at 10 m (no `scale` input: it would also resize the per-target systems; `scale.systems` is not in the client). The outer ring of the ground circle (r = 1.0 × 10 m) is the true range ring.

## Held layers (rotation hand-off)
Ground circle (6 layers), hexagram pillars, crown rings / crown sigil: ONE particle each in `aurora_veil` (life 59 s, `onStop` tail 1.5 s).
Their begin draw-on copies start at t_s with rot3 = ω·t_s, the sustain copies start at 1.5 s with rot3 = ω·1.5 and the same rotLife, so they line up (overlap 1.5–1.65 s = lock-in brightening).
Aurora curtains are 5 mesh layers (unique wavy ribbon meshes, each mapped to one cell of T_AVCurtain_Sheet, no flipbook) re-emitted at a low rate and cross-faded; undulation = overlapping copies with different yaw drift.

## Features
Only client-supported features (client_compat check OK, `fix` = 0 changes). High impact: meshParticle (circle line work, curtains, crown, pillars, ally ribbons),
bloom, orbitalVelocity (motes, veil, shimmer), attachedFollow (ally / weaken), phasedSequence + stopTail (held layers).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
