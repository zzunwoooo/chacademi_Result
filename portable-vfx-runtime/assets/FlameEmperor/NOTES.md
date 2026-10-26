# FlameEmperor (염제 강림, 궁극 368, id flame_emperor) — converter notes

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| flame_emperor_chant | attached, phase begin | cast (t = 0) | caster feet + facing. 3 s chant: circle (r 2.6) draws on, staged inflow, swirl; 2.55–3.0 s contraction |
| flame_emperor | attached, phase sustain | t = sequence.sustainStart = 3.0 s | runtime `holdDuration` = avatar time (default/max 8 s). Ignition burst at its t = 0, then held wings / crown / mantle / back halo / foot circle (one particle each, life 59 s, `onStop` tail 0.5 s = flare → collapse). Burning trail = world-space `rateOverDistance` layers (A29–A33): emitted only while the caster moves, particles stay where they were spawned (flames ~1.7 s, scorch ~2.8 s) — match the server's burn-ground lifetime to these if needed |
| flame_emperor_end | attached, phase end | hold expiry (or cancel → jumpToEnd) | all layers world space (blast stays where it went off). 0–0.35 s suck-in + contraction ring from r 6; **explosion visual at end + 0.35 s** → apply the radius-6 damage at end start + 0.35 s. Thin range ring at the true radius 6 m (E04) |
| flame_emperor_fireball | projectile | the mod, once per re-cast key press (max 4 — the server counts; the VFX has no count) | spawn at the caster's hand (demo: caster frame [0.35, 1.45, 0.7]), forward = flight direction. Muzzle burst at t = 0 (world). On hit: standard §5 (clear local, stop world emission) |
| flame_emperor_fireball_hit | impact | the mod, at each fireball hit | hitPoint + surfaceNormal (+ incoming); air hit = −incomingDirection. Visual radius 2.5 m (thin ring I08) — set I08/I06 size if the server's splash radius differs |

## Demo (preview only)
Chant in place 0–3 s, caster walks 3.4–10.8 s (trail), fireballs fired by events at 4.6 / 6.2 / 7.8 / 9.4 s (ground / wall / air / ground), end blast at 11.0 + 0.35 s.

## Features
Only client-supported features (client_compat check OK, `fix` = 0 changes). High impact: meshParticle (chant circle line work, wings/crown/mantle meshes, rings, columns),
bloom, orbitalVelocity (chant inflow/swirl), attachedFollow (chant/avatar/end), phasedSequence + stopTail (held avatar pieces), rateOverDistance (trail, fireball).
Texture memory: 18 unique textures ≈ 3.1 MiB RGBA (the check's WARN 4.8 MiB counts shared textures once per material).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
