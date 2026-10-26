# SealingThread (봉인의 실) — converter notes (REDO 2026-10-25)

## 시전 1회 = 시스템 2개
- `thread` (link) + 선택한 대상에 맞는 wrap 시스템 **하나만**: `wrap_chest` | `wrap_door_r` | `wrap_door_l`. 둘 이상 동시에 재생하지 않는다 (광역 봉인 아님).
- 모두 시전 시점 t=0 에 동시에 시작. wrap 쪽 지연(0.3 s 감김 시작, 0.86 s 매듭, 1.0 s 발광, 1.16 s 스며듦)은 burst 시간에 들어 있음. 전체 약 1.6 s, 유지/루프 없음.

## 런타임 입력
- `thread` (link, SCHEMA §5): source = 시전자 손끝(오른손 끝 권장), target = 대상의 잠금 지점 (= wrap 시스템의 P), 매 프레임.
- `wrap_*` (static): P = 잠금 지점
  - 상자: 정면(잠금쇠 쪽) 면 위, 잠금쇠 중심 (상자 바닥에서 0.5625 m, 좌우 중앙).
  - 문: 정면 면 위, 손잡이 위치 (문 아래끝에서 0.95 m, 자유단(경첩 반대쪽) 모서리에서 0.15 m 안쪽).
- static 기준 방향: 로컬 +Z = 정면 면을 통과해 대상 안쪽, +Y = 월드 위 (정면 법선 = −Z). 저작 크기는 바닐라 블록 기준 (상자 14/16, 문 두께 3/16 · 높이 2).
  - 서버가 static 기준 방향으로 시전자 수평 방향을 보내면, 시전자가 비스듬히 서 있을 때 감김이 상자/문 면과 어긋난다. **대상 블록의 facing(정면 법선의 반대, 90° 단위)을 static 기준 방향으로 넘기는 것을 권장.** 스키마 변경 없이 바인딩만 바꾸면 된다.
- 문 경첩 방향 → `wrap_door_r` = 정면에서 볼 때 손잡이가 오른쪽(경첩 왼쪽), `wrap_door_l` = 손잡이 왼쪽. 문 정면 = 시전자가 서 있는 쪽 면.

## 범위 밖 (외부 게임 기능)
- 본인이 설치한 문/상자인지 판정, 실제 접근 제한, 봉인 지속시간, 해제 방식/해제 연출 — 이 VFX 에 없음. 봉인 후 남는 실/상시 발광 없음.
- 미리보기의 마네킹·상자·문·바닥 상자는 데모용(demo props)이며 VFX 가 생성하지 않음.

## REDO 2026-10-25 — what changed visually (systems, roles, timing unchanged)
| system | role | loop | duration | key moments (s from cast) |
|---|---|---|---|---|
| `thread` | link (fingertip → lock point) | false | 0.2 (+ particle life ≈0.65) | strands arrive ≈0.27–0.30 |
| `wrap_chest` / `wrap_door_r` / `wrap_door_l` | static (lock point, +Z into the block) | false | 1.3 (≈1.6 total) | 0.30 wrap starts · 0.86 knot · 1.00 pulse · 1.16 seep |
- thread: fingertip line-work sigil + flick sparks + ink flecks; 3 lilac strands (trails) + 2 dark ink strands; rune beads light up along the thread; flare at the lock on arrival.
- wrap: arrival star + ring + ink mist; the 3 wrap threads (same meshes as before) now draw on with a crisp wipe and have a dark ink copy + a violet glow copy; motes converge as they tighten;
  0.78 a **seal sigil** (keyhole knot inside a double ring) + line rings + rune band contract onto the lock and turn; 0.86 clamp ring; 1.00 pulse = star flash, 2 shock rings, sparks, ink flecks; 1.16 the seal sinks into the surface, seep ring, motes + ink wisps.
- Nothing stays after ≈1.6 s (no permanent glow), as before.
- 2차 수정 (2026-10-25): 감김 실을 2.6배 넓힌 빛 리본으로 바꾸고, 가운데에 좁은 잉크 심(어두운 코어)을 얹었다. 같은 메시 경로를 써서 폭만 넓혔다. 실은 약 1.5 s 동안 남는다(전체 약 1.9 s). +0.74~0.76 s에 **결속 섬광**(별, 링 폭발, 불꽃, 실 빛 순간 상승)이 터진다. 블록 정면에 **봉인 자물쇠 시길**(열쇠구멍 매듭 + 라인 링 + 룬 띠, 상자 기준 지름 약 0.6~0.85 m, 어두운 바탕과 빛)이 0.78 s에 조여 붙어 약 1.85 s까지 유지되다 스며든다. thread link의 늦은 burst(구슬·도착 섬광)는 duration 0.2 s 안에서 생성해 투명하게 대기하게 고쳤다(1차 판에서는 재생되지 않았음). 시스템·타이밍(0.30 / 0.86 / 1.00 / 1.16)은 그대로다.



## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
