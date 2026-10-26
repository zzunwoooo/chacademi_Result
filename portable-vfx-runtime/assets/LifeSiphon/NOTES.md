# LifeSiphon (생명 갈취) — converter notes (REDO 2026-10-25)

Replaces the old single-target drain visual with a ground zone.

## Systems
- `begin` / `sustain` / `end` — **static, phased** (SCHEMA §5.1). P = cast point on the ground, identity basis. Ink zone radius authored as **3 m** (fixed; no `scale` input).
  - sequence: `sustainStart` 0.35 s, `hold.default` 2.65 s → zone visible 3.0 s, then the end phase (~0.9 s soak-away via `onStop` tails).
  - The mod's 3 s duration = holdDuration 2.65 (or send the cancel/expiry at t = 3 s). Ink is held by one long particle per layer (life 30 s) and removed by `onStop`, so any hold up to 20 s works.
- `drain` — **link**, NOT part of the zone cast's timeline. Start **one new instance per real drain event** (per enemy, per tick) from the mod.
  - `source` = the drained enemy's feet (+0.03 m up), `target` = caster chest/hand (preview uses feet + [0, 1.15, 0.3] in the caster frame). Update both every frame.
  - Lifetime ≈ 0.9 s: foot glow + 4 small rising flecks, 5 life shards + 3 thin wisps that arc and accelerate into the caster (0.55–0.75 s), small absorb glow at the caster at 0.5 s. No continuous beam.

## Not decided here (mod side)
Area check, damage, tick period, heal ratio, 3 s duration, mana 30, cooldown 10 s, learning via the dark-magic basics class. The preview's drain timing (≈1 s apart, two enemies) is demo-only.

## Notes
- No blocks are placed/changed: the zone is alpha-blended quads 0.02–0.06 m above P. On uneven terrain the flat quad may clip into slopes (same as other ground zones).
- Ink layers are alpha blend (dark); ripples/rim/shards are additive with HDR tint. Required features are all in the client caps list.

## REDO 2026-10-25 — what changed visually (system ids, roles, sequence, timing unchanged)
| system | role / phase | loop | duration | notes |
|---|---|---|---|---|
| `begin` | static / begin | false | 0.35 | sequence.sustainStart 0.35 |
| `sustain` | static / sustain | true | 10 | held layers life 30 s, onStop tail 0.9 (any hold ≤ 20 s) |
| `end` | static / end | false | 0.1 | ≈0.9 s soak-away |
| `drain` | link (enemy feet → caster chest) | false | 0.1 | one instance per drain event, absorb at +0.5 s |
- Zone: ink pools now carry a **crisp mesh magic circle at the true 3 m radius** (double boundary ring + ticks, heptagram {7/3}, vertex circles, inner ring + {7/2}, 14-segment rune band, siphon sigil with 7 inward fangs) that draws on in the begin phase and turns (layers at different speeds); edge shock + sparks + ink splash + edge mist at the start.
- Sustain: inward-curling violet + crimson drain spirals, contracting ripple rings, crimson life motes and violet motes drifting up and inward, rim wisps at the boundary, dark mist, glints. All held layers drain into the centre on stop.
- End: soak ripple contracting, spirals fade, small centre flash + rising life motes, ink sinks inward.
- Drain: foot line ring + glow + star at the enemy, crimson life shards with trails, violet wisps, dark ink strands, lifted shards/ink flecks; absorb glow + star + ring at the caster at +0.5 s.
- 2차 (2026-10-25): duration보다 늦게 잡혀 재생되지 않던 burst(흡수·도착 섬광·구슬 등)를 창 안에서 생성해 투명하게 대기하도록 고쳤다. 보이는 타이밍은 같다.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
