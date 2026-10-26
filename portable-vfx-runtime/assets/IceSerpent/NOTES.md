# IceSerpent (얼음 뱀, 상급 361, id ice_serpent) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| ice_serpent_cast | attached | cast (t = 0) | caster feet + facing. Snowflake ground circle + rising crystals, charge 0 … 0.3 s, **launch flash at 0.3 s** → spawn the projectile then. Ends by itself. |
| ice_serpent | projectile | the mod at cast + 0.3 s | P = serpent head centre, **0.55 m above the ground** (caster + ~1.0 m forward), +Z = travel. The mod moves P along its S path on the ground (preview: 20 blocks in 1.65 s; any speed works, the body is emitted by distance). **Pierces: do NOT stop it on enemy contact.** Stop it at 20 blocks or on a block hit (SCHEMA §5: local head clears, the world-space spike body / frost track finish ≈ 1.4 / 2.4 s). The head grows in during its first 0.16 s. |
| ice_serpent_hit | impact | once per enemy, when the head passes it | hitPoint = **enemy feet** on the ground, surfaceNormal = up, incoming = travel. Three spike-cage pulses at **+0.00 / +0.15 / +0.30 s** → apply the multi-hit damage ticks then; apply the 3 s slow at +0. |
| ice_serpent_end | impact | projectile stop | range end on the ground: hitPoint = head position projected to the ground, normal = up. Block hit: hitPoint/normal of the block. Air (no ground below): normal = −forward. Ice crown shatter + 3 shock rings + gems. |

## Body
The serpent body is not one mesh: faceted spike clusters (W01, 1.5 per metre) + side spikes (W02) are spawned in **world space** 1.6 m behind the head on the ground and play a baked rise → glint → crack → crumble flipbook (T_ISFacet_Sheet 4×2, light baked per face, alpha-blended so the ice bodies stay solid with white rims). Because they are emitted by distance (`rateOverDistance`), the body follows exactly the path the mod drives.

## Features
Client-supported only (client_compat fix: 0 changes, check OK). High impact: meshParticle (head hull, spike clusters, cages, crown, magic circle line work), bloom, attachedFollow (cast). Also worldSpace + rateOverDistance (body, frost track, mist), flipbook, stretchedBillboard, noise. No orbitalVelocity.
Texture memory: 13 unique textures = 1.77 MiB RGBA.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
