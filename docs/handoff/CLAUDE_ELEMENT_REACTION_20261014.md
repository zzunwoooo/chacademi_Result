# 원소 반응 1차 (Claude, 2026-10-14)

브랜치: `claude/element-reaction-20261014` (기준: `claude/followup-fixes-20261013`)
상태: **코드만 작성, 빌드 안 됨.** 작업 환경에서 paper-api·Fabric·MythicMobs 의존성을 받을 수 없어
Gradle 빌드를 못 돌렸습니다. 순수 로직(`ReactionBook`, `ElementProtocol`)만 javac로 컴파일하고 시나리오 검사를 통과시켰습니다.
GPT 쪽에서 빌드·테스트를 부탁합니다.

## 무엇을 하나

- 마법이 적에게 피해를 넣는 데 성공하면(chahit / chabolt / chazone, 기존 `element=` 값 사용) 원소 표식이 3초 남는다.
- 표식이 살아 있을 때 **다른 원소**로 맞히면 반응 1회 → 표식 소멸 → 같은 대상은 **3초 내부 쿨다운**: 그동안 맞아도 표식이 아예 안 생김(아이콘도 없음), 불씨 확산도 건너뜀.
- 같은 원소는 시간만 갱신. 반응 효과가 다시 표식을 만들지 않음(`applying` 가드).
- 파티원끼리도 반응함 (표식은 대상 기준, 누가 남겼는지 무관).
- 오너 확정 규칙(2차): 반응은 **적에게** 거는 간단한 효과 하나씩(무지개만 아군 회복), 제어는 1.5초 이하,
  피해 반응은 작게(반응을 일으킨 마법 피해의 15~20%, 최소 기준 2), 오래 남는 효과 없음. 보스는 제어·어그로 해제 제외.

| 반응 | 효과 |
|---|---|
| 화염+물 증기 폭발 | 주변 2블록 피해 15% + 살짝 튀어 오름 |
| 화염+바람 불씨 확산 | 근처 적 최대 2마리에게 화염 표식이 번짐 (표식 없는 대상만, 반응은 안 일으킴) |
| 화염+대지 용암 분출 | 2초 불붙음 |
| 화염+빛 태양 플레어 | 4초 발광 |
| 화염+어둠 잿불 낙인 | 8초 안에 처치되면 경험치 2배 |
| 물+바람 연쇄 번개 | 근처 적에게 최대 3번 튐, 15% 시작 매번 x0.7 |
| 물+대지 진흙 | 1.5초 구속 IV |
| 물+빛 무지개 | 시전자·주변 4블록 아군 체력 +1 |
| 물+어둠 심연 | 1초 공중부양 II |
| 바람+대지 모래 회오리 | 주변 3블록 적을 대상 쪽으로 빨아들임 |
| 바람+빛 섬광 | 주변 6블록 몬스터가 노리던 플레이어를 놓침 |
| 바람+어둠 공허 균열 | 1초 뒤 반응한 자리로 텔레포트되어 돌아옴 (1~10블록 밖이면 안 함) |
| 대지+빛 수정화 | 0.5초 경직: Mob AI 정지(`setAware(false)`) 후 복구. 플레이어는 같은 시간 강한 구속 |
| 대지+어둠 매장 | 1초 이동 불가 (구속 X) |
| 빛+어둠 일식 | 0.5초 뒤 반응 자리 3블록 피해 20% |

## 변경 파일

서버 `magic-mythic-paper`
- `ReactionBook.java` (신규, 순수 로직) / `Reactions.java` (신규, Bukkit 연결·효과·패킷)
- `HitMech`, `BoltMech`(strike·burst), `ZoneMech`(pulse·trap): `targets.hurt(...)` 성공 직후 `plugin.reactions().hit(...)` 한 줄
- `MagicMythicPlugin`: 생성·리스너 등록·`/마법미몹 새로고침`·`상태`에 연결, onDisable에서 close
- `config.yml`: `reactions:` 섹션 (enabled, mark-ticks, cooldown-ticks, damage-floor, damage-scale, icon-range)
- `build.gradle`: `magic-codex-protocol`에서 **ElementProtocol.java 한 파일만** 소스로 포함, 버전 `...-reaction.1`
- 테스트 `ReactionBookTest.java`

공용 `magic-codex-protocol`
- `ElementProtocol.java` (신규): 채널 `magiccodex:element`, S2C 전용, 6~8바이트 프레임(Mark / Clear / Pop)

클라이언트 `magic-codex-fabric`
- `ElementMarkClient.java` (신규), `MagicCodexClient.initialize()`에 등록 한 줄
- `assets/magiccodex/textures/element/{fire,water,wind,earth,light,dark}.png` (256px, GPT 생성 아이콘 원본 그대로 잘라냄)

## 확인 부탁 / 주의

1. **빌드**: `Reactions`의 Paper API 사용 — `Bukkit.getCurrentTick()`, `Player#getListeningPluginChannels()`, `Mob#setTarget(null)`, `Mob#setAware/isAware`, sealed record 패턴 switch(Java 21).
   - 지연 작업은 공허 균열(1초)·일식(0.5초)·수정화 해제(0.5초)뿐. 반응 쿨다운 때문에 대상당 3초에 1개 이하. onDisable에서 경직된 몹 AI 복구.
2. **클래스 중복**: `ElementProtocol`이 MagicCodexBridge jar(프로토콜 전체 포함)와 MagicMythic jar에 둘 다 들어간다. 내용이 같고 각 플러그인이 자기 jar를 먼저 읽으므로 문제는 없어야 하지만, 로딩 경고가 뜨는지 확인 부탁.
3. **아이콘 그리기**: 월드 텍스처가 아니라 HUD 이미지로 그림. 몹 머리 위치를 `AimView.toScreen`으로 화면 좌표로 바꾸고 `HudTextureCache.drawCentered`로 PNG를 그대로 그림(다른 HUD 아이콘과 같은 경로). `AimView`는 `AimRenderer.render`가 매 프레임 캡처하므로 조준 중이 아니어도 유효해야 함 — 확인 부탁.
   - 32블록 이내, `player.canSee(entity)`일 때만, 최대 24개. 아이콘 둘레 링 = 남은 시간, 마지막 1초 깜빡임.
   - 반응 시 반응 이름이 1초간 떠오름 (CodexTypography hud_bold).
4. **부하**: 매 틱 작업 없음. 적중 시 맵 조회 1번 + 반응 시 반경 2~6블록 검색 1번. 30초마다 만료 정리. 패킷은 48블록 안, 채널 등록한 클라이언트에게만.
5. **아직 원소가 안 붙는 피해**: `chamark`(재의 낙인·폭발 쐐기·화염 검무 등)와 `chaworld companion`(운디네) 피해는 반응에 안 들어감. 2차에서 `tools/spells.py`가 이 줄에도 `element=`를 붙이고 `Marks`/`WorldMech`에서 hit 호출하면 됨.
6. 반응 피해는 마법 밸런스 확정 때 `damage-scale`로 같이 조정.
7. VFX(반응 이펙트)는 `claude/reaction-vfx-20261015`에서 추가했습니다(15종, 0.15~0.5초, 공허 균열 1초). [CLAUDE_REACTION_VFX_20261015.md](CLAUDE_REACTION_VFX_20261015.md) 참고.
