# NightSight (밤눈) — converter notes (REDO 2026-10-25)

**What this file is**: only the short cast reaction (~1.2 s). The 60 s night vision itself (vanilla Night Vision effect / gamma) is handled by the mod, outside this file.
Do **not** loop or repeat this VFX for the 60 s; play one instance per cast and let it end on its own.

## Contract (unchanged from the previous pack)
| system | role | loop | duration | started by | stop |
|---|---|---|---|---|---|
| `night_sight_cast` | attached (caster feet + body facing) | false | 0.75 s (+ particle life → ≈1.2 s) | cast t=0 | none (ends on its own) |

- Placement: client has no `headAttach`, so the effect sits at the fallback eye point **[0, 1.55, 0.225]** from the feet in the caster's body frame (same as the previous client-compatible pack). No runtime inputs (no scale input).
- No light, no screen filter, no beam — none is in the file.
- Peak moment: eyes ignite at **+0.30 s** (same as before).

## What changed visually (redo)
- 0.00–0.32: a violet **iris sigil** (eye line-work: almond, slit pupil, tick ring) opens ~10 cm in front of the face, turns and collapses into the eyes at 0.30; a crisp horizontal **head ring + rune band** contracts around the head at eye height.
- Lilac motes (with short trails) and **dark ink motes** spiral in from around the head; translucent ink mist behind the head.
- 0.20–0.56: curved wisps along the eye line (stroke-wipe ribbons) with dark ink copies behind.
- 0.30 peak: almond eye glow + horizontal **anime gleam** + star flare per eye + a small line ring flash.
- 0.31–1.1: **violet glow trails** stream back from the outer eye corners along the sides of the head (two wipe ribbons per eye + ink shadow), sparks with trails, dark motes drift off.
- Everything stays head-sized (within ~0.5 m of the eyes); all layers local (they follow the caster).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
