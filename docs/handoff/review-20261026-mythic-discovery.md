# 코드 리뷰 2026-10-26 — mythic-discovery 영역

범위: `70044a79..c8e27c44` 에서 바뀐 자바 — magic-mythic-paper(MagicProtection/MagicArmor/DiscoveryHooks/Potions/PotionCooldowns/ChaosElements/ChargeInput/TrainingDummies/Nova*/Targets/Reactions/Marks/Guards/StatusEffects 등 36개),
magic-discovery-paper(LifeConditions/NativeConditions/ExpansionConditions/ConditionRules/Definitions/MagicDiscovery/DiscoveryService), creature-spawns-paper(CropAwakening/WildlifePlugin), tornado-event-paper, kyudichi Results, portable-vfx CatalogVisualEngine, 각 ChacaLink,
그리고 magic-mythic-paper/mythicmobs + deployment/operating-config/MythicMobs 의 Mobs/Skills yml.
방법: 변경 diff 전부 읽고 주변 코드 확인. 자바 문법 검사(`javasyntax.sh`, 55개 파일) 통과. Gradle 빌드·실행 검증은 없음.

## 결론

치명·높음 없음 → 고친 것 없음(커밋 없음). 아래는 중간·낮음(보고만).

## 핵심 확인 사항 (문제 없음)

| 항목 | 확인 결과 |
|---|---|
| 발견 카운터 핫 이벤트 (EntityDamage·PlayerMove·BlockBreak·투사체·시전) | 전부 O(1) 맵 조회 + `plugin.signal` → `index.containsKey(key)` 로 즉시 탈출. DB 쓰기 없음(세션 `dirtyKeys` → 주기 저장). 무거운 확인(바이옴·주변·다음 틱)은 `counting(key) && needed(p, spell)` 뒤에만. |
| 번개 "가까이에서 봄" | `LightningStrikeEvent`(WEATHER) 당 한 번, `counting("lightning.witnessed")` 일 때만 그 월드 플레이어를 돌며 32블록(거리²) → needed → underSky. 틱 스캔 없음. |
| 사막/심해 초 세기 | 1초마다(`tick%20`) 접속자 순회, `needed()` 두 개 먼저 보고 바이옴은 필요한 사람만. 잠 안 잔 시간은 1분마다. |
| 쿨 맵 정리 | LifeConditions `quenched/spyglass/clicked` 5분마다 10분 지난 것 삭제 + quit 에서 전부 제거. Replant 플레이어당 32곳 상한. leashed 16 상한. |
| 처치 귀속(한 방 처치 수정) | `MagicProtection.damage` 가 피해 전 `DiscoveryHooks.pre` 로 LAST 기록 → 사망 이벤트가 damage 안에서 떠도 귀속됨. 막히면 `post` 가 이전 기록 복원(같은 시전자·같은 마법일 때만). LAST 4096 상한 + 3초 정리. 이중 집계 없음(EntityDeathEvent 1회, remove). |
| learned.<원소> 재계산 | 세션 캐시 30초(600틱) 또는 습득/관리자 변경 뒤에만 300개 권한 조회. signal 마다 재계산 아님. |
| 포션 쿨 | `PotionCooldowns` 체력·마나 따로, quit 에서 forget, 60초마다 prune. 매 틱 작업 없음. 쿨·봉인·가득 거절 시 아이템 안 씀, 적용 성공 뒤에만 1개 차감(복제·소실 없음). |
| MagicProtection PVP/MOB/EXECUTE/FLAT | `rewrite`(HIGHEST) 는 `hits.peek()` 가 같은 대상·같은 시전자일 때만 방어/보호/저항 0 + 흡수 재계산. MOB 모드는 무적 초기화 안 함(`resetInvulnerability` 가 VANILLA·MOB 제외). 가시 반사 등 다른 대상 이벤트는 건드리지 않음. 피해 나눠 받기(Guards)는 `reducedHitOn` 으로 이중 감소 방지. |
| Nova/Arcana 기존 마법 | ArcanaMech 변경 없음. Nova 는 신규(chanova). 모든 반복은 `Ticker` nova 종류(한도 512, 시전자 퇴장 시 종료). 정적 맵(FORM/LURED/COMBO/COATS/HELD/FLUNG/DOLLS/step/herds/wards…) 모두 quit·종료·만료 정리 있음. |
| hintProgress 비동기 | 메인 스레드 강제(`main()`), 접속자는 세션 값 즉시, 아니면 `store.load` 비동기 → `later()` 로 메인 복귀. 세션 맵을 비동기에서 안 만짐. |
| 몹 스펙 yml | 변경된 Health/Damage: 소환수 30/4, 허수아비 1024/0(피해 0 은 의도). 체력 0 몹 없음. Skills 변경은 범위 숫자·flat 값·주석 번호 정리뿐, `damage{}` 직접 사용 없음(전부 cha* 메커닉 경유 → pvp_scale 은 Mech.traced 에서 변수로 읽음). 저장소 mythicmobs 와 operating-config 동일. |

## 발견 목록

| # | 심각도 | 위치 | 내용 | 제안 |
|---|---|---|---|---|
| 1 | 중간 | `magic-mythic-paper/.../DiscoveryHooks.java:45-49, 103-111` | `kill.one_hit.<원소>` 오판정: `full` 은 마지막 **마법** 타격 시점의 체력 가득 여부만 기록. 시전자가 체력 가득 몹에 약한 마법을 맞힌 뒤 3초 안에 근접(또는 다른 사람이) 마무리하면 한 방 처치로 셈 (크로스 애로우 100회 조건 부풀림). | `Last` 에 기록 틱을 넣고 사망 시 `Bukkit.getCurrentTick()==기록 틱` 일 때만 one_hit 인정 (한 방 처치는 같은 damage 호출 안에서 죽음). |
| 2 | 중간 | `Nova.java:118-136`, `Ticker.java:25-31` | `Nova.later()`(짧은 지연)도 nova 종류 Tick 을 하나씩 써서 `limits.novas`(설정 없음 → 512) 를 같이 소모. 한도에 닿으면 `add` 가 false 를 **조용히** 돌려 지연 타격·VFX·`loop` 의 end 가 빠짐. 특히 `flame_emperor` 는 `FORM.put` 뒤 loop 가 안 붙으면 `flameEnd` 가 안 돌아 그 플레이어는 재로드까지 재시전 불가. | config 에 `limits.novas: 2048` 명시하거나 지연용은 별도 kind("delay")로 분리. `flame_emperor` 는 loop add 실패 시 FORM 되돌리기. |
| 3 | 중간 | `magic-discovery-paper/.../MagicDiscovery.java:252-255` | 조건 충족 뒤 확률 실패마다 `ChacaLink.stat`(리플렉션 `getMethod` + 문자열 연결). 현재 chance<1 인 마법은 `kill.mob.*` 24개(드묾)라 실제 부하 없음. 나중에 `damage.taken` 같은 잦은 키에 chance 를 주면 매 이벤트 리플렉션. | 유지하되 ChacaLink.call 의 Method 캐시 고려. |
| 4 | 낮음 | `Potions.java:kind()`, `StatusEffects.isPotionItem` | 우클릭·섭취 이벤트마다 `Links.customId`(ItemsAdder 리플렉션 `Class.forName`+`getMethod` 2회) 를 마나·체력 두 번. 예전 마나 포션 때도 같은 패턴이라 새 부담은 1배 증가. | `customId` 에 Method 캐시, 또는 PDC 표시 먼저 확인. |
| 5 | 낮음 | `ChargeInput.java:17`, `NovaLife3.State.deaths/herdCooldown` | quit 에서 안 지우는 플레이어당 항목(각 1개). 접속했던 사람 수만큼만 커짐 — 사실상 무해. | quit 핸들러에 remove 한 줄. |
| 6 | 낮음 | `MagicDiscovery.values()`/`Definitions.hasCounts` | signal 마다 스펠별 `requirements().keySet().stream()` 할당. 소량 가비지. | Spell 레코드에 boolean 캐시. |
| 7 | 낮음 | `LifeConditions.close()` | `replant` 는 close 에서 안 비움(플러그인 종료 시 GC 되므로 무해). | 정리 한 줄. |
| 8 | 낮음(설계 메모) | `MagicProtection.mobMagic` | 플레이어 소환수(비플레이어 시전자)가 플레이어를 맞히는 마법도 MOB 경로(마법 방어 적용, PvP 배율 없음). 의도인지 확인만. | 필요하면 소환수 주인 기준으로 PVP 처리. |

## 빌드 없이 확인 못 한 것

- MythicMobs/ModelEngine API 호출 (`type.get().spawn(...)`, `castSkill`, `state{}` 메커닉) 과 `TrainingDummyProtocol`·`MagicCastAttemptEvent` 리플렉션 시그니처는 컴파일/실행으로만 확인 가능.
- `MagicProtection.rewrite` 의 `DamageModifier` 쓰기는 Paper 버전에 따라 `isApplicable` 가 false 일 수 있음(try/catch 로 보호돼 있어 터지진 않음).
