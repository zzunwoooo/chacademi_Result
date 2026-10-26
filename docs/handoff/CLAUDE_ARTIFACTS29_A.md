# 아티팩트 29종 — 그룹 A "모션" (2026-10-26)

오너 결정 "꺼져 있던 아티팩트 29개 전부 만들자" 중 모션 9종. `artifacts.yml` 에서 9개의 `enabled: false` 줄을 지웠다 (맨 위 "121개 구현" 주석은 통합 때 고침).

## 아티팩트별 구현

| id | 동작 | 방법 |
|---|---|---|
| dancing_ribbon 춤추는 리본 | 맞을 때 10% 춤 `artifact_dance` (1.7초) | `EntityDamageEvent` MONITOR, 내부 쿨 3초 |
| tap_dance_shoes 탭댄스 구두 | 맞을 때 20% 1초 탭댄스 `artifact_tap_dance` + 그동안 받는 피해 -30% | MONITOR 에서 발동(쿨 1.5초, 탭 사운드, 알림), HIGH 에서 `setDamage ×0.7` (ArtifactEffects.hurt 와 곱연산) |
| waving_glove 인사 장갑 | 다른 플레이어를 바라보고 웅크리면 손 흔들기 `artifact_wave` | `PlayerToggleSneakEvent` 켜짐 → 시선 12° 안·16블록 안·보이는(canSee + 시야) 플레이어 있으면. 쿨 3초 |
| shy_mask 수줍은 가면 | NPC 에게 말 걸 때 고개 숙여 인사 `artifact_bow` | ChacaNPC·Bridge 고정 대화 모두 Citizens NPC 우클릭에서 시작(NpcManager → NpcSocialFacade.openStory). Bridge 는 대화창 "열림" 이벤트를 받지 않으므로 `PlayerInteractEntityEvent`(주 손, `hasMetadata("NPC")`, 취소 여부 무관) 로 판단. 쿨 8초. `/말` 명령으로 연 대화는 해당 없음 |
| laughing_mask 웃음 가면 | 채팅에 ㅋㅋ → 웃기 `artifact_laugh` | `AsyncChatEvent`(취소된 채팅 제외)에서 글자만 보고 → `runTask` 로 메인 스레드에서 장착·쿨(3초) 확인 후 재생 |
| sleepy_pillow 졸린 베개 | 2분 가만히 → 바닥에 앉아 꾸벅꾸벅 (반복) `artifact_doze` + "Zzz…" 말풍선 | 0.5초 타이머가 장착자만 위치(5cm)·시선(15°) 비교. 땅 위·탈것/활공/수영/비행 아님. 움직이면(PlayerMoveEvent, 위치만) / 맞으면 / 해제하면 끝 → 모드에 `stop` |
| turtle_shell_backpack 거북이 등껍질 배낭 | 웅크린 채 1.2초 가만히 → 몸 말고 숨기 (반복) `artifact_shell_hide` + 등에 거북 등딱지 | 등딱지 = `ItemDisplay` 하나 (TURTLE_HELMET, FIXED, 35° 기울여 등 위, 저장 안 함). 움직이거나 일어서면 끝(표시 제거 + `stop`) |
| stretch_headband 기지개 밴드 | 처음 접속할 때 기지개 `artifact_stretch` | 상태 로드(playerStateReady) 후 1.5초. 서버 이동 도착 구분: 장착 중 0.5초마다 runtime `r:stretch_online` 을 45초 뒤까지로 갱신 → 퇴장 때 공유 runtime 에 저장되어 다음 서버로 따라감. 도착 시 이 키가 살아 있으면 "서버 이동(또는 45초 안 재접속)" 이라 생략 |
| jack_in_the_box 깜짝 상자 | 쓰러지면 그 자리에 용수철 광대 인형 연출 | 새 PortableVFX 팩 `portable-vfx-runtime/assets/JackInTheBox` (`claude:jackinthebox/pop`, static 2.6초)를 서버에서 재생 (kyudichi Hooks 와 같은 리플렉션). 0.25초 달걀·슬라임 소리, 1.8초 펑 소리. 쿨 5초 |

모든 트리거는 `ArtifactMotions.java` 한 파일. ArtifactEffects 에는 필드·생성·close 세 줄만 추가.
모션 재생은 `ChacademyMotionPlugin.playMotion(player, name, true)` 리플렉션 (본인 + 주변). 연타 방지는 아티팩트별 `ready()` 쿨(공유 runtime).

## 반복 모션 (졸기·등껍질)
- 모션 JSON `isLoop: true`, `returnTick` 으로 모드가 계속 재생 → 서버는 시작 1번, 끝 1번만 보낸다 (주기 재생 없음).
- 끝내기: 예약 이름 **`stop`** 을 기존 `chacademy:anim` 채널로 보냄. 모드 `MotionPlayer.play` 가 `stop` 이면 6틱 페이드로 레이어를 비운다 (모드 변경). `stop` 은 `allowed-animations` 에 넣지 않는다(모드가 보낼 수 없음). 예전 모드는 "모션을 찾을 수 없음" 경고 로그만 남고 반복이 안 멈춤 → **모드 갱신 필수**.
- 반복 중에 시야에 새로 들어온 유저에게는 안 보임 (다시 보내지 않음, 서버 부담 우선).

## 바꾼 파일
- `magic-codex-paper/.../ArtifactMotions.java` (새), `ArtifactEffects.java` (3줄), `resources/artifacts.yml` (9줄 삭제)
- `chacademy-motion/chacademy-motion-mod/src/main/resources/assets/chacademy_motion/player_animations/artifact_{dance,wave,doze,bow,laugh,shell_hide,stretch,tap_dance}.json` (새, Emotecraft v3 = wand_cast 와 같은 형식, playerAnimator 2.0.5)
- `chacademy-motion/chacademy-motion-mod/.../anim/MotionPlayer.java` (`stop` 처리)
- `chacademy-motion/chacademy-motion-plugin/src/main/resources/config.yml` (allowed-animations 8개)
- `portable-vfx-runtime/assets/JackInTheBox/` (vfx.json + textures 7장 RGBA 약 1.2 MiB + NOTES.md, client_compat check OK)

## 배포 주의
- **세 가지를 같이**: MagicCodexBridge(플러그인), ChacademyMotion 모드(새 JSON·stop) + 플러그인, 클라 PortableVFX 효과 폴더에 `JackInTheBox` 팩(`config/portablevfx/effects/JackInTheBox/`). `deployment/external-vfx-assets.json` 목록은 고치지 않았다.
- 서버에 깔린 ChacademyMotion `config.yml` 은 덮어쓰이지 않으니 allowed-animations 8줄을 직접 넣고 `/서버관리 새로고침 모션`. (서버가 재생하는 playMotion 은 허용 목록을 보지 않지만 `/테스트 모션` 자동완성에 쓰임)
- 기존 테스트 `ArtifactCatalogTest` 는 "121개 / 29개 꺼짐" 을 검사하므로 통합 때 숫자·목록을 같이 고쳐야 한다 (그룹별 충돌 피하려고 건드리지 않음).

## 게임에서 확인
- `/테스트 모션 artifact_doze` 등으로 모션만 확인, `/테스트 모션 stop` 으로 멈춤 확인.
- 장착 후: 몹에게 맞기(리본·구두), 다른 사람 보고 Shift(장갑), NPC 우클릭(가면), 채팅 "ㅋㅋ"(웃음 가면), 2분 가만히(베개), Shift 누른 채 가만히(배낭), 재접속(밴드 — 45초 넘게 나갔다 들어와야 함, 학교↔야생 이동은 안 함), `/kill`(깜짝 상자).

## 못 한 것 / 추정
- 빌드·게임 실행 못 함 (파싱 검사만). 모션은 스킬 미리보기(3D 시트)로 자세만 확인.
- 등딱지 ItemDisplay 위치·기울기(`SHELL_UP 0.95`, `SHELL_BACK 0.28`, 35°, 크기 1.5)는 추정값 — 거북 등딱지 아이템은 평면 아이콘이라 등 위에 비스듬히 붙은 그림으로 보인다. 어색하면 ArtifactMotions 상단 상수만 조정.
- 졸기 모션은 바닥에 앉는 자세라 body y -0.62 (스킬 권장 범위 밖, 의도). 웅크린 상태의 바닐라 자세 위에 등껍질 모션(팔·머리·몸 높이만 키)이 얹히는 모습은 게임에서 확인 필요.
- 깜짝 상자 인형은 64px 칸 8x8 플립북 빌보드(카메라를 봄) — 위에서 내려다보면 기울어 보인다.
- 오너 PC 모션 폴더(`차카데미model_claude\모션`)·VFX 폴더 자동 저장은 PC 연결이 없어 건너뜀.
