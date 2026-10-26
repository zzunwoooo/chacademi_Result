# CLAUDE_FIX_CLIENT_20261021 — 클라이언트 묶음 수정 인계

- 브랜치: `claude/fix-client-20261021` (기준 `0cd1454`)
- 작성: Claude, 2026-10-21. 참고 설계: `claude-reply-to-gpt-20261021.md` 부록 2(A12·A14·A11·정정 b), 감사 01·04·05·09.
- **빌드 미검증: 문법 검사(`parsecheck.sh`)만 통과.** Gradle 빌드·테스트는 이 환경에서 돌리지 못했습니다. GPT 쪽에서 아래 "테스트" 항목을 꼭 돌려 주세요.

## 커밋

| 커밋 | 항목 |
|---|---|
| 2804e88 | 1. VFX readiness 철회 |
| c246b27 | 2. 블룸 0.40 + 슬라이더, 3. 어깨 옆 거리 1.0 (A12) |
| d09223f | 4. HudTextureCache 전체 계측 (A14) |
| 61de752 | 5. 이로치→변색종, 통발→아공간 수조 (A11) + 324 이전 |
| 47de688 | 6. 모션 모드 R키·디코더 |
| 871e28b | 7. HUD 크기 = 핫바만 |
| 1fc1b43 | 8. 마법 실패 안내 → 알림 카드 |
| 9317da6 | 9. 낚시 흔들림·지침 신호 |
| d36f2c3 | 10. 수조 크기 차이 |
| 76577e5 | 11. 판매소 잠금 저장 확인 |

---

## 1. VFX 준비 상태(readiness) 30초 철회

- **고친 것**: 클라는 30초 대체 재확인(`REANNOUNCE_TICKS`) 때 readiness를 무효화하지 않습니다. 서버는 새 세대의 첫 조각을 받아도 준비 상태를 빼지 않고, 이전에 검증된 목록을 유지합니다. 새 목록이 다 모이면 그때 교체합니다. 실패·시간 초과·크기 위반·속도 제한일 때만 철회합니다(기존 분기 그대로).
- **파일**: `portable-vfx-runtime/client/.../network/VfxNetworking.java`(`rehandshake(boolean)`), `portable-vfx-runtime/paper/.../internal/RelayEngine.java`(`catalogReadyV2`의 `started` 분기), 테스트 `ReadinessRelayTest`, `ReadinessUploadTest`
- **동작 변화**: 채널 재광고(플러그인 재활성화) 경로는 지금처럼 전체를 다시 보냅니다. 30초 경로는 hello만 다시 보냅니다. 서버 상태가 정말 사라졌다면 새 epoch가 오고, `ReadinessUpload.epoch()`가 알아서 무효화합니다. v1(분할 없는) 경로의 `lastReady` 초기화는 그대로 두었습니다. 프로토콜은 바뀌지 않았습니다. 서버 쪽 수정만으로 구버전 클라도 보호됩니다.
- **테스트**: `ReadinessRelayTest`의 첫 테스트 마지막 단언을 바꿨습니다(새 세대 첫 조각 뒤에도 `supportsCatalogCast`=true). 새로 `reannouncedGenerationKeepsPreviousReadinessUntilCompleteThenSwaps`(유지 → 완성 시 교체 → 모자란 목록이면 완성 순간 철회 → 시간 초과 철회)를 넣었습니다. `ReadinessUploadTest`에는 `sameEpochAndUnchangedInventoryNeedsNoNewGeneration…`을 넣었습니다.
- **실게임**: 접속 후 1분 이상 마법을 연속으로 시전합니다. "마법 이펙트가 아직 준비되지 않았어요"가 한 번도 뜨지 않아야 합니다. 클라 VFX 진단 문자열(`VfxNetworking.diagnostic()`)의 `sentV2`가 30초가 지나도 -1로 돌아가지 않아야 합니다(같은 세대 유지).

## 2. 블룸 기본 0.65 → 0.40 + 슬라이더

- **고친 것**: `ClaudeBloomConfig.DEFAULT_STRENGTH=0.40`. `bloom.properties`에 `version` 키가 생겼습니다. 버전 키가 없고 값이 **정확히 0.65**이면 1회 0.40으로 바꾸고 `version=2`를 기록합니다. 버전 키가 없는 다른 값은 그대로 두고 버전만 기록합니다. 설정 → 스킬 이펙트 페이지에 세 번째 슬라이더 **"이펙트 빛 번짐"**(0~100%)을 기존 슬라이더 위젯 그대로 추가했습니다. 새 그림은 없습니다. 저장하면 바로 적용됩니다(포스트 패스가 매 프레임 읽음).
- **파일**: `portable-vfx-runtime/client/.../claude/ClaudeBloomConfig.java`(`save`, 원자적 쓰기), `.../api/VfxPreferences.java`(`bloom()`, `saveBloom(float)`), `PortableVfxClient.java`(로그 문구), `magic-codex-fabric/.../SettingsScreen.java`, 테스트 `ClaudeBloomConfigTest`, 스모크 `tools/claude-smoke/ClaudeCoverageGlTest.java`(파일에 version 키 추가)
- **배포 주의**: 런처가 `config/portablevfx/bloom.properties`를 배포한다면 `version=2`, `strength=0.4`로 맞춰 주세요. 옛 PortableVFX(메서드 없음)와 함께 쓰면 슬라이더 행이 숨겨지고 나머지는 그대로 동작합니다(리플렉션).
- **테스트**: `ClaudeBloomConfigTest` 전체. 새 `unversionedOldDefaultMigratesOnceButChosenValuesStay`가 있습니다.
- **실게임**: 기존 PC에서 처음 켜면 블룸이 약해져야 합니다(0.65→0.40). 슬라이더를 0%와 100%로 바꿔 저장하면 즉시 차이가 나야 하고, 재시작 뒤에도 그 값이 유지되어야 합니다.

## 3. A12 어깨 옆 거리 기본 1.0

- **고친 것**: `AimSettings.DEFAULT_SIDE=1.0`(필드 기본값). 설정 화면 "초기화"도 이 값을 씁니다. `load`는 `aim-version`이 없고 `side-offset`이 정확히 1.5면 메모리에서 1.0으로 바꾸고 `migrated`를 켭니다. `get()`이 그 경우 1회 저장해서 `aim-version=2`를 기록합니다. `persist`는 항상 `aim-version=2`를 씁니다.
- **파일**: `magic-codex-fabric/.../AimSettings.java`, `SettingsScreen.java`, 테스트 `AimPreferencesTest`
- **알려진 단점(오너 승인됨)**: 이 업데이트 전에 일부러 정확히 1.5를 고른 사용자도 한 번 1.0으로 바뀝니다.
- **테스트**: `AimPreferencesTest`(기본값 1.0, 새 이전 테스트: 버전 없는 1.5→1.0, 1.2는 유지, `aim-version=2`인 1.5는 유지, 저장 후 다시 불러와도 같음)
- **실게임**: 옛 `aim.properties`(side-offset=1.5, 버전 없음)로 접속하면 옆 거리가 1.0이고, 파일에 `aim-version=2`가 생겨야 합니다.

## 4. A14 HudTextureCache 전체 계측

- **고친 것**: 새 `HudTextureStats`(프로세스 전체 합계: 살아 있는 인스턴스 수, 텍스처 수, 보유 바이트, 업로드 수, 동기 로드 횟수·시간). 업로드·정리·`close`에서 갱신합니다. 합계가 16MiB 단위의 새 최고치에 닿을 때마다 `magiccodex` 로거에 **debug**로 한 줄 남깁니다. `HudTextureCache.globalStats()`로도 읽을 수 있습니다. 동작 변화는 없습니다.
- **디버그 오버레이**: MagicCodex에는 기존 F3/디버그 오버레이 연결이 없어서 로그로만 남깁니다(새 UI는 만들지 않음).
- **파일**: `HudTextureCache.java`, `HudTextureStats.java`(신규), 테스트 `HudTextureStatsTest`(신규)
- **실측 방법**: 클라 log4j에서 `magiccodex` 로거를 DEBUG로 켜고 30분 플레이한 뒤 `HUD textures (all caches): …` 줄의 최대치를 봅니다. 수치가 크면 부록 2의 2단계(전투 중 처음 뜨는 HUD 비동기화)를 진행합니다.

## 5. A11 공식 명칭 (이로치 → 변색종, 통발 → 아공간 수조)

- **바꾼 곳**(표시 문구·관리 명령 literal·주석; `git grep 이로치\|통발`을 `.md`·`reference/`·`docs/` 밖에서 돌렸을 때 0건):
  - 변색종: `ShinyBridge.java`(`/월드관리 변색종 <지정|해제>`, `/서버관리 새로고침 변색종`, 메시지·설명·콘솔 로그), `CommandHub.java:61`(월드관리 설명), `PreviewSender.java:31`(`/테스트 몹 체력바 변색종`, 값 `shiny` 유지), `creature-spawns-paper/.../WildlifeCommands.java`(`/테스트 몹 작물몹 <작물> 변색종`, 메시지), `magic-mythic-paper/.../TelegraphCommand.java`(`봉인진변색종`, `빙핵레이저변색종`), 주석: `TargetHudClient.java`, `chacademia_crops.yml`, `chacademi_mob_skills.yml`
  - 아공간 수조: `mana-spells.yml:1576`(`name: 아공간 수조`), `default-spells/324_fish_trap.yml:2`, `FishingBridge.java`(가득 참 알림, 관리 명령 설명·오류·목록), `FishTankBridge.java`(알림 2건, 콘솔 로그), `FishingGear.java:268`(새로 꺼내는 물고기 lore), `plugin.yml:32`(권한 설명), 주석: `FishShopBridge`, `ManaBridge`, `FishTankClient`, `FishTankScreen`, `HudTextureCache`, `fish-shop.yml`, `fishing.yml`, `FishShopProtocol`, `FishTankProtocol`, `FishingProtocol`, `FishTankProtocolTest`
- **바꾸지 않은 저장 ID**: `shiny`(PDC 키, `shiny.yml`, 미리보기 값, `CA_Crop_*_shiny`, 태그·모델), `fish_trap`(마법 id, 권한 `magic.learned.fish_trap`, 명령 `마법 fish_trap`, 파일 이름, 아이콘), `rainbow_collar`, FishTank DB·프로토콜 이름
- **324 함정 해결**: `DefaultSpellMigration.managedHistoryName`가 `324_fish_trap.yml`을 관리 대상으로 봅니다(`RENAMED_FILES`). 그래서 옛 배포 해시(`known-hashes.tsv`의 `d7cb45…`)인 파일은 새 바이트로 바뀌고, 사용자가 고친 파일은 그대로 둡니다. 새 해시 `740d14…`도 기록에 추가했습니다(CRLF 유지). `VERSION`은 `spells-fish-trap-324-20261021`입니다.
- **배포 주의**:
  - 관리 명령 literal이 바뀌었습니다. 운영 매크로나 문서에서 `/월드관리 이로치`, `/서버관리 새로고침 이로치`를 쓰고 있다면 `변색종`으로 바꿔야 합니다.
  - 이미 꺼낸 물고기 아이템의 lore("통발 수조에…")는 아이템 NBT에 남아 있어서 바뀌지 않습니다. 새로 꺼내는 것부터 바뀝니다.
  - 운영 서버 plugins 폴더의 MythicMobs/MCPets yml은 레포에 없습니다. 서버에서 `grep -r 이로치`로 따로 확인해 주세요.
  - 배포 오버레이 `deployment/operating-config/MagicCodexBridge/mana-spells.yml`에는 `fish_trap`이 없습니다(이번에 건드리지 않음, 감사 05의 확인 필요 사항).
- **테스트**: `DefaultSpellMigrationTest.fishTrapRenameReachesShippedCopiesButNotEdits`(옛 배포본 → 새 이름, 사용자 수정본 유지, 기록에 옛 해시 존재). 실게임: 기존 PC의 마법 도감에서 324가 "아공간 수조"로 보여야 합니다. `/월드관리 변색종 지정` 자동완성이 되어야 합니다.

## 6. 모션 모드 (chacademy-motion-mod)

- **R키**: `ChacademyMotionClient`가 테스트 키(`key.chacademy_motion.cast`, 기본 R)를 `FabricLoader.isDevelopmentEnvironment()`일 때만 등록합니다. 배포본에는 키가 없어서 누구나 모션을 재생·전송할 수 없습니다. `cast(String)` API는 그대로입니다. 언어 파일 항목은 개발용으로 남겨 두었습니다.
- **디코더 보호**: 새 `net/SafeCodec.failSafe(name, codec, INVALID)`로 서버→클라 패킷 5종(`broom_ride`, `broom_state`, `broom_fx`, `broom_list`, `anim`)을 감쌌습니다. 디코드 예외(필드 부족·64개 초과·잘못된 문자열)가 나면 남은 바이트를 버리고 `INVALID`를 돌려줍니다. 핸들러는 `INVALID`를 무시합니다. 디코드 뒤 남는 바이트(더 새 서버의 추가 필드)도 버립니다. 경고는 종류마다 한 번만 남깁니다. 바이트 형식은 그대로라 플러그인과 호환됩니다. C2S 코덱은 그대로입니다.
- **퀘스트 패킷**: 지시서의 `QuestPackets`(감사 09가 본 `7e6ac03` story 브랜치)는 이 기준 커밋(`0cd1454`)에 **없습니다**. 그래서 이번에는 고치지 못했습니다. story 묶음을 합칠 때 같은 `failSafe` 방식(모드 쪽 `kr.chacademy.cutscene` 패키지에 같은 도우미)을 적용해야 합니다. 컷신 패킷(`CutscenePackets`)은 이미 버전 채널(`story_v2`)이 있어서 건드리지 않았습니다.
- **파일**: `ChacademyMotionClient.java`, `net/BroomPackets.java`, `net/MotionPackets.java`, `net/SafeCodec.java`(신규), 테스트 `net/SafeCodecTest.java`(신규)
- **테스트**: `SafeCodecTest`(잘린 ride → INVALID·예외 없음, 뒤에 붙은 바이트 무시, 65개 목록 → INVALID). `RegistryAccess.EMPTY`와 `Unpooled`를 씁니다. 테스트 클래스패스에서 안 되면 이 테스트만 빼도 됩니다.
- **실게임**: 배포 빌드에서 키 설정에 "마법 시전 (모션 테스트)"가 없어야 합니다. R을 눌러도 아무 모션이 나오지 않아야 합니다. 빗자루 타기·목록·성소 효과는 그대로 동작해야 합니다.

## 7. HUD 크기 = 핫바만 (9-2 #1, #2)

- **고친 것**: `PlayerHudLayout.player()`는 이제 핫바 도크(핫바, 허기/방어 칩, 들고 있는 아이템 이름)만 담당합니다. 초상화·체력·마나는 `renderStatus`에서 항상 `of()`(기본 크기·위치)로 그립니다. `portraitLift`를 없애고 `dockShift`로 바꿨습니다. 커진 핫바가 초상화와 겹치면 순서대로 이렇게 피합니다.
  1. 스킬바 왼쪽 끝까지 오른쪽으로 민다.
  2. 그래도 겹치면 위로 올린다. 게이지 위(초상화 원 옆 구간만 겹칠 때) 또는 원 위(원과 겹칠 때)까지 올리고, 칩이 화면 위로 나가지 않게 제한한다.
  - 50%는 원래 좌표 그대로입니다.
- 조준 알약(`AimHud`), 큐디치 F키(`KyudichiHud.keysBottom`)는 `player(…, percent)`로 계산합니다. 알약·칩·아이템 이름은 밀린 핫바의 가운데를 따라갑니다. `HeldItemNameHud`의 초상화 겹침 판정은 `of()`를 씁니다. 설정 화면 이름을 "핫바 크기"로 바꾸고 안내 문구를 "핫바만 바뀌고 초상화·체력·마나는 그대로입니다 (자동 저장)"로 바꿨습니다.
- **파일**: `PlayerHudLayout.java`, `PlayerHudRenderer.java`, `HeldItemNameHud.java`, `AimHud.java`, `KyudichiHud.java`, `SettingsScreen.java`, 테스트 `PlayerHudScaleTest`
- **판단 사항**: 16:9 화면에서 100%면 핫바(2배)가 초상화와 스킬바 사이에 들어갈 수 없습니다. 그래서 핫바가 초상화 위로 올라갑니다. 이전에는 초상화가 올라갔고, 이제는 초상화가 고정이고 핫바가 올라갑니다. 75%에서는 게이지 위까지만 올라갑니다.
- **테스트**: `PlayerHudScaleTest`. 초상화 단언은 `of()` 기준으로 바꿨습니다. 새 단언은 "핫바가 초상화와 가로로 겹치면 세로로 비킨다"입니다. 새 `portraitStaysPutWhileDockGrows`도 있습니다.
- **실게임**: 1920×1080에서 GUI 2·3, 크기 0/25/50/75/100%마다 다음을 확인합니다. 초상화·체력바가 움직이지 않아야 합니다. 핫바가 초상화·스킬바와 겹치지 않아야 합니다. 조준 알약이 칩 바로 위에 있어야 합니다. 큐디치 F키가 칩과 겹치지 않아야 합니다.

## 8. 마법 실패 안내 (액션바 → 알림 카드 + 삐빅)

- **고친 것**:
  - `ManaClient`의 서버 거절(마나 부족·쿨타임·잠김·미등록·실패)은 `SharedNotifications.offer("mana:fail:<status>","error",…)`로 보냅니다.
  - `CastingClient`에서 전송 실패 2곳은 `cast:send`로 보냅니다. 미습득·파일 없음·명령 없음은 `error`, 권한 확인 중(UNKNOWN)은 삐빅 없는 `warning`으로 보냅니다. 기존 1초 간격은 유지했습니다.
  - 슬롯 순서 저장 실패는 고정 문구 "순서를 저장하지 못했어요"로 바꿨습니다. 예외는 로그에만 남습니다(경로 노출 제거).
  - 같은 id 카드가 떠 있는 동안에는 중복으로 쌓이지 않습니다.
  - 같은 규칙으로 낚시 클라 액션바 2곳(`FishingScreen` 응답 지연, `FishingClient` 서버 종료 안내)도 카드로 바꿨습니다.
- **파일**: `ManaClient.java`, `CastingClient.java`, `FishingScreen.java`, `FishingClient.java`
- **실게임**: 마나 0에서 연타하면 카드 하나와 삐빅이 나오고 액션바는 비어 있어야 합니다. 쿨타임 중에도 같아야 합니다.

## 9. 낚시 손맛 (클라 FishingScreen)

- **흔들림**:
  - 전체 계수 `SHAKE=.5`를 두었습니다.
  - 당기는 동안 계속 떨리던 1.1px·38Hz 떨림을 **제거**했습니다.
  - 장력 떨림은 92% 이상(끊기기 직전)에서만 나고 크기는 절반입니다.
  - 당김 시작 쿵과 공격 충격은 절반입니다.
  - 물고기 그림의 상시 ±2~7px 떨림은 ±0.6px 완만한 흔들림으로 바꿨습니다. 85%를 넘을 때만 커집니다.
  - 장력 게이지 떨림은 절반입니다.
  - 크게 흔들리는 순간은 **입질에 걸었을 때**(`startReel`의 1회 충격)와 **잡혔을 때**(1회 0.3초)뿐입니다.
  - 결과 대기(WAIT_RESULT) 중에는 그 0.3초 충격 말고는 흔들리지 않습니다.
- **지침 신호**: 새 `FishMood`(순수 로직)를 두었습니다.
  - 저항(RESIST): 당김 이벤트 중이거나 당기는 힘이 1.25배를 넘을 때. 빨간 빛과 "저항 중 · 풀어 주기"가 뜹니다. 이때 감으면 장력 목표가 +12% 올라갑니다.
  - 지침(TIRED): 매 당김이 끝난 뒤 1.1초, SURGE의 쉬는 구간, 체력 25% 미만일 때. 하늘색 빛과 밝아진 실루엣, "지쳤다! 지금 감기", 효과음(자수정 차임) 1회가 나옵니다. 이때 감으면 감는 속도가 ×1.3이고 장력 목표가 ×0.8입니다. 물고기 힘도 0.6배 이하로 줄어듭니다.
- **서버 검증과의 관계(경제 묶음에 전달)**:
  - 지침 중 최대 감기 속도는 `0.75×1.3 = 0.975 × reelSpeed`입니다. 현재 서버 `minimumCatchMillis`의 허용치(`reel*.7` → 최대 1.43×)보다 낮습니다.
  - 서버 판정을 더 조이면 이 0.975×를 기준으로 삼아 주세요.
  - 클라는 결과 전에 bite의 rarity/grade를 새로 쓰지 않습니다. 기존 그림(등급 빛)만 `bite.grade()`를 씁니다. 필드가 없어지면 그 줄만 영향을 받습니다.
- **파일**: `FishingScreen.java`, `FishMood.java`(신규), 테스트 `FishMoodTest`(신규)
- **실게임**: 각 패턴(DASH·SURGE·THRASH·CALM)으로 낚시합니다. 확인할 것은 다음과 같습니다.
  - 당기는 동안 화면이 계속 떨리지 않는다.
  - 당김이 끝나면 "지쳤다!"와 차임이 나온다.
  - 그때 감으면 빨리 올라오고 장력이 덜 오른다.
  - 저항 중에 감으면 장력이 빨리 빨간 구간에 간다.
  - 잡히는 순간 짧게 한 번 흔들리고 결과 대기 중에는 조용하다.

## 10. 아공간 수조 크기 차이 (FishTankScreen)

- **고친 것**: 크기 계산을 `FishTankUiMath`로 옮겼습니다.
  - `absoluteLength(cm) = clamp(9·cm^0.55, 18, 140)`. 이전 `20·cm^0.36`(최소 40)보다 가파르고, 3cm 물고기는 18px까지 작아집니다.
  - `length = absoluteLength(cm) × (0.9 + 0.2 × 종 안 순위)`. 순위는 수조에 있는 같은 종 사이의 최소~최대 기준입니다. 한 마리이거나 차이가 5% 미만이면 가운데로 봅니다.
  - 깊이 배율은 0.62~1.0에서 **0.88~1.0**으로 바꿨습니다.
  - 상태가 올 때마다 종 범위를 다시 계산합니다.
- **판단 보류**: 종의 진짜 크기 범위(`fishing.yml` min/max)는 프로토콜 `Info`에 없습니다. 넣으려면 `FishTankProtocol` 형식을 바꿔야 하는데, 옛 클라 디코더가 뒤에 붙은 바이트(trailing)를 거부하기 때문에 호환이 깨집니다. 그래서 이번에는 "수조 안 같은 종 기준"을 썼습니다. 다음 프로토콜 버전을 올릴 때 `Info`에 `minSize/maxSize`를 넣으면 `length(cm,min,max)`에 그대로 쓸 수 있습니다.
- 선택 확대(`magnification`, 약 172px로 맞춤)는 그대로입니다.
- **파일**: `FishTankScreen.java`, `FishTankUiMath.java`, 테스트 `FishTankUiStateTest.sizesReadAcrossAndWithinSpeciesAndDepthNoLongerMasksThem`
- **실게임**: 같은 종 8cm·15cm와 3cm 물고기를 넣습니다. 15cm가 뚜렷하게 크고, 3cm가 확실히 작고, 깊이 때문에 순서가 뒤집히지 않아야 합니다.

## 11. 물고기 판매소 잠금 탭 (FishShopScreen)

- **고친 것**:
  - 「적용」을 누르면 보낸 잠금 집합을 `savingLocks`로 기억합니다. 서버 State의 `locks`가 그 집합과 **정확히 같을 때만** 저장된 것으로 봅니다. 그때 하단에 "저장됨 · …"이 4초 동안 표시됩니다.
  - 그 전까지는 "잠금을 저장하는 중…"과 비활성 "저장 중" 버튼이 보입니다.
  - 실패 알림이 붙은 State가 오면 수정 내용을 유지하고 미저장 상태로 돌아갑니다.
  - 10초 안에 응답이 없으면 안내하고 다시 조회합니다.
  - 미저장 수정이 있는 채로 **판매 탭으로 가면 자동으로 적용**합니다.
  - 저장이 확인되기 전(저장 중이거나 미저장)에는 판매 버튼이 막히고 안내가 나옵니다.
  - `FishShopClient.locks`는 전송 여부(boolean)를 돌려줍니다.
- **파일**: `FishShopScreen.java`, `FishShopClient.java`
- **한계**: 서버가 모르는 id를 걸러 내서 돌려준 잠금이 보낸 것과 다르면, 알림이 붙어 있으므로 "실패"로 처리됩니다. 이때 수정 내용은 미저장으로 남습니다. 판매소에 뜨는 종은 서버가 보낸 것이라 실제로는 일어나지 않습니다.
- **실게임**: 잠금을 바꾸고 바로 판매 탭을 누르면 "저장하고 있어요"가 뜹니다. 서버 응답 뒤 판매할 때 잠근 종이 빠져야 합니다. 서버 쪽 1초 10회 제한에 걸리게 연타하면 "저장됨"이 뜨지 않아야 합니다.

---

## 못 한 것 · 보류

- 6번의 **퀘스트 패킷 디코더**: 기준 커밋에 `QuestPackets`가 없습니다(story 브랜치 `7e6ac03`에만 있음). story 묶음에서 같은 방식으로 처리해야 합니다.
- 10번의 **종 크기 범위 프로토콜 추가**: 호환이 깨져서 보류했습니다(위 설명).
- 4번의 **비동기 전환·공유 캐시**: 계측 결과를 본 뒤 하기로 했습니다(설계 그대로).
- 5번의 **표시 문구 0건 검사 테스트**: 모듈을 가로질러 검사하는 테스트라 넣지 않았습니다. 대신 `git grep -n "이로치\|통발" -- ':!*.md' ':!reference/**' ':!docs/**'`가 0건이어야 합니다.

## 컴파일 위험 (확신이 덜한 곳)

- `SafeCodec.failSafe("…", StreamCodec.of(…lambda…), INVALID)`: 중첩 제네릭 추론이 필요합니다. 원래는 필드 타입으로 추론되던 것이고 Java 추론 규칙상 될 것으로 봅니다. 안 되면 `StreamCodec.<RegistryFriendlyByteBuf, RideS2C>of(` 처럼 타입 인자를 적어 주세요. `MotionPackets`의 `StreamCodec.composite`도 같습니다.
- `SafeCodecTest`의 `RegistryAccess.EMPTY`, `new RegistryFriendlyByteBuf(ByteBuf, RegistryAccess)`(Mojang 매핑 1.21.4)
- `FabricLoader.getInstance().isDevelopmentEnvironment()`(Fabric Loader API, 레포 안에 다른 사용 예는 없음)
- 나머지는 같은 파일·모듈에 이미 있는 API만 썼습니다. `SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME`은 `NotificationPresentation`에서 같은 방식으로 쓰고 있습니다.
