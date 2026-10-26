# PrismCage (빛의 감옥, #365 빛 상급) — converter notes

A light cage closes on ONE target: 6 light pillars on a golden hexagon circle (r 2.4 m at a 1.8 m target), charge 1.5 s, then 6 lasers converge into the target's chest.
Visual only: damage, the 1 s stun and the debuff icon belong to the mod.

## Systems and runtime signals
| system | role | started by | notes |
|---|---|---|---|
| cage | static | cast (t = 0) | P = the TARGET's feet at cast time, M = caster facing (server basis). Not moved afterwards (the target is caught inside). Duration 1.6 s, last particles gone by ≈ 2.6 s |
| stun | attached | the mod, at cage t = 1.5 s (the HIT) | attach to the target (feet + facing). One-shot, 1.0 s of visuals = the stun length. If the stun is cancelled early, stop it (the layers are one-shot, they finish on their own) |

**HIT timing: cage t = 1.50 s** — lasers fire from the pillar tops at 1.37 s and reach the chest (feet + 1.15 m × scale) at ≈ 1.45–1.5 s; flash / shock / shard burst at 1.50 s. Apply damage + stun 1 s at 1.5 s and start `stun` then.

## Runtime inputs
`scale = {reference 1.8, min 0.75, max 2.2}` → runtime `targetHeight` (target's hitbox height): the cage, pillar height, chest point and stun bands scale uniformly about P/feet. Both systems use the same targetHeight.

## Timeline (cage)
0–0.45 s circle draws on (frame → rune bands → hexagram → sigil), pillars rise one by one 0.16–0.46 s · 0.55 / 0.8 / 1.05 s cage lines connect pillar to pillar (low / mid / top) + lattice, wall scans · 0.6–1.5 s light gathers at the chest (inflow streaks in growing bursts, star, contracting rings at 1.03 / 1.1 / 1.2 s) · 1.25 s emitters ignite on the pillar tops · 1.37 s lasers · 1.5 s HIT · 1.5–2.5 s afterglow, light falls from the pillars, pillars shrink, circle fades.

## Features
Client-supported only (client_compat check OK, fix = 0 changes). High impact: meshParticle (circle line work, pillars, cage lines, lasers), bloom, orbitalVelocity (motes, stun crystals), attachedFollow (stun), instanceScale (targetHeight).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
