# 미구현 마법 71종 게임플레이 (2026-10-14, Claude)

브랜치 `claude/magic-71-gameplay-20261014`, 기준 `claude/followup-fixes-20261013` @ `41ee355`.
입력: 사용자 인계 `차카데미_미구현마법_167번까지_클로드전달.txt` + `source_and_approved_specs.json` (이전 미완 7 + 101~167 신규 64),
VFX 묶음: 사용자 PC `Desktop\차카데미 vfx_Claude` 의 62개 zip(vfx.json). 103 덩굴 사다리·134 빛의 낙인은 VFX 폴더가 없다.

## 0. 먼저 읽을 주의사항 (GPT)

1. **빌드·실게임 안 함.** Paper 1.21.4 API 소스 + MythicMobs/WorldGuard 스텁으로 `javac` 타입 검사만 통과. JUnit 은 작성만 하고 실행 못 함
   (클라우드에 Maven 접근 없음). 호스팅에서 `magic-mythic-paper`, `magic-discovery-paper` 테스트를 돌려 주세요.
2. **64종은 아직 시전되지 않는다 (의도).** catalog-mode 에서는 카탈로그 VFX 가 없는 마법을 Bridge 가 거부·환불한다(`CatalogVfxLink`).
   신규 VFX 62개는 vfx.json **1.13~1.21** 이고 현재 PortableVFX 클라이언트가 못 읽는 항목을 쓴다(최상위 `height`/`depth`, `staticFacing`,
   `heightScale`, `depthScale`, `pointLight`, `salvoFlight`, `salvoCount`, `followSmoothed`, `travelRoll`, `headAttach`).
   이런 바인딩을 `spell-bindings.yml` 에 넣으면 그 이펙트가 "준비 완료" 필수 목록에 들어가 **모든 플레이어의 모든 카탈로그 마법이 막힌다.**
   그래서 바인딩은 `portable-vfx-runtime/docs/pending/spell-bindings-101-167.yml` 초안으로만 두었다(코드가 읽지 않음). → 8장: 묶음 자체는 호환판으로 고침, 텍스처 메모리 문제가 남음.
   마법별로 켜는 순서: 클라이언트가 그 묶음을 읽을 수 있게 됨 → 바인딩 병합 + `spell-catalog.yml` reviewed/enabled/phases → `mana-spells.yml` enabled:true.
3. **VFX 없이 기능 시험:** `/마법미몹 시험 <마법ID>` (op) — 마나·쿨타임·권한·카탈로그 VFX 를 건너뛰고 그 마법의 CHA_ 스킬만 실행
   (`plugins/MagicCodexBridge/spell-runtime.yml` 라우트를 읽음). 패시브는 `/마법미몹 장착 <플레이어> <id...>`.
4. **운영 설정 파일은 jar 교체로 바뀌지 않는다.** 직접 병합: `MagicCodexBridge/spell-runtime.yml`(152 라우트), `MythicMobs/Skills/chacademi_spells.yml`,
   `ChAcademiaMagicMythic/config.yml`(새 항목), `MagicDiscovery/discoveries.yml`·`manual-conditions.yml`, `MagicCodexBridge/mana-spells.yml`,
   `PortableVFX/spell-catalog.yml`.
5. **MagicCodex(클라이언트·Bridge) 코드는 바꾸지 않았다.** 필요한 것은 제안으로만:
   `docs/handoff/proposals/BRIDGE_SPELL_COOLDOWN_API_20261014.patch` (쿨타임 조회/초기화 API + 클라이언트 알림 채널 + friend.made 신호, `git apply --check` 통과),
   `docs/handoff/proposals/CLIENT_SPELL_SLOTS_20261014.md` (키 슬롯 보고 채널, 쿨타임 초기화 수신, 빛의 필사 입력창 선택).
   단, **데이터 파일은 사양에 맞춰 갱신**했다: `default-spells/101~167`(설명·획득 조건 문구·연구 일지·마나·쿨), `mana-spells.yml`/`spell-catalog.yml` 마나·쿨,
   `spell-targeting.json`(생성기 출력, 신규 17종 추가·기존 변화 없음 — 줄바꿈이 CRLF→LF 로 바뀌어 diff 가 전체로 보임).
6. 사용자 결정: 마법 수치 최종 검토 전 → **피해·범위·지속 등 "미정" 값은 모두 임시값**(4장). 승인값처럼 쓰지 말 것.

## 1. 방식 (기존 magic-mythic 패턴 그대로)

- 원본 표: `magic-mythic-paper/tools/spells.py` → `python3 tools/spells.py` 로 미몹 스킬·Bridge 라우트·`SPELLS.md`·조준 미리보기 재생성.
- 미몹 YAML 은 "어떤 메커닉을 어떤 값으로" 만 적고 실제 처리는 애드온(ChAcademiaMagicMythic) 자바 메커닉.
- 모든 `cha*` 줄에 `element=`·`spell=` 자동 삽입: `spell=` 로 그 마법의 진행 중 VFX 에 `external:*` 이벤트를 보냄(`PortableVFX.emitSpellVisualEvent`),
  `element=` 은 기믹 `MagicImpactEvent` 와 획득 조건(6원소)에 사용.
- 보호: 모든 블록 변경은 기존처럼 가짜 Break/Place/Grow 이벤트 + WorldGuard `chacademi-magic` 확인, 피해는 `Targets`/`MagicProtection` 경유.
- 유틸 라우트 15종의 미몹 줄 `delay 1` → `chanote{n=1} @self` (아무것도 안 함, 시전 원소 기록용).

## 2. 변경 범위

| 파일 | 내용 |
|---|---|
| magic-mythic-paper `tools/spells.py` | 신규 66 라우트(+추가 스킬 `CHA_solar_judgment_fire`), `PASSIVE` 6, `SKIP` 3(wind_message, rainbow_collar, luminous_archive) |
| 새 메커닉 | `chafarm`(FarmMech), `chautil`(UtilMech), `chasummon`(SummonMech), `chabuff`(BuffMech), `chachannel`(ChannelMech), `chanote`(NoteMech) |
| 기존 메커닉 확장 | chahit `undead`/`hitevent`, chabolt `count/countmax/interval`·`rehit`·`ally`(아군 치유)·`proc`·`hitevent/missevent`, chazone `solid`(돌벽)·`manaregen`·`knock/up`·`pick=random`·`height`, chaguard `thorns/anchor/counter/ward/invuln/homing` (+ AllyMech 가 쓰는 `immune/oath`), chamark `root`/`stun`, chaally `heal/buff/feed/comfort/dawn/oath` |
| 새 클래스 | `Passives`(슬롯 패시브 6종), `SpellSlots`(장착 상태), `Links`(Bridge 마나·PortableVFX·ItemsAdder 리플렉션), `TempAbsorb`, `MagicRevealEvent`(진실의 눈 → 퍼즐 단서), `SpellTrace`, `DiscoveryHooks`(애드온만 아는 획득 카운터), `TestCast` |
| `config.yml` | `passive-slots.mode`, `passives.*`, `flame-forging.recipes`(비어 있음), `return-point`, `mana-potion.restore`, `guiding-star-text`, limits channels/summons |
| `plugin.yml` | softdepend PortableVFX, ItemsAdder / 명령 사용법 |
| magic-discovery-paper | `ExpansionConditions`(바닐라 자연 조건 카운터) 등록, `discoveries.yml` +47 자연, `manual-conditions.yml` +9 수동, 테스트 수 72→119 / 81→90 + `ExpansionDefinitionsTest` |
| portable-vfx-runtime `docs/pending/` | `vfx-101-167-systems.json`(묶음별 시스템·길이·필요 기능), `spell-bindings-101-167.yml`(초안, 미사용), 생성기 `magic-mythic-paper/tools/vfx_pending.py` |
| 데이터 | default-spells 64개, mana-spells.yml·spell-catalog.yml 마나/쿨 64개 (enabled/reviewed 는 그대로 false) |

## 3. 마법별 처리

전체 표: `magic-mythic-paper/SPELLS.md` (자동 생성). 요약:

- **패시브(장착)** 017 미라지 슈터, 030 미스틱 슈터, 049 광석 재련, 052 불꽃 걸음, 055 화염의 정복자, 121 자동 보급 → `Passives`.
  추가 화살은 `proc` 표시라 패시브를 다시 부르지 않음(무한 발동 방지). 030 은 무기/화살 직접 공격만(마법 피해 제외), 017 은 소유자가 나인 모든 피해.
  마나는 발동 시 `ManaService.tryConsume`, 부족하면 조용히 미발동. 052 는 mana-spells `enabled:false` 유지.
- **보류 1종**: 136 빛의 서고 — 잠긴 상자 시스템이 없음.
- **해석이 들어간 것 (확인 필요)**: 129 대지의 품 "더위로 마른 작물" → 더위 건조 시스템이 없어 *완전히 마른 농지(수분 0) 위 작물* 로 해석(농지 적시고 1단계 성장).
  118 퇴비 환원 → 바닐라 퇴비통 기대값(7층=뼛가루 1)을 임시 비율로. 145 동행 귀환 → `return-point` 미설정 시 첫 월드 스폰.
  151 빛의 필사 → 채팅 한 줄 입력. 163 별빛 재현 → 쿨타임 여부와 무관하게 장착된 다른 마법 중 무작위 1개(문자 그대로).
  137 진실의 눈 → 투명한 적에 발광(모두에게 보임; 시전자 전용 윤곽은 클라이언트 작업), 단서는 `MagicRevealEvent` 로 기믹이 처리.
  104 그놈 → 실제 몹 없이 가상 골렘 위치가 따라다니며 근접 공격(몸체는 VFX), 148 반딧불 → 서버에 빛 블록을 놓지 않고 주변 플레이어에게 빛 블록 변경만 전송.
- **Bridge/클라이언트 제안이 있어야 완전한 것**: 131 원초의 돌 — 쿨타임 API 가 없으면 모은 뒤 **자동 발사**(대체 동작), API+클라이언트 알림이 있으면 사양대로 "다시 사용 시 발사"(2단계는 마나 환불).
  163 별빛 재현 — 쿨타임 API·슬롯 보고 둘 다 필요(없으면 안내 후 실패, 마나는 VFX 이후라 소모됨). 패시브 6종 — 슬롯 보고(또는 `learned` 모드).

## 4. 미정·임시값 (사용자/검토 결정 필요)

| # | 항목 | 지금 넣은 임시값 |
|---|---|---|
| 017/030 | 추가 화살 피해·딜레이·내부 쿨 | 3+0.25마력 / 6+0.4마력, 017 10틱 대기·내부쿨 10틱 |
| 036 | 단조 재료표 | 비어 있음 → 항상 실패 |
| 052 | 폭발 지연·간격·피해·범위 | 30틱, 1.5블록, 3+0.2마력, 반경 1.5 |
| 055 | 획득 조건 "100회" vs "피해 100" 불일치 | 손대지 않음 (수동 조건 그대로) |
| 063 | 중첩 | 같은 마법끼리 미중첩, 반경 안 모든 플레이어 |
| 101 | 피해·부채꼴 각도 | 4+0.3, 60° |
| 102 | 벽 크기 | VFX 기준 4×3 |
| 104 | 공격력·크기 | 5+0.4, 사거리 8, 1.8초 간격 |
| 105 | 지속·피해 | 3초 3회, 6+0.45 ×0.5 |
| 108 | 반경 | 8 |
| 109 | 반사 피해 | 2+0.15 |
| 113 | 저항 레벨 | I |
| 114 | 표시명·마나·쿨·조건·범위 | 탐지 깊이 64, 마나/쿨은 옛값 유지(enabled false) |
| 116/119 | 사거리·피해 | 15블록, 2+0.2 / 5+0.35 |
| 118 | 재료·비율 | 바닐라 퇴비 기대값 |
| 126 | 증가량 | 크기 +50%, 거리 +1, 근접 피해 +30% |
| 127 | 횟수·높이·유지 | 3회, 위 1.1·앞 0.5, 30초 |
| 128 | 크기·사거리·피해 | 반경 1.4, 16블록, 8+0.5 ×0.4, 8틱마다 재타격 |
| 131 | 확률·피해 | 수업 이벤트당 10%, 20+1.0 |
| 134 | 피해·사거리 | 2+0.25, 20 |
| 137 | 범위 | 16 |
| 138 | 전환량·상한 | 투사체당 2, 최대 10, 10초 |
| 139 | 획득 조건·효과 레벨 | 미연결, 신속 I·저항 I |
| 140 | 곡괭이 한정 | 곡괭이만 |
| 143 | 반경·피해 | 8, 8+0.5 |
| 147 | 피해·사거리 | 10+0.6 ×0.15 ×12회, 30 |
| 153 | 반경 | 8 |
| 156 | 범위·지속 | 10, 5초 |
| 157 | 계수·반경·유지 | 0.5×마력, 6, 20초 |
| 158 | 치유량 | 4+0.3 |
| 160 | 거리·지속·비율 | 10, 10초, 30% |
| 161 | 전환 비율 | 마나 1 = 경험치 0.5 |
| 166 | 사거리·둔화 | 10, 구속 II 3초 |
| 획득 미정 | 114, 116, 122, 124, 136, 139, 159 | 정의 없음(관리자 `습득`만 가능) |

## 5. 획득 조건

- **수동(조건지급 명령)**: 102 석벽, 104 그놈, 105 지진, 130 숲의 왕좌, 133 작은 치유, 138 보호 결계, 145 동행 귀환, 155 별빛 측량, 166 그림자 매듭.
  `/유저관리 <유저> 마법 조건지급 <마법>`.
- **낮은 확률 수업**(수동 오버레이는 확률 불가라 자연 정의): 131 원초의 돌 `lesson.earth.applied`, 158 자비의 화살 `lesson.light.applied`, chance 0.1.
  수업 때 `/마법발견관리 이벤트 <닉> lesson.earth.applied` → 그때마다 10% 판정.
- **자연 카운터** 47종: `ExpansionConditions`(블록 설치/채굴/번식/방패/처치/모루/이동/어둠/계절 등), `DiscoveryHooks`(애드온: 마법 처치·빛 언데드·정화·마법 치유·마나 0·6원소 30초).
  160 수호자의 서약 `friend.made` 는 Bridge 제안 패치가 있어야 오른다.
- 해석: 113 철 갑옷 = 네 부위 모두 철, 119 선인장 = 부순 칸 위로 이어진 선인장 수까지, 121 = 한 종류 블록 최다 누적, 152 달리는 = 수평 속도 0.1 이상,
  157 = 애드온 마법 치유만(물약 제외), 106 = 접속 중 1분마다 현재 계절 기록.

## 6. 확인할 것 (호스팅/실게임)

1. `magic-mythic-paper`·`magic-discovery-paper` 테스트 (새: `ExpansionSpellsTest`, `ExpansionDefinitionsTest`).
2. MythicMobs 5.9 에서 새 메커닉 이름 등록(`/mm reload` 후 경고 없음), `chanote{n=1;...} @self` 파싱.
3. Paper 이벤트: `EntityKnockbackByEntityEvent`(대지의 닻), 방패 막기 판정(`DamageModifier.BLOCKING`, 반격의 무게추·획득 조건), `AsyncChatEvent` 우선순위(빛의 필사 — 다른 채팅 플러그인보다 먼저 취소되는지).
4. `/마법미몹 시험` 으로 64종 기능 확인, `/마법미몹 상태` 로 진행 중 개수.
5. VFX 이벤트 이름은 초안 바인딩의 `gameplay-events` 목록과 묶음 시스템 id 를 맞췄다. 바인딩 병합 시 NOTES.md 기준으로 트리거·앵커 재확인.

## 7. 별도 검토 후 반영한 수정

- `Targets.canBreak` 의 가짜 BlockBreakEvent 를 마력 각인(3×3)·획득 카운터가 실제 채굴로 세던 문제 → `Targets.probing` / `isDropItems()` 로 제외.
- 수호자의 서약: 동료의 피해가 무적 등으로 취소되면 보호자도 피해를 받지 않도록 MONITOR 에서 적용.
- 보호 결계: 다른 흡수(위로의 빛·황금사과)를 깎지 않고 결계 몫만 상한/종료 처리. 반사(되받는 바람)는 투사체당 1회.
- 불꽃 단조 재료표 파싱(ItemsAdder id 의 ':' 처리), 광석 재련은 재련할 것이 있을 때만 마나 소모, 발자국 폭발은 생성된 월드에서만.
- 영창 등록 실패 시 영구 잠김, 빙옥·기절 겹침 시 조기 해제, 위/아래를 볼 때 골렘·도약판 방향 NaN, 소환 동물 사망 메시지, 만료된 필사 대기 중 채팅 삼킴,
  플러그인 종료 시 임시 하트 수정자 잔존, 모루 획득 카운터 중복 집계를 고침.
- 남김: 광맥 파쇄·마력 각인으로 캔 광석은 `BlockDropItemEvent` 가 없어 광석 재련(049) 대상이 아님. 원목 껍질 벗기기는 축이 다른 가지 원목도 포함.


## 8. VFX 묶음 클라이언트 호환 변환 (2026-10-14 추가)

- 사용자 요청으로 PC `차카데미 vfx_Claude` 의 62개 묶음을 **현재 PortableVFX 클라이언트가 읽는 형태로 고쳐 덮어씀**. 원본은 각 폴더 `_original\<Name>.zip`.
  각 zip 의 NOTES.md 끝에 바뀐 점, preview.html 은 고친 vfx.json 을 재생.
- 검증: `portable-vfx-runtime/client` 의 실제 `ClaudeConfigLoader.load` 를 컴파일해 62팩 전부 로드 성공(시스템 271개, 오류 0).
- 바뀐 것(27팩): 최상위 height/depth 와 heightScale/depthScale(기준 크기로 고정), staticFacing 표기(서버가 static 기준 방향을 이미 시전자 수평 방향으로 보내므로 방향은 유지),
  pointLight·followSmoothed·headAttach·travelRoll(동적 조명·스무딩 추적·머리 부착·구르기 없음 — NightSight 는 머리 예상 위치로 레이어 이동),
  salvoFlight/salvoCount(발 수 고정, 속도·사거리는 서버 바인딩), scale objectSize/keepPosition/systems, 60초 넘는 유지 입자 수명 60초로, HDR 색 키 1로 제한 2곳.
  나머지 35팩은 값이 비어 있던 필드 표기만 정리(연출 변화 없음).
- **남은 막힘 — 텍스처 메모리**: 클라이언트는 모든 팩을 1 GiB RGBA 예산에 상주시키며 기존 97팩이 약 926 MiB, 이번 62팩이 약 440 MiB.
  둘 다 켜면 예산 초과라 클라이언트 쪽 변경(지연 로딩 또는 상한 상향 — 상한 설정 최대값도 1024) 또는 텍스처 축소 결정이 필요. 그래서 바인딩은 여전히 `docs/pending` 초안.
  → **해결 패치**: [CLAUDE_VFX_BC7_20261014.md](CLAUDE_VFX_BC7_20261014.md) — 클라이언트 BC7 압축으로 신규 62팩 430→119 MiB. 이 패치 배포 후 바인딩을 합칠 수 있다.
- 툴킷/스킬: Anime VFX Toolkit 에 `tools/client_compat.py`(check/fix) + `tools/client_caps.json` 추가, make_bundle 끝에 자동 검사. anime-vfx-maker 스킬 갱신 제안.
