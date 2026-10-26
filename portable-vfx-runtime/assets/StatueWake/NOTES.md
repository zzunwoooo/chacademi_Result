# StatueWake (247 석상 깨우기, dark) — converter notes

Element palette: dark (lilac → violet → deep indigo, ink accents). No system is tintable. No `scale` module (plays at scale 1).

| system | role | duration | origin / anchor | shows |
|---|---|---|---|---|
| cast | attached | 0.8 s | caster feet, follows caster, +Z = caster facing; effect at the hand (0, 1.25, 0.38) | violet whisper glow, small rune-ring/eye glyph (XY plane, faces forward) drawing on + spinning, inflowing motes with trails, ink motes (dark crescents with violet rims), tiny flash + sparks at 0.58 s |
| wake | impact | 1.5 s | FLOOR under the statue (y = 0 = statue feet), local +Y up, +Z = server yaw (statue front) | violet mist ring + crisp ground circle (rings, 8 vertex circles, rune band) drawing on, dark ink mist on the floor, shock ring; thin ring scanning up the body; two eye glints at (±0.11, 1.55, 0.24) with streaks (t 0.06 s); violet crescent arrow (tilted band, r 0.8, y 0.95 → 1.13) sweeping 110° from +Z toward −X between 0.2 and 0.65 s with arrow-head chevron, halo, echo strokes and a dark ink underlay; ink wisps peeling off behind the head, sparks; end flash + flung dark shards; ground answer ring; all faded by 1.5 s |

Timeline (wake): 0 circle draw-on + mist + scan; 0.06 eyes open; 0.2–0.65 crescent sweep (head linear in time); 0.65 end flash/shards/ground ring; 1.2–1.5 fade.
Statue size assumed 0.9 w × 1.8 h; the eyes sit 0.24 m in front of the origin (brief: ~0.2 m) so they clear a ~0.4 m deep head.
Rotation sense: sweep goes +Z → −X (mesh chevron spins about local Y at −4.07 rad/s; wipe flipbook head is linear over the first 60 % of the stroke life).
Textures: T_SW_* (13 PNG, ≤ 512 px), unique RGBA ≈ 1.6 MiB.
Demo only: caster at (0,0,3) facing the statue; stand-in statue = two grey boxes.



## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
