# 2026-10-26 패치 독립 코드 리뷰 — 클라이언트 (Fabric 모드)

범위: `70044a79..c8e27c44` 에서 바뀐 `magic-codex-fabric/src/main`, `chacademy-motion-mod`, `chacademy-story/mod`, `portable-vfx-runtime/client`, `chaca-portrait-protocol` 의 Java 전부 (96 + 15 파일).
기준: 클라 멈춤(렌더 스레드에서 큰 파일·그림 처리), GPU 메모리 누수, 프레임마다 쓰레기 생성, 창 예외로 게임 전체가 꺼지는 것, 서버→클라 패킷 디코더가 이상한 데이터에 던지는 것.

## 결론 한 줄

치명 없음. 높음 1건(상대 일러스트 창 열 때 렌더 스레드에서 4백만 픽셀 훑기 → 끊김) 고침. 나머지는 중간·낮음이고 서버→클라 패킷 디코더 10종은 전부 `IllegalArgumentException` 으로 묶여 있어 네티 파이프라인까지 올라가는 예외는 없었다.

## 표

| # | 심각도 | 파일:줄 | 무엇이 문제인가 (실제 상황) | 조치 |
|---|---|---|---|---|
| 1 | 높음 | `client/OtherPortraits.java` upload() (옛 171~174줄) | 상대 일러스트 PNG(최대 2048×2048)를 받은 뒤 보이는 상자를 찾느라 `PlayerTurnPortraitLayout.bounds` 로 픽셀 4백만 개를 **클라이언트(렌더) 스레드**에서 훑었다. 내 일러스트(PortraitClient)는 IO 스레드에서 하는데 여기만 달랐다. 상대 정보창을 열 때마다 수십 ms 끊김. | **고침** — IO 스레드(디코드 직후)에서 계산해 넘기고, 상자 계산이 실패한 그림은 올리지 않음. |
| 2 | 중간 | `client/OtherPortraits.java` IO 실행기 (32줄) | 큐 8칸 + `DiscardOldestPolicy`: 정보창을 빠르게 여러 번 열면 디코드 작업이 버려지고, 그러면 그 사람의 `decoding=true` 가 영영 남아 "불러오는 중" 이 안 끝난다 (status() 가 다시 읽지도 않음). | **고침** — 무제한 `LinkedBlockingQueue` (작업 수는 창 열기·서버 응답 수로 한정). |
| 3 | 낮음 | `client/StatsScreen.java:85` | `live=false` 이고 `remote=null` 인 미리보기 고정값 생성자(`StatsScreen(Supplier,false)`, 비주얼 테스트 SocialVisualCheck)로 열면 `remote.profile()` NPE. 운영 경로에서는 안 생김. | **고침** — `remote==null` 가드. |
| 4 | 낮음 | `client/GalaxyBagScreen.java` onMouseClick (잠긴 쪽 빠른 이동 차단) | 클라에서 QUICK_MOVE·PICKUP_ALL 을 서버에 보내지 않고 끊으므로 클라-서버 불일치·아이템 증발은 없다. 다만 2×2 제작칸 되돌리기(`returningGrid`)는 잠긴 쪽에서도 QUICK_MOVE 를 보내며, 그 아이템이 어디로 가는지는 **서버**(바닐라 quickMove → 보이지 않는 27칸 포함)가 정한다 — 이 패치 전부터 같은 동작. 핫바 숫자키 SWAP·Q 버리기는 바닐라 그대로. | 보고만. 서버 쪽 리뷰에서 "잠긴 쪽 27칸으로 quickMove 가 들어가면 어떻게 보이는지" 확인 권장. |
| 5 | 낮음 | `client/ReconfigurationScreen.java` tick() | 2.5초 안에 답이 없으면 SELECT/APPLY 를 **한 번 더 보낸다**. 서버(`ReconfigurationBridge` 160줄)가 같은 토큰 APPLY 를 두 번 적용하지 않고 결과만 다시 보내는 것을 확인함 → 중복 적용 없음. | 보고만 (서버 확인 완료). |
| 6 | 낮음 | `client/ChunkClaimClient.java` edge() | 땅문서를 든 동안 매 프레임 (반지름 2 → 60변 × 8 × 3 칸) 마다 `float[][]` 를 새로 만든다. 반지름은 프로토콜에서 2 로 막혀 있어 프레임당 수천 개 수준 — 체감 없음. | 보고만. |
| 7 | 낮음 | `client/TeleportPickScreen.java` visible() | 매 프레임 300명까지 소문자 변환·필터 → 새 리스트. 300명 한도라 미미. | 보고만. |
| 8 | 낮음 | `client/TrainingDummyClient.java` Atlas.bake() | `new CodexTypography(c)` 를 글자판 한 번 굽는 데만 쓰고 close 하지 않음 (글꼴 객체만 들고 있음, 텍스처는 안 만듦). 한 번뿐이라 누수라 하기 어려움. | 보고만. |
| 9 | 낮음 | `motion/ChacademyMotionClient.java` PerkS2C 수신 | `BroomFlight.onPerks` 를 `client.execute` 없이 네티 스레드에서 바로 호출 (정수 하나 쓰기). 깨지진 않지만 다른 수신기와 방식이 다름. | 보고만. |
| 10 | 낮음 | `client/HousingClient.java` suspend()/tick() | 아티팩트 진열장 → 도감 구경 → 다른 창으로 가 버리면 `closed()` 로 방 세션을 닫는다. 참조 비교 두 번이라 비용 없음. 재접속 시 `reset()` 에서 비움 — 정상. | 이상 없음. |

## 패킷 디코더 점검 (서버 → 클라, 이상 데이터에 던지지 않는가)

| 채널 | 디코더 | 결과 |
|---|---|---|
| Tarot | `TarotProtocol.decode` + `TarotClient` | 크기·별 1~3·그림 0~19·꼬리 바이트 검사, IAE 로 묶음, 수신기에서 catch 후 무시. `TarotScreen` 은 `ILLUSTRATIONS[card.illustration()]` 를 검증된 값으로만 씀. 통과 |
| DrawBoard STATE/CATALOG | `DrawBoardProtocol.state/catalog` | cell·item < 100, 중복 cell 금지, catalog start+n ≤ total, 아이템 바이트 ≤ 3072. `DrawBoardClient.item()` 의 NBT 읽기는 try/catch → EMPTY. 통과 |
| TeleportPick | `TeleportPickProtocol.frame` | entries ≤ 300, token≠0, 이름 길이 자름. 통과 |
| LootBox PLAY/ADMIN | `LootBoxProtocol.play/response` | 결과 1~10개, 아이템 ≤ 32KB, speed 50~200, 등급 0~3. `LootBoxScreen` 의 `OPEN[clamp]`·`GRADE_KEY[clamp]`. 통과 |
| Megaphone | `MegaphoneProtocol.frame` | 글 ≤ 200, 아이템 ≤ 16KB, 표시 시간 2~30초(0 나누기 없음). 통과 |
| ChunkClaim | `ChunkClaimProtocol.decode` | 반지름 ≤ 2, 칸 수 = (2r+1)², 사람 ≤ 48, 상태값 범위. 통과 |
| HouseBoard | `HouseBoardProtocol.decode` | 점수 4개 고정, 판 ≤ 32, facing 0~3, 좌표 finite. 통과 |
| Portrait(상대) | `PortraitProtocol.decode` + `OtherPortraits.meta/chunk` | UUID 정규식, sha 정규식, 전체 크기 ≤ 8MB, 조각 번호·길이 일치 확인 후만 쓰기. 통과 |
| PetCompanion / BookMark / TrainingDummy / ArtifactFx / Stats v3 / MapPing / ManaHeat | 각 protocol | 상한·범위 검사 후 IAE, 수신기 catch. MapPing 은 문자열 파싱이지만 `UUID.fromString`·`parseDouble` 예외가 전부 IAE 계열이라 한 catch 로 막힘. 통과 |

## 확인한 "멈춤·누수" 후보 (문제 없음)

- 타로 68MB 그림 세트: `TarotScreen` 전용 `HudTextureCache(client, true)` (비동기 디코드, 프레임당 업로드 1장) 를 쓰고 `removed()` 에서 `close()` → GPU 에서 내림. 뽑기판·확성기·내 땅 칸도 같은 패턴(닫힐 때 close, 리소스 세대 바뀌면 다시 만듦).
- 아공간 배낭 `bagImages/bagText` 분리: `UiResources.reset()` 에서 같이 닫음. 누수 없음.
- 동행 펫: 모델 캐시 24개 LRU, 캐시 교체 폭주 방지(1초 안 쓰인 것은 밀어내지 않음), `dispose` 에서 텍스처 삭제·NativeImage 닫기. 월드 그리기는 미리 만든 행렬·배열 재사용 (프레임마다 할당 없음). 펫 수는 패킷 256 한도 + 64블록 거리.
- 허수아비 피해 숫자: 글자판 1장을 한 번만 굽고, 허수아비 32개·숫자 24개 상한, 사라진 것은 렌더마다 정리.
- `DefaultSpellMigration.applyRenames` / `known-hashes` 해시: 시작 때 한 번, 틱·프레임 경로 없음.
- `RemoteStatsClient` 내 정보 첨부(attach): 단축키 창이 열려 있으면 새 창을 열지 않고 붙임 — 요청 → OPEN → attach 가 한 바퀴로 끝나 반복 요청 없음. REFRESH 는 5초마다 1회.
- `ArtifactFxClient` 불덩이: 5틱 뒤 반드시 제거, 대상이 사라져도 제거. `flags/trails/bubbles` 는 서버 목록·시간으로 정리.

## 빌드 없이 확인 못 한 것

- Gradle 이 없어 구문 검사(`javasyntax.sh`)만 했다. 고친 두 파일은 통과. 타입 검사(예: `PlayerTurnPortraitLayout.Bounds` 접근 범위 — 같은 패키지라 문제 없을 것)는 호스팅 빌드에서 확인 필요.
- `DrawContext.enableScissor` 가 0.85 배율 행렬 안에서(ItemTooltipRenderer 등급 연출) 올바른 화면 좌표로 잘리는지는 실제 화면으로 확인 필요 (1.21.x 는 행렬을 반영하므로 괜찮을 것으로 봄).
- `QuestWorld.vertex` 가 POSITION_TEX_COLOR 레이어에 `setOverlay/setLight/setNormal` 을 부르는 것: 1.21 BufferBuilder 는 형식에 없는 요소를 조용히 건너뛰므로 안전하다고 판단했지만 실행으로 확인하지 못함.
