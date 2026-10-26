# WraithMarch (망령의 행진, #366 상급 어둠, id wraith_march) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| wraith_march | static | cast (t = 0) | P = caster feet, M = caster horizontal facing (+Z = aim). Sigil at the feet, 5 wraiths march +Z from z 1 to z 15, ink dissolve at z 15. Duration 3.6 s, everything authored in place (no runtime inputs). |
| wraith_march_fear | impact | the mod, once per enemy hit | hitPoint = enemy feet, surfaceNormal = up, incomingDirection = aim. Scream ripple + shudder motes + ink cling, ~1 s (fear 1 s). |

## Timing (seconds from cast)
- Sigil draws on 0 … 0.3 s, lane edge lines (true hit width 2 m = x ±1.0, z 0.6 … 15) wipe on 0.1 … 0.6 s, end cap at z 15.
- Wraith k (k = 0 … 4) rises out of the sigil at t_k = 0.42 + 0.2·k at z 1.0 and glides +Z at 7.5 m/s (fixed velocity), reaches z 15 at t_k + 1.867 s (2.29 / 2.49 / 2.69 / 2.89 / 3.09 s) and tears into ink there.
- **Damage**: the leader's front passes a point at distance z (m from the caster) at **t = 0.42 + (z − 1.0) / 7.5** s; the column covers that point for the next 0.8 s (followers). Start one `wraith_march_fear` impact per enemy at its first hit. If the server applies damage per wraith, each wraith k passes at that time + 0.2·k.
- If the server's march speed / range differ from 7.5 m/s / 15 m, tell us and the pack is rebuilt (static pack, no scale input).

## Features
Only client-supported features (client_compat check OK, fix = 0 changes). High impact: meshParticle (sigil line work, lane lines, footprint lane, shoulder ribbons, chains), bloom, orbitalVelocity (motes/arcs). No attachedFollow.
Wraith bodies = 4×4 flipbook billboards (alpha, HDR rim) with flipbook cycles + an additive echo copy; lane lines use a 1×8 wipe flipbook; footprints a 1×16 reveal flipbook.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
