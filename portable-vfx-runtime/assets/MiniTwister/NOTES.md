# MiniTwister (작은 회오리, 기초 330, id mini_twister) — converter notes

Gameplay: a small tornado (~1.6 m tall) walks slowly 8 m along the ground; every enemy it touches gets a small lift (knock-up). Hit radius shown on the ground = 0.75 m (mini wind circle under the twister).

## Systems and runtime signals
| system | role | started by the mod | stopped |
|---|---|---|---|
| cast | attached (caster feet + facing) | cast t = 0 | one-shot ≈0.6 s: wind puff at the right hand [0.32, 1.32, 0.42] |
| projectile | projectile (P = the ground point under the twister, +Z = travel, up = world up) | ≈0.15 s after the cast, ~0.9 m in front of the caster; the mod moves it along the ground (demo 3.6 m/s → 8 m in 2.2 s) | NOT stopped by enemy touches (it passes through). Stop it (SCHEMA §5 hit rule: clear local layers, world layers finish) when it reaches 8 m or is blocked, and spawn `end` there |
| lift | impact | once per enemy touched: hitPoint = enemy feet, surfaceNormal = world up, incomingDirection = travel direction | ≈0.9 s one-shot; apply the small lift at the same tick |
| end | impact | at the projectile's last ground point (8 m or blocked): surfaceNormal = world up, incoming = travel | ≈1 s: the funnel unwinds, circle fades, leaves scatter |

Keep the projectile P on the ground (follow terrain height); all funnel layers are authored from the ground up. Held layers (circle, runes, shadow) are ONE particle each with life 30 s; the stop clears them, and `end` plays the matching fade copies.
World-space layers (dust wake, trail arcs, trail motes) use rateOverDistance on the projectile's travel.

## Features
Only client-supported features (client_compat check OK, fix = 0 changes). High impact: meshParticle (funnel helices, wind circle line work, swirl decals, lift helix), bloom, orbitalVelocity (leaves / motes / streaks orbiting the funnel), attachedFollow (cast).
