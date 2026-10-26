# CLAUDE_FIX_COMBAT_20261021 — 전투 묶음 (B4) 인계

- 브랜치: `claude/fix-combat-20261021` (기준 `0cd1454`, push 안 함)
- **빌드 미검증: 문법 검사(parsecheck)만 통과.** Gradle 빌드·단위 테스트·실게임은 돌리지 못했음. GPT가 빌드·테스트를 돌려 주세요.
- 마법 밸런스 수치(피해·범위·쿨)는 바꾸지 않았음. VFX/판정 단일화(L)는 범위 밖.
- 경로 약어: `MM` = `magic-mythic-paper/src/main/java/school/chacademia/magicmythic`, `BR` = `magic-codex-paper/src/main/java/school/magiccodex/paper`, `FC` = `magic-codex-fabric/src/main/java/school/magiccodex/client`, `PV` = `portable-vfx-runtime/paper/src/main/java/dev/portablevfx/paper/internal/spell`

## 배포 순서·호환 (먼저 읽기)

함께 배포할 것: MagicMythic, MagicCodexBridge, ChacaKyudichi, GimmickPlugin, PortableVFX(paper), MagicCodex 클라 모드, 그리고 설정 파일 4개.

| 조합 | 결과 |
|---|---|
| 새 서버 + 옛 클라 | 동작함. 조준은 예전처럼 서버 레이(조준점 패킷이 안 옴). 파티원 조준 보정 제외만 없음 |
| 옛 서버 + 새 클라 | 동작함. `magiccodex:cast_aim` 채널이 없으면 클라가 보내지 않음 |
| MagicMythic만 새로, PortableVFX 옛것 | **falling_star 착탄 연출이 두 번(VFX 자체 착탄 + 서버 external:impact 무시)·위치 불일치.** PortableVFX와 `spell-bindings.yml`을 같이 배포해야 함 |
| 새 chacademi_spells에 옛 MagicMythic | `chameteor` 메커닉이 없어 falling_star가 아무 효과 없음. 같이 배포 |

설정 파일(운영 overlay도 같이 고쳤고 `deployment/operating-config-manifest.json` 해시 갱신):
- `MythicMobs/Skills/chacademi_spells.yml` — `CHA_falling_star`가 `chameteor{...;spell=falling_star}` (운영 overlay에서 빠져 있던 `spell=`도 들어감)
- `PortableVFX/spell-bindings.yml` — falling_star impact·afterglow `trigger: external:impact`
- `MagicCodexBridge/spell-runtime.yml` — 바닥형 마법에 `ground: true` (운영 overlay 27개, 기본 리소스 15개, 원본 생성물 40개)
- 주의: manifest의 `chacademi_spells.yml` 해시는 **이 작업 전부터 실제 파일과 안 맞았음**(LF·CRLF 둘 다 불일치). 새 값은 CRLF 기준으로 넣었음. `spell-bindings.yml`은 CRLF, `spell-runtime.yml`은 LF 기준이 기존 값과 맞아서 그 규칙대로 갱신함. C05 overlay 재생성 때 함께 정리 바람.
- 운영 overlay는 여전히 원본보다 오래됨(C05, B6 묶음). 이번에는 해당 줄만 고쳤음.

---

## 1. C03 아군 판정 통일 (오너 결정 7)

**무엇을:** 아군 = 시전자 본인 + 같은 파티원. 큐디치·결투 경기 중에는 경기 팀만 아군(파티 무관, 랜덤 매칭 포함). 그 밖의 플레이어는 어디서든 아군이 아님(회복·버프 없음). 피해·CC는 지금처럼 WorldGuard PvP를 따르되, 아군끼리는 피해·CC·원소 표식·넉백·끌기가 전혀 들어가지 않음.

**바뀐 파일**
- `MM/AllyResolver.java`(신규): 파티는 MagicCodexBridge `PartyRoster` 서비스(이미 Bukkit 서비스로 등록돼 있음)를 리플렉션으로 `sameParty(UUID,UUID)` 호출. 경기 팀은 `MagicAllyEvent`로 물어봄. 순수 규칙 `allied(Boolean match, BooleanSupplier party)`.
- `MM/MagicAllyEvent.java`(신규, public): `getFirst()/getSecond()/setAlly(boolean)`. 듣는 쪽이 없으면 비용 0.
- `MM/Targets.java`: `enemy()` — 둘 다 플레이어이고 아군이면 false. `ally()` — `e==caster || 아군`(예전: 시전자가 플레이어면 모든 플레이어).
- `MM/MagicMythicPlugin.java`: AllyResolver 등록·`allies()`.
- `MM/Reactions.java`: 표식/반응 전에 `enemy` 확인(`hit()` 입구), 불씨 확산(화상)·모래 회오리(끌기+고정) CC 전에 `hurt(caster,t,0)` 보호 탐침.
- `kyudichi-paper/.../Hooks.java` `listenAlly`, `GameListener.hookAlly`, `KyudichiPlugin`에서 호출: 둘 다 경기 밖이면 답하지 않음(파티가 정함), 한쪽만 경기 중이거나 다른 경기면 false, 같은 경기면 같은 팀일 때 true.
- `FC/AimTargeting.targetable` + `friendly()`, `FC/PartyHudClient.member(UUID)`, `FC/KyudichiClient.inMatch()/teammate(UUID)`: 클라 조준 보정(대상 스냅)에서 파티원(경기 중에는 같은 팀) 제외.

**적용 범위 확인:** 회복·버프·보호막·정화·체력 나누기·맹세·위로의 빛(AllyMech 전부 `alliesNear/ally`), 회복 투사체(BoltMech `ally=true`), 회복 소환수(SummonMech), 무지개 회복, 연쇄 번개(enemiesNear), 예고 장판 넉백(TelegraphMech: enemy), 돌벽 밀어내기·투사체 차단(ZoneMech barrier: enemy), 원소 표식(Reactions.hit 입구 + forceMark/spread의 enemy), 서버 마법 대상 레이(`canTarget`=enemy라서 파티원은 레이가 통과).

**동작 변화:** 파티 밖 유저에게 힐·버프가 더 이상 안 들어감(PvE 서버에서 모르는 사람에게 힐 주던 것도 막힘 — 결정 7 그대로). 파티원은 PvP 구역에서도 서로 마법 피해를 받지 않음. 몹이 시전자인 경우(몹 스킬)는 변화 없음.

**테스트:** `AllyResolverTest`(규칙표, 파티원=아군·적 아님, 낯선 플레이어=아군 아님·적 가능, 몹 시전자 무관). 기존 `TargetsTest`는 plugin.allies()가 null(목)이라 그대로 통과해야 함.

**실게임 확인:** ① 파티원 A·B, 비파티 C. PvP 구역에서 A의 범위 마법 → B 무피해·무표식·무넉백, C 피해. ② A의 회복 장판/무지개 → B만 회복, C 안 됨(PvP·비PvP 구역 모두). ③ 큐디치 랜덤 매칭: 같은 팀(파티 다름)에게 회복 들어감, 상대 팀(같은 파티였더라도)에게 회복 안 들어가고 피해 들어감. 모래 회오리·불씨 확산이 같은 팀에 안 걸림. ④ 클라: 파티원 위에 커서를 올려도 대상 고정이 안 됨.

## 2. A04 chabolt 균등 분할 + 실제 충돌 모양

- `MM/Geometry.steps(distance)`(신규, 0.4 이하 같은 간격), `MM/BoltMech.advance`: 한 틱 이동량 = 설정 speed(빗자루 상속 포함). 지연 보정 구간(`leadLeft`)도 같은 방식.
- 블록 판정: 칸 단위 `isPassable()` → 구간마다 `world.rayTraceBlocks(prev, along, step, NEVER, ignorePassable=true)`. 맞으면 기존 `breaks`/`turnBack` 처리, 멈춘 위치를 블록 표면(0.05 앞)으로 옮김(끝 폭발·VFX 위치).
- `MM/MobShotMech`: 같은 충돌 판정(이동 분할은 원래 정확했음).
- **동작 변화:** 0.4로 나누어떨어지지 않는 속도의 bolt가 설정대로 느려짐(예: mountain_back 0.8→0.5 b/t, tidal_wave 1.2→0.9). 반블록·계단·울타리·판유리·카펫 위 빈 공간을 지나가는 투사체가 이제 통과함.
- 테스트: `BoltStepTest`. 실게임: 반블록 위로 쏘기, 울타리 너머로 쏘기, 판유리 칸 통과, 벽에 닿은 끝 폭발 위치, mountain_back 굴러가는 속도.

## 3. A16 메테오 서버 권위 착탄 (오너 결정 3)

- `MM/MeteorMech.java`(신규, 메커닉 `chameteor`), `MM/MeteorFall.java`(순수 상태: CHARGING→FALLING→LANDED 1회 / CANCELLED).
  - 시전 때 착지점 고정: 조준점 아래 20블록 안의 첫 충돌면, 없으면 조준점 그대로(공중).
  - 착지 틱 = charge(68) + ceil(|sky|/speed) = 68 + ceil(30.76/0.6) = **120틱**. 그 틱에 딱 한 번 chahit과 같은 판정(r=4, max=12, knock, up, ignite — 수치 그대로)을 착지점 중심으로 적용, `MagicImpactEvent(HIT)` 포함. 이어서 VFX `external:impact`.
  - 경로의 블록은 보지 않음(지붕·나무 관통).
  - 시전자 로그아웃·사망·월드 이동·플러그인 종료(`Ticker.close`→`stop`)면 피해 없이 VFX `external:cancel`.
  - 시전 직후 VFX에 `external:land_at`(착지점)을 보내 표식·운석이 서버 착지점을 쓰게 함.
- 설정: `tools/spells.py`(원본) → `mythicmobs/Skills/chacademi_spells.yml`, 운영 overlay `CHA_falling_star` 교체. 파라미터 `charge/speed/skyx/skyy/skyz`는 `spell-bindings.yml` 운석 단계의 `delay-ticks/speed/offset`과 **같아야 함**(계약 테스트가 고정).
- PortableVFX `PV/CatalogVisualEngine.java`:
  - `moveMeteor`: 블록 조기 폭발 제거(관통). 바인딩의 impact 단계가 `external:impact`면 도착해도 착탄 연출을 내지 않고 몸체만 끝냄(`serverImpact(plan)`).
  - `trigger(owner, spell, "external:land_at", point)`: targeted-meteor 시전의 `c.aim`을 서버 착지점으로.
  - `external:impact`가 오면 아직 떨어지는 몸체를 끝내고 impact·afterglow 시작.
  - 지면이 없을 때 시전 거부 대신 조준점(공중) 착지(서버와 같은 규칙).
- 다른 낙하형 마법: 소스에서 `targeted-meteor`/낙하 연출 + `delay N + chahit` 조합은 falling_star 하나뿐이었음(void_maw는 바닥의 입, frost_rupture는 땅에서 솟는 가시라 해당 없음).
- 테스트: `MeteorFallTest`(착지 틱 120에 정확히 1회, 취소 후 착지 없음, 소스·운영 설정과 바인딩 일치, impact 계열 external:impact), `CatalogMeteorCastTest` 갱신(자체 착탄 없음 → 서버 이벤트로 1회, land_at으로 표식·운석 위치 이동), `ExpansionSpellsTest`에 chameteor 추가.
- 실게임(GPT 최소검증): 지형 위·나무 아래·지붕 아래 대상, 허공 조준(아래 20블록 안 지면/없음), 시전 후 대상 이동(착지점 고정), 시전 중 로그아웃·사망, 관찰자 여러 명이 같은 틱에 폭발을 보는지. **피해 시점이 25틱→120틱으로 늦어짐**(결정 3 의도).

## 4. HIT 취소 계약

- `gimmick-paper/.../MagicHook.java`: HIT는 간격 검사를 통과하고 `puzzles().trigger(...)`가 true일 때만 취소(`consumesHit`). BOLT 처리(`magic-blocks-bolts`)는 그대로.
- `MM/MagicImpactEvent` javadoc: HIT 취소 = 기믹 입력으로 소비(엔티티 피해 영향 없음, 대상 없어도 시전 성공·환불 없음) / BOLT 취소 = 그 자리에서 끝(끝 폭발 포함) / ZONE 취소 무시.
- `MM/HitMech`: `result(hitsEmpty, consumed)` 분리(동작 동일), Passives·SummonMech는 결과 미사용 주석.
- 테스트: `HitContractTest`. gimmick 쪽 단위 테스트는 테스트 classpath에 magic-mythic이 없어 추가하지 않았음. 실게임: 퍼즐 기믹 옆 빈 공간에 chahit 두 번 연속(`magic-hit-gap-ticks` 안) → 첫 번째만 퍼즐 입력·HIT 취소, 두 번째는 취소되지 않음(예전에는 입력 없이도 취소됨). 기믹 옆 몹은 두 번 다 피해.

## 5. 조준: 클라 조준점 사용 + 서버 검증 (C04)

- 프로토콜 `magic-codex-protocol/.../CastAimProtocol.java`(신규, C2S `magiccodex:cast_aim`): 마법 id, 조준점 xyz, 대상 UUID(선택), 공중점 여부. 31~110바이트, 엄격 디코드.
- 클라 `FC/CastAimClient.java`(신규, `MagicCodexClient`에서 초기화), `FC/AimTargeting.confirm`: look 패킷 → (큐디치 Aim) → cast_aim → 시전 → **`CrosshairAim.restore`로 서버 머리 방향 복원**. 대상이 있으면 점을 대상 상자 안으로 당겨 보냄(ModelEngine 히트박스).
- 서버 `BR/SpellRuntime.prepare`: 같은 마법·10틱 이내 조준점을 1회 사용. 검증(`BR/AimCheck.java` 순수 함수):
  - 사거리: 3D(바닥형은 수평 + 아래 34블록) + 여유(지상 2, 공중·비행·탑승 6블록)
  - 방향: 서버 시선과 12도 이내, 또는 시선에서 옆으로 여유 이내
  - 시야: `rayTraceBlocks`가 점 1블록(공중 2블록) 앞까지 막히지 않음
  - 대상: 같은 월드, 살아 있음, `canTarget`(아군 제외 포함), 점이 대상 상자(+0.6+여유) 안
  - 실패하거나 옛 클라면 **기존 서버 레이 그대로**.
- 큐디치 공중 조준점(`MagicAimPointEvent`)에도 같은 사거리·방향·시야 검사 적용(예전: 사거리만).
- 화상 대상 마법: 클라가 `isOnFire()`만 보던 것을 상태 아이콘 BURN도 인정(`AimTargeting.burning`). 서버는 기존대로 `hasStatus(burn)`로 검증.
- 테스트: `CastAimTest`(프로토콜 왕복·잘못된 입력, 사거리·방향·시야·대상 판정).
- 실게임: 빗자루로 빠르게 날며 몹/플레이어에 UNIT·직선 마법(이전보다 덜 빗나가야 함), 어깨 시점으로 벽 모서리 너머 조준, 시전 후 다른 플레이어가 보는 내 머리 방향이 바로 돌아오는지, 스타 퓨리(화상 대상).

## 6. 바닥 범위 마법 공중 시전 (1-6 #6)

- `spell-runtime.yml` `ground: true`(AIM + 클라 area/point/wall과 같은 목록, spells.py 생성), `BR/SpellRules.Rule.ground`.
- 서버: 바닥형은 수평 사거리로 검증, 대상이 없으면 시전점을 아래 20블록 안 지면으로 내림(`SpellRuntime.groundBelow`), 없으면 조준 높이에 공중 장판. 큐디치 공중점은 내리지 않음.
- 클라: `groundUnder` 수평 거리 판정(`AimMath.horizontalWithin`), 탐색 깊이 48→32, 지면이 없으면 "바닥을 조준해 주세요" 거절 대신 조준점(공중) 사용.
- 테스트: `AimRangeContractTest.groundFlagMatchesGroundedPreviewShapes`.
- 알려진 작은 차이: 바닥형 마법을 **벽 옆면**에 조준하면 서버가 그 점을 아래 바닥으로 내릴 수 있음(미리보기는 벽면). 단순화를 위해 그대로 둠.

## 7. 서버 부담 (04-mobs D1/D2)

- `BR/TargetHudBridge.scanBosses`: 1초마다 모든 월드 전체 생물 → HUD 채널을 쓰는 플레이어 주변 64블록(`getNearbyLivingEntities`)만, 중복 제거. 보스 판정(Mythic·테이밍 리플렉션)은 개체별 캐시(사망·퇴장·종료 시 제거, 4096 넘으면 비움, 갓 스폰된 40틱 이하 개체의 "보스 아님"은 캐시 안 함).
- `look()`: 조준 레이(ModelEngine 서브 히트박스 전수 검사)를 조준·위치·시선이 그대로면 최대 10틱 재사용. 최대 0.5초 늦게 대상이 바뀔 수 있음.
- `TargetHudAimTest` 하네스에 `looked` 필드 주입 추가.
- 실게임: 보스 근처 HUD 상단 보스 바, 커서 대상 바 갱신, `/서버관리 상태` 틱 시간 비교.

---

## 못 한 것·보류

- VFX 투사체와 판정 bolt 단일화(94개 range 불일치, L): 범위 밖.
- 화상·화염 아이콘 구분: 오너 결정 9로 유지.
- gimmick `MagicHook` 단위 테스트: gimmick 테스트 classpath에 magic-mythic이 없어 생략(빌드 설정 변경 회피).
- ModelEngine 서브 히트박스 검사 자체의 사전 거리 필터: 거대 보스 모델 판정이 깨질 위험이 있어 TamingBridge는 손대지 않고 HUD 쪽 재사용으로만 줄였음.
- 운영 overlay 전체 재생성(C05)은 B6 묶음.

## 컴파일 위험 지점 (확신 낮은 API)

- `Bukkit.getEntity(UUID)`(SpellRuntime 대상 조회), `World.getNearbyLivingEntities(Location,double)`(Paper), `Entity.getTicksLived()` — 모두 1.21.4 Paper에 있다고 보지만 빌드로 확인 필요.
- `Player.isOnGround()` deprecated 경고(SuppressWarnings 처리).
- Fabric: `Entity.getUuid()`, `Entity.getId()`, `Box.minX..`, `MathHelper.clamp(double,..)`, 패키지 전용 `record Payload implements CustomPayload` 등록.
- mockito: `AllyResolverTest`가 final 클래스 `AllyResolver`를 목으로 만듦(기존 테스트도 final `MagicMythicPlugin`을 목으로 쓰므로 inline mock maker 전제).
