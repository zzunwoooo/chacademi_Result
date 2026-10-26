# TideSlash (물살 베기 #327, tide_slash, 물 기초) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| tide_slash | attached | each cast | P = caster feet, M = caster horizontal facing (+Z forward, +X right). Water crescent swept RIGHT → LEFT at chest height (~1.05 m, visual radius ~2.2 m). The blade crosses the front at **+0.10 s** → apply damage + the 1-block push then. One-shot, particles end by ~0.8 s; nothing to stop. |
| tide_slash_hit | impact | the mod, once per enemy hit (at +0.10 s) | hitPoint = enemy chest (~1.1 m), air-hit convention: surfaceNormal = −incomingDirection, incoming = caster → enemy (the push direction). Foam burst + splash + push ripples travelling along −Y (= push direction). |

Gameplay range: layer `20 RangeLine` draws the TRUE hit area on the ground — a 3 m half-circle in front of the caster (arc + both side radii). If the server range changes, set its `start.size` = range in metres (and `21 GroundWave` ≈ 0.97 × range).

## Features
Only client-supported fields (no height/depth/orientation/follow/lights/attachPoint/rollWithTravel). High impact: **meshParticle** (sweep ribbons, curl sheet, crescent, range line, ripples), **bloom**, **attachedFollow** (tide_slash follows the caster while it plays), **orbitalVelocity** (flung droplets/spray follow the sweep direction). Also flipbook (stroke wipes, splash), stretchedBillboard, trails, gravity, drag, radial/linear velocity, rotation3D/rotationOverLifetime.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
