# Rewind (248 되감기, dark) — converter notes

Element palette: dark (violet-silver). No system is tintable. No `scale` module.

| system | role | duration | origin / anchor | shows |
|---|---|---|---|---|
| cast | attached | 0.8 s | caster feet, follows caster, +Z = facing; effect at the hand (0, 1.25, 0.38) | violet-silver clock glyph (ring, ticks, hands) spinning backward, ring + rune band, inflowing motes, ink motes, small flash + sparks at 0.58 s |
| rewind | impact | 1.2 s | FLOOR under the device, +Y up, +Z = server yaw | vertical clock sigil in the local XY plane facing +Z, centre (0, 1.0, 0.45), r 0.8: rings + 12/60 ticks drawing on, rune ring turning backward, clock face, minute/hour hands spinning BACKWARD fast (−15 / −5.5 rad/s, counter-clockwise for a viewer on +Z) with afterimage hands; dark ink disc + ink swirl behind; afterimage ripples contracting inward (5 staggered), face echoes shrinking; 3-arm sparkle spiral turning inward; motes drawn in with trails; ink shards; snap flash + ring + sparks at 0.92 s; small ground ring + mist + motes under the device; all faded by ~1.2 s |

Decision: the brief gives no z for the sigil; it is placed 0.45 m in front of the origin (+Z) so a device up to ~0.8 m deep does not hide its centre. Authored for a ~1.5 m device (devices 0.5–3 m; no runtime scale).
Textures: T_RW_* (13 PNG, ≤ 512 px), unique RGBA ≈ 1.3 MiB.
Demo only: caster at (0,0,3); stand-in device = grey box 0.7 × 1.5 × 0.7.



## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
