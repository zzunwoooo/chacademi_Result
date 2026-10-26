# PackSort (배낭 정리, 생활 #382, id pack_sort, 어둠) — converter notes

## System and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| pack_sort | attached | cast (t = 0), once | anchor = caster feet, basis = caster horizontal facing (+Z forward). duration 3.0 s, loop false, no stop signal; everything is gone by ≈2.75 s. |

Timeline: 0.00 pinch spark → 0.05–0.31 small galaxy pocket portal opens at the caster's back-right (local (0.67, 1.1, −0.67), r 0.42 m; ink-indigo starry disc + counter-spinning swirl, violet line-work rim/ticks/hexagram, constellation rune band, ink mist behind) · ground constellation circle (r 1.15 m) draws on · 0.12–0.20 three slot rows (orbit ring r 0.95 m + 12 slot circles) draw on at heights 1.37 / 1.10 / 0.83 m
→ 0.28–0.82 mixed item stream (gold coin / cyan block / pink gem, interleaved) leaves the portal and **splits by colour** into the rows (coins top, blocks middle, gems bottom); items orbit at 5.5 rad/s and the slot rows turn with them, so every item sits on a slot dot
→ ≈0.9–1.4 **merge**: at the row's stack station (left side) the second item of each pair catches up with the one ahead → pop star + snap ring + spray; the kept item bumps to a bigger "stack" (×1.12)
→ 1.12–1.87 the merged stacks finish the loop and are sucked back into the portal one by one (twinkle at the mouth each time)
→ 1.88 portal swells → **2.00 snaps shut: "click" flash** (star, ring, sparkles, coloured confetti), ground circle/sigil flare, wave → motes to ≈2.7 s.

Item choreography is exact (fixed-step math in the spec): each item = kept (A) → return (R) copies; second (B) → merge (M) copy that catches up with A over 0.14 s (orbitalY = ω + slot/0.14). Hand-off positions are computed from the drag formula so there are no jumps. Merge pops are local orbiting particles placed at the stack station.

**Recommended mod timing:** run the actual sort + merge at cast +2.0 s (the click), or at t = 0 if the cast is instant; the server/inventory UI shows the result. Not a combat effect: no range ring.

## Features
Only client-supported features (client_compat fix: 0 changes; check OK; validate OK).
High impact: **meshParticle** (ground circle line work, slot rows, portal disc/rim/runes), **bloom**, **attachedFollow**, **orbitalVelocity** (sorted items, merges, pops, motes, ink shards).
Others: worldSpace, stretchedBillboard, trails, drag, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha blend, hdrTint, neutral tonemapping.
Texture memory: 18 unique textures (≤ 256 px), ≈2.21 MiB RGBA unique.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
