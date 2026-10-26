# 원소 반응 CC 개편: VFX 팩 교체 + 서버 반응 효과 (Claude, 2026-10-20)

브랜치: `claude/reaction-cc-vfx-20261020` (기준: `codex/operating-final-20261019` @ `8356980`)

## 0. 상태

- VFX 팩(클라이언트 에셋)과 서버 코드(`Reactions`, `ReactionVfx`, `config.yml`)를 함께 바꿨습니다. 오너가 바로 구현을 요청했습니다(2026-10-20).
- **빌드하지 않았습니다.** 이 환경에서 paper-api를 받을 수 없어(403) javac도 돌리지 못했습니다.
  - API 시그니처는 손으로 검토했습니다. 컴파일 오류가 나면 "3. 서버 변경" 부분부터 봐 주세요.
- 툴킷 `client_compat.py check` **OK**입니다.
  - 고유 텍스처 36장, RGBA 3.96 MiB입니다 (반응마다 직접 칠한 텍스처, 레이어 81개).
  - 검사기 WARN 6.2 MiB는 재질마다 중복으로 센 값입니다.
- 오너 요청: 새 반응 기획(아래 표)에 맞춰 다시 만들기, 예전보다 잘 보이게, 몹 크기에 따라 크기 조절.

## 1. 새 반응 기획 (오너 확정 2026-10-20)

일반 CC는 3초, 하드 CC(기절·수면·속박·공포·무장해제·고정)는 1초입니다.

| 반응 | 효과 |
|---|---|
| 증기 폭발 (화+물) | 반경 3 작은 피해 + 살짝 띄움 |
| 불씨 확산 (화+바람) | 반경 2 화상 3초 |
| 용암 분출 (화+대지) | 회복봉인 3초 |
| 태양 플레어 (화+빛) | 발광 3초 + 약화 3초 |
| 잿불 낙인 (화+어둠) | 마나번 |
| 연쇄 번개 (물+바람) | 연쇄 번개 + 맞은 적 무장해제 1초 |
| 진흙 (물+대지) | 둔화 3초 |
| 무지개 (물+빛) | 주변 아군 회복 |
| 심연 (물+어둠) | 독 3초 |
| 모래 회오리 (바람+대지) | 반경 4 끌어모으기 + 고정 1초 |
| 섬광 (바람+빛) | **미정** (기존 효과 = 주변 몹 어그로 해제 유지, VFX는 간단한 섬광) |
| 공허 균열 (바람+어둠) | 공포 1초 |
| 수정화 (대지+빛) | 기절 1초 |
| 매장 (대지+어둠) | 속박 1초 |
| 일식 (빛+어둠) | 수면 1초 |

## 2. 팩 구성 (`portable-vfx-runtime/assets/ElementReactions/`)

효과 ID는 `claude:elementreactions/<시스템>`입니다. 모두 `impact` 역할입니다.

| 시스템 | 재생 위치 | 길이 |
|---|---|---|
| `steam` | 대상 발밑, 범위형 (반경 3으로 제작) | 0.8초 |
| `wildfire` | 대상 발밑, 범위형 (반경 2) | 0.8초 |
| `wildfire_burn` **새로** | 화상이 걸린 적마다 | 0.5초 |
| `magma`, `flare`, `ember_curse`, `mud`, `abyss` | 대상 발밑 | 0.8~0.9초 |
| `shock` | 튈 때마다 이전 대상 가슴 → 다음 대상 (예전과 같음) | 0.26초 |
| `shock_disarm` **새로** | 번개 맞은 적마다 | 1초 |
| `rainbow` | 시전자 발밑, 범위형 (반경 4) | 1초 |
| `rainbow_heal` **새로** | 회복된 아군마다 | 0.7초 |
| `sandstorm` | 대상 발밑, 범위형 (반경 4) | 0.7초 |
| `sandstorm_pin` **새로** | 끌려온(고정된) 적마다 | 1초 |
| `flash` | 예전 그대로 | 0.2초 |
| `void_gust`, `crystal`, `bind`, `eclipse` | 대상 발밑 (하드 CC 동안 유지) | 1초 |

- 단일 대상 연출은 1.8 m 기준으로 만들었습니다. 일반 CC는 걸리는 순간만 보여 주고, 남은 시간은 머리 위 디버프 아이콘이 보여 줍니다.
- 하드 CC 연출은 CC 시간 1초 동안 유지됩니다.

## 3. 서버 변경 (적용함)

### 3-1. 크기 (`ReactionVfx`)

- **대상 크기:** ModelEngine 모델이 있으면 메인 히트박스(가로·높이 × 모델 배율)를 쓰고, 없으면 `getBoundingBox()`를 씁니다.
  - ModelEngine은 리플렉션으로 호출하고, 실패하면 바운딩 박스로 넘어갑니다.
- **단일 대상 배율:** `s = clamp((높이/1.8 + 가로/0.6) / 2, 0.6, 4)`
  - 플레이어·좀비는 1, 거미는 약 1.4, 철 골렘은 약 1.9입니다.
  - 예전 식(높이/1.8, 0.5~1.5)은 넓적한 몹에서 너무 작았습니다.
- **범위형 배율:** `(반경 + 대상 가로/2) / (반경 + 0.3)`
  - 큰 몹은 범위 원이 몸 바깥까지 보이게 됩니다.
  - 판정 반경은 그대로(대상 발밑 중심)입니다. 몸 가장자리부터 잴지는 오너 확인이 필요합니다.
- 재생 시간(`ticks`)은 위 표 길이에 맞췄습니다.
- API: `body(String system, LivingEntity, ticks)`, `body(Reaction, LivingEntity, ticks)`, `area(Reaction, Location, radius, LivingEntity centre, ticks)`, `size(LivingEntity)`.
  - 예전 `body(Reaction, LivingEntity)` / `body(Reaction, Location, double)` / `area(Reaction, Location, int)`는 지웠습니다. 호출하던 곳은 `Reactions`뿐이었습니다.
  - ModelEngine 조회는 `ModelEngineAPI.getModeledEntity(uuid).getModels()` → `getBlueprint().getMainHitbox().getWidth()/getHeight()` × `getScale()`입니다.
  - 이름이 하나라도 다르면 그 세션 동안 조회를 끄고 바운딩 박스를 씁니다. 실서버에서 큰 모델 몹 크기가 맞는지 봐 주세요.

### 3-2. 반응 효과 (`Reactions`)

- 위 1번 표대로 바꿨습니다. CC는 모두 상태이상 엔진(`plugin.status().apply`)으로 겁니다.
  - 바닐라 포션(구속·공중부양)과 수정화의 자체 AI 정지(`frozen`)는 없앴습니다. 기절이 겹칠 때 해제 순서가 꼬이던 문제도 같이 없어집니다.
  - 태양 플레어의 발광만 바닐라 GLOWING 그대로입니다(디버프 아님).
- 경험치 2배(`doubleExp`), 공허 균열 되돌리기, 일식 지연 폭발, 불씨 확산 표식 번짐 코드는 삭제했습니다.
  - 아티팩트 API `spread()`(표식 옮기기)는 그대로이고, 연출만 `wildfire_burn`으로 바꿨습니다.
- 연쇄 번개: 첫 대상과 튄 대상 모두 무장해제 1초입니다.
- 모래 회오리: 범위 안 적을 끌어모으고(보스 제외), 첫 대상 포함 모두 고정 1초입니다.
- 마나번 시간은 `status.mana-burn-ticks`(내부 쿨) 값을 그대로 씁니다.
- 보스는 상태이상 엔진 규칙을 그대로 따릅니다(기절·수면·공포 → 둔화, 속박 면역).
- 아티팩트 보너스(`control` 틱)는 새 CC 시간에 더합니다. 반응 장인의 인장 +0.5초가 그대로 들어갑니다.
- 새 config(`reactions:`): `cc-ticks: 60`, `hard-cc-ticks: 20`, `steam-radius: 3`, `wildfire-radius: 2`, `sandstorm-radius: 4`, `mud-slow: 0.4`.
  - 기존 서버 config에 없어도 코드 기본값으로 동작합니다.

### 3-3. 아티팩트 (오너 결정 대기)

반응 장인의 인장·느린 잉크병·무지개 프리즘·반응 촉진제 수정과, CC 감소 아티팩트 교체안은 오너가 아직 고르지 않았습니다.

## 4. 원본

- `tools/vfx-src/ElementReactions/elementreactions.js`: spec. 맨 위 주석에 재생 규칙이 있습니다.
- `tools/vfx-src/ElementReactions/gen_er2.py`: 텍스처 생성기입니다 (예전 gen_er.py 대체).
- 고칠 때는 anime-vfx-maker 툴킷의 `make_bundle` → `client_compat fix/check` 순서로 돌립니다.

## 5. 배포

- ChAcademiaMagicMythic (magic-mythic-paper) jar: 학교·야생 서버 모두 해당합니다.
- 클라이언트 VFX 팩: 설치 위치는 `.minecraft/config/portablevfx/effects/ElementReactions/`입니다.
  - 팩 없이 서버만 올려도 반응 효과는 동작하고, 연출만 안 보입니다.
