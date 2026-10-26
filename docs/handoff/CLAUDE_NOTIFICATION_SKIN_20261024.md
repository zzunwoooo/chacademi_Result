# Claude 2026-10-24 (not built): 알림 카드 외형 교체 — 승인 PNG 묶음(12_notifications_approved) 적용

Branch `claude/notification-skin-20261024`, based on `claude/galaxy-bag-20261024` @ f088c59 (오늘 최신 Claude 브랜치 위에 이 작업 커밋만 추가).
**클라이언트 모드만.** 서버·프로토콜·채널 변경 없음. 알림 대기열·클릭·소리·표시 시간·위치 기준점은 그대로, **그리는 방식만** 바뀐다.

미리보기(오프라인 렌더, 실제 게임 화면 아님 — 같은 좌표/크기 숫자로 파이썬에서 그림): `docs/handoff/img/CLAUDE_NOTIFICATION_SKIN_20261024.png`

## 0. 먼저 읽을 것 (주의사항)

1. **Gradle 빌드·실게임 확인 안 함.** 이 세션은 Maven/Fabric/Mojang 저장소에 접근할 수 없었다.
   - 바꾼 Java 파일은 javac 구문 검사 통과. MC 의존이 없는 배치 로직(`SharedNotifications.layout`, `NotificationSkin.icon`)은
     새 테스트 `NotificationSkinTest`와 같은 내용을 로컬에서 따로 실행해 통과했다. 호스팅에서 `NotificationSkinTest` 포함 전체 테스트를 돌려 줄 것.
   - 새로 쓴 MC/모드 API는 이미 이 저장소에서 쓰고 있는 것뿐이다: `HudTextureCache.drawTexture(...,tint)`, `drawTextureAdditive`,
     `CodexTypography.draw/width`, `DrawContext.drawTexture(RenderLayer::getGuiTextured, …)`, `drawItem`, `CodexTextLayout.wrap`.
2. **패널 전체 opacity를 낮추지 않는다.** `toast_background.png` 자체 알파(가운데 ≈232/255)를 그대로 쓰고, 페이드 인/아웃 알파만 곱한다.
   이전의 `ctx.fill(... 0x142737)` 대체 사각형·주황 경고 막대는 없앴다.
3. **9-slice.** 바탕은 원본 640x103 기준 각 변 32px 보호 → 이 PNG(1402x226)에서는 70px 모서리를 늘이지 않는다(`NotificationSkin.nine`).
   버튼 PNG(372/368x160)도 깎인 모서리 30px 보호. 조각마다 `HudTextureCache` 영역 텍스처가 하나씩 생긴다(바탕 9 + 버튼 9×2, 캐시됨).
4. **글자는 전부 런타임 렌더.** PNG에 문구 없음. 버튼 라벨(수락/거절)도 코드에서 그린다.
5. **새 주문 발견 알림(`DiscoveryClient`, 화면 상단 중앙)은 건드리지 않았다.** 하단 토스트로 옮기거나 중복 표시하지 않는다.

## 1. 바뀐 것

### 공용 알림 카드 `SharedNotifications` (오른쪽 아래 쌓이는 카드: system·warning·error·shop·quest 등)
- 바탕 `notification/toast_background.png` + 원형 받침 `icon_seat.png` + 승인 아이콘 + 오른쪽 위 닫기 `close.png`.
- **긴 제목/본문을 자르지 않는다.** 예전엔 한 줄 넘으면 글자를 잘라냈는데, 이제 `CodexTextLayout.wrap`으로 줄바꿈하고 카드 높이를 늘린다
  (최소 96 단위 = 제목 1줄 + 본문 1줄). 줄바꿈 결과는 알림별로 캐시(최대 24, 리소스 리로드/접속 종료 때 비움).
- 쌓기: 카드 높이가 제각각이라 위로 쌓다가 화면 위 여백(24)을 넘으면 그 카드부터는 그 프레임에 안 그린다(대기열·시간 처리는 그대로).
- 크기: 1080p에서 카드 가로 약 640px(`CARD_SCALE=640/560`, 패키지 기준 640x103). 오른쪽·아래 기준점(오른쪽 24·아래 230, 1080 기준 ×0.9)은 예전과 같다.
  HUD 꾸미기(`HudCustomization "notifications"`) 영역은 실제 그려지는 묶음 크기로 넘긴다.
- 클릭: 예전처럼 **카드 아무 데나 클릭 = 닫기**(닫기 X는 보이는 표시일 뿐, 동작 동일).
- quest 카드 제목을 "새로운 퀘스트"로 바꾸고 본문에 퀘스트 이름을 넣는 기존 규칙 유지. shop 영수증의 아이템 아이콘도 그대로(받침 위에 그림).

### 아이콘 연결 `NotificationSkin.icon(kind)` — icons/ 승인 10종만 사용
| kind | 아이콘 |
|---|---|
| warning, error | warning |
| friend, party | social |
| shop (아이템 아이콘이 없을 때) | mail |
| quest / system | quest / system |
| bag_unlock, collection, mail, match, quest, social, system, taming, warning, waypoint | 같은 이름 그대로 |
| 그 밖의 kind, null | system |

서버(`ChacaApi.notify` / journal kind 4)가 kind를 아이콘 이름(예: `mail`, `taming`)으로 보내면 그 아이콘이 나온다. 서버 쪽은 이번에 바꾸지 않았다.

### 친구 신청 카드 `FriendRequestToast` (오른쪽 위, 수락/거절)
- 같은 바탕 + 받침(social 아이콘) + 수락(금색)/거절(네이비) 버튼 PNG + 진행 막대 금색.
- **버튼 좌표·크기·클릭 판정 상수(`BUTTON_*`, `ACCEPT_X`, `DECLINE_X`, `W`, `H`, `X`, `Y`)는 그대로** → `FriendRequestQueue.hit` 테스트 영향 없음.
- 글자 시작을 받침 오른쪽(`TEXT_X=108`)으로 옮겼다. 이름이 길어 한 줄에 안 들어가면 이름/문구를 두 줄로 나눠 버튼과 겹치지 않게 한다.
- 문구("친구 신청", "님이 친구 신청을 보냈습니다", "+N건 대기", "Ctrl 클릭")는 그대로.

### 파티 초대 카드 (같은 클래스 `renderParty`)
- 파티 UI 아틀라스(`PartyUiAtlas.TOAST`, 별, 세로선) 대신 같은 알림 바탕·버튼·닫기 PNG로 통일. 초대한 사람 얼굴은 원형 받침 안에 유지.
- 버튼·닫기 판정 좌표(`P_*`)와 동작(X=닫기만, 초대는 /파티 창에 남음) 그대로. 긴 닉네임은 두 줄.
- 오너가 파티 카드만 예전 모양을 원하면 `renderParty`/`partyButton`을 이 커밋 이전으로 되돌리면 된다(다른 곳 영향 없음).

## 2. 파일
- 새 클래스: `magic-codex-fabric/.../client/NotificationSkin.java` (그리기 전용)
- 수정: `SharedNotifications.java`(render·배치), `FriendRequestToast.java`(친구/파티 카드 그리기)
- 새 테스트: `magic-codex-fabric/src/test/.../client/NotificationSkinTest.java`
- 새 리소스: `assets/magiccodex/textures/gui/notification/`
  - `toast_background.png`(1402x226), `icon_seat.png`(220), `close.png`(84), `button_accept.png`(372x160), `button_decline.png`(368x160) — 패키지 원본 그대로(알파 보존)
  - `icons/*.png` 10종 — 원본 1254px를 내용 기준으로 정사각 크롭 후 192px로 축소(용량·VRAM 절약, 화면에선 최대 ~60px)
- 기존 `textures/gui/notification_card.png`는 더 이상 참조하지 않지만 지우지 않았다(다른 브랜치 병합 충돌 방지). 정리할 때 삭제 가능.

## 3. 이번에 안 한 것 (오너 결정: 이번 범위는 "클라 외형만")
- **시스템 채팅 탭**(`system_chat_tab.png`, `expand_chevron.png`): 저장소에 시스템 채팅 탭 화면 자체가 없다. 새 기능이라 리소스만 받고 이번엔 넣지 않았다.
- **숫자 배지**(`count_badge.png`): 같은 종류 알림 개수를 세는 로직이 없다(지금은 같은 id면 덮어씀). 넣으려면 대기열 동작 변경이 필요해 보류.
- **알림 규칙 1·2**(명령 응답은 시스템 채팅으로, 하단 팝업은 중요 이벤트만): 어느 알림을 어디로 보낼지는 서버/호출부 동작 변경이라 이번 범위 밖.
  현재 호출부(`ManaClient`, `CastingClient`, `HousingClient`, `PartyUiClient`, `PreviewClient`, `JournalClient` kind 4 등)는 그대로다.
- 위 3개 리소스는 `notification-png-complete-20261024.zip`(오너 PC)에 있다.

## 4. 확인 순서 (호스팅)
1. `Build-Server.ps1 -Verify` → `NotificationSkinTest`, `NotificationQueueTest`, `NotificationPresentationTest`, `FriendRequestQueue` 관련 테스트 통과 확인.
2. 게임에서 `/테스트 화면 알림 정보|경고|실패|보상`(`PreviewClient`) 각각 띄워 바탕 알파·아이콘·닫기 위치 확인.
3. 긴 문구 알림(서버 notify로 200자 이상) → 줄바꿈되고 카드가 길어지며 위 카드와 겹치지 않는지.
4. 친구 신청 / 파티 초대 → Ctrl 커서로 수락·거절·닫기 클릭이 예전처럼 동작하는지, 아주 긴 닉네임에서 버튼과 안 겹치는지.
5. 새 주문 발견 알림이 여전히 화면 상단 중앙에 한 번만 뜨는지.
