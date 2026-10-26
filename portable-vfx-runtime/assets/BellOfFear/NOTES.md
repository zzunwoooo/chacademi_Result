# BellOfFear (공포의 종) — converter notes (REDO 2026-10-25)

Visual only. Who gets Fear (normal monsters in range; never players or bosses), the range check, the Fear status itself, its icon/timer and the monsters' flee AI are all done by the mod / common status system. **This file draws no icon.**

## Spell data (for the mod, not part of vfx.json)
- Mana 140, cooldown 30 s.
- Unlock: ring a bell at night. The old "kill 5 monsters" unlock condition is removed.
- Range (areaRadius) and Fear duration are config values (not fixed here).

## Systems (one cast = 1 × bell + 1 × ripple + N × fearApply + N × fearHold)
| system | role | placement (runtime input) | areaRadius |
|---|---|---|---|
| `bell` | static | P = caster feet, basis = caster horizontal facing (server sends it as the static basis) | **do not pass** (s = 1) |
| `ripple` | static | same P / basis as `bell` | **pass** → s = clamp(R / 8, 0.375, 2) |
| `fearApply` | attached | EACH monster that really got Fear: anchor = its feet, facing = its body yaw (every frame) | do not pass |
| `fearHold` | attached, `loop: true` | same monster, started together with `fearApply` | do not pass |

- **Scale only the ripple.** The client has no `scale.systems` yet, so the file cannot say "ripple only". Start `bell`, `fearApply` and `fearHold` without areaRadius (missing input → R = reference → s = 1), and pass areaRadius only to `ripple`. The preview does exactly this (per-instance scale). If every system received R, the bell and the afterimages would grow/shrink with the range (wrong). When the client gains `scale.systems`, set `scale.systems = ["ripple"]` and this rule disappears.
- Ripple radius at time t after the strike (t' = t − 0.30 s, life 0.85 s): r ≈ R·(0.07 + 0.93·(1 − (1 − t'/0.85)²)). Suggested Fear start per monster = 0.30 + 0.85·(1 − √(1 − (d/R − 0.07)/0.93)) s (d = horizontal distance from the caster), so the afterimage appears when the wave passes. Monsters not feared (bosses, players, out of range) get nothing.
- `fearHold`: stop it (stop-emitting signal) when the Fear status ends or is removed; its layers use `onStop {jumpToTail, 0.12}` so it is gone within ~0.12 s. It loops for any duration, so the real duration is whatever the status system holds. Monster dies/despawns → stop + clear local layers.
- No icon, no marker above the head, no timer — the common status system shows those.

## Timeline (t from cast)
0.00 small matte black bell fades in (0.36 m right, 1.58 m up, 1.25 m forward of the caster) · 0.14–0.6 one swing (16-frame flipbook) · 0.30 clapper strike: tiny glint + small sound ring at the bell, **one** thin low ground ripple (double line baked in one texture = one wave, not repeated hits) + a faint upper edge at 0.42 m + thin streaks ride outward to R by ~1.15 s · ~0.9–1.15 bell fades, a few dark ink motes drop · per feared monster: 3 dark trembling afterimages + small dark ink + a faint ground touch ring (~0.7 s), then a subtle trembling shade + dripping ink until the status ends.

## Look
- Bell: alpha blended, matte hand-painted (dark plum-black face, soft broad light on the face, thin grey-violet rim). Billboard flipbook, no mesh.
- Ripple / glint / streaks: additive grey-violet, low HDR tint (1.1–1.6). Afterimages and ink: alpha blend, dark (no purple smoke clouds).
- Not included on purpose: big purple smoke, skulls, blood, explosions, big magic circles, icons.

## Features
High impact: `meshParticle` (flat ground ripple quads — without it the ripple would not lie on the ground), `attachedFollow` (afterimages follow fleeing monsters), `bloom`, `instanceScale` (range), `stopTail` (clean removal on status end). Nothing is approximated.

## REDO 2026-10-25 — what changed visually (system ids, roles, durations, scale rule and wave timing unchanged)
| system | role | loop | duration | key moments |
|---|---|---|---|---|
| `bell` | static (caster feet, **no** areaRadius) | false | 0.95 | strike +0.30 s, gone ≈1.15 s |
| `ripple` | static (areaRadius, R = 8 reference) | false | 0.35 | wave front from +0.30 s, same curve r(t') as before, reaches R at ≈1.15 s |
| `fearApply` | attached (each feared monster) | false | 0.15 | ≈0.7 s |
| `fearHold` | attached, loop | true | 1 | stop when Fear ends (onStop 0.12) |
- Bell: the flat billboard bell is replaced by a **solid 3D bell** (lathe mesh, depth-writing alpha material: dark plum metal, baked side light, glowing violet rune band and lip) + additive glow copy + handle ring. It appears with a small overshoot, **really swings** (4 hand-off copies: out → struck at +0.30 at the extreme → back → settle) and dissolves into ink flecks + mist at ≈0.95–1.15. Strike: star at the lip, glow, sparks, and **sound-wave shells** = 3 staggered expanding sphere-outline rings + 2 crossed line-ring shells.
- Ripple: crisp ground wave line (double line + ticks) riding the old front curve, glow band, dark ink wake just inside the front, a low **sound wall** (0.75 m, fades upward) and an echo wave 0.14 s behind; outward flow streaks; **fleeing shadow wisps** (dark heads with ink trails + a few violet glints) racing outward along the ground; ground ink mist pushed out; a faint **true-range ring at R** that brightens as the wave arrives; small centre flash.
- fearApply: shell hits the monster (contracting ring), 3 shivering dark afterimages drifting back, ink flecks, foot line ring, shadow tendrils streaming backward (-Z of the monster = away from where it flees), glint.
- fearHold: trembling shade, ink drips, dark wisps (with ink trails) + violet glints circling the head, shadow stream trailing behind. No icon/marker/timer, as before.
- New high-impact feature used: `depthWrite` (solid bell; in client caps). Others as before.
- 2차 수정 (2026-10-25): 종을 1.8배로 키우고, 시전자 머리 위(꼭지 [0, 3.3, 0.2], 몸체 중심 약 2.76 m)에 띄워 초점으로 만들었다(`bell`은 여전히 시전자 발 기준 static이고 areaRadius를 받지 않는다). 뒤에 보라 후광을 깔아 어두운 실루엣이 테두리 빛처럼 보이게 했고, 시전자에게서 종으로 오르는 빛줄기를 더했으며, 음파 껍질도 키웠다. 파동 아래에는 **어두운 충격파 몸체**(앞쪽으로 갈수록 짙어지는 잉크 띠, 반경 0.74R~R, 앞 가장자리 보라 테두리)와 보라 연무 사본을 깔았다. 달아나는 그림자 위습은 54개로 늘리고 크게 했으며, 느리게 흩어지는 위습 26개와 달아나는 그림자 잔상 10개를 더했다. 파면 곡선과 +0.30 s 타종은 그대로다. 모든 burst는 각 시스템 duration 안에 있다. 데모 `bell` 카메라도 새 종 위치에 맞췄다.



## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
