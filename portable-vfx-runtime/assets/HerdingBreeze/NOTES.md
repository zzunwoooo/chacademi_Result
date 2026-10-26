# HerdingBreeze (몰이 바람, id herding_breeze, 바람 생활) — converter notes

## Systems and runtime signals
| system | role | length | started by | inputs |
|---|---|---|---|---|
| cast | attached | duration 1.5 s, loop false (gone ≈1.7 s) | cast, once (t = 0) | caster feet + facing; targetHeight = caster height (1.8 → scale 1) |
| lead | attached | duration 3.0 s, loop false (gone ≈3.1 s) | mod, once per animal that starts following (recommended at cast + 0.3…1.4 s = when the ground wave reaches it; replay e.g. every 10 s while it follows, optional) | anchor = the ANIMAL's feet + its facing; **targetHeight = that animal's bounding-box height** |

Top-level `scale` = {input: targetHeight, reference 1.8, min 0.35, max 1.2}: every instance is scaled by clamp(h / 1.8). The lead ribbon is authored at 2.05 m for a 1.8 m entity, so it lands just above
any animal's head (chicken 0.7 → 0.8 m, sheep 1.3 → 1.48 m, cow 1.4 → 1.6 m, horse 1.6 → 1.82 m). Pass the caster's height for `cast`.

Timeline (cast): 0.00 mint wind circle at the feet (notes/hoof/leaf rune band, pentagram, swirl-note sigil) → whistle pulses at the lips at **0.20 / 0.48 / 0.76 s** (glint, small sound rings,
wind music notes ♪♫ and gusts spreading out, leaves swirling up the body) → **0.28 s ground breeze** runs out to the real reach **r 12 in 1.15 s** (crisp thin double line + scalloped echo +
rune band, glints/streaks/leaves/notes riding the front: radius = 0.8 + 11.2·(1 − e^(−2.4 t))/(1 − e^(−2.76))) → 1.2–1.5 fade.
Lead: 0.00 pop (flash + ring) → wavy mint ribbon crown (two counter-rotating bands + streamer curl + halo) above the head, notes at 0.15 / 1.2 / 2.1 s, two orbiting leaves, motes → 2.6–3.0 fade (+ motes).
Arrival time of the wave at distance r (for the lead start): t = 0.28 − ln(1 − (r − 0.8)/(11.2/(1 − e^(−2.76))))/2.4.

## Features
client_compat fix: 0 changes; check OK; validate OK; burstchk 0 bad.
High impact: **meshParticle** (circle, wave rings, ribbon crown), **bloom**, **orbitalVelocity**, **attachedFollow** (both systems follow their entity), **instanceScale** (targetHeight per instance).
Others: flipbook + flipbookCycles (leaves), stopTail (orbit leaves, 0.3 s), stretchedBillboard, trails, drag, gravity, noise, linearVelocity, rotation3D, rotationOverLifetime, additive + alpha, hdrTint, neutral tonemapping.
Unique textures: 15, 1.85 MiB RGBA.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
