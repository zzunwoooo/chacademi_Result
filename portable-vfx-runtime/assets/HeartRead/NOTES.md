# HeartRead (마음 읽기, #383, heart_read) — converter notes

Life spell (어둠 / 생활). The caster looks at an NPC: a soft violet-pink thread with star beads runs from the caster's eye to the NPC's chest,
a small keyhole-eye heart glint blooms above the NPC's head, pulses once (= the moment the gift hint UI appears) and a pink glint runs back to
the caster's eye sigil. ~2.8 s, one-shot, no combat. Visual only: the gift hint, affinity lookup and the once-per-day-per-NPC limit are the mod's job.

## Systems and runtime inputs (all three start at the same tick, at cast; no stop / hold / trigger signals)
| system | role | placement | length |
|---|---|---|---|
| read | link | source = caster eye point, target = NPC chest point (both every frame) | emits 0–1.45 s, all gone ≈ 1.95 s |
| glimmer | attached | the NPC's feet + facing | heart 0.8–2.6 s, gone ≈ 2.8 s |
| cast | attached | the caster's feet + facing | gone ≈ 2.0 s |

- Link direction: z runs caster → NPC. Source (suggested) = caster eye position + 0.28 m along the look direction (keeps the eye sigil in front of the face; in first person it sits just in front of the camera — fine, or offset 0.45 m). Target = NPC feet + 0.67·height (preview feet + 1.2 m).
- Heart height: authored at 2.45 m above the NPC's feet for a 1.8 m NPC (≈ 0.65 m above the head). For other NPC sizes use the NPC's height + 0.65 m if the mod can offset; otherwise fine as is.
- Timing: thread reaches the NPC chest at **0.65 s**; heart appears at 0.8 s; **heart pulse = 1.25 s → show the gift hint UI then**; feeling returns to the caster's eye at 1.6 s.
- If the cast is refused (already used today on this NPC / not an NPC), do not start the VFX (or start only `cast`).
- If the NPC or the caster disappears early: standard attached/link rules (stop emitting, clear local layers).

## How it is built
- Thread = stretched path segments (SCHEMA §4.8): white core (40/s) + lilac haze + two thin helix strands (pink / violet, wave phase 0 / π) on one gentle upward arc (bend y 0.1). A star "draw head" leads it in 0.5 s. Star beads, pink dots and dark ink star beads (alpha, violet rim = dark accent) drift along more slowly with small random bends.
- Caster: eye sigil (almond eye + keyhole pupil) with rotating starry halo and glow at the link source; small line-work foot circle (hexagram + rune band, counter-rotating) + rising motes/stars/spiral streaks.
- NPC: line-work foot circle answers on arrival; a light wisp rises from the chest to the heart; heart = ink veil (alpha, behind) + pink glow + rotating starry halo + double heart outline with dotted inner heart + keyhole eye inside; 3 orbiting stars + 3 counter-orbiting ink beads; the pulse = hot heart copy expanding, two rings, a glint, 10 motes with trails + 6 stars released upward.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
