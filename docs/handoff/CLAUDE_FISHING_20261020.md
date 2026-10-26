# 낚시 시스템 (Claude, 2026-10-20, 미빌드)

브랜치 `claude/fishing-20261020`, 기준 `codex/operating-final-20261019` @ `81c7458`. 오너 승인된 계획(채팅 2026-10-20)대로 구현.

## 0. 먼저 읽을 것

- **빌드 안 함.** 확인한 것:
  - 서버 새 파일(`Fishing*.java`)은 PaperMC `ver/1.21.4` paper-api 소스 + adventure 4.18 등에 대고 javac 로 타입 검사, 오류 0 (MagicCodexBridge·ClimateService·AlchemyBridge 는 필요한 메서드만 있는 임시 stub 으로 대체해서 검사).
  - 클라 새 파일은 Minecraft/Fabric 이 없어 **API stub** 으로만 검사(오류 0). 실제 yarn 시그니처는 빌드에서 확인 필요.
  - 프로토콜 왕복은 직접 실행해 통과. JUnit `magic-codex-fabric/src/test/.../FishingProtocolTest.java` 추가.
- **같이 배포:** MagicCodexBridge(paper, 학교·야생 둘 다) + MagicCodex HUD(fabric). 새 채널 `magiccodex:fishing_request` / `magiccodex:fishing_response`. 다른 플러그인·Velocity 변경 없음.
- 기존 파일 수정은 세 군데뿐: `MagicCodexBridge.java`(필드·생성·종료 + `climateService()` 접근자), `MagicCodexClient.java`(`FishingClient.initialize()`), `plugin.yml`(명령 2개·권한 2개).

## 1. 파일

| 위치 | 파일 |
|---|---|
| protocol | `FishingProtocol.java` |
| paper | `FishingBridge`(흐름·명령), `FishingConfig`(yml 4개), `FishingGear`(아이템 PDC·로어·모델), `FishingWater`(물 측정), `FishingStore`(도감 DB) |
| paper 리소스 | `fishing.yml`, `fishing-fish.yml`(200종), `fishing-gear.yml`(27종), `fishing-treasure.yml` |
| fabric | `FishingClient`, `FishingScreen`(패널+장비 창), `FishingCodexScreen`(도감), `FishingArt`(그림·그리기 도우미) |
| fabric 리소스 | `textures/gui/fishing/**` (GPT UI PNG 79개 + 수중 배경 4장 + 코드로 만든 fx 3장), `items/fishing/**`, `models/item/fishing/**`, `textures/item/fishing/**` (장비 아이콘 27) |
| 도구 | `tools/fishing/install_fish_art.py` (물고기 그림 200장 받으면 실행) |

## 2. 흐름

1. 바닐라 낚싯대로 던짐 → 찌가 물에서 BOBBING 이 되면 서버가 물 측정(수면 연결 칸 수 BFS 4096 한도, 실제 깊이, 바이옴, 하늘빛) → `SESSION(WATER)` → 패널 열림.
   - 그 훅은 바닐라 입질이 안 나게 대기 시간을 1시간으로, BITE·CAUGHT_FISH·LURED 이벤트는 취소.
2. 클라가 찌를 내리고 클릭(또는 최대 깊이) → `STOP(depth)` → 서버가 물고기/보물 추첨 → `BITE`.
3. 클라 연출: 대기 → 물고기 접근 → 약올리기(툭 건드림, 이때 클릭하면 SPOOKED) → 찌 감도만큼 떨림 → 진짜 입질 "!!" + 타이밍 바 → 성공이면 줄다리기.
4. 줄다리기: 누르면 감기(장력↑), 떼면 풀림(물고기가 끌고 내려감). 수면 0m = CAUGHT, 장력 100% 넘김 = SNAPPED, 낚싯줄 길이 끝 = ESCAPED.
5. `RESULT` → 서버가 최소 시간(`FishingProtocol.minimumCatchMillis`) 확인, 내구도·미끼 차감(PDC), 지급(인벤 → 가득 차면 우편 → 우편 없으면 발밑), 도감 기록, 훅 제거, 결과 전송.
6. 결과 확인 → "던지기 전" 패널. 클릭 → `RECAST` → 서버가 처음 던진 위치로 실제 FishHook 을 발사(`launchProjectile(FishHook.class)` + 탄도 계산 `FishingBridge.aim`). 40칸 넘게 멀어지면 끝냄.

ESC = `CANCEL`(훅 제거). 손으로 회수·아이템 바꾸기·월드 이동·훅이 48칸 밖 = 서버가 `CLOSE`.

## 3. 오너 결정 반영

- 장비는 아이템 PDC: 낚싯대 PDC 에 끼운 찌·줄·미끼 ID와 내구도/개수. 장비 창은 손에 든 낚싯대 기준, 바꾸면 인벤 아이템과 맞바꿈. 낚시 중 변경 거부.
- PDC 없는 바닐라 낚싯대 = 기본 세트(대나무·코르크·무명실·지렁이, 줄지 않음). 빠진 부품도 기본으로 채움. 바닐라 낚싯대에 부품을 끼우면 대나무 낚싯대(내구도 가득)로 바뀜.
- 내구도: 낚싯대 줄다리기마다 -1, 찌 던질 때마다 -1, 줄 줄다리기마다 -1 (끊기면 추가 -10, `snap-line-damage`). 0 = 고장. 낚싯대 고장이면 던지기 자체를 막음, 찌·줄이 고장 나면 낚싯대에서 빠져 인벤으로 돌아옴(고장 상태).
- 미끼: 잡거나·놓치거나·끊기면 1개, **약올릴 때 너무 일찍 눌러 놀라게 한 것(SPOOKED)은 안 줄어듦**. 진짜 입질을 놓친 건(MISSED, "미끼만 먹고 도망갔다") 줄어듦.
- 전설 확률 낮춤 (얕은 0.1 / 중층 0.5 / 깊은 1.5 / 심해 3%).
- 바이옴: Terralith 커스텀 바이옴 67개 포함 34개 낚시 분류 (`fishing.yml biome-groups`). 배경 4종 판정: 동굴(동굴 바이옴 또는 하늘빛 < 8) → 얼음 → 바다 → 민물.
- 미니게임 최대 깊이 = min(낚싯줄 길이, 실제 깊이 제한 `depth-cap`).
- 도감 학교·야생 공유: player-state MariaDB (`codex_fishing`, `codex_fishing_claim`). MariaDB 없으면 서버별 `fishing.db`(SQLite).
- 도감 완성 보상 25/50/75/100%: `fishing.yml codex-rewards` 비어 있음(명령/아이템 채우면 됨). 보물 보상 `fishing-treasure.yml` 은 임시 바닐라 아이템.

## 4. 명령

- `/낚시도감 [플레이어]` (권한 `magiccodex.fishing.codex`, 기본 true; 남의 것은 admin)
- `/낚시관리 리로드 | 장비지급 <플레이어> <장비ID> [개수] | 물고기지급 <플레이어> <물고기ID> [cm] | 도감초기화 <플레이어> | 미리보기 [민물|바다|얼음|동굴]` (`magiccodex.fishing.admin`, op)
- **미리보기**: 훅 없이 패널을 열어 가라앉기→입질→줄다리기→결과까지 전부 시험 (보상·내구도·도감 기록 없음). 오너 검토용.

## 5. 주의 (빌드·실게임에서 확인)

1. `FishHook` 대기 시간: `setMaxWaitTime → setMinWaitTime → setWaitTime` 순서, 예외는 무시. 바닐라 입질이 그래도 나면 이벤트 취소로 막힘.
2. `launchProjectile(FishHook.class)` 로 다시 던진 훅이 플레이어의 낚싯대 훅으로 인정되는지(손으로 회수 가능한지) 실게임 확인.
3. 낚싯대 아이템 모델: `items/fishing/rods/*.json` 은 GUI·바닥·액자에서 아이콘, 손에서는 바닐라 낚싯대(던지면 cast 모델). 이 select 정의라 `ItemIconTextures`(부드러운 큰 아이콘) 대상이 아니어서 인벤 아이콘은 바닐라 방식으로 축소됨. 찌·줄·미끼는 단순 모델이라 부드럽게 그려짐.
4. 물고기 그림 200종(GPT 게임용 256x256, 이름·등급 yml 과 전부 일치 확인) 반영 완료: `textures/gui/fishing/fish/`(256, 결과·도감) + `textures/item/fishing/fish/`(128, 아이템) + models/items json. `fishing.yml settings.fish-item-models: true` 로 켜 둠. 물고기를 새로 추가할 땐 그림도 같이 넣어야 함(없으면 아이템이 보라·검정 텍스처). 줄다리기 중에는 일부러 실루엣만 보임(잡기 전엔 뭔지 모르게).
5. 스크롤되는 수중 배경(724x2172)은 통째로 그리고 scissor 로 자름(영역 잘라 그리기를 매 프레임 바꾸면 텍스처 캐시가 늘어나서).
6. 클라 텍스트는 정수 m 로만 그려서 글자 캐시가 폭증하지 않게 함.

## 5-1. 독립 검토 후 고친 것 (커밋 3)

- 줄다리기 시작 첫 프레임에 바로 '놓침'이 나던 문제: 챔질한 클릭이 그대로 감기로 이어지게 함. 찌는 낚싯줄 길이의 85%까지만 내려가서 물고기가 끌고 갈 여유가 있음.
- 입질 뒤 ESC·손으로 회수 = 놓친 것과 같은 내구도·미끼 소모 (공짜 다시 뽑기 막음).
- MagicCodex 모드가 없는 플레이어(채널 미등록)는 바닐라 낚시 그대로. 가라앉기 90초 타임아웃.
- 입질 뒤 손에 든 낚싯대 칸이 바뀌면 결과 거부(미끼만 다른 낚싯대로 바꿔치기 방지). 낚시는 주 손 낚싯대만 (보조 손은 바닐라 낚시).
- 세션 생성은 PlayerFishEvent MONITOR(ignoreCancelled), 거부·바닐라 입질 취소는 HIGH.
- 다시 던지기 거리 30칸 (바닐라 훅이 32칸 밖에서 사라짐).
- 도감 첫 로딩이 겹쳐 횟수가 두 배가 되던 문제, 미리보기 세션이 실제 던지기를 먹던 문제, wait-seconds 55초 상한, 클라 CASTING 10초·결과 대기 8초 타임아웃, 도감 검색창 입력 안 되던 문제, 장비 창 마지막 카드 선택.
- `Bite.magic` 추가(프로토콜): 마력증폭 낚싯대 `magic_reel` 이 마법 물고기에서 실제로 적용됨.

## 6. 남은 일 (오너와 정할 것)

- 보물·도감 보상 내용, 장비 획득 경로·수리, 상점 판매, 하우징 수조 연결 (`FishingGear.fishOf(item)` 로 물고기 ID 읽기 가능).
