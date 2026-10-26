# ChAcademiaMagicMythic

차카데미 마법의 **게임 효과**(피해, 밀치기, 지대, 보호막, 작은 월드 유틸)를 MythicMobs 스킬에서 쓸 수 있게 해 주는 보조 플러그인.
마나·쿨타임·권한·대상 선택은 MagicCodexBridge, 시각효과는 PortableVFX 가 맡는다.

## 설치

1. `chacademia-magic-mythic-<버전>.jar` → `plugins/`
2. `mythicmobs/Skills/chacademi_spells.yml` → `plugins/MythicMobs/Skills/` (기존 `CHA_*` 마법 스킬 파일은 제거)
3. `server-config/spell-runtime.yml` → `plugins/MagicCodexBridge/spell-runtime.yml` (교체)
4. MagicCodexBridge 에 `docs/handoff/proposals/BRIDGE_CATALOG_GAMEPLAY_20261011.patch` 가 적용된 빌드 필요
5. 서버 재시작 (또는 `/mm reload` + `/마법관리 새로고침`)

## 마법 수정 방법

`tools/spells.py` 의 표가 원본이다. 고친 뒤 `python3 tools/spells.py` 로 YAML 두 개와 `SPELLS.md` 를 다시 만든다.
한 줄 = `(id, 스킬이름, 대상, 사거리, 기본피해, 마력계수, 유틸여부, [미몹 줄], 설명)`.

- 대상: `SELF` / `AIM`(바라보는 곳, 맞은 적이 있으면 그 적) / `ENTITY`(적 필수) / `AIR_ENTITY` / `BURNING_ENTITY`
- 피해: `base-damage + power-ratio × 마력` 이 스킬 변수 `damage` 로 들어오고, 각 메커닉의 `mult` 를 곱한다.
- 대상 없이 쓰는 줄은 Bridge 가 고른 대상(적 또는 조준 위치)에 적용된다. 시전자 중심은 `@self`.

## 명령어

- `/서버관리 새로고침 몹마법` — config.yml 다시 읽기 (스킬 YAML을 고쳤다면 `/mm reload`도)
- `/서버관리 상태` — 몹마법 줄: 진행 중인 투사체·지대·보호막·표식·임시 블록 개수
- `/유저관리 <닉네임> 마법슬롯 [마법…|비우기]` — 슬롯 장착 상태 보기/강제 지정 (패시브 시험용)
- `/테스트 마법 시전 <마법>` — 마나·쿨타임·권한·카탈로그 VFX 없이 그 마법의 CHA_ 스킬만 실행 (VFX 준비 전 기능 시험)
- `/테스트 몹 예고 <원형|직선|부채꼴|전부|봉인진|봉인진이로치|빙핵레이저|빙핵레이저이로치> [초]` — 예고 장판만 (피해 없음)
- `/테스트 몹 예고효과 <팩/효과> <원형|원형내발|직선|부채꼴> <크기> [각도·폭] [틱] [선행] [배율]` — 예고 + 그 VFX
- `/테스트 몹 상태이상 <종류|해제|목록> [초] [나|바라봄|닉네임] [세기]`

## 슬롯 장착형 패시브

017 미라지 슈터, 030 미스틱 슈터, 049 광석 재련, 052 불꽃 걸음, 055 화염의 정복자, 121 자동 보급은 미몹 라우트가 없고 `Passives` 가 처리한다.
장착 여부는 `SpellSlots`: 클라이언트가 `chacademia:spell_slots` 로 보고(제안) 하거나, `config.yml passive-slots.mode: learned` 로 배운 마법 전체를 장착 취급.

## 메커닉 옵션

각 클래스 상단 주석에 전체 옵션이 있다 (`HitMech`, `BoltMech`, `ZoneMech`, `GuardMech`, `MarkMech`, `AllyMech`, `WorldMech`,
`FarmMech`(chafarm), `UtilMech`(chautil), `SummonMech`(chasummon), `BuffMech`(chabuff), `ChannelMech`(chachannel), `NoteMech`(chanote)).
모든 `cha*` 줄에는 생성기가 `element=`·`spell=` 을 넣는다. `spell=` 로 그 마법의 진행 중 PortableVFX 연출에 `external:*` 이벤트를 보낸다.
