# VacuumBurst (진공 폭발, 중급 344, 바람) — converter notes

## System and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| vacuum_burst | static (P = aim point on the ground, basis = caster's horizontal facing) | cast, t = 0 | one-shot, not looped, duration 1.1 s; last particles end ≈ 2.1 s. No stop / hold needed |

## Gameplay timing
- 0 … 0.8 s: pull phase (air of radius 4 is gathered into a compressed orb 1.2 m above P). The thin range ring (G01 RangeRing) is drawn at the real radius **4 m** from t = 0.
- **0.8 s: BURST = damage tick** (flash, ground shock rings, outward rays/strokes). Edge dust at the range ring follows at 0.86–0.92 s.
- Radius 4 is fixed (no scale input). If the server radius changes, tell us and the pack is rebuilt.
- If the mod applies a pull force, apply it during 0 … 0.8 s (the visuals show inward motion exactly in that window).

## Features
Only client-supported features. High impact: meshParticle (line-work circle, rune band, spiral wind ribbons, range ring), bloom. No orbitalVelocity-dependent shape beyond inflow swirl (orbitalY is used on inflow streaks/motes), no attachedFollow.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
