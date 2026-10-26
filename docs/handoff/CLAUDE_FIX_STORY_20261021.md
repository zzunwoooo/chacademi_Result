# CLAUDE_FIX_STORY_20261021 — 스토리 묶음 수정 (A07 · A13 · C10 · C11 · D-12 · D-13 · 기숙사분류 · 로컬 호감도 · 아이템 조건)

- 브랜치: `claude/fix-story-20261021` (기준 `7e6ac03` = `claude/story-steps-20261020`, **아직 서버에 켜지지 않은 대기 브랜치**)
- 커밋: `228377f` (스토리 플러그인·모드), `2762dc1` (MagicCodexBridge 기숙사 분류), 이 문서
- **빌드 미검증: 문법 검사(parsecheck)만 통과.** Gradle 빌드·테스트는 이 환경에서 못 돌림. GPT 빌드 때 아래 테스트를 꼭 돌려 주세요.
- 경로 약어: `SP/` = `chacademy-story/plugin/src/main/java/kr/chacademy/storyplugin/`, `SM/` = `chacademy-story/mod/src/main/java/kr/chacademy/`, `MP/` = `magic-codex-paper/src/main/java/school/magiccodex/paper/`

---

## 1. A07 — DIALOGUE_REWARD 재전송 (상승 기류 즉시 습득 · 호감도 −1 반복)

**고친 것**
- 보상 패킷은 **서버 세션에 묶인 것만** 받는다 (`LocalStoryRewards.bound`).
  - 키 `대화.end` / `대화.<결말 장면>`: 서버가 결말까지 확인한 완료(세션 finish + `endingAllowed`)가 **60초 안에** 그 대화로 있어야 함. 완료는 `recentFinish`(메모리, 나가면 지움)에 기록.
  - 그 밖(이벤트, 예 `ch1-2.e_s11`): 그 대화의 세션이 열려 있고 `ready` 상태여야 함. 서버 흐름(dialogue.yml)이 있으면 그 흐름에 있는 이벤트여야 함.
  - 보상 키가 있는 대화(`ch1-2`, `ch1-3`)는 끝 명령이 없어도 서버가 **빈 명령 세션**을 연다 (`requestNicknameDialogue`).
- **플레이어·키당 한 번**: `progress.yml` 플레이어 항목에 `rewards: {키: pending|applied|failed}` 영수증.
  - 순서: 영수증 `pending` → `saveSoon(after)`로 디스크 저장 → 저장 콜백에서 효과 적용 → `applied`/`failed` 저장.
  - 같은 키가 다시 오면 상태와 상관없이 무시. `failed`·`pending`(불확실)은 자동 재시도 없음. `/유저관리 <닉네임> 스토리기록 초기화 <대화|전체> 확인`으로만 지움(지우면 다시 받을 수 있음).
  - 호감도 future 가 예외로 끝나도 `failed` 로 기록 (`whenComplete`).
- `/유저관리 <닉네임> 스토리기록 보기 [대화]`에 영수증 상태 표시.

**파일**: `SP/LocalStoryRewards.java`, `SP/StoryState.java`(PlayerStory.rewards 저장·불러오기·resetLedger), `SP/StoryPlugin.java`(claimLocalReward·applyLocalReward·rewardDone·recentFinish·세션 열기)

**동작 변화**: 클라 파일을 지우고 다시 봐도, 패킷을 다시 보내도 보상은 1회 이하. 세션 없이(개조 클라·예전 모드 DIALOGUE_VIEW 경로) 보낸 보상은 버림. 저장 전에 서버가 멈추면 0회.

**주의**: 예전 모드(세션 채널 없음)로 ch1-2 를 보면 보상(엘레나 −1)을 못 받음 — 의도된 것.

## 2. A13 — 제출(SUBMIT) 저장 먼저 · 시작명령-한번

**제출** (`SP/QuestEngine.java` `fire` → `afterSubmitSaved`)
1. 개수 확인 → `st.submitted=true` 를 **먼저** `quest-progress.yml`에 저장(`saveSoon(after)`), 대기 중 표시 `submitting`.
2. 저장 콜백(메인 스레드)에서 **다시 세고**, 모자라면 `submitted=false` 로 되돌려 저장 + "가져갈 아이템이 부족해요"(알림+삐빅).
3. 충분하면 걷고 `p.saveData()` → 원래 호출(대화/컷신/다음 단계) 이어서 진행.
- 남는 크래시 창: YAML 저장 뒤 ~ 걷기 전 → 아이템은 남고 단계는 진행 (손해는 서버 쪽, 이중 차감 없음). `submitted` 가 저장돼 있으니 재시작 뒤에도 다시 걷지 않음.

**시작명령-한번** (`SP/QuestDefs.java` `Step.startOnce`, `SP/QuestProgress.java` `startedRun`)
- steps.yml 단계 키 `시작명령-한번: true|false`. **생략하면** 명령에 `give·지급·주기·칭호·아이템관리·permission set·parent add·eco ·money·돈·재화` 가 들어 있으면 true, 아니면 false (`QuestDefs.grantLike`).
- true 이면 그 단계 회차(`run`)에 한 번: `startedRun=run` 을 디스크에 저장한 뒤 실행. 재접속·대화 닫기·서버 재시작 모두 다시 실행 안 함. 저장 콜백 때 유저가 나갔거나 대기 중이면 표시를 되돌려 다음에 실행.
- `/유저관리 <닉네임> 스토리진행 다시`(관리자)는 한번 표시를 무시하고 실행.
- 기본 `steps.yml`(플러그인 리소스) ch1-14 에 `시작명령-한번: false` 를 명시하고 머리 주석에 설명 추가.
- `/대화관리 단계 <ID> 정보`에 "(한번)/(반복 가능)" 표시, 분류 시작명령이 한번으로 켜져 있으면 `단계 점검`에 경고.

**운영 주의**: 앞으로 시작명령에 지급 명령을 넣을 때는 반드시 `시작명령-한번: true`(또는 생략 + 위 단어 포함). 생략 판정은 글자 검사라 완벽하지 않음 → 명시 권장.

## 3. C10 — 스토리 막힘

**(a) 단계 모듈 전에 ch1-5 를 끝낸 유저** (`QuestEngine.onJoin`)
- 접속 때 단계 기록이 없고(또는 시작 전) `시작.대화완료`(ch1-5)를 이 서버 `progress.yml` 의 `done` 에 끝낸 기록이 있으면 첫 단계(ch1-6)를 바로 시작 (`StoryPlugin.dialogueDone`). 콘솔에 info 로그.
- **한계**: 서버 흐름(`/대화관리 재생`)으로 본 대화만 `done` 에 남는다. 예전 Skript/로컬 경로로만 ch1-5 를 본 유저는 서버 기록이 없어 자동으로 못 잡음 → `/유저관리 <닉네임> 스토리진행 단계 ch1-6` 으로 옮겨 주세요. (클라 기록은 믿을 수 없어 쓰지 않음)

**(b) ch1-19 컷신 실패 반복** (`QuestEngine.cutsceneFailed`)
- 예전: 컷신 요청이 취소되면 ACTIVE 로 되돌려 대화 ch1-19 부터 약 13초마다 무한 반복.
- 지금: 대화로 되돌리지 않고 **컷신만** 최대 3번(`CUTSCENE_RETRIES`) 다시 요청(각 10초 이상 간격). 그래도 안 되면 SEVERE 로그 + 유저 알림 "장면을 불러오지 못해 건너뛰었어요" + **다음 단계로 진행**. `quest-progress.yml` 의 `skipped-cutscene: 단계:컷신` 에 남고 `/유저관리 <닉네임> 스토리진행 보기`에 ⚠ 로 보임.
- 횟수는 메모리(회차별)라 서버 재시작 때 0부터.

**(c) ch1-14 결정 끝 명령**
- 코드 문제가 아니라 운영 설정 문제: 운영 `plugins/ChacademyStory/dialogues/server_command.yml` 의 `commands:` 아래에 반드시
  ```yaml
  ch1-14:
    - "기숙사분류 결정 {player} {ending}"
  ```
  가 있어야 6문항 결말(p_TD)로 배정이 실행됨. 없으면 6문항을 끝내도 아무 일도 안 일어나고 ch1-14 단계에서 멈춤.
- 코드 보강: `/대화관리 단계 점검`(·`정보`)이 "기숙사분류 시작" 시작명령이 있는 단계에서 `ch1-14` 끝 명령에 `기숙사분류 결정 … {ending}` 이 없으면 경고를 표시.
- `{ending}` 은 서버가 흐름의 끝 장면인지 확인한 뒤에만 들어감(기존). `{player}` 는 계정 이름.

## 4. C11 — 창시자(f-) 연출은 미리 정한 유저만 (`MP/SchoolStore.java`, `MP/SchoolBridge.java`)

- 새 표 `house_presets`(SQLite) / `codex_house_presets`(MariaDB) `(player PK)`. 자동 생성.
- `/유저관리 <닉네임> 기숙사 <기숙사>` = **미리 정하기**: 소속 저장 + 미리 정함 표시를 한 트랜잭션(`presetHouse`).
- `/기숙사분류 시작 <유저>`:
  - 미리 정한 유저 → `ch1-14f-<기숙사>` (창시자)
  - 이미 분류된 일반 유저(발표 중 접속 끊김·대화 중단 뒤 시작명령 재실행) → `ch1-14r-<기숙사>` (일반 결과 발표 다시)
  - 소속 없음 → `ch1-14` 6문항
- **배포 주의**: 이 배포 **전에** `/유저관리 … 기숙사 …` 로 미리 정해 둔 유저는 표시가 없어 `r-` 를 보게 됨 → 배포 뒤 같은 명령을 **한 번 더** 실행해 주세요 (멱등).

## 5. D-13 / D-12

- **D-13**: 권한(`magiccodex.house.*`)·스탯 기숙사로만 소속이 있는 유저는 다시 분류하지 않음. `시작`·`결정` 모두 그 소속을 DB 로 옮기고(`adoptHouse`, 이미 DB 소속이 있으면 그것) `ch1-14r-<기숙사>` 를 띄움. 새로 옮긴 경우에만 `dormitory.assigned` 신호.
- **D-12**: `/유저관리 <닉네임> 기숙사 초기화 확인` (OP, '확인' 없으면 안내만). DB 소속 + 미리 정함 표시를 지움(`clearHouse`, 배정 잠금 행으로 직렬화). 권한/스탯 기숙사가 남아 있으면 알려 줌. **스토리 단계 진행은 건드리지 않음**(필요하면 `/유저관리 <닉네임> 스토리진행 단계 ch1-14`).
  - 지운 뒤 기숙사 점수·기증 기록은 그대로.

## 6. `/기숙사분류` 명령 규칙 (`MP/SchoolBridge.java`)

- 이름은 그대로 둠: steps.yml 시작명령(`기숙사분류 시작 {player}`)과 server_command(`기숙사분류 결정 …`)가 콘솔로 부르기 때문에 대단원 아래로 옮기면 1장 데이터가 깨짐.
- **OP 또는 콘솔만** (`sender.isOp()`; 예전엔 `magiccodex.school.admin` 권한).
- 결과·오류는 허브 알림(`ChacaApi.ok/fail` → 알림 + 실패 삐빅). 허브가 없을 때만 채팅.
- 예외 `getMessage()` 를 유저에게 보내지 않음: 유저가 고칠 수 있는 입력 오류(`SortingInput`)만 그 문구, 나머지는 "명령어 사용에 실패했습니다" + 원인은 콘솔.
- `현황`은 줄 목록(`CodexCommands.lines`)으로.

## 7. 클라 로컬 호감도 제거 (owner: 호감도는 MagicCodex 하나)

- 모드 `SM/story/dialogue/LocalDialogueStore.java`: `affinity()` 삭제, `claimAffinity` 는 "한 번 처리함" 표시(`gain.*`)만. 예전 파일의 `affinity.*` 줄은 무시하고 다음 저장 때 지움.
- 서버 `StoryPlugin.withServerAffinity`: 로컬 대화를 열 때 서버 흐름(`dialogues/<id>/dialogue.yml` 쪽 `npcs`)의 NPC 별 **MagicCodex 점수**를 글자 값 `@affinity:<npc>=<점수>` 로 같이 보냄(3초 제한, 실패하면 점수 없이).
- 모드 `ChacademyCutsceneClient.openLocalDialogue`: 선택지 조건(need)은 `Affinity.fromVars(vars)` 만 사용. 없으면 기본값(25). 글자 바꿔 넣기에서는 `@affinity:` 항목을 뺌.
- 1장은 `need` 를 쓰지 않아서 지금 보이는 변화는 없음. 서버 흐름이 없는 대화는 점수를 받지 못해 기본값으로 판단.
- 예전 모드는 `@affinity:` 를 무시(무해)하고 자기 로컬 값을 계속 씀 → 모드 배포 필요.

## 8. 아이템 조건 0.5초 전체 스캔 (`QuestEngine.currents(…, cached)`)

- 주기 확인(0.5초)·HUD 패킷은 플레이어별 개수 캐시를 씀. 캐시는 인벤토리 이벤트(클릭·드래그·닫기·줍기·버리기·먹기·부서짐·설치·사망)에서 지우고, 이벤트가 없어도 **5초**(`ITEM_RESCAN_MS`)마다 다시 셈 (명령·다른 플러그인 지급 대비).
- 호출 직전(자동·위치)과 NPC 클릭·제출은 항상 새로 셈 → 캐시 때문에 잘못 호출되지 않음.
- 이벤트 핸들러는 맵에서 지우기만 함 (부담 없음).

---

## 9. (후속) 스토리 단계 HUD 패킷 디코더 보호 (`SM/story/quest/QuestPackets.java`, `SafeCodec.java`, `QuestClient.java`)

- 서버→클라 `chacademy:quest_step` / `chacademy:quest_update` 디코더를 `SafeCodec.failSafe` 로 감쌈 (모션 모드 47de688 과 같은 방식, 이 모드 안의 별도 사본).
- 깨진 패킷(모자란 필드·조건 수 초과·잘못된 글자 길이 등): 예외로 접속이 끊기지 않고 남은 바이트를 버린 뒤 `INVALID` 를 돌려줌 → 핸들러가 무시. 종류별 콘솔 경고 1번.
- 뒤에 붙은 모르는 필드(더 새 서버): 버리고 앞부분은 정상 처리, 경고 1번.
- **바이트 형식은 그대로** (플러그인 QuestCodec 변경 없음). 테스트 `QuestSafeCodecTest`.

## GPT 가 돌릴 테스트

```
./gradlew :magic-codex-paper:test                 # 루트 빌드
(cd chacademy-story/plugin && ./gradlew test)     # 스토리 플러그인 (따로 빌드)
(cd chacademy-story/mod && ./gradlew test)        # 스토리 모드 (따로 빌드)
```
(스토리 플러그인·모드는 루트 settings.gradle 에 없어 각 폴더의 빌드로 돌립니다 — 평소 쓰는 방법에 맞춰 주세요)
- 새 테스트: `StoryFix20261021Test`(보상 묶기·영수증 저장/불러오기·초기화, 시작명령-한번 기본값·왕복, startedRun/submitted/skipped 저장), `SchoolHouseFixTest`(미리 정함/일반 분류 구분, 재시작 유지, 권한 소속 adopt, 초기화), 모드 `AffinityVarsTest`, 모드 `QuestSafeCodecTest`(후속).
- 고친 테스트(로컬 호감도 값 검사 → "저장하지 않음" 검사): `LocalDialogueTest`, `RepeatedEndingRewardTest`, `JournalLedgerTest`.

## 실게임 확인 절차

1. **A07**: 개조 없이 ch1-2 를 보고 s11 선택 → 엘레나 −1 한 번, `/유저관리 <닉> 스토리기록 보기 ch1-2` 에 "고정 보상 ch1-2.e_s11: 받음". 같은 대화를 다시 열어 s11 → 변화 없음. 대화 밖에서 보상 패킷만 보내면 콘솔 "서버 대화 세션에 묶여 있지 않아 무시".
2. **제출**: 제출 조건 단계에서 호출 → 아이템이 한 번만 빠짐, 호출 직전에 아이템을 버리면 "가져갈 아이템이 부족해요"·단계 그대로.
3. **시작명령-한번**: 테스트 단계에 `유저관리 {player} 칭호 주기 x` 시작명령 → 재접속·서버 재시작해도 한 번만.
4. **C10(a)**: `quest-progress.yml` 에서 테스트 계정 항목을 지우고(ch1-5 done 이 progress.yml 에 있는 계정) 재접속 → ch1-6 시작 로그·HUD.
5. **C10(b)**: 클라에서 `ch1_ashen_night` 컷신 파일을 빼고 ch1-19 진행 → 컷신 재요청 경고 3번 뒤 SEVERE + 알림 + ch1-20 으로 진행, 대화 ch1-19 가 반복되지 않음.
6. **C11**: 일반 계정이 6문항 → 결과 대화 도중 접속 끊기 → 재접속 → `ch1-14r-<기숙사>` (창시자 대사 아님). `/유저관리 <닉> 기숙사 루미나` 로 미리 정한 계정 → `ch1-14f-lumina`.
7. **D-13**: DB 소속 없이 `magiccodex.house.noxer` 권한만 준 계정 → 6문항 없이 `ch1-14r-noxer`, `/기숙사분류 현황` 인원에 노크세르 +1.
8. **D-12**: `/유저관리 <닉> 기숙사 초기화` → 확인 안내, `… 초기화 확인` → 현황 인원 −1.
9. **/기숙사분류**: OP 가 아닌 계정(권한만 있음) → "관리자(OP)와 콘솔만" 알림+삐빅. 잘못된 입력 → 알림, 채팅 없음.

## 1장 데이터(다른 저장소) 쪽에 필요한 변경 (여기서 직접 고치지 않음)

`chacademi-server` `origin/claude/story-ch1-20261020`:
- `story/ch1/서버/steps.yml` ch1-14 에 `시작명령-한번: false` 를 명시 권장 (생략해도 "기숙사분류 시작"은 지급류가 아니라 false 로 판정되지만, 운영자가 알아보기 쉽게). 머리 주석에 이 문서 2절의 시작명령-한번 설명을 같이 넣어 주세요.
- `story/ch1/서버/server_command_1장_추가분.yml` 의 `ch1-14: "기숙사분류 결정 {player} {ending}"` 은 이미 있음 → 운영 파일에 합쳤는지만 확인 (3절 c).
- ch1-20 의 칭호·권한 지급은 **끝 명령**(세션당 1회 소비)이라 시작명령-한번과 무관. 다만 같은 대화를 다시 보면(allow-replay) 다시 실행되므로 ch1-20 다시 보기는 막아 두세요.

## 못 한 것·판단 보류

- C10(a): 서버 기록(`progress.yml done`)이 없는 기존 유저는 자동으로 못 잡음 (클라 기록은 신뢰 불가). 관리자 명령으로 옮겨야 함. 대량 이동 명령은 만들지 않음.
- A07 `pending` 영수증(저장 뒤 효과 적용 전 서버 멈춤)은 "불확실"로 남기고 자동 재지급하지 않음 — 관리자가 보고 초기화로 판단.
- 시작명령-한번 생략 판정은 단어 검사(휴리스틱). 운영 문서에 명시하도록 안내.
- 컷신 실패 횟수는 메모리 → 서버 재시작마다 다시 3번 시도.
- `/기숙사분류` 를 대단원 아래로 옮기지 않음 (1장 데이터·Skript 대체 명령 호환). 규칙(OP·알림·콘솔 원인)만 맞춤.
- AlchemyBridge·Rooms 등 다른 묶음 파일은 건드리지 않음.

## 컴파일 위험 (확신 낮은 곳)

- `Player.saveData()` (Bukkit API, 같은 저장소 ShopBridge 등에서 사용 중이라 존재는 확인).
- `QuestEngine` 의 새 이벤트 핸들러 9개 (Bukkit 표준 이벤트, getter 반환형은 HumanEntity/LivingEntity/Player → `Entity` 로 받음).
- `StoryPlugin`: `CompletableFuture.whenComplete` 안에서 메인 스레드로 넘기는 람다, `defs.get(..).graph.events()`(패키지 private).
- `SchoolBridge`: `CodexCommands.confirmed(Commands.literal("초기화"), …)` 를 `Commands.literal("기숙사")` 아래 리터럴로 — 같은 갈래의 인자 `<기숙사>` 와 형제. Brigadier 는 리터럴이 맞으면 리터럴을 우선함.
