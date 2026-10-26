# FaultRift (단층 붕괴, 상급 367, id fault_rift) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| `fault_rift` | static | cast t = 0 at the caster's feet, basis = caster horizontal facing (+Z = forward) | stomp + caster sigil (r 1.6 m) → glowing crack races z 1 → 16 m during t 0.12–0.44 s → charge (true-width corridor outline, light leaking up, embers, hopping pebbles) → **collapse at t = 1.4 s** (slabs tilt and sink, amber burst, chunks, dust, moss motes) → trench cools until ~3.8 s. Ends by itself |
| `fault_rift_pin` | impact | the mod, once per enemy hit by the collapse, at cast + 1.4 s | hitPoint = the enemy's feet, surfaceNormal = up (incoming unused). Stone clamps + shackle ring for the **3.0 s root**, release crumble at instance t = 3.0 s. Duration 3.2 s, no stop signal needed |

## Hit timing / range
- **Damage + root at cast + 1.4 s** (crack reaches the end at 0.44 s, then ~1 s warning, as in the plan). Start one `fault_rift_pin` per hit enemy at that moment.
- Hit corridor shown on the ground = **x ±1.25 m (2.5 m wide), z 0.8 → 16.2 m** in front of the caster (outline mesh `frOutline`, crisp lines + inward ticks + end chevrons). If the server's width/length differ, tell us and the corridor mesh is rebuilt (no runtime width input is used: `width` scaling would also stretch the rift).
- No real terrain change: slabs are particle meshes that tilt, sink and fade.

## Layers
Crack race = one 2×8 wipe flipbook (`T_FRCrack_Sheet`, frames 0–11 reveal along U, U = (z−1)/15 on every crack mesh) on core/mid/wide/branch/trench/outline strips, so all reveal in sync with the racing head (K07–K09, z speed 46.9 m/s).
Held charge copies (H01–H07) start at 0.41 s on a static frame texture (`T_FRCrackHold`) and flare at 1.4 s.
Slabs: 6 segments × 2 sides × (2 slab A + 1 slab B) solid meshes with `depthWrite`, rock atlas with hot amber bottoms (warm rims) + additive glow shells.

## Features
Only client-supported features (client_compat fix = 0 changes, check OK). High impact: **meshParticle** (crack strips, corridor outline, light curtains, slabs/chunks/clamps, line-work circles), **bloom**, **orbitalVelocity** (caster streaks, pin embers). No attachedFollow. Also depthWrite, flipbook, stretchedBillboard, gravity, drag, noise, rotation3D.
Textures: 17 unique ≈ 2.3 MiB RGBA (the check's WARN counts shared textures once per material).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
