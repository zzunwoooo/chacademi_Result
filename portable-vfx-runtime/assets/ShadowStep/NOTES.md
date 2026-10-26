# ShadowStep (그림자 걸음, 중급 #350, id shadow_step, 어둠) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| `shadow_step_depart` | static | cast t = 0 at the caster's start feet (basis = dash direction, horizontal) | sigil flash, the body melts into an ink silhouette (flipbook 4×2), ink swirl, forward streaks, ink puffs/shards. Ends by itself (~1 s) |
| `shadow_step` | attached | dash start (caster feet, forward = dash direction) | local ink comet around the moving body + world ink band / afterimages / streaks / ground smear left along the path. **Loops: the mod stops it on arrival** (stop signal, onStop 0.03 s; world layers finish their lifetimes ≤ 1.1 s). W10 `Lane` = one world burst at dash start showing the 5 m pass corridor (±0.6 m) |
| `shadow_step_arrive` | static | on arrival at the end feet (basis = dash direction) | converging ink strokes → silhouette re-forms (flipbook reversed) → ink puff ring + stain + small sigil flash at instance t ≈ 0.10–0.12 s. Ends by itself (~1 s) |
| `shadow_step_blind` | attached | once per enemy the shadow passes through, P = enemy feet (follows the enemy) | 1.4 s: flash + dark veil wisps wrapping the eyes (authored for eye height 1.62 m of a 1.8 m target), ink puffs, violet drips. The blind debuff (3 s) itself is shown by the game's icon |

## Timing (demo)
depart t = 0 · dash 0.06 → 0.31 s (5 m in 0.25 s; the mod moves the caster and hides the model while in shadow form, demo reveal 0.03 → 0.14 hidden, shown again 0.35 → 0.57) · arrive at 0.31 s · blind when the shadow passes each enemy (demo z/5·0.25 s after dash start).
The VFX has no fixed timing between systems: the mod's real dash start/end drive `shadow_step` start/stop and `shadow_step_arrive`.

## Features
Only client-supported features. High impact: meshParticle (sigil line work, comet, ink bands, swirls, veil), bloom, orbitalVelocity, **attachedFollow** (`shadow_step` follows the dashing caster, `shadow_step_blind` follows each enemy). Also flipbook, trails, worldSpace + rateOverDistance (world layers only), stretchedBillboard, stopTail, gravity, drag, noise.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
