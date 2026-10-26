# 파티 창 UI + 파티 초대 알림 (Claude, 2026-10-16)

브랜치: `claude/party-ui-20261016`
기준: `codex/operating-alchemy-carrier-20261016` @ `9cefa25` (GPT 운영 통합본, 학교·야생 배포와 같은 소스)
상태: **빌드·실게임 안 함.** 클라우드에서 Gradle·Maven 접속이 막혀 있다. 확인한 것은 아래 「확인한 것」 참고.

오너 요청: "명령어로 되어 있는 파티 시스템을 레퍼런스 이미지(CHACADEMI_PARTY_UI_PNG.zip)처럼 UI로. 차카데미 폰트 사용. 우하단 알림은 기존 알림 기능을 조금 고쳐서."

## 0. 먼저 볼 것 (배포)

1. **같이 바꿔야 하는 것 2개.** 프로토콜이 새로 생겨서 한쪽만 바꾸면 창이 열리지 않는다(오류는 없음).
   - Velocity: `chacademi-party-velocity` → 버전 `0.2.1-discovery.1-partyhud.1-effects.1-partyui.1`
   - 클라이언트 모드: `magic-codex-fabric` (+ `magic-codex-protocol` 새 파일). `mod_version`은 GPT 버전 체계와 충돌하지 않게 건드리지 않았으니 배포 때 올려 줄 것.
   - 학교·야생 서버 플러그인(MagicCodexBridge 등)은 **바뀌지 않았다.**
2. **새 채널 `chacademi:party_ui`** (클라이언트 ↔ Velocity). 게임 서버로는 넘기지 않는다(양방향 모두 `handled`).
   - Velocity는 자기가 등록한 채널을 접속 시 클라이언트에 알린다(`ClientPlaySessionHandler.activated`). 그래서 클라이언트의 `ClientPlayNetworking.canSend`가 참이 된다. 창에서 "파티 서버에 연결되어 있지 않아요"가 뜨면 이 부분을 볼 것.
3. **새 리소스 2장** `assets/magiccodex/textures/gui/party_ui/panels.png`(1.2MB), `components.png`(0.9MB). 오너가 준 아틀라스 원본 그대로(무손실 재압축만). 영역 좌표는 `PartyUiAtlas.java`에 납품 json 값 그대로 적었다.
4. **명령어는 그대로 남아 있다.** `/파티`(인수 없음)만 창 열기로 바뀌었고, 사용법은 `/파티 도움말`로 옮겼다. 하위 명령(생성·초대·수락·거절·탈퇴·위임·강퇴·해산·목록·채팅)은 전과 같다.

## 1. 동작

### 파티 창 (`/파티`)
- 레퍼런스 왼쪽 패널과 같은 배치: 제목 「파티」, 「함께하는 동료 N명」, 「+ 초대」, 파티원 줄(얼굴·닉네임·파티장 왕관·온라인/오프라인·버튼), 「파티 나가기」.
- 버튼은 보는 사람에 따라 다르다.
  - 파티장이 보는 다른 파티원: 정보 / 위임 / 강퇴
  - 그 밖(내 줄, 파티원이 보는 창): 정보만
  - 「+ 초대」는 파티장, 또는 파티가 없는 사람에게만 보인다.
- 위임·강퇴·나가기는 확인 창을 한 번 거친다(레퍼런스에는 없음. 실수 방지용, 알림 패널 그림 재사용).
- 정보: 나 → 내정보 창, 다른 사람 → 기존 `/스텟창 <UUID>`(친구창과 같은 방식). 오프라인이면 안내 문구만.
- 5명 이상이면 휠·드래그로 스크롤(오른쪽에 스크롤바).
- **파티가 없을 때:** 「아직 파티가 없어요」. 받은 초대가 있으면 같은 줄 모양으로 보여 주고 수락/거절 버튼을 단다. 초대가 없으면 「동료를 초대하면 파티가 만들어져요」.
- **파티 없이 초대하면 파티가 자동으로 만들어진다**(내가 파티장). 창에서 보낸 초대만 해당하고, `/파티 초대` 명령은 전처럼 파티가 있어야 한다.

### 초대 패널 (「+ 초대」)
- 레퍼런스 오른쪽 패널. 파티 창 오른쪽으로 0.22초 동안 밀려 나온다(창 전체가 가운데로 다시 맞춰짐).
- 목록 = **학교·야생(신뢰 서버)에 접속 중이고 파티가 없는 다른 플레이어**, 한글 닉네임 순, 최대 256명. 창이 열려 있는 동안 2초마다 새로 받는다(서버 쪽은 사람당 0.9초에 한 번까지만 응답).
- 닉네임 검색은 받은 목록 안에서 클라이언트가 거른다(대소문자 무시, 포함 검색).
- 이미 초대한 사람은 「초대함」으로 흐리게 표시되고 누를 수 없다.

### 파티 초대 알림 (레퍼런스 우하단)
- 기존 **친구 신청 알림 카드(`FriendRequestToast`)를 고쳐서** 파티 초대도 띄운다. 친구 신청 카드 모양은 그대로다.
- 모양: 알림 패널 PNG, 얼굴+금테, 별 + 「파티 초대」, 「OO님이 파티에 초대했어요.」, 수락(파란 버튼) / 거절(회색), 오른쪽 위 X.
- 조작은 친구 카드와 같다: Alt(HUD 커서)를 누른 채 클릭. 커서가 없을 때는 「Alt 누른 채 클릭」 안내가 보인다.
- 20초 뒤 사라진다(거절하지 않음). X도 닫기만 한다. 초대는 2분 동안 유효하고 `/파티` 창에서 처리할 수 있다.
- 파티 초대 카드가 친구 카드보다 먼저 나온다(한 번에 하나).
- 창에서 처리했거나 만료된 초대의 카드는 상태를 받는 즉시 닫힌다.
- 초대받은 사람에게 채팅 한 줄도 같이 간다: 「OO 님이 파티에 초대했어요. 알림이나 /파티 창에서 수락할 수 있어요. (2분)」. 참가·탈퇴·위임·강퇴·해산 채팅 알림은 전과 같다.

### 글꼴 (차카데미 폰트, `CodexTypography`)
- 제목(「파티」, 「파티 초대」): `section` = 마루부리 Bold
- 「함께하는 동료 N명」: `heading` = 마루부리 SemiBold
- 닉네임: `hud_bold` = Pretendard Bold, 나머지: `label` = Pretendard Medium

### 얼굴
- 같은 서버에 있으면 탭 목록 스킨.
- 다른 서버(학교↔야생)·오프라인이면 Velocity가 알려 준 스킨 해시로 `textures.minecraft.net`에서 받아 그린다(서명 없는 표시 전용 프로필, `RemoteStatsClient`와 같은 `getSkinTexturesSupplier`).
- 해시가 없거나 받는 중이면 UUID 기본 스킨. 오프라인 파티원은 어둡게.
- 오프라인 파티원 얼굴은 프록시가 재시작 전까지 기억한 스킨을 쓴다(재시작 후에는 기본 스킨).

## 2. 프로토콜 `chacademi:party_ui`

`PartyUiProtocol`(클라) ↔ `PartyUi`(Velocity). 양쪽 `PartyUiTest`의 같은 GOLDEN 문자열로 고정.

```
클라 → 프록시: u8 1 · u8 action [· UUID]
  1 상태 요청  2 초대 후보 요청  3 초대(대상)  4 수락(초대한 사람)  5 거절(초대한 사람)
  6 나가기  7 위임(대상)  8 강퇴(대상)
프록시 → 클라: u8 1 · u8 kind
  1 STATE      u8 flags(1=창 열기) · u8 정원 · u8 n · n×Person(1 온라인, 2 파티장) · u8 m · m×Person (나에게 온 초대)
  2 CANDIDATES u16 n · n×Person(1 이미 초대함)
  3 INVITE     Person (알림 카드)
  4 RESULT     u8 ok · UTF 문구
Person = UUID · UTF 닉네임 · i8 기숙사 · UTF 스킨 해시("" 가능) · u8 slim · u8 flags
```

- STATE는 **바뀌었을 때만** 보낸다(기존 위젯 0.25초 주기 안에서 비교, 하트비트 없음). 서버 이동 1초 뒤 다시 보낸다(위젯과 같음).
- 창 동작(초대·수락·…)은 기존 명령어와 **같은 함수(`act`)**를 쓴다. 검사(파티장만, 정원, 중복 초대, 2분 만료 등)는 전부 `PartyBook` 그대로다. 명령어와 창 사이에 판정 차이는 없다(자동 생성 하나만 예외, 위 참고).
- 동작 뒤에는 STATE → (초대면 CANDIDATES) → RESULT 순서로 보내 창이 바로 맞춰진다.
- 클라이언트가 보낸 요청은 크기(2 또는 18바이트)·버전·동작·남는 바이트를 엄격히 검사하고, 이상하면 조용히 버린다. 권한 `chacademi.party`=false면 거절 문구.
- 동작은 기존 명령과 같은 0.15초 간격 제한을 공유한다.

## 3. 파일

| 위치 | 파일 | 내용 |
|---|---|---|
| Velocity | `PartyUi.java` (새) | 형식, 스킨 해시 추출(textures.minecraft.net만), 상태 변경 감지, 후보 요청 간격 |
| Velocity | `PartyPlugin.java` | 채널 등록, `uiRequest` 수신, `flushHud`에서 STATE 전송, `/파티`→창, 명령 처리를 `act()`로 분리(명령·창 공용), `person()`/`candidates()` |
| Velocity | `PartyUiTest.java` (새) | GOLDEN, 요청 검사, 스킨 해시, 상태 전송 조건 |
| Velocity | `build.gradle` | 버전 `-partyui.1` |
| 프로토콜 | `PartyUiProtocol.java` (새) | 클라이언트 쪽 형식 |
| 클라 | `PartyUiClient.java` (새) | 채널 등록(S2C·C2S 같은 ID), 상태·후보 보관, 창 열기, 얼굴 텍스처 |
| 클라 | `PartyScreen.java` (새) | 파티 창 + 초대 패널 + 확인 창 (`SocialScreen` 상속: 검색 입력·안내 문구·소리 공용) |
| 클라 | `PartyUiAtlas.java` (새) | 아틀라스 영역·보이는 테두리 여백, 9조각 그리기 |
| 클라 | `FriendRequestToast.java` | 파티 초대 카드 추가(`offerParty`, `syncParty`, `renderParty`, `partyHit`) |
| 클라 | `MagicCodexClient.java` | `PartyUiClient.initialize()` 한 줄 |
| 클라 | `PartyUiTest.java` (새) | GOLDEN 디코드, 요청 형식, 잘못된 패킷 |
| 리소스 | `textures/gui/party_ui/panels.png`, `components.png` (새) | 오너 납품 아틀라스 |

## 4. 확인한 것

- Velocity 모듈: 실제 Velocity API 대신 손으로 만든 최소 스텁 + gson 2.11.0 소스로 `javac` 통과(새 파일·바뀐 파일 전부). 실제 API의 `Player.getGameProfile().getProperties()`, `GameProfile.Property.getName/getValue`, 클라→프록시 `PluginMessageEvent(source=Player)`, 채널 광고는 Velocity 소스(`dev/3.0.0`)에서 확인.
- 바이트 형식: Velocity 인코더 출력 → 클라 디코더로 읽고 다시 쓰면 같은 바이트(STATE·CANDIDATES·RESULT). 요청 형식도 같음.
- 클라이언트(Fabric): 마인크래프트 jar가 없어 **컴파일 확인 못 함.** 문법 수준(파싱)만 확인. 쓴 API는 기존 코드에서 이미 쓰는 것만 골랐다(`getSkinTexturesSupplier`, `DefaultSkinHelper.getSkinTextures(UUID)`, `DrawContext.drawTexture(RenderLayer::getGuiTextured, …)` 12인자, `enableScissor`, `HudTextureCache.drawTexture`, `CodexTypography.draw`).
- 배치: 코드와 같은 좌표·9조각 계산으로 PIL 미리보기를 그려 레퍼런스와 비교(오너에게 전달).

## 5. 실게임에서 볼 것 (우선순위)

1. `/파티` → 창이 열리는지. 안 열리면: Velocity 로그, 클라 `canSend(chacademi:party_ui)`.
2. 파티 없이 「+ 초대」 → 후보 목록 → 초대 → 내 창이 파티(나 1명, 파티장)로 바뀌는지, 상대에게 우하단 카드 + 채팅 한 줄.
3. 카드: Alt 누른 채 수락/거절/X. 수락하면 양쪽 창·위젯이 바로 맞춰지는지.
4. 파티장 창에서 위임·강퇴(확인 창), 파티원 창에서는 정보만, 나가기.
5. 학교↔야생에 나뉜 파티원 얼굴(스킨 해시 경로), 오프라인 파티원(어둡게).
6. GUI 배율 2·3·4, 창 비율 16:9·4:3에서 잘림 없음. 초대 패널 열고 닫을 때 위치 이동.
7. 9명 이상 정원(`max-members`)이면 스크롤.

## 6. 한계·남은 일

- 레퍼런스 제목 옆 나침반 별 아이콘은 아틀라스에 없어서 기존 위젯의 별(`party/house_star.png`)을 썼다. 전용 아이콘이 오면 `PartyScreen.STAR`, `FriendRequestToast.STAR`만 바꾸면 된다.
- 「파티 나가기」 아이콘도 아틀라스에 없어 선으로 그렸다.
- 레퍼런스의 거절 버튼(회색)도 아틀라스에 없어 기존 캡슐 그리기로 비슷하게 그렸다.
- 파티 창을 여는 방법은 지금 `/파티`뿐이다. 상단 메뉴 버튼이나 단축키는 넣지 않았다(오너 결정 필요).
- 해산은 창에 없다(레퍼런스에 없음). `/파티 해산`은 그대로.
