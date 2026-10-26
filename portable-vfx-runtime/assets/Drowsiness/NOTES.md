# Drowsiness (졸음 #175, drowsiness, 어둠) — converter notes

New pack (no earlier version). Game behaviour = `ArcanaMech.drowse` (speed 0.8 b/tick, range 16, r = 3, wait 200 ticks, root 60 ticks).
The VFX only shows the spell; the game applies the root and shows its debuff icon.

## Systems and timing
| system | role | started by | placement | length |
|---|---|---|---|---|
| orb | static | `external:orb` at cast (t = 0) | P = the projectile start (eye − 0.3 + 0.6·look), basis = caster facing (+Z forward). Small cast flash: sleep sigil + hexagram frame facing forward, snap rings, sparks, dream bubbles, ink flecks | one-shot, emission 0.1 s, gone by ~0.6 s |
| fly | projectile | t = 0 together with `orb` (`orbvfx` = this system's full id) | P = orb centre, +Z = travel. Server-moved (`orb.move` every tick, 16 m/s). When it arrives it is no longer moved and keeps hovering 0.6 m above the eye point until `orb.close()` | loop, held layers (life 60 s), world trail = rateOverDistance |
| eye | static | `external:eye` at the ground point when the orb arms | P = ground point, +Y up. Ink pool, crisp double boundary line at the TRUE radius 3.0 m (+ dashed track, rune band, 6 low posts), inner sleep circle r 1.7, sleep sigil, the sleeping eye lying on the ground (8-frame drowsy blink loop every 3.2 s), sleep waves breathing out to the boundary every 2 s, bubbles / crescents / motes / ink wisps, a soft thread up to the hovering orb. Arming flash in the first 0.5 s | loop, duration 12 s (held life 12 s ≥ wait 10 s). **Stop it when eye_end starts** (`stop: ['eye']`): held layers `onStop` jumpToTail 0.3 s |
| eye_end | static | `external:eye_end` at the ground point (expiry after 10 s, or an enemy stepped in) | same P as eye. Full-size copies of the circles fold inward and spin down, the eye closes (flipbook) and shuts at +0.45 s with a small flash, crescents fall in, bubbles pop, ink puff | one-shot, emission 0.5 s, gone by ~1.0 s |
| latch | attached | `external:latch` on the rooted enemy | enemy feet + facing (authored for a 1.8 m body). Orb clamps onto the chest (splash at +0.16 s), three dream-chain shackles snap around ankles / knees / waist (+0.12…0.3 s) and turn slowly, sleep circle + sigil + ink pool under the feet, ink tendrils, bubbles, drooping motes, 3 crescents circling the head; shackles break at **+3.0 s** (= root end) and fade by +3.25 s | one-shot, emission 3.15 s |

Suggested binding (arcana_spells.py style): `sta(X('orb'), 'orb', 12)`, `sta(X('eye'), 'eye', 200)`, `sta(X('eye_end'), 'eye_end', 20, stop=['eye'])`, `att(X('latch'), 'latch', 64, 'target')`; `orbvfx` = `<pack>:fly` for the moved projectile.

- No runtime inputs (no scale / width / sequence). Ring radius is baked at 3.0 m (= r in the mechanic config); if `r` changes, the pack must be rebuilt.
- `orb.close()` (finish, clear) removes the hovering orb; its world trail particles fade on their own (< 1.4 s).
- Demo: the hovering phase is a second `fly` instance placed at the point (the preview's projectile events cannot stop mid-air), so the orb re-forms for 0.15 s at arrival in the preview only.

## Features
Client-supported only (client_compat fix = 0 changes, check OK). High impact: meshParticle (all line work, shackle bands, sigils, eye decal), bloom, orbitalVelocity (orb satellites, crescents, motes), attachedFollow (latch).
Also: flipbook (eye blink / close, stroke wipes), trails, worldSpace + rateOverDistance (trail), stopTail, stretchedBillboard, noise, drag, gravity, radialVelocity, rotation3D, additive/alpha blend.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
