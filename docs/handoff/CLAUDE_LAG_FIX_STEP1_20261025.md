# 서버 렉 1단계 수정 (Claude 2026-10-25, 미빌드)

- **브랜치:** `claude/lag-fix-step1-20261025`
- **기준:** `claude/travel-map-20261023` @ `af3bffa3`
- **범위:** GPT 검토(2026-10-25)를 통과한 항목만 반영했다. 근거 문서는 프로젝트 문서 `claude/server-lag-20261025.md`.
- **빌드:** 이 환경에서는 Gradle 배포판을 받을 수 없어 빌드·테스트를 돌리지 못했다. 빌드와 테스트는 GPT 쪽에서 해 줘야 한다.

## 반영한 것

| 검토 번호 | 파일 | 내용 | 동작 차이 |
|---|---|---|---|
| 1 | magic-mythic `Guards.java` `active()`, `deflect()` | 가드 맵 → 타입 → 만료 확인을 먼저 하고, 살아 있는 가드가 있을 때만 WorldGuard를 조회한다. deflect는 가드가 없으면 `enemy()`/`harm()` 전에 바로 return | 만료된 가드가 WG 거부 구역에서도 정리되어, 정리가 조금 빨라진다 |
| 1 | magic-mythic `Passives.java` `dealt()` | owner null 확인은 그대로 둔다. 패시브 두 개의 on 여부를 한 번만 계산하고, 둘 다 꺼져 있으면 `enemy()` 전에 return한다. 계산한 boolean은 아래에서 재사용하고, direct 판정은 기존 그대로 | 없음 |
| 3 | creature-spawns `WildlifePlugin` regenTick (40,40)→(43,40), cleanup (200,200)→(211,200). magic-codex `TargetHudBridge` (5,5)→(7,5) | **타이밍 조정.** 첫 실행뿐 아니라 이후 실행 위상도 바뀐다 | 세 작업끼리는 같은 틱에 겹치지 않지만, 다른 플러그인 작업과의 겹침까지 보장하지는 않는다 |
| 4 | magic-codex `ShinyBridge.track()` | 변색종일 때만 true를 보내고, 일반 몹에는 보내지 않는다. `mark(false)`가 명시적으로 false를 보내는 경로는 유지 | 클라는 모르는 개체를 일반으로 본다. UUID와 엔티티 ID를 검사하고 unload·JOIN·DISCONNECT 때 정리한다 |
| 6 | magic-codex `PlayerStateBridge.apply()` 끝 | `p.saveData()` 한 줄만 제거 | 접속 직후 로컬 playerdata를 즉시 저장하지 않는다. 다음 자동저장이나 퇴장 때 저장된다. **우편·상점·낚시·NPC 거래·퀘스트의 saveData는 디스크 영수증용이라 건드리지 않았다** |
| 8 | magic-mythic `TempBlocks.placeAll(Collection,Material)`, `removeAll(Collection)`. `WorldMech.platform()`/`clearPlatform()` | 공중 발판 9칸을 저널 1회 저장으로 처리한다. 순서는 원본 수집 → 저널 저장 성공 → 블록 설치. 저장에 실패하면 아무것도 설치하지 않는다. 복구하지 못한 블록(월드 미로드)의 기록은 남긴다. 인자 없는 `removeAll()`은 전체 종료 복구용으로 그대로 둔다 | 없음 |
| 9 | magic-mythic `Links.java` | snapshot/tryConsume/add/setRegenerationMultiplier Method를 서비스당 한 번만 찾는다. current/maximum은 실제 Snapshot 클래스 기준으로 캐시하고, 클래스가 바뀌면 다시 찾는다. clearCooldown은 선택 API라 없으면 null로 기억만 하고 마나 연결 전체는 정상 동작한다. `resetMana()`는 Method와 Snapshot 클래스 참조를 모두 비운다 | 없음 |

테스트: `RecoveryTest`에 두 개를 추가했다.
- 배치 설치: 저장 전에는 설치하지 않음, 막힌 칸은 건너뜀, 월드 미로드 시 기록 유지 후 로드되면 복구.
- 배치 저장 실패: 아무것도 설치하지 않음.

## 2번 추가 (2026-10-25, 오너: "원래대로 돌아오게만", chase-max 96 유지)

`creature-spawns-paper` `WildCombat` (+ `WildlifePlugin.onDisable`). GPT 검토 조건을 반영했다.

- **기록:** 추적 거리를 늘릴 때 몹 PDC에 원래 값(`follow_original`)과 늘린 값(`follow_raised`)을 남기고, 메모리 `raised` 맵에 등록한다. 이미 늘린 몹을 더 늘려도 원래 값은 그대로 둔다. 단, 지금 값이 우리가 늘린 값과 다르면 외부에서 바꾼 것으로 보고 지금 값을 새 원래 값으로 삼는다.
- **복구 시점:** `regenTick` 맨 앞의 `restorePass()`에서 전투가 끝난(`out-of-combat-seconds` 경과) 몹을 되돌린다. 회복 제외 조건(regen 0·만피·AI 없음·무적)이나 기능 꺼짐보다 먼저 돌고, 기능이 꺼져 있으면 전투 중이어도 전부 되돌린다.
- **덮어쓰기 방지:** 현재 base 값이 우리가 늘린 값과 같을 때만 원래 값으로 돌린다. 외부 플러그인이 바꿨으면 기록만 지우고 값은 건드리지 않는다.
- **`raised` 맵 관리:** 4096 정리 대상이 아니다. 복구·사망·무효 개체일 때만 빠진다.
- **언로드·종료·크래시:** `EntitiesUnloadEvent` 때 복구하고, `onDisable` 때 `restoreAll()`로 전부 복구한다. 크래시로 기록이 남은 채 다시 로드된 몹은 `EntitiesLoadEvent`에서 바로 복구한다.
- **강제 타깃(setTarget):** 그대로 둔다. 추적 거리가 돌아오면 바닐라 규칙대로 타깃이 풀린다.
- **진단:** `/야생 진단`에 `추적 늘림 중=N 추적 복구=M`을 추가했다.

## 반영하지 않은 것 (검토 결과)
- **5. 이동 발판 markDirty:** 유지. 이 함수는 boolean만 바꾸고 실제 저장은 40틱마다라서, 고칠 때 얻는 이득보다 복구 쪽 위험이 크다.
- **7. 빗자루 퇴장 저장 제거:** 보류. 비정상 종료 시 잃는 범위가 커진다. 비동기화는 불변 스냅샷·단일 writer·dirty 세대 관리·종료 flush를 갖춰 별도 작업으로 한다.

## 배포

- **대상 jar:** MagicMythic(학교·야생), creature-spawns(야생), MagicCodexBridge(학교·야생).
- **함께 배포할 것:** 없음. 클라 모드·프로토콜·설정 파일 변경이 없다.
- 변색종 패킷은 서버 쪽 송신만 줄였다. 클라 기본값이 "일반"이라 현재 클라와 그대로 호환된다.

## Codex integration correction (2026-10-25)
- Preserve original/raised PDC values and the raised registry until attribute restoration succeeds. Attribute/base lookup and setter exceptions remain retryable on the next pass; load-discovered records are registered before attempting restoration.
- A confirmed external base-value change still abandons ownership without overwriting it. Temporarily unavailable attributes keep their records.
- Isolate unload/load/disable failures per entity, retain failed records, and preserve chase-max96 and existing target handling.
- WildFollowRestoreTest covers retries after lookup/setter exceptions, missing attribute, external changes, load-discovered failures and shutdown failures.
