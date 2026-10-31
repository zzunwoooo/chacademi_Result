# 진행 중 의뢰 HUD (subquest-hud-20261031)

오너 요청 2026-10-31: 화면 퀘스트창(스토리 메인)처럼, 받은 의뢰(서브 퀘스트)도 같은 디자인으로 HUD 에 띄우기.

## 무엇이 바뀌나
- 의뢰 게시판에서 받은 진행 중 의뢰(오늘 것, 최대 4개, 메인 의뢰 먼저)가 스토리 퀘스트창 바로 아래에 같은 모양(금색 장식선·책·목표 n/m·체크)으로 쌓여 보인다.
- 던전 단계창이 떠 있으면 그 아래에 붙는다. 스토리 단계가 없으면 퀘스트창 자리에 뜬다.
- 첫 의뢰의 −/+ (채팅창 열고 클릭) = 의뢰 HUD 접기 (스토리창 접기와 따로, `config/chaca_story/subquest_hud.properties`).
- MagicCodex HUD 편집 id `quest_sub` 로 옮기기·크기 바꾸기 가능.
- 목표를 다 채우면 "목표 완료 · 의뢰 창에서 보상 받기". 제출 목표는 가방 안 개수를 바로 센다.

## 파일 (모두 `// [기능:subquest-hud-20261031 시작/끝]` 표시)
서버 MagicCodexBridge (`magic-codex-paper`)
- 새 파일 `QuestHudPush.java` — 1초마다 바뀐 사람에게만 채널 `magiccodex:quest_hud` 로 목록 전송 (채널을 등록한 클라에만).
- `QuestBridge.java` 표시된 4곳: 필드 `hudPush` + `hudEntries(UUID)`, 생성자 끝 `registerCommands();` 뒤 생성, `quit` 맨 앞 `forget`, `close()` 맨 앞 `close`.

클라 스토리 모드 (`chacademy-story/mod`)
- 새 파일 `SubquestHud.java` — 패킷(magiccodex:quest_hud) 등록·수신·그리기·접기.
- `QuestClient.java` 표시된 1곳: `HudRenderCallback.EVENT.register(QuestHud::render);` 바로 뒤 `SubquestHud.init();`

## 바이트 형식 (서버 QuestHudPush.encode ↔ 클라 SubquestHud.ListS2C.CODEC)
VarInt 형식(=1) · VarInt 의뢰 수 · [UTF 제목 · UTF 등급 · bool 메인 · bool 완료 · VarInt 목표 수 · [UTF 이름 · VarInt 현재 · VarInt 필요]]
(UTF = VarInt 길이 + UTF-8, bool = 1바이트)

## 반영 순서·주의 (규칙 세션)
- 배포된 MagicCodexBridge·스토리 모드 jar 는 소스와 다르다 → 표시된 부분만 배포 jar 에 주입. 클래스 통째 교체 금지.
- 서버 jar 는 다음 점검(재시작) 때 학교·야생1·야생2(의뢰가 도는 서버 전부). 클라 모드는 런처 배포.
- 순서 무관: 서버만 바뀌면 옛 클라는 채널을 등록하지 않아 아무것도 안 받음. 클라만 바뀌면 HUD 가 안 뜰 뿐.
- 빌드 확인: 스토리 모드 `gradle --offline build -x test` 성공. MagicCodexBridge 는 소스 상태 문제로 전체 빌드가 안 됨(InventorySaveBridge·TravelBridge·RewardDelivery 의 기존 오류 12개) — 새 코드 두 파일은 오류 없음.
- 게임 안 시험은 아직 안 함.
