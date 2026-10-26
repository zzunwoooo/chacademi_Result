# Counterweight (242 무게추) — converter notes

Element: earth (pale gold → amber → ochre, moss-green accent on rising motes). Not tintable (no system is tintable).

| system | role | duration | origin / anchor | shows |
|---|---|---|---|---|
| `cast` | attached | 0.8 s | caster feet, follows caster, +Z = facing; elements at hand height (0, 1.2, 0.38) | amber glow, vertical earth sigil quad (spinning), two line rings, inward motes, sparks, small flash at 0.2 s, two rings of orbiting dark pebbles |
| `drop` | impact | 1.6 s | floor at the pressure-plate centre (y = 0 = floor, plate top y = 0.2), +Y up, +Z = server yaw (effect is symmetric, yaw only rotates the rune details) | see timeline |

`drop` timeline (s): 0–0.55 amber rune sigil at y = 3.0 (r 0.6: line rings, hexagram + vertex circles, rune band, central emblem quad, drips, motes) ·
0–0.25 weight forms at y = 2.75 (solid mesh, dark stone with amber rune lines, depthWrite) · 0.25–0.65 weight falls (gravityModifier computed so the
fixed-step simulation lands exactly at 0.65 s: centre 2.75 → 0.5, bottom on the plate top) + speed streaks + growing shadow on the plate ·
0.65 impact: small amber flash, plate ring, 2 shock line rings, ground crack decal (dark + glow copies), dust ring, rock chips, sparks; ground circle
(rings, octagram, rune band, sigil quad, r ≤ 1.05 around the 1.0 m plate) flares · 0.65–1.05 weight sits, rune motes rise · 1.05–1.25 weight shrinks/fades
while dark shards, sand puffs and sand grains burst out · everything faded by 1.6 s.

Notes
- The weight is three consecutive copies of the same mesh particle (form / fall / rest); the hand-off times are exact (0.25, 0.65 s). Each copy has an
  additive rune-overlay copy (same mesh, size 1.012).
- Weight body: alpha material with depthWrite (texture alpha 1, per-face shading baked into a 2×2 atlas) and HDR tint 1.55 so the rune lines bloom while the stone stays dark.
- No scale module, no client-unsupported fields. Textures ≤ 512 px.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change

## 2026-10-20 이름 변경
폴더·팩 이름을 Counterweight → StoneWeight 로 바꿨다 (123 반격의 무게추 팩 Counterweight 와 겹침). 효과 id = claude:stoneweight/cast, claude:stoneweight/drop.
