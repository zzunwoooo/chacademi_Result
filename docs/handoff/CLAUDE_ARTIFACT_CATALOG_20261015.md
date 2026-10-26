# 아티팩트 전체 목록 + 아티팩트 사용 키 (Claude, 2026-10-15)

브랜치: `claude/artifact-catalog-20261015`. 기준은 `claude/reaction-vfx-20261015` @ `b07c62c`이고, 원소 반응·반응 VFX·아티팩트 도감 브랜치를 모두 포함합니다.

## 0. 먼저 읽을 것

- **빌드 안 됨.** Gradle을 돌리지 못했습니다. 대신 아래만 확인했습니다.
  - `ArtifactProtocol`: javac 컴파일. 새 테스트 `ArtifactProtocolTest` 3개를 간이 실행기로 돌려 통과.
  - `ArtifactBridge`·`ArtifactUseEvent`: Bukkit 스텁으로 javac 통과.
  - `KeySettingsLayout`·`SpellKeySettings`: 실제 클래스로 X 금지와 기존 X 바인딩 이관을 실행해서 확인.
  - Fabric 화면 코드(`ArtifactClient`, `EquipmentScreen`, `ArtifactCodexScreen`)는 Minecraft 의존성이 없어 **컴파일 확인을 못 했습니다.**
- **프로토콜 v2.** 새 서버(MagicCodexBridge)와 새 모드를 **같이 배포**해야 합니다. 옛 모드는 v2 응답을 못 읽어서 도감이 비어 보입니다. 새 모드는 v1 응답도 읽습니다.
- **아티팩트 능력은 여전히 미구현입니다(오너 결정: 이번엔 도감 + 키까지).** 사용 키는 서버까지 전달되지만, 효과를 내는 리스너가 아직 없습니다. 그래서 지금 누르면 "○○의 효과는 아직 준비 중입니다."가 뜨고 재사용 대기도 시작되지 않습니다.

## 1. 오너 요청

1. 검수를 마친 아티팩트 목록 전체를 실제 도감에 적용합니다. 아이템으로 만들 필요는 없습니다.
2. 아티팩트 사용 키를 만들고 **기본 키는 X**로 합니다.
3. MagicCodex 마법 키세팅에서 **X는 지정할 수 없게** 합니다.

## 2. 바뀐 것

| 위치 | 파일 | 내용 |
|---|---|---|
| 서버 | `magic-codex-paper/src/main/resources/artifacts.yml` | 검수 완료 목록 150항목 (일반 76 / 고급 40 / 유일 17 / 전설 17). 원소 반지는 6종으로 나눔. 보류 2개(`bug_repellent_incense`, `comeback_medal`)는 `enabled: false`. 사용형 20개는 `use: true` + `cooldown`(초). 아이콘이 있는 71개는 `icon` 채움, 나머지 79개는 빈칸(아이콘 제작 중) |
| 공용 | `ArtifactProtocol` v2 | `Entry.cooldown`(-1 = 상시형, 0 이상 = 사용형 재사용 대기 초), 요청 `USE`, `State.cooldownMs`(장착 순서대로 남은 대기 ms). 읽기는 v1/v2 모두, 쓰기는 v2 |
| 서버 | `ArtifactBridge` | yml의 `use`/`cooldown` 읽기. `USE` 처리 순서: 캐릭터 로드 → 보유·장착 확인 → 사용형인지 → 대기 시간 → `ArtifactUseEvent` 호출 → 리스너가 `setUsed(true)`일 때만 대기 시작. 대기 시간은 서버 메모리(같은 서버면 재접속해도 유지, 서버 간 공유 안 됨). `/아티팩트관리 목록`에 사용형 표시 |
| 서버 | `ArtifactUseEvent` (새 파일) | 능력 구현 플러그인이 받을 이벤트: `getArtifactId()`, `setUsed`, `setResponse`(액션바 문구) |
| 클라 | `ArtifactClient` | 키 `key.magiccodex.artifact_use` 기본 X(조작 설정에서 바꿀 수 있음). 누르면 장착한 사용형 아티팩트를 서버에 요청하고, 결과 문구를 액션바에 표시. 사용형이 아니거나 대기 중이면 로컬에서 바로 안내 |
| 클라 | `KeySettingsLayout`, `SpellKeySettings`, `KeySettingsScreen` | X(88)를 예약 키에 추가. 아티팩트 키를 다른 키로 바꾸면 그 키도 막힘. 키보드 그림에서 X가 금지 키(빨강)로 보임. 기존 설정에 X로 등록된 마법은 마법은 남기고 키만 비움(P·O와 같은 방식) → 키세팅에서 다시 지정 필요 |
| 클라 | `EquipmentScreen` | 장착한 아티팩트가 사용형이면 칸 오른쪽 아래에 키 표시, 대기 중이면 어둡게 하고 남은 초 표시 |
| 클라 | `ArtifactCodexScreen` | 상세 효과 툴팁 아래에 "사용 키 [X] · 재사용 대기 1분 30초" 줄 |
| 클라 | `textures/artifact/*.png` | 아이콘 71장 (128px, 원래 1~66번 + 원소 반지 6종) |
| 클라 | `lang/ko_kr.json`, `en_us.json` | 키 이름 "아티팩트 사용" |
| 테스트 | `magic-codex-fabric/src/test/.../ArtifactProtocolTest.java` | 200개 카탈로그 분할 왕복, 대기 ms, USE 요청, v1 프레임 읽기 |

## 3. 확인 부탁

1. **배포 순서:** 서버 Bridge와 모드를 같은 점검 때 같이 올려 주세요(프로토콜 v2). 운영 서버의 `plugins/MagicCodexBridge/artifacts.yml`은 JAR 교체로 바뀌지 않으니, 새 기본 파일로 교체해 주세요. 지금 운영본은 예시 4개뿐이라 병합할 내용은 없습니다. 교체 후 `/아티팩트관리 새로고침`.
2. **빌드 때 볼 것:**
   - `ClientTickEvents` / `KeyBindingHelper` import
   - `ArtifactClient.useKeyLabel()` (`getBoundKeyLocalizedText`)
   - `EquipmentScreen.artifactSlot`의 `TextRenderer.draw` 인자
   - `ArtifactCodexScreen.tooltip`의 `LABEL` 글꼴
3. **실게임:**
   - X를 눌렀을 때 문구가 나오는지: 미장착 / 상시형 / 사용형("효과는 아직 준비 중")
   - 키세팅에서 X가 빨간 금지 키로 보이는지
   - 조작 설정에서 아티팩트 키를 바꾸면 그 키도 마법에 못 넣는지
   - 크리에이티브의 바닐라 "핫바 불러오기"(X)와 겹침: 크리에이티브에서만 둘 다 동작
4. **능력 구현 방법(다음 단계):** 효과를 담당할 플러그인에서 `ArtifactUseEvent`를 듣고, 아이디가 맞으면 효과를 낸 뒤 `setUsed(true)`를 부르면 yml의 `cooldown`이 시작됩니다. 상시형(패시브) 능력은 각 플러그인이 장착 목록을 보고 적용합니다. `ArtifactBridge.equipped(Player)`를 다른 플러그인이 쓸 수 있게 열어 줄 공개 API는 아직 없으니, 능력 작업 때 같이 정하면 됩니다.
5. **아이콘 79개:** 오너가 GPT에게 맡겨 받는 중입니다. 받으면 `textures/artifact/<아이디>.png`로 넣고 yml `icon`만 채우면 됩니다. 아이디·그릴 것 목록은 오너에게 드린 `아티팩트_아이콘_프롬프트_67-145.md`에 있습니다.
6. **기존 인계 유지:** 장착 기록 DB 저장(`CLAUDE_ARTIFACT_CODEX_20261015.md` 4-2)은 아직 GPT 작업으로 남아 있습니다.
