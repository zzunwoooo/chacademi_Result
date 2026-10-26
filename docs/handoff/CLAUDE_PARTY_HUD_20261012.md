# Claude 인계: 파티 위젯 (체력·마나 HUD) — 2026-10-12

브랜치 `claude/party-hud-20261012`, 기준 `origin/codex/chat-discovery-magic-protection-20261011` @ `e11af86`.
**빌드·서버 설치 안 함.** Minecraft/Paper/Velocity 의존성이 필요한 코드는 컴파일하지 못했다 (아래 "검증" 참고). GPT 검토·호스팅 빌드 후 병합해 주세요.

## 0. 하는 일

- 실제 파티(Velocity `chacademi-party`)를 화면 위젯으로 보여 준다: 기숙사 아이콘, 한글 닉네임(MagicCodex 닉네임), 체력 막대, 마나 막대.
- 숫자는 평소에 안 보이고, Alt(HUD 커서)를 누른 채 막대에 마우스를 올리면 `체력 15.5 / 20`, `마나 82 / 100` 팝업.
- Alt 누른 채: 위젯을 끌어 이동, 오른쪽 아래 모서리를 끌어 크기 조절(0.5~2.5배), 우클릭으로 위치·크기 초기화. ChatPlus(chacademi-chat-layout) 창 조작과 같은 방식.
  저장: 클라이언트 `config/magiccodex/party-hud.json` (x, y = 화면 비율, scale, enabled).
- 명령: `/hud 파티` (도움말) · `/hud 파티 켜기` · `/hud 파티 끄기` · `/hud 파티 초기화`.
- 서버 간 파티 지원: 학교/야생에 나뉘어 있어도 Velocity가 합쳐서 보낸다. 오프라인 파티원은 흐리게 + "오프라인".

## 1. 데이터 흐름

```
Paper(MagicCodexBridge) --chacademi:party_vitals--> Velocity(chacademi-party) --chacademi:party_hud--> Fabric(MagicCodex UI)
  본인 체력·마나만 (0.2초 주기, 바뀔 때만 + 2초 heartbeat)   파티별로 묶어 0.25초마다 바뀐 것만 (3초 heartbeat)
```

- 닉네임·기숙사: 기존 `chacademi:party_identity` (PartyIdentityBridge → NetworkChat) 값을 그대로 씀. 새 서버 값 없음.
  오프라인이 되면 이번 Velocity 실행 중 마지막으로 본 닉네임을 쓰고, 그것도 없으면 "오프라인 파티원".
- vitals 신뢰 조건은 identity 와 동일: 신뢰 서버(`trusted-servers`)가, 그 플레이어의 **현재** 백엔드 연결로 보낸 것만. 클라이언트가 보낸 vitals/hud 는 전달·수신 안 함.
- 서버 이동: `ServerPostConnectEvent` 후 1초 뒤 해당 플레이어에게 처음부터 다시 보냄. `ProtocolState.PLAY` 가 아닐 때(구성 단계)는 보내지 않음.
- 파티 없는 사람: 빈 패킷 `01 00` 을 한 번만 (heartbeat 없음). 클라이언트는 12초 동안 아무것도 못 받으면 위젯을 숨김.

## 2. 변경 범위

| 파일 | 내용 |
| --- | --- |
| `magic-codex-protocol/.../PartyHudProtocol.java` (새) | `chacademi:party_hud` 형식 encode/decode, 검증 |
| `magic-codex-paper/.../PartyVitalsBridge.java` (새) | 본인 체력(`Attribute.MAX_HEALTH`)·마나(`ManaService.snapshot`) 17바이트 전송 |
| `magic-codex-paper/.../MagicCodexBridge.java` | `partyVitals` 생성/close 3줄 |
| `chacademi-party-velocity/.../PartyHud.java` (새) | vitals 수신·검증, 기억한 닉네임, 수신자별 중복 억제, 인코더 |
| `chacademi-party-velocity/.../PartyPlugin.java` | 채널 2개 등록, vitals/hud 리스너, 서버 이동 재전송, 0.25초 스케줄러 `flushHud` |
| `magic-codex-fabric/.../PartyHudClient.java` (새) | 수신, 그리기(9조각 막대·잔상·부드러운 변화), Alt 조작, 팝업, 저장, `/hud 파티` |
| `magic-codex-fabric/.../PartyHudLayout.java` (새) | 배치·충돌·크기 계산 (MC 없음) |
| `HudCursorScreen.java` | `mouseClicked` 에 `PartyHudClient.click`, `mouseReleased`, `removed()` 추가 |
| `MagicCodexClient.java`, `PlayerHudClient.java` | 초기화 1줄, `/hud` 에 `파티` 하위 명령 |
| `textures/gui/party/*.png` (새 7개) | 사용자 제공 이미지를 잘라 축소: 아이콘 128², 막대 높이 36 |
| 테스트 3개 (새) | Velocity `PartyHudTest`, Fabric `PartyHudTest`, Paper `PartyVitalsBridgeTest` |

## 3. 주의사항

1. 기숙사 → 아이콘 (사용자 확정, `PartyHudLayout.icon`): 0 아르케온=나침반 별, 1 루미나=초승달, 2 베스티아즈=나뭇가지·잎, 3 노크세르=태양. 기숙사 미정(-1)은 금색 별 도형.
   달 아이콘은 다른 아이콘보다 커 보여서 PNG 안에서 약 82% 크기로 줄였다 (그리는 칸 크기는 모두 같음).
   글씨체는 기존 HUD와 같은 `magiccodex:hud_bold`(Pretendard Bold, 이름·제목) / `magiccodex:label`(Pretendard Medium, 팝업·안내).
2. Velocity/Fabric 바이트 형식은 두 테스트의 `GOLDEN` 상수로 고정. 한쪽을 바꾸면 양쪽 다 바꿀 것.
3. `chacademi-chat-layout` 의 `MagicHudChatMixin` 은 `PlayerHudClient.lambda$initialize$7` 을 대상으로 한다. 이번 변경은 그 클래스에 **람다를 추가하지 않았다** (`.then(PartyHudClient.command())` 는 메서드 호출) — 람다 번호 변화 없음을 빌드 후 한 번 확인해 주세요 (`javap -p`).
4. 클릭 순서: chat-layout 이 먼저 자기 창을 처리하고, 아니면 `HudCursorScreen.mouseClicked` → TopMenu → 파티 위젯. 위젯과 채팅창이 겹치면 채팅창이 우선.
   드래그는 `mouseDragged` 대신 매 프레임 마우스 위치를 읽어 처리한다 (chat-layout 의 drag redirect 와 충돌 없음). Alt 를 놓으면 `removed()` 에서 저장.
5. Velocity `Player#getProtocolState()` 를 씀 (Velocity 3.3+ API; 현재 3.6.0-SNAPSHOT).
6. 백엔드 → Velocity 전송은 기존 identity 와 같은 방식 (Velocity ChannelRegistrar 에 등록된 채널을 백엔드에 알림). `chacademi:party_vitals` 를 registrar 에 추가했다.
7. 체력은 바닐라 체력/최대 체력 그대로 (흡수 하트 미포함). 마나를 모르는 서버(MagicCodex 없음)면 마나 막대는 빈 트랙.
8. 트래픽: 플레이어당 vitals 최대 5회/초(변화 있을 때만, 17바이트), 파티원당 hud 최대 4회/초(파티 8명 기준 ~1KB 이하).
9. 버전 번호(mod_version, Velocity plugin version)는 올리지 않았다. 배포 시 GPT 가 정해 주세요.

## 4. 검증

- 이 환경에서는 Gradle/Minecraft/Paper/Velocity 의존성을 내려받을 수 없어 **전체 빌드·실행 못 함**.
- MC 의존성이 없는 `PartyHudProtocol`, `PartyHud`, `PartyHudLayout` 과 새 테스트 2개(Velocity·Fabric PartyHudTest)는 javac 로 컴파일하고
  최소 JUnit 대체 실행기로 돌려 **10개 모두 통과** (Fabric 테스트는 실제 PNG 리소스 포함).
- `PartyHudClient`, `PartyPlugin` 변경, `PartyVitalsBridge`(+테스트)는 **컴파일 미확인**. API 이름은 같은 저장소의 기존 코드(ManaClient, FriendRequestToast, PartyIdentityBridge)와 맞췄다.

## 5. 확인할 것 (호스팅 / 실게임)

- [ ] `Build-Server.ps1 -Verify` + magic-codex-fabric `test`/`remapJar`
- [ ] 2계정 이상: `/파티 생성` → `/파티 초대` → `/파티 수락` 후 위젯이 0.5초 안에 나타나는지, 피격/회복/마나 사용이 바로 반영되는지
- [ ] 한 명이 학교→야생 이동: 1~2초 안에 값이 다시 차는지, 이동 중 클라이언트 오류 없는지
- [ ] 탈퇴·강퇴·해산 시 위젯이 사라지는지, 오프라인 표시
- [ ] Alt 이동/크기/우클릭 초기화, 재접속 후 위치 유지, chat-layout 창과 겹칠 때 동작
- [ ] GUI 배율 2/3/4, 창 크기 변경 시 위젯이 화면 안에 남는지
