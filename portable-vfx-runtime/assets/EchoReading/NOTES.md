# EchoReading (잔향 읽기) — converter notes (REDO 2026-10-25)

**What**: a short, calm violet "reading the echo" effect around an investigated object. Visual only — which objects can be investigated, clue display and acquisition are handled by the game outside this file.

**Placement**: one system `echo_reading`, `role: static`, `loop: false`. P = **bottom centre of the investigated object's bounding box** (the surface it rests on), +Y = world up. Facing = the server's default horizontal facing (the effect is rotationally symmetric, so facing does not matter).

**Size (SCHEMA §5.3)**: runtime input `targetHeight` = the object's **largest bounding-box dimension** (max of width/height/depth), not just its height, so flat objects (letters, plates) still get a sensible ring. s = clamp(h / 0.5, 0.4, 4): item 0.25 m → ×0.5, bag 0.5 m → ×1, chest 1 m → ×2. Uniform scale only.

**Timeline** (s from start, REDO 2026-10-25): 0.00–0.55 a violet memory circle (mesh line work: double rim + ticks, rotating rune band, inner hexagram with vertex dots) draws itself on the base plane while motes and streaks gather inward in growing bursts · 0.45 and 0.78 two rune "scan" bands (vertical rune wall + edge lines) rise up the object · 0.40–1.45 echo ribbons (bright violet + dark ink ribbons behind) spiral up around it, memory flakes and ink shards lift off · 0.62 an eye-of-memory sigil opens flat above the object · **0.95 peak**: small flash, circle/inner flare, 3 ripple rings (0.95 / 1.05 / 1.15), short rays and sparks up · then everything drifts up and fades; the eye closes ≈2.3 s, the circle is gone at 3.0 s, last motes ≈3.3 s. Emission ends at 1.8 s (system duration 1.8, loop false — unchanged).

**Not supported (reported, not substituted)**:
- Object-shaped outline / rim glow that follows the real model silhouette (as in the concept image): the schema has no mesh-surface/outline emitter or entity overlay shader, and the client has no such feature. The gathering light is therefore placed on a generic ring around the object's base + soft glows on a small circle at its edge — it does not trace the object's shape. A true outline needs a new client feature (e.g. an outline/overlay render pass on the target entity/block, or emission from the target's model surface).
- The ripple lies flat on the object's base plane; if the object sits on a narrow support (pedestal, shelf), the outer part of the ring floats past the edge. No surface-conforming decal exists in the schema.
- The demo pedestal / boxes in preview.html are demo-only props, not part of the effect.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change (empty placeholder keys for client-unsupported fields — height/depth/orientation/follow/lights/rollWithTravel etc., all null/empty — were removed from vfx.json)


## REDO 2026-10-25 (visual rebuild, same contract)
- Same system: `echo_reading`, role static, phase main, duration 1.8, loop false; same scale input (`targetHeight`, reference 0.5, 0.4–4); ripple peak still 0.95 s. No new systems, no new runtime inputs.
- Look rebuilt (old pack was a faint haze ring + a few veils): crisp mesh line-work circle at the object base (radius 0.5 m at scale 1), rising rune scan bands, spiral echo ribbons, flat eye sigil above the object, ripple rings, flakes/ink shards/dark mist accents.
- Only client-supported fields (client_compat fix = 0 changes, check OK). Textures: 13 unique, 2.08 MiB RGBA.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
