# Claude 인계: 빗자루 비행 (2026-10-12)

브랜치 `claude/broom-flight-20261012` (기준 `codex/chat-discovery-magic-protection-20261011` @ `c373610`)

비행 마법(`flight`)을 **빗자루 비행**으로 바꾼다. 빗자루를 소환해 타고 나는 독자 기능이며 MCPets 와 무관하다(펫 동시 소환 가능).
움직임은 클라이언트 모드가 계산하고, 서버는 시작·종료와 가벼운 검사만 한다(서버 부담·끊김을 줄이려는 사용자 결정).

## 0. 먼저 읽을 것 (주의사항)

1. **원본 위치**: `chacademy-motion/` 은 사용자 PC `Desktop\차카데미model_claude\setting\chacademy-motion\` 의 사본이다(이 커밋 시점 동기화).
   모드·플러그인 jar 는 사용자 PC(Windows)에서 빌드한다: `chacademy-motion-0.3.0.jar`(클라이언트 모드), `chacademy-motion-plugin-0.3.0.jar`(Paper).
   이 저장소의 settings.gradle 에는 넣지 않았다(독립 Gradle 프로젝트 2개, 모드는 Fabric Loom).
2. **magic-mythic-paper 변경은 빌드하지 않았다**. 리플렉션만 쓰므로 새 컴파일 의존은 없다. 호스팅에서 `magic-mythic-paper` 빌드·테스트 필요.
3. **MagicCodex(magic-codex-paper/fabric)는 손대지 않았다.** 아래 5장의 제안은 GPT 검토·승인 후에만 적용한다(사용자 규칙).
4. 게임 안 실행 테스트는 아직 없다. 조작감 수치(BroomPhysics 상수, brooms.yml)는 실기 후 조정 대상이다.
5. 배포 안 함. 서버에는 `ChacademyMotion` 플러그인(0.3.0)이, 클라이언트에는 모드 0.3.0 + playerAnimator 2.0.5 + Fabric API 가 있어야 빗자루 비행이 켜진다.
   어느 하나라도 없으면 비행 마법은 **예전처럼 10초 바닐라 비행**으로 동작한다(자동 대체).

## 1. 동작 요약

| 항목 | 내용 |
|---|---|
| 빗자루 있음 | 보유 = 권한 `chacademy.broom.own.<ID>` (0.3.0). 장착 빗자루(없으면 가진 것 중 최고)로 시간 제한 없이 비행. 장비창 연동은 `CLAUDE_BROOM_EQUIP_UI_20261012.md` |
| 빗자루 없음 | 연습용 빗자루(`broom_1`)로 마법의 ticks(200 = 10초) 동안 |
| 다시 비행 마법 | 내림 |
| 조작 | W 가속 / S 약한 브레이크 / 마우스 방향(빗자루가 늦게 따라옴) / A·D 드리프트 / A·A·D·D 배럴롤 / 스페이스·Shift 오르내리기 / 달리기 키 부스트 / 땅에서 Shift 내리기 / 공중 Shift 두 번 뛰어내리기 |
| 낙마 | 균형 게이지 0 (빠를 때 급회전·급제동, 충돌, 땅에 처박기, 피격, 악천후, 고공 바람). 숙련도 낮을수록 잘 떨어짐. 떨어진 뒤 약 2초 안에 스페이스로 다시 잡기 |
| 숙련도 | 누적 비행 시간 (기본 3시간 = 100%), `broom-players.yml` |
| 빗자루 10종 | 외형이 화려할수록 속도·회전이 좋고 안정성은 낮게. 수치는 `brooms.yml` |
| 위치 이동 마법 | 빗자루 중에는 `chaselfvelocity`(상승기류 등)가 나가지 않음. Bridge 유틸(바람 걸음)은 5장 |

## 2. 변경 범위

| 경로 | 내용 |
|---|---|
| `chacademy-motion/chacademy-motion-plugin/.../broom/*` | 새 패키지: BroomService(탑승 세션, 틱 검사, 이벤트), BroomCommand(`/빗자루`), BroomCodec(패킷), BroomStore, SpeedGuard, BroomMastery, BroomType, BroomStartEvent(취소 가능) |
| `chacademy-motion/chacademy-motion-plugin/.../ChacademyMotionPlugin.java` | 공개 메서드 `broomFlight(Player,int)`, `isRidingBroom(Player)`, `stopBroom(Player)` |
| `chacademy-motion/chacademy-motion-plugin/src/main/resources/brooms.yml`, `plugin.yml` | 빗자루 수치, 명령어·권한 (`chacademy.broom.use` 기본 true, `chacademy.broom.admin` op) |
| `chacademy-motion/chacademy-motion-mod/.../broom/*` | BroomPhysics(조작감), BroomFlight(내 비행), BroomRiders(주변 빗자루 표시 추정), BroomRenderer(bbmodel 직접 렌더), BroomPose(playerAnimator 레이어+기울기), BroomHud, BroomModels, model/BroomModel·BbJson(bbmodel 파서) |
| `chacademy-motion/chacademy-motion-mod/.../mixin/PlayerTravelMixin.java` | 빗자루 중 `Player.travel` 을 빗자루 물리로 교체 (내 캐릭터만) |
| `chacademy-motion/chacademy-motion-mod/src/main/resources/assets/chacademy_motion/brooms/*.bbmodel` | 빗자루 모델 10종 (사용자 제공) |
| `.../player_animations/broom_{ride,boost,wobble,fall}.json` | 탑승 모션 (ride/boost/wobble 반복) |
| `magic-mythic-paper/.../BroomLink.java` (새) | ChacademyMotion 리플렉션 연결 |
| `magic-mythic-paper/.../WorldMech.java` | `chaworld{type=flight}` → `broomOrFlight`: 빗자루 우선, 불가하면 예전 비행. 빗자루 탄 사람이 마법 금지 구역에 들어가면 내리게 함(5틱마다) |
| `magic-mythic-paper/.../SelfMech.java` | 빗자루 중 `chaselfvelocity` 거부 |
| `magic-mythic-paper` plugin.yml / SPELLS.md / 스킬 yml 주석 / tools/spells.py / 버전 `-broom.1` | 문서·softdepend |

## 3. 패킷 (모드 ↔ ChacademyMotion)

| 채널 | 방향 | 내용 |
|---|---|---|
| `chacademy:broom_ride` | 서버→본인 | String 빗자루, float×7 (maxSpeed, accel, turn, brake, stability, climb, mastery), int×3 (limitTicks, boostCooldown, rollCooldown) |
| `chacademy:broom_state` | 서버→본인·추적자 | UUID, String 빗자루(빈 문자열 = 내림), byte 이유(0 일반,1 낙마,2 시간끝,3/4 배럴롤 좌/우,5 시작) |
| `chacademy:broom_evt` | 모드→서버 | byte 종류(0 내리기,1 낙마,2 다시잡기,3 부스트,4/5 배럴롤,6 충돌), float 값 |

- 늦게 시야에 들어온 유저에게는 `PlayerTrackEntityEvent` 다음 틱에 상태를 다시 보낸다.
- 모드 설치 여부는 `getListeningPluginChannels()` 에 `chacademy:broom_ride` 가 있는지로 본다.
- 바이트 형식 단위 테스트: `chacademy-motion-plugin/src/test/.../broom/BroomCodecTest.java` (클라우드에서 7개 통과, PC 빌드는 한글 경로 문제로 `-x test`).

## 4. 서버 쪽 확인할 것

- 탑승 시 `setAllowFlight(true)`(모드가 abilities.flying 을 켜서 비행 금지 킥·낙하 피해 누적을 막음), 내릴 때 원래 값으로 복원. 다른 플러그인이 allowFlight 를 건드리면 충돌 가능.
- 탄 사람만 틱마다: `setFallDistance(0)`, 1초 이동 거리 상한(SpeedGuard, 초과 3회면 내리게 하고 경고 로그), 연습 빗자루 시간, 비행 1초당 숙련도 +1초. 탄 사람이 없으면 작업이 멈춘다.
- 낙하 피해는 탄 동안만 취소. 낙마·뛰어내리기 후에는 정상 낙하 피해.
- 배럴롤 시 0.5초 `setNoDamageTicks` (서버 쿨타임 검사 있음).

## 5. MagicCodex 제안 (GPT 검토 후 적용 — 이 브랜치에는 없음)

1. **위치 이동 Bridge 유틸 막기**: `SpellUtility` 의 `gale_dash`(바람 걸음) 등 시전자를 움직이는 유틸은 빗자루 중이면 거부하고 마나·쿨타임을 쓰지 않게.
   제안 위치: `ManaBridge.cast` 의 `ManaCasting.attempt` 안, `MagicProtectionLink.cast(p)` 다음에
   `if (BroomCheck.riding(p) && MOVES_CASTER.contains(spell.id())) { p.sendMessage("빗자루를 타는 중에는 쓸 수 없는 마법입니다."); return false; }`
   (`BroomCheck` 는 `MagicProtectionLink` 와 같은 방식으로 `ChacademyMotion` 의 `isRidingBroom(Player)` 를 리플렉션 호출, `MOVES_CASTER = {gale_dash, light_leap}` — 목록은 사용자 확인).
   지금도 효과 자체는 막혀 있다(모드가 빗자루 중 서버 속도 변경을 무시, magic-mythic 이 `chaselfvelocity` 거부). 다만 마나·쿨타임은 소모된다.
2. **비행 마법 설명 문구** (`magic-codex-fabric/.../default-spells/006_flight.yml`): "빗자루에 탑승하여 자유롭게 하늘을 비행합니다. 빗자루가 없다면 연습용 빗자루로 10초간 비행할 수 있습니다." 정도로.
3. **비행 마법 쿨타임**(현재 30초, 마나 80): 빗자루는 다시 시전하면 내리므로 그대로 두면 내린 뒤 30초 못 탄다. 땅에서 Shift 로 내리면 쿨타임과 무관. 값 변경은 사용자 결정.

## 6. 테스트 방법 (실기)

1. 서버 `plugins/` 에 `chacademy-motion-plugin-0.2.0.jar`(기존 0.1.0 교체), 클라이언트 `mods/` 에 `chacademy-motion-0.2.0.jar`.
2. `/빗자루 타기` → 바로 탑승(마법 없이). `/빗자루 선택 broom_7` → 다른 빗자루로 갈아탐.
3. 비행 마법: 빗자루 없음(`/빗자루 해제`) → 10초 연습 빗자루, 끝나면 느린 낙하.
4. 두 클라이언트로 서로의 빗자루·기울기·배럴롤·낙마 연출이 보이는지.
5. 모드 없는 클라이언트: 비행 마법이 예전 10초 비행인지.
6. 마법 금지 구역 진입 시 내려지는지, 물에 닿으면 내려지는지.

## 7. 남은 것

- 빗자루 아이템·획득 경로(ItemsAdder) — 나중 (사용자 결정).
- 부스트 마나 소모 — 마나 API 연결 필요(지금은 쿨타임만).
- 조작 수치 실기 튜닝.
