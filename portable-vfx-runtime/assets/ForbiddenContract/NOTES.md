# ForbiddenContract (금서의 계약) — converter notes (REDO 2026-10-25)

Visual only. Mana→health conversion, mana-regen block, 70 % max-mana cost, 180 s cooldown and the library book condition are game features outside this file.
Removed effects (magic damage up, healing halved) are intentionally NOT represented.

**Four systems, all `role: attached` to the caster** (anchor = feet, facing = body yaw, SCHEMA §5 attached). Each is started/stopped by its own mod event:

| system | start | stop | notes |
|---|---|---|---|
| `contract` | cast moment | — (one-shot, duration 1.15) | foot contract circle + 7 grimoire pages whirl around the caster 0–0.35 s, contract seal stamps on the chest at **0.30 s** (flash, wave, sparks), two seal chains snap around the torso 0.30–0.45 s, pages burn crimson 0.6–1.35 s, ash/embers to ~2.0 s |
| `conversion` | conversion event begins | conversion event ends (3 s, mod-timed) → stop emitting | `loop: true`, duration 1 (unchanged). Held chest seal / ring / torso chain ring / hand rings = one particle each (onStop tail 0.15 s). Violet mana streams from both hands into the seal and turn crimson; heal pulse (wave, body ring, crimson ribbon, flash) at 0.45 s and then once per second (rate 1). Particles finish ≤ 0.7 s after stop. Has its own chest seal, so it does not need `contract` to be running. |
| `seal_lock` | mana-regen-blocked state begins | state ends (15 s, mod-timed) → stop | `loop: true`, duration 20 (unchanged): dark locked seal on the chest + two crossed chains + a chain ring binding the torso (one held particle each, life 20), breathing glint, embers / ash / dark wisps. Held layers have `onStop` jumpToTail (vanish ≤ 0.12 s). |
| `seal_break` | same moment the blocked state ends | — (one-shot, duration 0.05, ~0.9 s) | seal and chains snap outward and shatter: flash, wave, link shards, ash, embers, ink puff |

**Do not chain** `conversion` and `seal_lock` to each other or to `contract`: the spell does not fix their start relation. They may overlap (demo scenario "전환·금지 동시 시작"); the conversion visuals must still play while regen is blocked.
If the blocked state is refreshed/re-applied while active, keep the running `seal_lock` instance (do not restart) — restart only if it was stopped.

Demo scenarios: `seq` (lock shortened to 6 s), `overlap`, `full` (real 15 s), `walk`. Demo start/stop times are illustrative only.


## REDO 2026-10-25 (visual rebuild, same contract)
- Same 4 systems, ids, roles (all attached to the caster), durations and loop flags; chest seal positions unchanged ([0, 1.3, 0.34] / [0, 1.32, 0.19]); hands at (±0.42, 1.02, 0.32).
- The old flat UI-like billboards (paper scrap, round stamp icon) are replaced by 3D forms: tumbling grimoire page meshes orbiting the caster, a vertical contract seal (pentagram + keyhole) and line-work ring facing forward, interlocking chain-link meshes around the torso and crossed over the chest, a mesh line-work contract circle at the feet.
- Note: by SCHEMA a looping system's bursts fire once per run (only rate emission repeats). The old file's "heal pulse once per cycle" was a one-time burst; the redo uses rate 1 so the pulse really repeats every second while converting.
- Only client-supported fields (client_compat fix = 0 changes, check OK).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
