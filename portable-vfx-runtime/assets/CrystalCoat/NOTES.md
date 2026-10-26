# CrystalCoat (결정 갑옷, #342, crystal_coat, 물 중급) — converter notes

One ally (or the caster itself) gets a faceted ice crystal shell for up to 4 s. If the shield is BROKEN (absorbed damage used up) the shell shatters:
enemies within 3 m are slowed 3 s. If 4 s pass unbroken (or it is dispelled) it melts away quietly. Visual only: absorb amount, slow and timing are the mod's job.

## Systems and runtime signals
| system | role | started by | placement |
|---|---|---|---|
| crystal_coat_cast | attached | cast (t = 0), caster | caster feet + facing (hand flare at local [0.32, 1.25, 0.35]) |
| crystal_coat_link | link | cast (t = 0), **only when target ≠ caster** | source = caster hand (feet + facing·[0.08,1.25,0.42]), target = ally chest (feet + 1.15 m), every frame. Glint arrives at **+0.2 s** |
| crystal_coat_begin | attached, phase begin | cast | ally feet + facing. Shell crystallizes 0.2 … 0.5 s (feet → apex) |
| crystal_coat | attached, phase sustain | sequence t = 0.5 s | ally. `holdDuration` = 3.5 s (4 s total). Held shell (life 59 s, onStop 0.12 s) |
| crystal_coat_shatter | attached, phase end, `endBranch: "trigger"` | **trigger** = shield broken | ally feet. Everything flies in WORLD space (stays where it broke). Frost ring at r = 3 m (true slow radius) |
| crystal_coat_fade | attached, phase end, `endBranch: "expire"` | hold expiry (4 s) or cancel (dispel / death) | ally. Quiet melt ≈ 0.6 s, no burst |
| crystal_coat_absorb | impact | optional, per blocked hit | hitPoint on the shell (≈ 0.8 m from the body axis), surfaceNormal = outward from the ally, incoming = attack direction |
| crystal_coat_chill | impact | at the shatter, once per slowed enemy | enemy feet, normal = up |

- **Which end plays**: trigger (broken) → `crystal_coat_shatter` (+ `crystal_coat_chill` on each slowed enemy, apply the slow at the trigger tick; the shock reaches the 3 m ring at ≈ +0.15 s). Expire / cancel → `crystal_coat_fade` only.
- Send `trigger` only while the sustain runs (0.5 … 4 s). If the shield breaks during the 0.5 s begin, start sustain is skipped by the phased rule (trigger = jumpToEnd) — fine.
- Self-cast: skip `crystal_coat_link`; begin still starts at t = 0 (the shell grows at 0.2 s as if the glint landed).
- Slow radius is fixed at 3 m (layers T10/T11/T12/T19, authored radius). No `scale` input.

## Held layers
Shell panes / edges / body / inner edges, ground hex circle, rune band, sigil, feet crystals and 3 orbiting shards: ONE particle each in `crystal_coat` (life 59 s, `onStop` 0.12 s shell, 0.3 s others).
Begin draw-on copies start at t_s with rot3 = ω·t_s, same rotLife (circle ω 0.5, runes −0.8, sigil 0.9 rad/s) and overlap the held copies 0.5 … 0.6 s.
Shell mesh = 6-sided antiprism (feet origin, r 0.6 → 0.8 → 0.78 → 0.55, apex 2.36 m) with each triangle mapped to the T_CCFacet pane (white rim + hex lattice);
`body` = alpha-blended navy copy behind (dark accent), sheen = 4×2 flipbook sweeping a glint across every facet ~0.85 / s.

## Features
Only client-supported features (client_compat fix = 0 changes, check OK). High impact: meshParticle (shell, edges, circle, crystals, panes, range ring), bloom,
orbitalVelocity (orbiting shards, mist, flakes), attachedFollow (shell follows the ally), pathFollow + linkTarget (cast glint), phasedSequence + triggeredEnd + stopTail.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
