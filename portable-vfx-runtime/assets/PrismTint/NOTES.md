# PrismTint (프리즘 물들이기, 246) — converter / server notes

| system | role | duration | origin / anchor | shows | tintable |
|---|---|---|---|---|---|
| `cast` | attached | 0.7 s (non-loop) | caster feet, +Z = facing; glyph at (0, 1.2, 0.38) | pale-gold prism glyph (hexagon/hexagram/diamond sigil), hexagon ring, glow, orbiting facets, inflowing motes, sparkles, small flash at 0.42 s | **no** (pale gold) |
| `tint` | impact | 1.0 s (non-loop, fade last 0.2 s) | FLOOR under the prism, +Y up, +Z = yaw; crystal centre at y 1.1 | 0–0.45 s two light ribbons + one thin ribbon swirl in along a conical spiral (r 0.85 → crystal centre), motes gather; 0.45 s crystal flash (flare, glow, star, vertical/horizontal ray cross, vertical hexagon); ring of facets, sharp ring + hexagon ring, sparks and sparkles burst outward at crystal height; 0.47–1.0 s colour motes and glints drift up; ground facet circle (hexagram line work) under the prism throughout | **yes** (near-white) |

## Tint
- All `tint` materials are neutral near-white (HDR tints ~[2.0,2.0,1.95]); the server's colour multiply gives the new prism colour
  (checked with a magenta tint in the preview). No dark accents. `cast` keeps its own pale gold and is not tinted.

## Notes
- The flash billboards sit at the crystal centre; an opaque crystal model will hide their middle (the outer flare/rays/rings still read, as in the preview
  with the stand-in box). If the crystal model is translucent the whole flash shows.
- Swirl ribbons use a 1×16 comet-wipe flipbook (256×512 px) so the head reaches the crystal at ≈ 0.42–0.45 s.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
