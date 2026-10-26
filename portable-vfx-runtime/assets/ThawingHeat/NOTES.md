# ThawingHeat — 239 해빙의 열기 (fire)

Caster channels heat into an ice seal crystal (model 1.0 w × 1.6 h) for 3 s; damage cancels the channel.
All systems are non-loop, scale 1, no tint (none are tintable). Impact systems: local origin = FLOOR under the seal (y = 0 ground),
+Y = world up, +Z = yaw direction = toward the caster.

| system | role | duration | origin / anchor | shows |
|---|---|---|---|---|
| cast | attached | 3.0 s | caster feet, +Z = facing | both hands glow orange with small rising flames (x = ±0.26, y ≈ 1.05, z = 0.34); fire hexagram sigil (mesh quad, 0.44 m) at chest y 1.2 z 0.42 spinning + counter-rotating line-work ring with ticks; pulsing core glow; heat motes drawn into the sigil; heat-shimmer streaks pushed forward (+Z); small sparks. Sustained 3 s, sigil fades in the last 0.2 s. |
| melt | impact | 3.0 s | floor under the seal | scorch decal (dark translucent) + flat heat glow; line-work fire circle r 0.88 (rings, octagram {8/3}, vertex circles, ticks) drawing on in 0.3 s and turning 0.25 rad/s, rune band r 0.60–0.74 counter-rotating; brighter copies of the circle at 1.5 s and 2.3 s (glow intensifies); rim flames in growing bursts; 3 sets of heat ribbons (stroke-wipe helix meshes r 0.80→0.62, up to 1.8 m) spiralling up the crystal, more often over time (climax 1.95–2.4 s); heat licks + skin glows climbing the crystal; steam wisps off the top and sides (additive white); water drips (stretched pale-cyan dots) and ice glints on the crystal; embers spiralling up; heat motes streaming in from the caster side (+Z, 1.7 m); a small fire crown ring at 1.74 m from 0.95 s. Everything fades by 3.0 s. |
| thaw | impact | 1.2 s | same origin | completion: fire circle blows out (starts at rotation 0.75 rad = melt's end rotation), warm star flash + glow at 1.0 m, steam burst outward + steam column upward from ~0.9–1.4 m, ice shard fragments (stretched + tumbling, pale cyan with white rims), ice glitter, ground water ring (2 waves) + water splash droplets, warm shockwave ring, sparks, embers. |
| fizzle | impact | 0.6 s | same origin | cancel: fire circle snaps (flash → shrinks to 0.72 and goes dark red), ring bits falling inward, small cold puff (blue-white steam) around the crystal, blue frost sparkles, small frost ring, a few dark smoke wisps at the ring. |

Server order: cast + melt start together; on success the server ends melt at 3.0 s and plays thaw; on damage it removes cast + melt and plays fizzle.
Demo: stand-in box 1.0 × 1.6 × 1.0 (opaque), caster 2.6 m away on −Z of the world (seal +Z points to the caster). Scenarios: full, close, cancel (cancel at 1.6 s).
Decisions: ribbons/glows sit at r ≥ 0.62 so they stay visible around the box corners (a 1 m box reaches r 0.71; the real crystal model is presumably slimmer).
Textures are 512 px or smaller; flame/steam sheets are 4×4 × 96 px.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
