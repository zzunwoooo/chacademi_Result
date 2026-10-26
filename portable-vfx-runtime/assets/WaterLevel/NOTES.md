# WaterLevel — 241 수위 조절 (water)

Raise (normal cast → `rise`) or lower (sneak cast → `drain`) the water level of a water pillar (0.8 w × 2.0 h). Non-loop, scale 1, none tintable.
Impact origin = FLOOR under the pillar, +Y = world up, +Z = yaw (effect is rotationally symmetric, yaw does not matter).

| system | role | duration | origin / anchor | shows |
|---|---|---|---|---|
| cast | attached | 0.8 s | caster feet, +Z = facing | right hand (x 0.26, y 1.1, z 0.4): cyan glow, droplets gathering in staged bursts, 7 droplets orbiting the hand (r 0.17) with water-stroke arcs, small water sigil (octagram + droplet glyph) in front; at 0.6 s a small flash + spray forward. |
| rise | impact | 1.2 s | floor under the pillar | ground: dark-blue wet decal, line-work water circle r 0.84 (rings, octagram {8/3}, vertex circles, ticks) + rune band, base ripple rings; three water ribbons (stroke-wipe helix meshes r ≈ 0.6) spiral UP from 0 to 2.1 m (head reaches the top at ~0.4 s); foam sparkles rising with the ribbon head; droplets + streaks thrown off at 0.6 / 1.25 / 1.9 m; at 0.42 s at the top (2.1 m): two ripple rings, splash crowns, foam sparkles, mist, droplets, small glow. Circle swells slightly and fades by 1.2 s. |
| drain | impact | 1.2 s | same origin | top ripple contracting + foam at 2.1 m; three water ribbons spiral DOWN from 2.1 m into the ground; foam sparkles descending; sinking droplets at 1.8 / 1.15 / 0.55 m; from ~0.38 s on the floor: vortex spiral arms, splash crowns, three ripple rings spreading to r ≈ 1.0, floor spray, sinking glow; the ground circle contracts (to 0.82) as it fades by 1.2 s. |

Demo: stand-in box 0.8 × 2.0 × 0.8, caster 2.6 m away; impact starts 0.6 s after the cast. Ribbons use r 0.6 (brief r ≈ 0.55) so they clear the stand-in box corners.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
