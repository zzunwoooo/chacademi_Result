# Claude 인계: 어깨 시점 · 마법 에임 커서 · 롤식 조준 시전 (2026-10-13)

브랜치 `claude/aim-camera-20261013` (기준 `codex/chat-discovery-magic-protection-20261011` @ `59f10f8`)

사용자 요청 세 가지를 MagicCodex 클라이언트 모드(`magic-codex-fabric`)에 넣었다.

1. **어깨 시점 (Shoulder Surfing Reloaded 대체)**: F5 3인칭(뒤)만 약간 뒤·옆에서 보는 시점. 1인칭과 정면 3인칭은 바닐라 그대로.
2. **마법 모드 에임 커서**: Z(마법 모드) ON + 3인칭이면 켜는 순간의 시선을 고정한다. 마우스는 화면 위의 별도 커서를 움직인다. **Alt를 누르고 있는 동안**은 바닐라처럼 화면이 돌고, 떼면 커서가 화면 중앙으로 돌아온다. 기존 Alt 마우스 포인터 화면은 이 모드에서 열지 않는다(에임 커서로 상단 메뉴를 클릭할 수 있다).
3. **롤식 조준 시전(일반 시전)**: 마법 키를 누르면 바로 쏘지 않고 미리보기를 보여 준다. **좌클릭은 발동, 우클릭이나 같은 키는 취소**다.
   - 범위형(메테오 등): 커서 위치의 지형에 맞춰 범위 원을 표시하고, 발밑에 최대 사거리 원을 표시한다.
   - 대상형(화염채찍 등): 사거리 안의 몹 발밑에 링을 그리고, 커서를 올린 몹을 강조한다.
   - 투사체: 진행 방향 레인을 표시한다.
   - 화염벽: 벽 위치를 표시한다.
   - 브레스: 부채꼴을 표시한다.
   - 자기 대상·유틸 마법: 지금처럼 즉시 시전한다.

**서버·프로토콜 변경 없음.** 발동 순간 클라이언트가 고른 지점 쪽으로 시선 패킷(`PlayerMoveC2SPacket.LookAndOnGround`)을 한 번 보내고, 이어서 기존 시전 요청을 보낸다. 그래서 Bridge `SpellRuntime.prepare`의 눈 방향 레이, PortableVFX의 aim 레이, 미몹 메커닉의 시전자 방향이 모두 그 지점을 향한다. 사거리·시야·보호구역·마나 판정은 지금처럼 서버가 한다. 로컬 화면(고정된 시선)은 움직이지 않는다.

## 0. 먼저 읽을 것 (주의사항)

1. **빌드·실게임 테스트 안 함.** 사용자 PC Loom 캐시의 1.21.4 yarn MC jar와 Fabric API(intermediary→named 리맵)로 `magic-codex-fabric` main 전체를 `javac --release 21` 타입 검사했다. `CodexTextureFilterMixin`의 기존 access widener 오류 1건은 Loom이 처리하는 것이므로 제외했고, 나머지는 오류 0건이다. JUnit은 285개 중 277개가 통과했다. 실패 8개는 이 환경 때문이다(netty-codec jar 없음, 한글 파일명 로케일, 테스트 리소스 경로). 모두 이번 변경과 무관하다. 새 `AimTest`(10개)와 `CastingStateTest`는 통과했다. 호스팅 Gradle 빌드·테스트가 필요하다.
2. **Shoulder Surfing Reloaded를 런처/클라이언트 mods에서 빼야 한다.** 두 모드가 모두 `Camera`를 바꾸므로 같이 있으면 충돌한다. 저장소에는 SSR 관련 파일이 없다(런처 구성 쪽에 있다).
3. **MixinExtras `@WrapOperation`을 처음 사용한다.** Fabric Loader 0.16.14에 MixinExtras 0.4.1이 내장되어 있어 의존성 추가는 필요 없다(Loom 컴파일 클래스패스에도 들어간다). 대상은 javap로 확인했다:
   - `Camera.update(BlockView,Entity,ZZF)` 안의 `moveBy(FFF)V` ordinal 0(3인칭 분기, 두 번째 호출은 수면 분기)
   - `Mouse.updateMouse(D)` 안의 `ClientPlayerEntity.changeLookDirection(DD)V`
4. **chat-layout 모드 호환:** `MagicHudChatMixin`이 노리는 `PlayerHudClient.lambda$initialize$7`은 건드리지 않았다. `PlayerHudClient`에는 일반 메서드 `handleAlt`에 한 줄만 추가했고, 람다는 추가·수정하지 않았다.
5. `magic-mythic-paper/tools/spells.py`의 `main()`은 이 브랜치 이전부터 `rainbow_collar`(spell-bindings.yml에만 있음) 때문에 assert로 실패한다. 이번에는 이 문제를 고치지 않았다. 대신 `python3 tools/spells.py targeting`으로 조준 표만 따로 생성할 수 있게 했다(main 성공 시에도 함께 생성).
6. 조준 표(`spell-targeting.json`)는 **표시용**이다. 서버 `spell-runtime.yml`의 range를 운영 중에 바꾸면 클라이언트 표시와 어긋날 수 있다. 그런 경우 spells.py를 다시 돌려 모드를 갱신한다. 판정은 항상 서버 기준이다.
7. 빗자루를 타는 중에는 에임 커서를 끈다. 마우스로 빗자루를 조종해야 하기 때문이다. 이때 조준은 화면 중앙 기준이다(`BroomClientLink.riding()`).

## 1. 조작 요약

| 상황 | 동작 |
|---|---|
| F5 3인칭(뒤) | 어깨 시점. 바닐라 조준점은 캐릭터 시선이 실제로 닿는 곳에 투영해 그림(동적 조준점) |
| 1인칭 / 정면 3인칭 | 바닐라 그대로 |
| Z ON + 3인칭 | 시선 고정, 마우스 = `+` 에임 커서, 커서로 상단 메뉴·친구 신청 카드 클릭 |
| Z ON + 3인칭 + Alt 꾹 | 바닐라 시점 회전(pitch -30°~50°로 제한), 떼면 커서 중앙 복귀 |
| Z ON + 1인칭 | 시선 고정 없음. 조준 미리보기는 화면 중앙 기준 |
| 조준 마법 키 | 미리보기 시작(같은 키 다시 = 취소, 다른 마법 키 = 그 마법으로 전환) |
| 좌클릭 | 발동. 사거리 밖·시야 막힘·조건 불충족이면 사유를 표시하고 발동하지 않음 |
| 우클릭 | 취소 |
| Z OFF, 화면 열림, 사망 | 미리보기 자동 취소 |

명령(클라이언트, `config/magiccodex/aim.properties`에 저장):
- `/시점 어깨|기본|좌우|거리 n|옆 n|높이 n` (기본: 거리 3.4, 옆 0.85, 높이 0.2, 오른쪽 어깨)
- `/조준 커서 켜기|끄기 · 스마트 켜기|끄기 · 감도 n`
  - 스마트 시전 = 키를 누르는 즉시 커서 위치로 발동. 기본은 꺼짐.

## 2. 변경 범위

| 경로 | 내용 |
|---|---|
| `mixin/ShoulderCameraMixin.java` (새) | 3인칭 뒤로 4블록 이동을 어깨 위치로 교체(바닐라와 같은 8모서리 충돌 클립) |
| `mixin/AimMouseMixin.java` (새) | 커서 모드면 시선 회전 대신 커서 이동, Alt 중 pitch 제한 |
| `mixin/CodexMouseMixin.java` | `AimClient.mouse` 먼저 호출(좌클릭 발동/우클릭 취소/커서로 HUD 클릭), 처리한 버튼의 release도 바닐라에서 숨김 |
| `client/ShoulderCamera.java` (새) | 위치 계산·클립, `/시점` |
| `client/AimCursor.java` (새) | 커서 상태, Alt 자유 시점, 진입 시 중앙 정렬 |
| `client/AimView.java` (새) | 이번 프레임의 view×projection 저장, 월드↔화면 변환 |
| `client/AimTargeting.java` (새) | 미리보기 계산(커서 레이→블록/몹, 지형 바닥, 사거리 클램프, 시야), 발동(시선 패킷 + `CastingClient.fire`) |
| `client/AimRenderer.java` (새) | 지형 맞춤 바닥 표시(블록 칸마다 윗면 높이에 붙임, 높이 차 0.6↑는 계단처럼 꺾음) |
| `client/AimHud.java` (새) | `+` 커서, 힌트 바(기존 마법 아이콘 축소 + 마우스 아이콘 + "발동/취소", 폰트 `magiccodex:label`), 어깨 시점 조준점 |
| `client/AimClient.java` (새) | 이벤트 등록(AFTER_ENTITIES, HUD 레이어 CROSSHAIR 다음), `/조준` |
| `client/AimSettings.java`, `AimMath.java`, `SpellTargets.java`, `HudPointer.java` (새) | 설정(값 범위 보정), 순수 수학, 조준 표 로더, HUD 포인터(Alt 화면 마우스 또는 에임 커서) |
| `client/CastingState.java` | `input(..., Predicate<Spell> aim, ...)` 오버로드, `fire(id,...)` 추가, `Kind.AIM`. 기존 동작·기존 오버로드 결과는 같음 |
| `client/CastingClient.java` | 시전 후처리를 `report()`로 분리, `fire(Spell)` 추가, Z OFF 시 미리보기 취소 |
| `client/PlayerHudClient.java` | `handleAlt`: 커서 모드면 Alt 화면을 열지 않음(한 줄) |
| `client/TopMenuClient.java`, `FriendRequestToast.java` | 마우스 좌표·커서 모드 판정을 `HudPointer`로 |
| `client/MagicCodexClient.java` | `AimClient.initialize()` |
| `resources/magiccodex.mixins.json` | 믹스인 2개 추가 |
| `resources/assets/magiccodex/spell-targeting.json` (새, 생성물) | 조준 마법 42종: area 12, line 14, unit 9, point 5, wall 1, cone 1 |
| `resources/assets/magiccodex/textures/aim/*` (새) | 사용자 제공 PNG에서 자른 `area`(범위 원), `target`(대상 링), `mouse_left/right`. `band`·`fill`은 생성. 사거리 원은 큰 반경에서 선이 두꺼워지지 않게 `band`로 그림(사용자 PNG의 눈금 모양 4개 포함). `.mcmeta`로 blur+clamp |
| `test/.../AimTest.java` (새) | 지연 시전·재검사, 조준 표, 투영/역투영, yaw/pitch, 사거리 클램프, 설정 보정 |
| `magic-mythic-paper/tools/spells.py` | `aim_shape()`/`write_targeting()` 추가, `targeting` 인수 |
| `gradle.properties` | `-aim.1` |

## 3. 서버 쪽 확인할 것 (코드 변경 없음, 실기 확인만)

- 시선 패킷 직후 시전 요청이 같은 메인 스레드 순서로 처리되는지. 패킷 순서가 보존되므로 `SpellRuntime.prepare`의 `p.getEyeLocation().getDirection()`이 새 방향이어야 한다.
- 범위형 지점은 서버 레이가 바닥에 닿도록 지면 0.05 아래를 향한다. 클라이언트 사거리 클램프는 `range - 0.35`(눈 기준 3D 거리)다.
- 레이 경로 중간에 몹이 있으면 서버 레이(raySize 0.55)가 그 몹을 맞힌다. 이는 지금 동작과 같다.
- 시전 후 서버가 아는 시선은 마지막 대상 쪽으로 남는다. 다른 플레이어에게는 캐릭터가 대상을 바라보는 것으로 보인다. Alt로 시점을 돌리면 다시 동기화된다.
- 안티치트 플러그인이 회전 패킷을 검사한다면 확인이 필요하다.

## 4. 알려진 한계 / 후속 후보

- 실기에서 맞출 값: 커서 감도, 어깨 거리·옆 오프셋, 힌트 바 위치(화면 높이 72%), 색.
- 원소별 색: 불·물·바람·빛·어둠은 레퍼런스 색이다. 대지(EARTH)는 레퍼런스에 없어서 0xD9A066으로 정했다.
- 대상 몹 외곽선(레퍼런스의 주황 테두리)은 넣지 않았다. 글로잉 후처리가 저사양 PC에서 무겁기 때문에 발밑 링으로 대신했다.
- 에임 커서 모드에서 파티 위젯 끌기·크기 조절은 Alt 화면 전용이라 할 수 없다(마법 모드를 끄고 Alt로 조절).
- 내 화면의 내 캐릭터 몸은 고정된 시선 방향을 유지한다. 대상 쪽으로 몸을 돌리는 연출은 모션 모드 쪽 후속 작업이다.
- 벽에 바짝 붙으면 카메라가 머리 쪽으로 당겨진다(바닐라와 같은 클립). SSR의 근접 투명화는 없다.

## 5. 검증 재현

사용자 PC `~/.gradle`(Loom 캐시)에서 읽기 전용으로 가져온 파일:
- `minecraft-merged-1.21.4-net.fabricmc.yarn...build.8-v2.jar`
- fabric-api 모듈 8개: tiny-remapper로 intermediary→named
- loader 0.16.14, sponge-mixin, mixinextras 0.4.1, lwjgl, joml, gson, guava, netty-buffer/common/transport, junit 5.11.4 등

`javac --release 21 -proc:none`로 main 전체를 타입 검사한 뒤 JUnit Platform을 실행했다. 산출 JAR은 만들지 않았다.
