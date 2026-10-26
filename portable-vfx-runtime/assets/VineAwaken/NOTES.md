# VineAwaken (243 덩굴 깨우기) — converter notes

Element: earth, green-gold palette (white → pale gold → leaf green → moss). Not tintable.

| system | role | duration | origin / anchor | shows |
|---|---|---|---|---|
| `cast` | attached | 0.8 s | caster feet, follows caster, +Z = facing; elements at (0, 1.2, 0.38) | green-gold glow, vertical leaf sigil quad, line rings, inward motes, sparks, small flash, leaf motes orbiting the hand |
| `sprout` | impact | 2.0 s | floor under the seed pot (pot 0.8 w × 2.8 h around the origin), +Y up, +Z = server yaw | see timeline |

`sprout` timeline (s): 0–0.3 growth circle draws on the floor (leaf sigil quad r 1.0, two line rings + ticks, hexagram pair + vertex circles, leaf-green
rune band) · 0.1 sprout flash, leaves and dust burst at the pot base · 0.1–1.3 three vines spiral up the pot (r 0.58, one turn, 0 → 3.2 m): each vine
= 10 helix ribbon segments (alpha vine strip + additive glow ribbon) appearing as a glowing growth tip (orbital + linear velocity, with trail) passes;
leaves flutter off along the way, pollen and rising streaks · 1.3 bloom at the top (y 3.2–3.3): flash, glow, spinning leaf sigil, two ring pulses,
petals and sparkle burst; vine glow brightens · 1.76–2.0 everything fades.

Notes
- Growth tips: `orbitalY` = 2π/1.2 rad/s + linear y 2.667 m/s from the vine start points, so they trace exactly the helix the ribbon meshes are built on.
- Leaves use alpha-blended leaf textures (two tints; the darker copy is the dark accent).
- No scale module, no client-unsupported fields. Textures ≤ 512 px.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
