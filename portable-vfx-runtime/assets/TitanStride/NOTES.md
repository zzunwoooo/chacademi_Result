# TitanStride (타이탄의 발걸음, 궁극 373, id titan_stride) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| `titan_stride` | static | cast t = 0 at the caster's feet, basis = caster horizontal facing (aim) | caster earth sigil r 1.9 + path guide along +Z (1.2–13 m) + one sync pulse per stomp at t = 1.0 / 1.8 / 2.8 s. Ends by itself (~3.6 s) |
| `titan_stride_step_l` | impact | stomp 1, at cast t = 0 | P = ground point on the aim path (demo 3.5 m), up = surfaceNormal, **incomingDirection = aim direction** (toes point along the path). Warning ring at the TRUE radius **2.2 m** (W01); fill grows to the rim exactly at the hit |
| `titan_stride_step_r` | impact | stomp 2, at cast t = 0.8 s | same, right foot (demo 7 m), radius 2.2 m |
| `titan_stride_final` | impact | stomp 3, at cast t = 1.8 s | biggest foot, radius **3.5 m**, rock spike ring, quake rings during the 1 s stun (+0.25/+0.5/+0.75 s after the hit) |

## Hit timing (apply damage here)
Each step_l / step_r / final instance: amber warning 0–1.0 s → **foot lands at instance t = 1.0 s** (foot falls during the last 0.40 s / 0.46 s for the final).
With the default spacing: **damage at cast + 1.0 s, + 1.8 s, + 2.8 s; stun 1 s from + 2.8 s** (final).
The mod may change the spacing: just start each impact instance exactly 1.0 s before its damage tick. Radii are fixed in the VFX (2.2 / 3.5 m) — if the server radius differs, scale W01/W05/H06 sizes or ask for a rebuild.
Warning colour is warm amber/ochre on purpose (red is reserved for MOB warnings).

## Features
Only client-supported features (client_compat check OK, `fix` = 0 changes). High impact: meshParticle (warning line work, stone foot / chunk / spike meshes, path strip), bloom, orbitalVelocity (caster orbit stones/streaks).
No attachedFollow (nothing follows the caster). depthWrite: the stone foot, chunks and spikes are solid alpha meshes with `depthWrite` (FallingStar-style atlas).
Textures: 19 unique ≈ 3.5 MiB RGBA (the check's WARN counts shared textures once per material).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
