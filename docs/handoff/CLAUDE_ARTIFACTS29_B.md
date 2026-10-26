# 보류 아티팩트 29종 — 그룹 B "생활·빗자루" (2026-10-26)

10종 효과를 만들고 `artifacts.yml` 에서 `enabled: false` 를 지웠다. (맨 위 "121개 구현" 숫자는 통합 때 고침)

## 아티팩트별 구현

| id | 어디서 | 방법 |
|---|---|---|
| discovery_journal 반응 일지 | 서버 | MagicDiscovery 가 **처음 발견**(award.created, 수동 조건 지급 포함)일 때만 `MagicCodexBridge.discoveryFirst(UUID,String)` 를 리플렉션으로 부름 → `ArtifactComfort.discovered` 가 경험치 **+50** (다시 습득·접속 때 맞추기는 안 옴). 근거: 레벨 10 전후에서 1~2레벨(레벨 10→11 = 27점, 11→12 = 29점), 여행자의 수첩(바이옴 하나 30점)보다 조금 큼. 마법 300종이라 전부 발견해도 15,000점. |
| thermos_lunchbox 보온 도시락 | 서버 | 먹기 시작한 뒤 첫 2틱 검사에서 Paper `setActiveItemRemainingTime(남은 시간 ÷ 1.2)` 한 번. 아이템 consumable 컴포넌트는 안 건드림. 음식(`Material.isEdible`)만, 물약·우유 제외. 먹기 완료는 서버 판정이라 클라 먹는 모션이 조금 일찍 끊김. |
| squeaky_shoes 삑삑이 신발 | 서버 | 바닐라 발소리는 클라가 직접 내므로 서버만으로는 못 지움 → **걸음 1.7블록마다 삑 소리 추가** (박쥐 울음 pitch 1.85/2.0 왼발·오른발 + 플루트 고음), world.playSound 라 주변도 들림. 웅크려도 삑(조금 작게, "몰래 다니기는 글렀다"). 땅 위·비행/수영/탑승 아닐 때만. |
| yum_bib 냠냠 턱받이 | 서버 | 같은 이유로 바닐라 먹는 소리에 **덧붙임**: 먹는 동안 4틱마다 판다 먹기(1.8)·여우 먹기(1.6) 번갈아, 다 먹으면 작은 트림(1.7) + "냠냠!" 말풍선(4초에 한 번). |
| folding_spyglass 접이식 망원경 | 클라(magic-codex-fabric) | `SpyglassZoom`: 서버가 보낸 장착 목록에 있고 사용 키(X)를 **누르고 있는 동안** 1인칭이면 확대. `SpyglassFovMixin`(AbstractClientPlayerEntity.getFovMultiplier RETURN → 0.1, 바닐라 망원경과 같음), `SpyglassMouseMixin`(Mouse.updateMouse 의 isUsingSpyglass → 감도 낮춤), HUD 레이어로 바닐라 망원경 테두리(spyglass_scope.png)+검은 칸, 망원경 소리. 두 믹스인 모두 `require = 0` (이름이 어긋나도 게임은 안 꺼짐). 새 모드는 망원경일 때 USE 패킷을 보내지 않고, 옛 모드가 보내면 서버(ArtifactActions)가 "최신 모드에서 …" 안내. |
| broom_cord 빗자루 끈 | 모드(chacademy-motion) | 새 이륙(다시 잡기 제외) 뒤 60틱 동안 가속 ×1.6, 최고 속도 ×1.10. |
| broom_cushion 빗자루 쿠션 | 플러그인(ChacademyMotion) | 공중에서 빗자루를 떠나면(낙마·내리기·시간 끝) 시각 기록 → 10초 안의 첫 낙하 피해 ×0.7. |
| pinwheel_charm 바람개비 장식 | 모드 | 균형 감소 중 **급선회(시선과 빗자루 각도 차)·시선 홱 돌리기·부스트 중 급선회** 몫만 ×0.6 (-40%). 충돌·피격·날씨·급제동은 그대로. |
| star_echo 별의 잔향 | 모드 (클라 계산만) | `StarEcho`: 나는 동안 2틱마다 내 위치를 기록(최대 80점 링 버퍼), 8초 동안 END_ROD 별가루를 틱당 3개씩 뿌림(빗자루 궤적 VFX 보다 오래 남는 잔향, **내 화면에만** 보임). 1초 이상 지난 내 궤적 2블록 안을 같은 방향(약 45도 안)으로 날면 가속 ×1.35·최고 속도 ×1.10 (8틱 유예, 시작 때 자수정 소리). |
| storm_wings 폭풍 날개 | 플러그인 + 서버 | 플러그인이 탈 때 ride 패킷의 maxSpeed 에 ×1.15 (속도 검사 상한도 같이). 스킬 가속 +20 은 `ArtifactBroom` 이 1초마다 `isRidingBroom` 이면 1.6초 timed 버프. |

## 장착 → 빗자루 전달 구조
- magic-codex-paper `ArtifactBroom.second` (ArtifactEffects 1초 tick) 가 장착 비트(1 끈, 2 쿠션, 4 바람개비, 8 잔향, 16 폭풍)를 만들어
  바뀌었을 때(또는 30초마다 다시) `ChacademyMotionPlugin.setBroomPerks(Player,int)` 를 리플렉션으로 부름.
- 플러그인 `BroomService.begin` 이 새 채널 **`chacademy:broom_perk`** (int 4바이트)를 ride 바로 앞에 보냄. 타는 중에 바뀌면 바로 한 번 더 보냄.
  모드 `BroomFlight.onPerks` 가 기억하고 매 틱 `applyPerks` 로 `BroomPhysics.accelBonus / speedBonus / turnStress` 를 채움.
- 장착을 바꾸면 폭풍 날개 속도는 다음 탑승부터, 나머지(모드 쪽)는 타는 중에도 바로.
- 호환: 새 채널을 모르는 옛 모드에는 안 보냄(채널 등록 확인) → 폭풍 날개 속도·쿠션만 동작. 새 모드 + 옛 플러그인 = 패킷이 없어 효과 없음. 바이트 형식이 바뀐 기존 패킷 없음.

## 바꾼 파일
- magic-codex-paper: `ArtifactComfort.java`(새), `ArtifactBroom.java`(새), `ArtifactEffects.java`(필드·등록·tick/fastTick/quit 훅 몇 줄), `ArtifactActions.java`(folding_spyglass 옛 모드 안내 1줄), `MagicCodexBridge.java`(`discoveryFirst` 1줄), `artifacts.yml`(enabled 10줄 삭제)
- magic-discovery-paper: `MagicDiscovery.java` (`discoveryFirst` 리플렉션 호출 2곳 + 메서드)
- chacademy-motion 플러그인: `BroomCodec`(CHANNEL_PERK, PERK_*, encode/decodePerks), `BroomService`(perks·쿠션·속도 배율), `ChacademyMotionPlugin.setBroomPerks`, 테스트 `BroomCodecTest.perksAreFourBytesBigEndian`
- chacademy-motion 모드: `BroomPackets.PerkS2C`, `ChacademyMotionClient`(등록), `BroomFlight`(perks·끈·잔향·바람개비), `BroomPhysics`(보정 필드 3개), `StarEcho.java`(새)
- magic-codex-fabric: `SpyglassZoom.java`(새), `mixin/SpyglassFovMixin.java`·`mixin/SpyglassMouseMixin.java`(새), `magiccodex.mixins.json`(2개 추가), `ArtifactClient.use`(망원경은 USE 안 보냄 1줄), `MagicCodexClient`(초기화 1줄)

## 배포 시 주의
- **같이 배포**: MagicCodexBridge(paper) + MagicDiscovery + ChacademyMotion 플러그인 + chacademy-motion 모드 + MagicCodex 모드. 학교·야생 둘 다.
- 플러그인만 먼저 올려도 안전(옛 모드엔 새 채널 안 보냄). MagicDiscovery 만 옛 버전이면 반응 일지만 안 됨. ChacademyMotion 이 옛 버전이면 콘솔에 "setBroomPerks 없음" 경고 한 번, 빗자루 아티팩트(폭풍 날개 스킬 가속 제외)만 안 됨.
- Gradle 빌드를 못 해서 파싱 검사만 했다. 빌드 때 확인할 것: Yarn 이름 `getFovMultiplier(boolean,float)`(1.21.4+build.8 문서로 확인함), `Mouse.updateMouse`·`ClientPlayerEntity.isUsingSpyglass` 대상(require 0), `SoundEvents.AMETHYST_BLOCK_CHIME`(Mojang 이름, SoundEvent 타입 가정), `DrawContext.drawTexture` 12인자형(HousingScreen 과 같은 형태).

## 게임에서 확인
- 반응 일지: `/유저관리 <닉> 마법조건 조건지급 <수동마법>` 등으로 처음 발견 → 알림 "반응 일지 … 경험치 +50". 이미 발견한 것 다시 지급하면 안 나와야 함.
- 보온 도시락: 스테이크 먹기 32틱 → 약 27틱 (빠르게 끝나는지).
- 삑삑이 신발: 걷기·뛰기·웅크리기 때 삑삑, 다른 사람에게도 들리는지. 냠냠 턱받이: 먹는 동안 냠냠 소리, 다 먹으면 트림 + 말풍선.
- 망원경: 장착 → X 누르고 있기 = 확대 + 테두리, 떼면 원래대로. 3인칭에서는 확대 안 됨.
- 빗자루: 끈 장착 이륙 직후 3초 빠름 / 쿠션: 높은 곳에서 낙마 → 피해 30% 줄었는지 / 바람개비: 빠를 때 홱 돌려도 균형 게이지가 덜 깎이는지 / 잔향: 날면 별가루가 8초 남고 원을 그려 다시 지나가면 가속 소리+빨라짐 / 폭풍 날개: HUD 최고 속도 +15%, 비행 중 스킬 가속 +20 (스탯 창).

## 못 한 것 / 추정
- 삑삑이 신발·냠냠 턱받이는 바닐라 소리를 **대체하지 못하고 덧붙임** (서버만으로 불가, 클라 모드 소리 가로채기는 범위 밖). 완전 대체가 필요하면 MagicCodex 모드에서 주변 플레이어 발소리/먹는 소리를 가로채는 작업이 따로 필요.
- 별의 잔향 별가루는 내 화면에만 보임 (서버 부담 0 원칙). 다른 사람 화면에도 보이려면 broom_state 쪽 연동이 필요.
- 별의 잔향 "궤적이 더 오래 남음"은 빗자루 궤적 VFX 수명을 늘린 것이 아니라 별가루 잔향을 덧그린 것 (PortableVFX 인스턴스 수명은 vfx.json 에 고정).
- 수치(끈 ×1.6/3초, 잔향 ×1.35, 바람개비 -40%, 쿠션 10초 창)는 추정값 — 오너 체감 후 조정.
- 큐디치(규디치) 경기장에서도 빗자루 아티팩트가 적용된다 (다른 아티팩트와 같음). 막아야 하면 경기장 쪽에서 setBroomPerks(…, 0) 또는 ArtifactBroom 에서 월드 제외 필요.
