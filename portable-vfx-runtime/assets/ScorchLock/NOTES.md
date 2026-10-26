# ScorchLock (작열의 표적, #358 화염 상급) — converter notes

One target (≤ 25 m) is locked for 6 s. A heated aiming line runs from the caster's hand to the target, and a lock reticle tightens and heats on the target, with embers gathering 3 m above its head and a sky sigil opening 12 m up.
**Success** (target kept in sight 6 s): a flame spear drops from the sky sigil and hits the target. **Failure** (sight lost): quiet dissolve, no explosion.
Visual only: line of sight, damage and the mana refund belong to the mod.

## Systems (phased sequence + trigger, SCHEMA §5.1 / 1.7.0)
| system | role | phase | placement (every frame) |
|---|---|---|---|
| aimForm | link | begin (0–0.35 s) | source = caster hand, target = target chest (feet + 1.1 m) |
| lockForm | attached | begin | anchor = **TARGET feet**, facing = horizontal target → caster |
| aim | link | sustain (loop) | same link (the line follows both entities) |
| lock | attached | sustain (loop) | target feet / facing as above |
| aimFire | link | end, `endBranch: "trigger"` | same link: one bright pulse runs down the line |
| strike | attached | end, `endBranch: "trigger"` | target feet: spear fall + impact |
| aimBreak | link | end, `endBranch: "expire"` | line frays and dies |
| fizzle | attached | end, `endBranch: "expire"` | reticle loosens, core sputters out, wisps |

As in FlameLash, the `attached` contract (feet + facing every frame) is applied to the **target** entity. The facing must point toward the caster (the reticle is a vertical XY disc 0.5 m in front of the target's chest, facing the caster).
Hand point suggestion: caster eye − 0.25 m down + 0.3 m right + 0.45 m forward (the preview uses feet + (0.32, 1.38, 0.55)).

## Mod timing
- t = 0 cast → aimForm + lockForm. `sustainStart` = 0.35 s → aim + lock.
- **holdDuration**: send ≥ 5.65 s (default 6.4, min 5.65, max 12). It is only a safety timeout. The mod decides the result itself:
  - **Success** at cast t = 6.0 s (lock complete, target still in sight): send **trigger** → aimFire + strike. In `strike` the sky sigil flares at 0 s, the spear leaves the sigil at 0.10 s and falls 11.9 m, and it **hits at trigger + 0.43 s** (`T_HIT`): apply the big damage at trigger + 0.43 s.
  - **Failure** (sight lost at any time): send **cancel** → aimBreak + fizzle (≈ 1.2 s quiet fade). Refund half the mana. Hold expiry (no trigger) plays the same failure end.
- The impact layers of `strike` are `space: world`: they stay where the spear landed even if the target keeps moving. The spear itself (S10–S14, local) follows the target while it falls, so it never misses visually.
- If the target dies or disappears mid-lock, send cancel and stop the attached systems (clear local layers).

## Held layers
The reticle (L01–L04b), the ground lock circle (L06/L07/L07b), the ember-core glow (L12) and the sky sigil and guide (L17–L20) are each ONE particle, life 59 s, `onStop` tail 0.45 s.
Their colour and size curves are keyed in sustain-local seconds: the heat ramp is red → orange → gold → pale → white at 5.65 s (= cast 6.0 s), the brackets/rings tighten 2.0 → 0.98 in 8 beats that come faster near the lock (beat flash copies L05 at the same times), and the sky sigil opens from 3.4 → 4.7 s.
After the lock the held layers stay white until hold.max (12 s). In the 0.45 s tail they cool to orange and fade. The sky copies vanish in the tail because `strike` has its own flare copies (S03b–d).

## Features
All features are client-supported. `client_compat check` is OK and `fix` makes 0 changes. High-impact features used: **meshParticle** (reticle / ground / sky line work, column, arcs), **bloom**, **attachedFollow** (lock / strike on the target), **pathFollow + linkTarget** (aim line), **orbitalVelocity** (sky motes), phasedSequence + triggeredEnd + stopTail.
Texture memory: 18 unique textures ≈ 3.4 MiB RGBA.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
