# DimensionMark (250 차원 표식, dark) — converter notes

Element palette: dark (violet, violet-white flash). No system is tintable. No `scale` module.

| system | role | duration | origin / anchor | shows |
|---|---|---|---|---|
| cast | attached | 0.8 s | caster feet, follows caster, +Z = facing; effect at the hand (0, 1.25, 0.38) | violet portal glyph (ring + hexagram + rift eye) spinning, ring + rune band, inflowing motes, ink motes, small flash + sparks at 0.58 s |
| mark | impact | 1.5 s | FLOOR under the gate centre, +Y up, +Z = gate front (server yaw); sigil on the gate plane = local XY at z 0.1, centre y 1.5, r 0.9 | dark ink disc + ink swirl on the gate surface; crisp line work drawing on in three stages (outer rings 0 s → octagram {8/3} + vertex circles 0.12 s → inner star {8/2} + ring 0.22 s), layers turning at different signed speeds; outer rune band (r 0.92–1.01) and inner rune band (r 0.32–0.43) counter-rotating; rift-eye core sigil; vertex twinkles; pulse at 0.75 s (bright copies, expanding wave); motes drifting off, motes drawn in at the start, low ink mist + motes at the gate's foot; fade by 1.5 s |
| linked | impact | 1.0 s | same placement as mark (gate plane z 0.1, centre 1.5) | anticipation (rings contracting, sparks pulled inward with trails, 0–0.25 s) → bright violet-white flash at 0.25 s → 16 rays, three expanding rings offset in time/rotation, sigil + rune flare, core spin, released sparks/shards/motes, motes rising at the gate's foot; fade by ~1.0 s |

Play `linked` on each of the two gates when the second cast links them (the server decides; mark and linked can overlap).
Textures: T_DM_* (11 PNG, ≤ 512 px), unique RGBA ≈ 1.0 MiB.
Demo only: caster at (0,0,3); stand-in gate = two pillars + lintel + dark back plane (2.0 × 3.2).



## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
