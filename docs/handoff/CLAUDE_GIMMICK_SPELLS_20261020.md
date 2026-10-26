# 던전 기믹 마법 239~250 + 기믹 블록 설치·배리어·마력 신호 (Claude, 2026-10-20)

브랜치: `claude/dungeon-gimmick-spells-20261020` (기준: `codex/operating-final-20261019` @ `6b9866b`)

## 0. 먼저 읽을 주의사항 (GPT)

1. **빌드 안 했음.** Gradle은 돌리지 않았고, 실게임 확인도 하지 않았습니다.
   - 타입 검사는 GitHub의 Paper API 1.21.4 소스와 Adventure 소스로 `javac -sourcepath` 해서 했습니다. `gimmick-paper` 전체와 `magic-mythic-paper`의 새 파일 3개에서, 우리 코드 쪽 오류는 0건입니다.
   - ModelEngine·WorldGuard·MythicMobs는 소스가 없어 검사하지 못했습니다. ModelEngine 쪽 새 코드(틴트·레이어)는 리플렉션이라 이 셋과는 직접 묶이지 않습니다.
   - 퍼즐 엔진·클라이언트 마이그레이션의 순수 자바 부분은 손으로 만든 main으로 돌려 확인했고, JUnit 테스트도 추가했습니다(`PuzzleEngineTest.deviceConditionsAndManaInputs`, `DefaultSpellMigrationTest.retiredShippedSpellsAreRemovedButEditedOnesStay`).
   - 호스팅에서 `Build-Server.ps1 -Verify`를 돌려 주세요.
2. **오너 결정 (2026-10-20)**
   - 개발 안 된 168~299번 마법은 버립니다. 도감 파일 105개를 지웠습니다.
   - 지금 마법은 238번까지 개발된 것으로 봅니다(300~323 몹 습득 마법 = 기획 번호 215~238).
   - 새 기믹 마법은 239번부터 붙입니다.
   - 원소: 되감기·차원 표식 = 어둠, 도관 회전 = 대지. 무게추는 VFX로만 표현합니다.
3. **기믹 모델 26종 폐기, 37종만 남김** (`gimmick-paper/models.yml`, `gimmick-paper/models/*.bbmodel`).
   - 운영 서버 `plugins/ModelEngine/blueprints/`에 37개만 넣고, 예전 63개 중 폐기분은 빼 주세요.
   - 폐기: 바람 종, 그림자 촛대, 해시계 석판, 마력 축전기, 분배 수정, 논리 받침대, 승강 석주, 부서진 석판, 원소 조각 6종, 조각 받침 제단, 숨겨진 조각 보관함 6종, 영혼 등불, 천칭 저울, 연결 사슬 기둥, 거울 감옥, 쌍둥이 공명석.
4. **35 다시 타오르는 불 / 48 심지 줄이기는 Bridge 유틸이 아니게 바뀝니다.**
   - `spell-runtime.yml`의 두 라우트가 `utility: false`가 되고, 미몹 `chagimmick{type=ignite|extinguish}`가 처리합니다.
   - 원소 화로(기믹)를 먼저 찾고, 없으면 예전 동작(모닥불·초 점화 / 불 블록 끄기)을 그대로 합니다(`GimmickMech.java`로 옮김).
   - Bridge 자바 코드(`SpellUtility`)는 건드리지 않았습니다. 그쪽 `rekindle`·`wick_trimming` 분기는 이제 쓰이지 않습니다.
5. **운영 설정 파일은 직접 병합해야 합니다.** `deployment/operating-config/*`는 손대지 않았습니다.
   - `MagicCodexBridge/spell-runtime.yml`: 239~250 라우트 12개 추가, rekindle·wick_trimming `utility:false`.
   - `MagicCodexBridge/mana-spells.yml`: 239~250 추가, 버린 105종 삭제.
   - `MythicMobs/Skills/chacademi_spells.yml`: `CHA_rekindle`, `CHA_wick_trimming`, `CHA_<239~250>`.
   - `PortableVFX/spell-catalog.yml`·`spell-bindings.yml`: 239~250 추가, 버린 105종 삭제.
   - `MagicCodexBridge/donation-spells.yml`, `MagicDiscovery/discoveries.yml`: 버린 마법 항목·권한 줄 삭제.
   - `ChacaGimmick/config.yml`에 새 키가 있습니다. jar 교체로 덮이지 않으니 아래 7장을 보고 넣어 주세요.
   - `ChacaGimmick/models.yml`도 새 파일로 교체해야 합니다.
6. **VFX 팩 12개**: `portable-vfx-runtime/assets/<팩>/` (오너 PC `Desktop\차카데미 vfx_Claude\<팩>\<팩>.zip` + `preview.html`에도 있음).
   - 모든 클라이언트에 이 팩이 있어야 239~250의 카탈로그 마법이 "준비 완료"가 됩니다. 클라이언트 배포 목록에 넣어 주세요.
   - 12개 모두 `client_compat.py check` OK이고 `scale` 모듈은 없습니다. 고유 텍스처 RGBA는 팩당 0.7~3.5 MiB입니다.
   - 무게추 팩 이름은 **StoneWeight**입니다. 기존 123 반격의 무게추 팩이 이미 `Counterweight`라서 바꿨습니다.

## 1. 새 마법 (239~250, 생성기 `magic-mythic-paper/tools/gimmick_spells.py`)

마나·쿨·사거리는 모두 임시값입니다. 아이콘은 비어 있고(`icon: ''`), 획득 조건 문구만 넣었습니다(`discoveries.yml` 연결 안 함).

| # | id | 이름 | 원소 | 동작 (ChacaGimmick `SpellActions`) | 마나/쿨 | VFX |
|---|---|---|---|---|---|---|
| 239 | thawing_heat | 해빙의 열기 | 화염 | 얼음 봉인에 3초 채널링. 시전자가 피해를 받거나 16칸 넘게 멀어지면 취소되고, 끝나면 `thaw` 트리거 | 40/8 | ThawingHeat: cast·melt·thaw·fizzle |
| 240 | steam_ignition | 증기 점화 | 화염 | 증기 밸브 `steam` 트리거 (`fire_in` 애니메이션 겹침) | 30/6 | SteamIgnition: cast·ignite |
| 241 | water_level | 수위 조절 | 물 | 수위 기둥 수위 +1 (웅크리면 -1), 0~3 | 20/1.5 | WaterLevel: cast·rise·drain |
| 242 | stone_weight | 무게추 | 대지 | 압력판에 무게추가 떨어짐 → 0.65초 뒤 `weight` 트리거 | 30/5 | StoneWeight: cast·drop |
| 243 | vine_awaken | 덩굴 깨우기 | 대지 | 씨앗 화분 `grow` 트리거 → 켜지면 화분 앞 칸에 덩굴(climb) | 40/10 | VineAwaken: cast·sprout |
| 244 | light_beam | 빛줄기 | 빛 | 3초 채널링, **시전자 이동·점프 불가**(속성 수정자). 수평 빛줄기가 거울에서 90° 꺾이고, 프리즘을 지나면 그 색이 되고, 수신 수정에 닿으면 `light` 입력 | 50/6 | LightBeam: cast·beam(2 m 조각, 색 틴트)·hit |
| 245 | mirror_tune | 반사 조율 | 빛 | 반사 거울·회전 다이얼 위치 +1 (웅크리면 -1) | 15/1 | MirrorTune: cast·turn |
| 246 | prism_tint | 프리즘 물들이기 | 빛 | 프리즘 색 순환: 흰→빨→노→초→파→보 (웅크리면 반대). 모델 `prism` 뼈에 틴트 | 15/1 | PrismTint: cast·tint(틴트) |
| 247 | statue_wake | 석상 깨우기 | 어둠 | 잠든 석상 방향 +90° (activate → 잠시 뒤 상태 애니메이션) | 20/2 | StatueWake: cast·wake |
| 248 | rewind | 되감기 | 어둠 | 바라본 장치의 마지막 변화 1단계 되돌리기 (60초 안): 방향·위치·수위·색·노드 켜짐 | 30/5 | Rewind: cast·rewind |
| 249 | conduit_turn | 도관 회전 | 대지 | 마력 도관 방향 +90° → 마력 경로 다시 계산 | 10/1 | ConduitTurn: cast·turn |
| 250 | dimension_mark | 차원 표식 | 어둠 | 게이트에 표식(30초). 다른 게이트에 한 번 더 쓰면 두 게이트가 120초 이어짐(걸어 들어가면 이동) | 40/5 | DimensionMark: cast·mark·linked |

**흐름**
- 카탈로그는 각 팩의 attached `cast`만 재생합니다(바인딩 phase 1개, `follow-entity`).
- 나머지 이펙트는 ChacaGimmick이 `PortableVfxService.play(EffectRequest)`로 기믹 위치에서 재생합니다(`claude:<팩>/<시스템>`, scale 1, yaw, RGB 틴트).
- 빛줄기 조각과 녹이는 이펙트는 `stop(handle)`으로 중간에 끕니다.

**대상이 없거나 틀린 경우**
- 이벤트가 처리되지 않으면 `GimmickMech`이 마나를 환급하고(`Links.add`), 쿨을 지우고(`Links.clearCooldown`), 이유를 액션바에 띄웁니다.
- 마나는 미몹 줄의 `mana=`로 받습니다.

## 2. 기믹 플러그인 변경 (gimmick-paper)

### 2-1. 블록 단위 설치 (오너: "ItemsAdder 가구 설치하듯이")

- 기믹은 **앵커 블록(bx,by,bz) + 방향 0~3**(남·서·북·동, yaw = 방향×90)만 저장합니다. 모델 위치는 여기서 계산합니다.
  - 차지하는 칸 = `models.yml footprint [가로, 깊이]`.
  - 홀수 폭은 가운데 정렬, 짝수 폭은 앵커 + 오른쪽으로 놓입니다.
  - 예전 자유 좌표 `gimmicks.yml`은 읽을 때 앵커·방향으로 자동 변환합니다.
- 설치 방법은 두 가지입니다(`Placer.java`).
  - `/기믹 소환 <모델>`: 내가 서 있는 칸 중앙, 내가 보는 방향.
  - `/기믹 아이템 <모델>`: 블록 면을 우클릭하면 그 옆 칸에, 나를 바라보는 방향으로.
  - 다른 기믹과 겹치거나 막힌 칸에는 설치를 거부합니다.
- 디버그 막대(`DebugTool`)
  - 회전: 90°.
  - 이동·높이: 1칸.
  - 정보: 블록·방향·차지 칸 수·장치 상태·마력 수신 여부를 보여 줍니다.
  - 크기·애니메이션·삭제는 그대로입니다.
- `/기믹 설정 방향 <남|서|북|동|0|90|...>`, `위치 <블록 x y z>`, `높이 <블록 y>`, `상태 <0~3 | 색>`

### 2-2. 배리어 막힘 (`Collision.java`, `collision.yml`)

- `models.yml`의 `collision`에 따라 동작합니다.
  - `solid` = 차지한 칸 × 높이(올림)를 배리어로 채웁니다.
  - `platform` = 한 층만 채우고, 모델 윗면을 칸 윗면에 맞춥니다(부유 발판·빛의 다리).
  - `floor`/`none` = 막지 않습니다. 대상은 압력판·도관·바람 분출구(바닥 위에 얇게), 물레방아(물길 위), 텔레포트 게이트입니다.
- `open: on|off`: 노드가 그 상태일 때 배리어를 걷습니다.
  - 봉인문 2종, 시간차 문, 물길 수문, 얼음 봉인: `on`이면 열립니다.
  - 빛의 다리: 켜져야 밟힙니다.
- 안전장치
  - **공기 칸에만** 배리어를 놓습니다.
  - **우리가 놓은 블록만**(`collision.yml` 기록) 지웁니다. 그 칸이 아직 같은 블록일 때만 지웁니다.
  - 사람이 서 있는 칸은 1초마다 다시 시도합니다(갇히지 않음).
  - 기믹이 사라지면 그 기믹의 배리어도 걷습니다. 언로드된 칸의 배리어는 그 청크가 로드될 때 걷습니다.
  - 기록이 디스크에 먼저 저장된 다음에 블록을 놓습니다(서버가 꺼져도 기록 없는 배리어가 남지 않음).
  - `collision.yml` 형식: `owners.<기믹 id>: ["월드:x:y:z=BARRIER", ...]`.
  - 배리어 높이는 모델 높이 × 크기(scale)를 올림한 값입니다.
- 씨앗 화분이 켜지면 화분 **앞 칸**에 `climb` 높이만큼 뒤틀린 덩굴(타고 오르기)을 같은 방식으로 놓습니다.
- 배리어를 클릭해도 그 칸의 기믹을 클릭한 것으로 처리합니다(`Inputs.blockClick`). 모델 앞의 배리어 때문에 모델 클릭이 막히지 않습니다.
- 기믹 시선 판정은 우리 배리어를 무시합니다.

### 2-3. 마력 신호 (`ManaGrid.java`)

- `mana: out` 노드가 켜져 있으면 그 기믹의 모든 칸에서 6방향 이웃으로 신호를 보냅니다.
- 도관은 `ports`(front/back/left/right, 방향 회전 반영)로 들어온 신호를 다른 포트로 보냅니다.
- 그렇게 닿은 다른 기믹은 "마력 받는 중"이 됩니다.
- 퍼즐 노드는 연속 입력 `mana`를 받습니다.
  - `while: [mana]`: 신호가 있는 동안 켜짐.
  - `turn-on: [mana]` (+`hold`): 시작할 때 한 번 켜짐. 시간차 문이 이 경우입니다.
  - 서버가 켜진 뒤 5초 안에 이미 들어와 있던 입력은 "시작"으로 치지 않습니다(부팅 때 시간차 문이 열리지 않게).
- 퍼즐 밖 도관은 신호가 흐르면 켜짐 애니메이션을 재생합니다.
- ⚠ 도관 모델 포트 방향: bbmodel에서 코너는 -Z(정면)와 +X 팔, 분기는 ±Z와 +X 팔입니다.
  - +X를 오른쪽으로 봤습니다(`ports: [front, right]`).
  - ModelEngine이 X를 뒤집어 보이면 `models.yml`에서 `right` → `left`로 바꾸면 됩니다.
  - 실서버에서 디버그 막대 정보의 `포트`와 모델 모양을 한 번 맞춰 봐 주세요.

### 2-4. 장치 동작 (`Devices.java`)

- **장치 상태**: 거울·다이얼 위치(`rot_k`/`pos_k` 레이어), 수위(`level_k`), 프리즘 색(틴트), 방향. `gimmicks.yml`에 저장합니다.
- **노드 조건**: `level`, `position`, `facing`(남/서/북/동). 장치가 그 상태일 때만 켜집니다.
- **부유 발판**: 노드가 켜지면 `move: [x,y,z]`만큼 `speed`(칸/초)로 한 방향 이동하고 돌아오지 않습니다. `/기믹 초기화`로만 제자리에 옵니다.
  - 이동하는 동안에는 배리어가 없고, 위에 선 사람을 매 틱 발판 윗면에 맞춰 옮깁니다(낙하 속도 0).
  - 출발할 때 배리어를 걷고, 도착하면 새 자리에 다시 놓습니다. `move`는 정수 칸으로 주세요.
  - 실게임에서 타는 느낌(특히 위로 올라갈 때)을 확인해 주세요.
- **바람 분출구**: 켜진 동안 위(2.5칸)에 선 사람을 `launch`(기본 1.3) 속도로 띄웁니다.
- **물레방아**: 차지한 칸이나 그 아래 칸에 물(물 블록·물에 잠긴 블록)이 있으면 연속 입력 `water`가 들어옵니다. 72 수맥 개방으로 흘린 물도 여기에 해당합니다.
- **게이트 연결(250)**: 이어진 게이트에 걸어 들어가면 상대 게이트 앞으로 이동합니다(쿨 `teleport-cooldown-ticks`).
- **되감기 기록**: 장치 변화, `interact`를 받는 노드의 우클릭(레버 등), 기믹 마법 트리거 직전 상태 1단계를 기록합니다. 메모리에만 있어 재시작하면 사라집니다.
- `/기믹 초기화`를 해도 장치 자세(수위·위치·방향·색)는 그대로입니다. 초기화 뒤 그 자세로 조건 노드를 다시 계산합니다.
- 퍼즐 밖 장치의 켜짐(녹은 얼음 봉인, 켜진 화로 등)은 메모리에만 있어 재시작하면 꺼진 상태로 돌아옵니다.

### 2-5. 기존 마법 연동

| 마법 | 연동 |
|---|---|
| 11 윈드 커터 등 바람 공격 | 풍차 터빈 `magic:wind` (예시: `hold: 10, mana: out`) |
| 72 수맥 개방 | 물레방아 밑에 물이 흐르면 `water` |
| 127 용수철 지반 | 연동 없음 (이동 퍼즐용) |
| 168 그림자 분신 | 분신이 압력판 위에 서 있으면 밟은 것으로 침 (`MagicMythicApi.shadowDoubles()`) |
| 194 유령 도구 | 블록 사거리가 6칸보다 길면 허공 우클릭으로 먼 기믹(레버·다이얼 등)을 우클릭 |
| 48 심지 줄이기 / 35 다시 타오르는 불 | 원소 화로 `extinguish` / `ignite` 트리거 (퍼즐 밖이면 화로 자체를 끄고 켬) |

### 2-6. 퍼즐 설정 추가 (puzzles.yml 머리말에 설명, 예시 7개로 교체)

- 트리거: `ignite extinguish thaw steam weight grow`.
- 연속 입력: `stand mana light water`. `while`에 쓰면 들어오는 동안만 켜지고, `turn-on`에 쓰면 시작할 때 한 번 켜집니다.
- 노드 옵션: `mana: out`, `level`, `position`, `facing`, `color`(빛 수신 수정이 받는 색), `move`/`speed`, `launch`, `climb`.
- 폐기 모델을 쓰던 예시(종 순서, 쌍둥이 공명석, 원소 조각 제단)는 지웠습니다.
  - `collect`(아이템 넣기)·`teleport`·`solid` 기능 자체는 남겼습니다. 다른 모델에도 쓸 수 있어서입니다.

## 3. 클라이언트 (magic-codex-fabric)

- `default-spells`
  - 239~250 파일 12개를 추가했습니다(index·known-hashes 포함).
  - 버린 105개를 삭제했습니다. 목록은 `default-spells/removed-defaults.txt`에 있습니다.
- `DefaultSpellMigration` (VERSION `spells-gimmick-239-250-20261020`)
  - 새 239~250은 관리 대상입니다.
  - 버린 파일은 **배포된 그대로인 경우에만** 플레이어 폴더에서 지웁니다(known-hashes 기록과 비교). 고친 파일은 남깁니다.
  - 이걸 안 하면 예전 239~250번 파일(`239_bubble_brand.yml` 등)이 도감에 남아 번호가 겹칩니다.
- `SpellYamlLoaderTest`: 마법 수 330 → 237로 바꿨습니다(버린 칸·215~238 빈 칸 확인).
- 클라이언트 코드에 버린 id 이름이 남은 곳(`SpellCastSounds` 큐, 준비 아이콘 집합)은 동작에 영향이 없어 그대로 뒀습니다.

## 4. 확인할 것 (호스팅/실게임)

1. 빌드 + 테스트: `gimmick-paper`(PuzzleEngineTest), `magic-codex-fabric`(SpellYamlLoaderTest, DefaultSpellMigrationTest).
2. ModelEngine 틴트
   - `ActiveModel#getBone("prism")` → `ModelBone#setDefaultTint(Color)`를 리플렉션으로 호출합니다. 실패하면 모델 전체 `setDefaultTint`로 넘어갑니다.
   - 프리즘 색이 수정 부분에만 보이는지 확인해 주세요.
3. 도관 포트 방향 (2-3)과 반사 거울 반사 방향을 확인해 주세요.
   - 반사 규칙: 거울 방향 m = (방향+위치) mod 4. 정면으로 들어오면 오른쪽으로, 오른쪽에서 들어오면 정면으로 나가고, 나머지는 막힙니다(`SpellActions.trace`).
4. VFX 방향
   - MirrorTune·StatueWake·ConduitTurn의 회전 화살표는 vfx 로컬에서 +Z → −X(위에서 시계 방향 = Minecraft yaw +90)로 그렸습니다.
   - 실게임에서 반대로 돌면 팩 쪽 X를 뒤집어야 합니다.
5. 빛줄기
   - 2 m 조각을 이어 붙입니다. 마지막 조각은 끝점에 맞춰 뒤로 당겨 겹칩니다.
   - 시작 높이는 발 + 1.2 m, 수평입니다. 보는 방향에서 가장 가까운 축(동서남북)으로 나가고, 2틱마다 다시 계산합니다.
   - 시전자 고정은 일시 속성 수정자(`addTransientModifier`)라 저장되지 않습니다. 접속할 때도 한 번 지웁니다.
   - 조각 수가 많으면(32칸 = 16조각+) PortableVFX 재생 예산을 확인해 주세요.
6. 배리어
   - 설치·삭제·문 열림·서버 재시작 후 `collision.yml`과 실제 블록이 맞는지 확인해 주세요.
   - 기존 맵에 이미 놓인 기믹은 재시작 때 앵커 칸으로 맞춰집니다.

## 5. 배포 목록

- `chaca-gimmick` jar (school/wild 중 던전이 있는 서버). `plugins/ChacaGimmick/models.yml` 교체, `config.yml` 키 병합.
- `ChAcademiaMagicMythic` jar (MagicGimmickEvent·GimmickMech·MagicMythicApi 추가). 기믹 플러그인보다 먼저 켜져야 마법 연동이 켜집니다(softdepend 이미 있음).
- MythicMobs `chacademi_spells.yml`, Bridge `spell-runtime.yml`·`mana-spells.yml`·`donation-spells.yml`, MagicDiscovery `discoveries.yml`, PortableVFX `spell-catalog.yml`·`spell-bindings.yml` (위 0-5).
- PortableVFX 클라이언트 효과 폴더에 팩 12개, ModelEngine blueprints 37개(+ItemsAdder 리소스팩 병합).
- MagicCodex 클라이언트 모드 (default-spells·마이그레이션).

## 6. 새 config 키 (ChacaGimmick/config.yml)

```yaml
tool:
  material: BLAZE_ROD
  scale-step: 0.1        # rotate-degrees / move-step / height-step 은 없어짐 (90도·1칸 고정)
vent-launch: 1.3
vine-pot-climb: 5
rewind-seconds: 60
dimension-link-seconds: 120
light-beam-ticks: 60
light-beam-range: 32
vfx:
  enabled: true
  range: 48
```
