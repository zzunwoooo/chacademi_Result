# LightBeam (빛줄기, 244) — converter / server notes

| system | role | duration | origin / anchor | shows | tintable |
|---|---|---|---|---|---|
| `cast` | attached | 3.0 s (non-loop, fade last 0.2 s) | caster feet, +Z = facing; effect centred at (0, 1.35, 0.45) | radiant sigil quad + gold line-work frame (rings, ticks, square, {8/3} star), slow ray fan, held glow, flare twinkles, motes/streaks flowing in | no (white → pale gold → warm gold) |
| `beam` | impact | 3.0 s (non-loop, fade last 0.2 s) | segment START point on the beam centre line (in the air; y = 0 is the beam axis), +Z = beam direction | one 2 m beam segment: bright core line (~0.08 m) with pulses travelling +Z, soft halo (~0.35 m), twin helix strand + counter-twisting outer strand with flowing dashes/motes, drifting motes and glints | **yes** (near-white) |
| `hit` | impact | 3.0 s (non-loop, fade last 0.2 s) | where the beam ends on a device / wall (surface at z = 0), +Z = beam direction, +Y = up | small radiant flare (≤ 0.6 m) twinkling, two counter-rotating short ray fans, small rune ring, ripple rings on the surface, sparks splashing back toward −Z, sparkles, rising motes | **yes** (near-white) |

## Beam segment tiling (important)
- Every continuous part of `beam` is a mesh particle at position (0,0,0), size 1, no sizeOverLifetime change: core (3 crossed planes), halo (4 crossed planes)
  and strands (helix, exactly 1 / −1 turn per segment). All mesh vertices span **z = 0.000 … 2.000 exactly** (checked in vfx.json), no end caps.
- Textures along the beam (U = z / 2) are constant or exactly periodic (integer frequencies) → segment k's end matches segment k+1's start.
- Flowing patterns are flipbook scrolls (`flipbook.cycles`): core pulses 4 m/s (2 pulses per segment, 12 cycles), strands 3 m/s / 2 m/s.
  For the scroll phase to line up across a junction, all segments of one beam should be started in the same tick (the geometry tiles regardless).
- Free particles (B06 motes, B07 glints) spawn uniformly over z 0…2 and drift +Z by ≤ 0.3 m; the overshoot past z = 2 is exactly complemented by the
  next segment's thinner start, so a chain has uniform density. Only the LAST segment's motes may drift ≤ 0.3 m past its end (into the hit flare).
- If the server shortens the last segment (beam length not a multiple of 2 m) it must not scale the system (scale 1 rule) — a partial last segment
  will show the full 2 m mesh; clip / skip it on the server side or end the beam on a segment boundary.

## Tint
- `beam` and `hit` use neutral near-white materials (HDR tints ~[1.8,1.8,1.75]); multiplying all colours by a prism RGB tint keeps the look (checked with a
  cyan tint in the preview). No dark accents in the tintable systems.
- `cast` is not tinted (gold).

## Decisions
- No dark accents in this pack (light element, tintable beam/hit; brief: "no dark accents in tintable systems").
- Preview demo: caster at z = −1 channels; 3 segments → mirror bend (+X) → 2 segments → hit on a wall. Preview cannot apply the tint (server-only).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
