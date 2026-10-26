# InsightFlash (영감의 섬광, 생활 #384, id insight_flash) — converter notes

## System and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| insight_flash | attached | cast (t = 0), once | anchor = caster feet, basis = caster horizontal facing (+Z forward). duration 3.6 s, loop false, no stop signal; everything is gone by ≈3.45 s. Idea spark point = (0, 2.35, 0); head rune ring centre = (0, 1.92, 0), r 0.44 m; lingering glyph = (0.40, 2.02, 0.06) (right of the head). |

Timeline: 0.00 small light circle (r 0.9 m) draws on at the feet with a runner; gold + pale-cyan motes spiral up from it into a point above the head → 0.25–1.10 idea spark builds at (0, 2.35, 0): navy back disc, glow, star core growing, gear halo turning, three tilted orbit rings (atom/bulb motif) spinning, in-drawn sparks in growing staged bursts; 0.5 a golden rune ring draws on around the head → **1.15 s EUREKA**: star flash + ray bursts (gold + cyan), head ring flares with 16 short rays, ring waves, streak sparks, a few dark gold-rimmed shards, ground circle flare → 1.2–2.4 gold/white/pale-cyan confetti tumbles down, glints wink → 1.3–3.45 tiny bulb glyph + cyan orbit ring beside the head, winks out at 3.25.

**Recommended mod timing:** arm the hint (next alchemy attempt shows how many of the inserted ingredients are correct) at cast +1.15 s (eureka), or at t = 0 — the visual reads either way. The number itself is server UI, not part of this VFX. Nothing needs to be stopped if the caster moves (local layers follow the feet; confetti is world-space).

Rotation: ground circle outer 0.4, rune −0.55, inner 0.8 rad/s; head ring lines 0.9, runes −1.2 rad/s; flare copies start with rot3 = ω·(tFlare − tBorn) (head lines born 0.5, flare 1.15; ground outer born 0, flare 1.21; ground rune born 0.18, flare 1.23). Not a combat effect: no range ring.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
