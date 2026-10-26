# ConduitTurn (249 도관 회전) — converter notes

Element: earth (amber) with a cyan mana accent at the pipe ends. Not tintable.

| system | role | duration | origin / anchor | shows |
|---|---|---|---|---|
| `cast` | attached | 0.7 s | caster feet, follows caster, +Z = facing; elements at (0, 1.2, 0.36) | small amber glyph: glow, vertical earth sigil, line rings, spinning arc segments, motes, sparks, flash |
| `turn` | impact | 0.7 s | floor at the conduit centre, +Y up, **+Z = the pipe axis before the turn** (pipe 0.5 w × 0.4 h, ends at z = ±0.5) | see timeline |

`turn` timeline (s): 0–0.1 ground glyph draws on (r 0.36–0.6: line rings, ticks, rune band, small earth emblem quad, rotating) · 0.02–0.22 cyan line along
the pipe top (Z) · 0.05 cyan-amber flash + sparks + dust puffs at both pipe ends (0, 0.2, ±0.5) · 0.05–0.37 amber circular arrow sweeps 90° from +Z toward −X
(rotation about +Y by −90°, i.e. clockwise seen from above per the brief), arrowhead + tapered head arc + glow + glints, arc segments stay behind it ·
0.37 arrowhead settles at −X with a small flash, cyan line along X, flash/sparks/dust at (±0.5, 0.2, 0), a few chips · 0.5–0.7 fade.

Decision: the brief puts the arrow "on the ground (r≈0.45)", but the 1.0 m-long pipe hides a ground arrow at r 0.45 almost completely (start and end
points are under the pipe ends). The ground glyph (rings/runes/emblem) stays on the floor; the sweeping arrow (r 0.48) hovers at y = 0.445, just above the
pipe top (0.4 m), so it reads from every angle. Lower `pos` y of layers S01–S06 / A0x to 0.03 if the conduit model is shorter than assumed.
- No scale module, no client-unsupported fields. Textures ≤ 512 px.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
