# GhostTool (유령 도구, ghost_tool) — converter notes

## What the effect is / is not
- 30 s buff VFX only. Removing the block **place/break reach limit** is game logic (external). Attack reach, wall piercing,
  mining time, tool tier, durability and protected-area rules are unchanged and are not part of the effect. Unloaded chunks etc. = game side.
- No link line, long arm, giant floating hand or magic circle. The old "mine one far block once" effect is not used.
- Mana 100, cooldown 60 s, duration 30 s, unlock (break one pickaxe, one axe and one shovel by using up their durability; hoes excluded, block-break count irrelevant) = game logic.

## Systems
| id | role | placed at | when |
|---|---|---|---|
| `ghost_tool_begin` | attached, phase begin | caster feet, +Z = facing | cast (t = 0): small ghost-hand outline over the right hand → fingertip glint |
| `ghost_tool` | attached, phase sustain (loop) | same | t = 0.35 s … expiry: tiny breathing fingertip mark (+ rare twinkle, very few motes) |
| `ghost_tool_end` | attached, phase end | same | expiry / cancel: fingertip glint fades out softly (~0.8 s) |
| `ghost_tool_break` | static | **centre of the broken block**, grid aligned | each REAL break that needed the extended reach, at the moment the block is removed |
| `ghost_tool_swing_pickaxe` / `_axe` / `_shovel` | static | same point as break | together with `ghost_tool_break`, chosen by the held item's tool kind |
| `ghost_tool_place` | static | **centre of the placed block**, grid aligned | each REAL place that needed the extended reach, when the block appears |

## Runtime inputs
- Phased (SCHEMA §5.1): `holdDuration` = 30 s (sequence.hold max 30). Cancel (death, buff removed) → `jumpToEnd`.
  Sustain layers use `onStop` jumpToTail (0.45 s) so the fingertip mark fades instead of popping.
- Tool kind (external): pickaxe → `ghost_tool_swing_pickaxe`, axe → `_axe`, shovel → `_shovel`. Any other item / empty hand → only `ghost_tool_break` (outline, no tool afterimage).
- Only play block systems for events beyond the normal reach (in-reach actions keep vanilla feedback). One new instance per event; they overlap freely.

## Placement details (important)
- Block systems must be **grid aligned**: basis = identity, or the caster facing **snapped to the nearest 90°**. The outline is 6 square
  mesh quads on the block faces with no in-plane spin; an unsnapped yaw would rotate the cube outline off the block.
- Break outline quads sit 0.49 m from the centre (inside the now-empty cell, never hidden by neighbours). Place outline quads sit 0.505 m out (faces touching other blocks are hidden by depth).
- The tool afterimage is a camera-facing billboard (0.55 m) in the empty cell: one quick clockwise chop + a faint echo, 0.3 s.
- Hand: attached systems have no hand attach point in the client, so the right hand is a fixed caster-frame offset
  (`HAND` [0.36, 0.76, 0.24], fingertip [0.37, 0.67, 0.30], body 1.8 m). If the mod can feed the real third-person hand position, use it.
  In first person the caster's own view model is not at that point: either hide the attached systems for the caster's first-person
  view or offset them to the view-model hand (the effect is small and readable either way). Report if neither is possible.

## Look
Pale violet, additive, low tints (≤ 2.0) and bloom intensity 0.9 so small elements stay matte/hand-drawn rather than glowing.
Textures are painted ghost outlines (bright thin rim + faint brushy fill). No large circles, no smoke.

## Features
`attachedFollow` (high), `phasedSequence`, `stopTail`, `meshParticle` (high: block outline quads — without it there is no outline; report, do not substitute),
`bloom` (high, kept low), rotation3D, rotationOverLifetime, world-space motes. Texture RGBA ≈ 0.64 MiB.

## Demo only (preview)
Grey/brown boxes = demo blocks (props that vanish at the break / appear at the place). Hidden actors mark block centres. Scenario `far`: pickaxe ×2 → axe → shovel → 3 places at ~8 m.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change

## 2026-10-25 재제작 (시스템·역할·phase·타이밍 그대로)
- 8개 시스템 id/역할/phase, sequence(sustainStart 0.35, hold 30 s, 취소 = jumpToEnd), 손 위치(HAND/fingertip), 블록 중심 배치, 바깥 0.505 / 안쪽 0.49 면 거리, 도구 잔상 0.3 s·적중 글린트 +0.13 s 는 예전과 같음.
- 서버 바인딩 그대로: 시전 → begin / ghost_tool(+4 t, 600 t) / end(+600 t); 손이 닿지 않는 블록을 부수면 external:ghost_tool_break(+ 든 도구에 따라 external:ghost_tool_swing_pickaxe|axe|shovel), 설치하면 external:ghost_tool_place (블록 중심). **static 기준 방향은 시전자 수평 방향이라, 큐브 외곽선이 블록과 맞으려면 90° 스냅이 필요함 (위 Placement 항목 그대로).**
- 왜 고쳤나: 예전 기본 미리보기에서는 거의 아무것도 보이지 않았음(작은 손끝 점, 옅은 면 테두리). 이제 모든 시스템이 잘 보임:
  - begin: 유령 손(0.42 m)과 메아리 2겹, 손 위 유령 호, 곡괭이·도끼·삽 유령 3개가 손 둘레를 돌다가 작아지며 남음, 손끝으로 조여드는 고리, 0.3 s 글린트와 불꽃.
  - ghost_tool(유지): 숨쉬는 손끝 빛, 반짝임, 떠오르는 모트, 손 주위를 천천히 도는 작은 유령 도구들(약 1.7초마다 하나씩, 3초 수명), 유령 호, 손 잔상이 2.5초마다 떠오름. onStop 꼬리 0.2~0.45 s.
  - end: 손 잔상이 위로 사라지고, 작은 도구들이 흩어지며, 고리가 퍼지고, 모트가 남음.
  - break: 블록 모서리 12개를 선 메시(교차 띠)로 그린 큐브 외곽선(밝은 1겹 + 퍼지는 메아리 1겹), 면 프레임, 칸 안 섬광, 유령 블록 조각(중력), 모트, 바닥 선 링, 잉크 퍼프.
  - place: 바깥 큐브 외곽선이 1.35배에서 줄어들며 자리 잡음, 면 프레임, 모서리 글린트 4개, 채움 빛, 바닥 링 수축, 모여드는 모트.
  - swing_*: 도구 잔상 1.0 m(예전 0.55)과 메아리 2겹, 휘두름 호(스트로크 와이프), 적중 글린트와 불꽃.
- 큰 마법진, 링크선, 긴 팔, 거대한 떠 있는 손은 여전히 없음.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
