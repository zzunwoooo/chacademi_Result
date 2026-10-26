# Tailwind (순풍, 생활 380, id tailwind) — converter notes

## Systems
| system | role | started by the mod | what |
|---|---|---|---|
| `tailwind` | static, one-shot 1.9 s | at cast, P = caster feet (works in the air; everything is a flat horizontal disc at P + 0.3 m, nothing needs ground) | compact wind-rose line-work circle (4 counter-rotating layers) draws on under the broom; inflow 0–0.24 s; **release at +0.24 s**: flash, gust ring + crisp line ring expand to r 8 (reach ≈ +0.74 s), spiral gust arms + wind arcs + radial streaks sweep out, feathers / leaves / motes ride outward, slate wind accents; boundary double line + ticks at the true radius 8 appears at +0.66 s and fades by +1.7 s; circle lifts & fades by +1.85 s |
| `tailwind_buff` | attached (rider feet + facing), duration 15 s | once on every party member inside r 8 **including the caster**, at the moment the balance gauge is filled | 0–2 s: waist / broom wind ribbon loops (bright + soft + slate), "balance full" ring rises 0.05–0.45 s, ping + star at +0.42 s, feathers & motes orbiting; then until stop: very faint world-space glints / motes shed behind the broom (rate 1.6/s + rateOverDistance) |

## Timing / runtime inputs
- Gameplay timing: apply the effect (gauge full + 15 s balance-loss −30 %) at **+0.24 s** (release). Recommended: start `tailwind_buff` on the caster at +0.24 s and on each ally when the ring front reaches them: t ≈ 0.24 + 0.5·(d/8)^0.6 s (or simply all at +0.24 s — both read fine).
- Stop `tailwind_buff` (stop signal) when the 15 s buff ends, the rider dismounts, or the buff is removed; onStop tails 0.2 s. If not stopped it ends on its own after 15 s (duration).
- Static P = caster feet at cast time, basis = caster horizontal facing (the disc is rotationally symmetric, facing does not matter). Radius is baked at 8 m (no scale input). If the config radius changes, ask for a rebuild.
- No damage / hit timing. The 15 s duration is shown by the mod's buff UI.

## Features
Client-supported only. High impact: **meshParticle** (circle line work, rings, gust arms, waist ribbon), **bloom**, **attachedFollow** (buff follows each rider). Also **orbitalVelocity** (orbitalY swirl on feathers / motes / ribbon pieces). Also worldSpace + rateOverDistance (world trail layers only), trails, flipbook, stretchedBillboard, stopTail, drag, noise, gravity, rotation3D.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
