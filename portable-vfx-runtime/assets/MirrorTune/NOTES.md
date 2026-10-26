# MirrorTune (반사 조율, 245) — converter / server notes

| system | role | duration | origin / anchor | shows | tintable |
|---|---|---|---|---|---|
| `cast` | attached | 0.7 s (non-loop) | caster feet, +Z = facing; glyph at (0, 1.2, 0.38) | golden turning glyph (sigil quad with curled arrow), ring, fast curl streak, glow, sparkles, inflowing motes, small flash at 0.42 s | no (gold) |
| `turn` | impact | 0.8 s (non-loop, fade 0.6 → 0.8 s) | FLOOR under the mirror / dial, +Y up, +Z = yaw | golden circular arrow (mesh line work: double main band radial + vertical, guide lines, ticks) at y 0.8, r 0.7, sweeping 90° from +Z toward −X in 0.06 → 0.51 s; arc body draws on in 9 pieces behind the head, light streak trails the head, sparkles drop off the path, arrow flashes at completion, sparkle/spark burst + small ring at the head (−0.7, 0.8, 0); ground facet circle + faint dashed guide ring | no (gold) |

## Direction / handedness
- Authored in the vfx.json (Unity, left-handed) local frame exactly as the brief: the head starts at +Z (angle 0) and ends at −X
  (rotation about local +Y of −90°, `rotationOverLifetime.y` = −3.49 rad/s, orbitalY = −3.49 rad/s).
- In the left-handed preview seen from above this looks counter-clockwise; with the usual direct axis mapping into Minecraft (x→x, y→y, z→z,
  right-handed world) it is clockwise seen from above, as the brief says. If the server's mapping differs, mirror the X axis of this system only.

## Timing
- Sweep 0.06 → 0.51 s (constant angular speed; schema has no eased rotation). Arc piece k (10° each) appears at 0.06 + 0.45·(k+0.5)/9 s.
- Moving head (life 0.45 s) is replaced by a static head at −X at 0.51 s (same pose), completion flash 0.49–0.77 s, all faded by 0.8 s.

## Decisions
- Arc pieces are separate layers so the draw-on is crisp mesh line work without a large wipe flipbook (pack stays tiny).
- No dark accents (light element utility effect, owner asked not to overdo it).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
