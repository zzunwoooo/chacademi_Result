# AirChoke (숨 막힘, #346, id air_choke, 바람 중급) — converter notes

One target (≤ 12 blocks) has the air around it taken away and is **disarmed for 3 s** (can't cast or attack). The VFX shows the spell; the disarm icon comes from the game's own debuff UI.
Cast: a thin twisting wind thread runs caster hand → target throat in 0.2 s, and the rings snap shut. Hold: a mint line-work collar + rune band + slate dark band tighten around the neck in 6 squeezes. Two tilted chest rings counter-rotate, wind cuffs clamp both wrists, wind strokes wrap the upper body, and air streams OUT of the body (fine streaks, breath wisps from the mouth, bubbles, motes) in front of a dark vacuum lens. End: the rings release outward and the target gasps (inhale streaks + a soft puff and ring from the mouth).

## Systems (phased sequence, SCHEMA §5.1)
| system | role | phase | placement (every frame) |
|---|---|---|---|
| air_choke_cast | link | begin (0–0.25 s) | source = caster hand, target = target throat (target feet + 1.45 m) |
| air_choke_form | attached | begin | anchor = **TARGET feet**, facing = target body facing |
| air_choke | attached | sustain (loop) | target feet / facing |
| air_choke_release | attached | end | target feet / facing |

Hand point suggestion: caster eye − 0.25 m down + 0.3 m right + 0.45 m forward (the preview uses feet + (0.32, 1.38, 0.55)).

## Mod timing
- t = 0 cast → cast + form. The thread reaches the throat at **t = 0.2 s**: apply the disarm here (0.2 → 3.2 s).
- `sustainStart` = 0.25 s → air_choke. **holdDuration = 2.95 s** (default = max), so the end phase starts at 3.2 s = when the disarm ends → release (≈ 0.75 s soft gasp).
- Cleanse / target death / leaving range: send **cancel** (the same release end plays at once). If the target entity is removed, stop and clear the attached systems.
- Optional `scale` (targetHeight, reference 1.8, 0.6–2.4): the collar/rings fit small and large mobs. Without the input it plays at player size.

## Held layers
The collar (C01–C04b), chest rings (C05–C07), wrist cuffs (C07a/b), vacuum lens (C15), neck glow (C17) and ground mark (C18–C20) are each ONE particle, life 59 s, `onStop` tail 0.35 s.
Their size curves are keyed in sustain-local seconds: squeezes at 0.3 / 0.95 / 1.5 / 1.95 / 2.35 / 2.7 s, the collar tightens 1 → 0.84. In the tail they expand (×1.3–1.8) and fade = the release. `F03 CollarIn` (begin) ends exactly where C01 starts (same ω, rot3 = −ω·0.22).

## Features
All features are client-supported. `client_compat check` is OK and `fix` makes 0 changes. High-impact features used: **meshParticle** (collar / ring / cuff / ground line work, wind wrap, arcs), **bloom**, **attachedFollow** (everything on the target), **orbitalVelocity** (motes), plus **pathFollow + linkTarget** (cast thread), phasedSequence, stopTail, instanceScale, flipbook, noise.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
