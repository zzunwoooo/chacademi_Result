# misfortune_whisper — 나약함의 저주 (converter notes, REDO 2026-10-25)

Display name changed from 불운의 속삭임 to **나약함의 저주**; the internal id `misfortune_whisper` is kept (bundle / sustain system id).
Systems: `misfortune_whisper_begin` (begin), `misfortune_whisper` (sustain), `misfortune_whisper_end` (end), all `role: "attached"` to the **cursed enemy** (feet = origin, +Z = the enemy's facing, +X = its right side).

## Runtime
- Cast on one enemy: start one phased cast attached to that enemy (position + horizontal facing every frame, SCHEMA §5).
- `sequence.sustainStart = 0.7`, so for an 8 s debuff pass **holdDuration = 8 − 0.7 = 7.3 s** (the default). The end (break-up) plays at 8.0 s after the cast. If the debuff time ever changes, pass `debuffSeconds − 0.7`.
- Debuff removed early (cleanse, enemy dies): send cancel (`onCancel: jumpToEnd`) → fragments break up at once. Enemy despawns: the attached rule (stop emitting, clear local layers).
- `scale.input = targetHeight` (reference 1.8 m): pass the enemy's height so the head / wrist points fit small and large mobs.
- Attack / magic power reduction, mana 60, cooldown 20 s and the unlock (kill 5 monsters while weakened) are game logic, not part of this effect. Reduction values are **not decided** — nothing in the effect depends on them.

## Hand positions (approximation)
- The client has no hand/bone attachment, so the wrist and fingertip points are **fixed body-frame points** of a Minecraft-style idle arm pose (1.8 m): right wrist (+X, attacking hand) `[0.37, 0.86, 0.02]`, left wrist (−X, casting hand) `[-0.37, 0.86, 0.02]`, fingertips 0.19 m lower. They follow the enemy's position, facing and height but **not arm swing animations**. True hand tracking needs a client feature (hand attach point) — reported, not faked.

## Look (REDO 2026-10-25)
- Begin (emission 0–0.35 s): ink whisper ribbons (violet + dark ink) curl around the head while motes are drawn in; a drooping-thorn curse halo (mesh line work, 10 thorns hanging down = weakening) forms over the head at 0.15 s with a small flash/wave; a faint small curse sigil at the feet fades by 1.2 s. At **0.30 s** a ring contracts on each wrist and a broken curse cuff (segmented ring + ticks + rune band) snaps on: flash, wave, chips, sparks, ink puff.
- Sustain: per wrist a held cuff + rune ring + vertical rune band (one particle each, onStop 0.2 s), orbiting dark ring fragments, faint rim glow, black powder, ink drops (world space) and violet motes falling from the fingertips. Over the head the halo keeps turning; weak thoughts (violet motes with trails, thin streaks, dark wisps) sink from it.
- End: cuffs and halo flash, expand and break into shards (world space), powder crumbles down.
- Rotation hand-off: begin cuffs start at 0.30 s, sustain copies start at 0.7 s with rot3 = ω·0.4 (halo: ω·0.55), so they line up while crossfading.

## Features
High impact: `attachedFollow`, `phasedSequence`, `meshParticle` (wrist seal quads), `orbitalVelocity` (fragments), `bloom`. Also `stopTail`, `instanceScale`, `flipbook`.


## PortableVFX client compatibility
- Default/null fields of toolkit 1.14–1.21 (height, depth, scale.systems/keepPosition, orientation, attachPoint, follow, lights, rollWithTravel) removed with tools/client_compat.py fix; no visual change.


## REDO 2026-10-25 (visual rebuild, same contract)
- Same systems / ids / roles / phases / durations (begin 0.35, sustain loop 1.3, end 0.2), same sequence (sustainStart 0.7, hold 0.3–59, default 7.3) and scale (targetHeight 1.8, 0.5–3). Wrist / fingertip points unchanged.
- Replaces the old thin ink-ring quads with 3D mesh cuffs and a curse halo; still no full-body aura and no large circle (only a small fading sigil at the feet during begin).
- Only client-supported fields (client_compat fix = 0 changes, check OK). Demo scenario `wrist` (close-up) added.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
