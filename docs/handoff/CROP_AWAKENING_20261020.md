# 살아나는 작물 (2026-10-20, Claude)

다 자란 바닐라 작물을 수확하면 **5% 확률**로 작물이 몹이 되어 땅에서 기어나온 뒤 플레이어를 피해 도망간다. 때리거나 마법으로 잡는다. 보상은 아직 없음(나중에 정함).

## 바뀐 것

| 위치 | 내용 |
|---|---|
| `creature-spawns-paper/.../CropAwakening.java` (새 파일) | `BlockBreakEvent`(MONITOR, 취소된 이벤트 무시) → 다 자란 작물인지 확인 → 확률 → `CA_Crop_<id>` 소환 (다음 틱) |
| `WildlifePlugin.java` | 리스너 등록, `/야생관리 reload` 때 작물 목록 다시 읽기, 테스트 명령 `/야생관리 crop <작물ID> [shiny]` |
| `config.yml` | `crop-awakening` 섹션 추가 |
| `plugin.yml`, `build.gradle` | 0.3.0 → 0.4.0, 명령 사용법 |
| `creature-spawns-paper/mythicmobs/Mobs/chacademia_crops.yml` | 미몹 14개 (7종 × 기본/이로치) |
| `creature-spawns-paper/mythicmobs/Skills/chacademia_crops.yml` | `CA_Crop_Awaken`(기어나오는 2.4초 동안 AI 정지 + 흙 파티클), `CA_Crop_Hurt` |
| `creature-spawns-paper/mythicmobs/blueprints/*.bbmodel` | ModelEngine 모델 14개 |

## 동작 규칙

- 대상: 밀·당근·감자·비트·네더 사마귀(최대 성장 단계), 호박·수박(붙어 있는 줄기가 이 블록을 가리킬 때만 → 설치한 호박을 반복해서 부수는 꼼수 차단)
- 서바이벌·어드벤처만. 크리에이티브 제외
- 몹이 나오면 그 작물의 드롭은 취소(`cancel-drops: true`) — 작물이 몹으로 변한 것이므로
- 플레이어당 동시에 최대 3마리, 30초 뒤 연기와 함께 사라짐, 서버 재시작 시 저장 안 됨(`setPersistent(false)`)
- 이로치 확률은 `crop-awakening.shiny-denominator`(기본 4096, 야생 몹과 같음)
- `worlds`가 비어 있으면 모든 월드
- 서버 부담: 수확 1회당 맵 조회 + 난수 1번, 몹 1마리당 지연 작업 1개. 매 틱 작업 없음

## 배포 (GPT)

1. `creature-spawns-paper` 빌드 → `chacademia-wildlife-0.4.0.jar` 교체 (야생 서버)
2. `mythicmobs/Mobs/chacademia_crops.yml` → `plugins/MythicMobs/Mobs/`, `Skills/chacademia_crops.yml` → `plugins/MythicMobs/Skills/`
3. `blueprints/*.bbmodel` 14개 → `plugins/ModelEngine/blueprints/` 후 `/meg reload` (리소스팩 재생성·배포)
4. `/mm reload`, `/야생관리 reload`
5. 테스트: `/야생관리 crop wheat`, `/야생관리 crop pumpkin shiny` → 기어나오는 모션, 도망, 30초 뒤 사라짐 확인. 실제 수확은 `chance`를 잠깐 1.0으로 올려 확인

## 주의 (빌드·검증 때 확인)

- MythicMobs jar 없이 작성해서 **컴파일 미검증**. 특히 `SpawnReason.OTHER` 가 5.9.0에 없으면 `SUMMON` 등 있는 값으로 바꿀 것
- 서버의 기존 `plugins/ChAcademiaWildlife/config.yml` 에는 새 섹션이 자동으로 안 써진다(값은 jar 기본값으로 동작). 숫자를 바꾸려면 섹션을 직접 붙여 넣을 것
- 모델 id는 파일 이름 그대로 `crop_<작물>`, `crop_<작물>_shiny` (기존 `ca_*_s` 규칙과 다름)
- 애니 이름은 ModelEngine 기본 이름 `spawn / idle / walk / attack / death`. spawn은 `CA_Crop_Awaken`에서 `state{s=spawn}`로 직접 재생. ModelEngine이 spawn을 자동 재생하면 그 줄은 지워도 됨
- 히트박스는 bbmodel 안의 숨김 `hitbox` 그룹(밀 0.5×1.45, 호박 0.9×1.25 등)
- `fleeplayers` AI는 MythicMobs 기본 값(거리·속도 옵션 없음). 너무 느리거나 빠르면 `MovementSpeed`만 조절
- `setai{ai=false}` 동안은 맞아도 움직이지 않음(의도). 2.4초 뒤 풀림
- 보상(Drops)은 비어 있음 — 오너가 나중에 정함
