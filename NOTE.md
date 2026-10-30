# feature/affinity-quests-20261030 — 호감도 30 상한 퀘스트 (주요 NPC 23명) + 게시판 숨김

신기능 세션 작업. 빌드·서버 반영은 하지 않았다 → 규칙 세우기 세션이 반영.

## 왜
- 호감도는 30·60·90 에서 멈추고 그 NPC 의 호감도 퀘스트를 깨야 다음 구간이 열린다. 학교 `affinity.yml` 에 `gate-quests` 가 없어서 **모든 NPC 가 30 에서 잠겨 있다**.
- 기능 자체(NpcSocialService gate-quests)는 배포 jar 에 이미 있다 → 설정만 넣으면 동작. 이미 30 에서 멈춘 유저도 그 NPC 와 **대화를 열면** 퀘스트를 자동으로 받는다.
- 마법은 유저들이 이미 다 발견해서, 보상 = **마법의 타로카드 1 + 아티팩트 일반 확정 뽑기권 1** (오너 2026-10-30).

## 바꾼 것
### 설정 — `affinity-quests/school/plugins/MagicCodexBridge/`
- `quests-add-affinity30.yml` — 의뢰 23개 `aff30_<캐릭터ID>`. 학교 `quests.yml` 의 `quests:` 아래에 붙인다.
  - 목표: 야생 커스텀 몹 처치(MYTHIC_KILL, 전부 야생 MythicMobs 에 있는 ID), 바닐라 아이템 제출(SUBMIT), 다른 NPC 방문(NPC, 학교 Citizens 번호).
  - 보상 명령: `iagive {player} item:spell_hint 1 silent`, `iagive {player} item:artifact_ticket_common 1 silent` (ItemsAdder 아이템은 보상 items 칸에 못 넣어서 명령으로).
  - `main: true` = 진행 중 일반 의뢰 3개 제한에 안 걸림 (3개 차 있어도 NPC 가 줄 수 있게).
- `affinity-gate-quests.yml` — `affinity.yml` 의 `gate-quests: {}` 를 이것으로 바꾼다 (23명, 30 만).
- 대상 NPC (게임에 배치된 주요 NPC, 오너): 엘레나·하르바·베르나·세드릭·그레고르·이솔데·로웰·브람·피피·올레·셀마·펠릭스·구스타프·클라라·벤·브루노·힐데·이안·리아·카이·준·보미·루카.

### 코드 — `magic-codex-paper/.../QuestBridge.java` (표시 `// [기능:affinity-quests 시작/끝]`, 4군데)
- 배포 QuestBridge 는 소스와 다르다 (javap 140줄 차이) → **클래스 통째 교체 금지, 표시한 부분만 끼워 넣기**.
  1. 새 메서드 `gateHidden(String id)` + 필드 `gateIds·gateFileStamp·gateCheckedAt`: affinity.yml gate-quests 의 퀘스트 ID 목록 (10초마다 파일 변경 확인).
  2. `request()` action==1 (게시판 수락) 앞: 호감도 퀘스트면 "이 의뢰는 NPC 에게서만 받을 수 있습니다." 로 거절.
  3. `reply()` 받을 수 있음 목록 filter 에 `&&!gateHidden(q.id())`.
  4. `reply()` 기록 루프 조건에 `||gateHidden(e.quest().id())` + 그 안 첫 줄: 받은·깬 호감도 퀘스트를 진행 중·완료 탭에 넣음.
- NPC 가 주는 경로(`acceptFromDialogue`)는 그대로.

## 반영 순서 (규칙 세우기 세션)
1. QuestBridge 표시 부분을 학교·야생1·2·아레나1·2 MagicCodexBridge jar 에 끼워 넣기 (게시판이 있는 서버 전부). → 재시작 때 적용.
2. 학교 `quests.yml` 에 23개 붙이고 `/의뢰관리 reload` (카탈로그는 DB 로 공유).
3. 학교 `affinity.yml` 의 `gate-quests` 교체 → 호감도 설정 다시 읽기 (MagicCodexBridge 새로고침 또는 재시작).
   ⚠ 1번(숨김) 전에 2번을 하면 23개가 게시판 "받을 수 있음"에 모두 보여 누구나 받을 수 있다 → **1번 적용 뒤에 2·3번**.
4. `iagive` 가 콘솔에서 되는지 한 번 확인 (`iagive <닉> item:spell_hint 1`).

## 확인한 것 / 못 한 것
- ✔ 수정한 QuestBridge 를 학교 배포 jar 기준 javac 컴파일.
- ✔ 의뢰 23개를 배포 jar 의 `QuestDefinition`·`Objective` 검사(제목·설명 길이·목표·랭크·인코딩 크기)로 만들어 봄: 23/23 통과, 제출 재료 이름 전부 유효, 몹 ID 전부 야생 MythicMobs 에 있음, gate-quests 23개 모두 실제 퀘스트를 가리킴.
- ✘ 게임 안 확인 안 함. iagive 지급·NPC 방문 목표 진행은 실서버에서 봐야 함.
- 60·90 퀘스트는 아직 없음 (30 을 깨면 60 까지 오르고 다시 멈춤).
