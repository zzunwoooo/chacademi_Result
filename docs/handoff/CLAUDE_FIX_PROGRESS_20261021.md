# CLAUDE_FIX_PROGRESS_20261021 — 진행·보상·큐디치·채팅 묶음 수정

- 브랜치: `claude/fix-progress-20261021` (base `0cd1454`)
- 근거: `claude-reply-to-gpt-20261021.md` 부록 3 (A05 / A06 / A09 / A15), `08-pvp-dungeon.md` (큐디치 상태 머신·성능·DB), `06-economy-social.md` (C02 / C12)
- **빌드 미검증: 문법 검사(parsecheck)만 통과.** Gradle 빌드·테스트는 이 환경에서 돌릴 수 없었습니다. GPT 쪽에서 아래 테스트를 꼭 돌려 주세요.

---

## 1. A09 큐디치·결투 결과 저장 — match-ID 멱등 outbox

**고친 것**
- `Results.finish`: 레이팅 반영 경기의 결과(mode·경기장·점수·승자·사유·팀 이름·delta 전부)를 **DB에 쓰기 전에** `plugins/ChacaKyudichi/outbox/<경기ID>.yml` 로 원자 저장(임시 파일 → move). DB가 "기록됨"이라고 답한 뒤에만 파일 삭제.
- `RatingStore.record`: 트랜잭션 첫 단계에서 `tMatch` 에 같은 경기 ID가 있으면 아무것도 다시 쓰지 않고 저장된 행(레이팅은 `rating_after`)을 읽어 **성공으로** 돌려줌 → 재시도해도 이중 반영 없음, "PK 충돌로 영원히 실패"도 없음.
- `refreshChallengersNow` 를 commit 뒤 별도 try 로 분리: 실패해도 결과는 성공(경고 로그만).
- 실패 시: 선수에게 "경기 결과를 저장하는 중이에요 / 잠시 뒤 자동으로 반영돼요" 알림, outbox 파일 유지. 플러그인 시작 5초 뒤 + 1분마다 outbox 를 다시 기록(진행 중인 건 중복 제출 안 함).
- 결과 화면: 기록 성공(또는 이미 기록됨) 콜백에서 표시. 나중에 재시도로 성공하면 그때 이 서버에 남아 있는 선수에게 표시.

**바뀐 파일**: `kyudichi-paper/.../RatingStore.java`, `Results.java`, `KyudichiPlugin.java`, 새 `ResultOutbox.java`, 새 테스트 `ResultOutboxTest.java`

**배포 주의**: `plugins/ChacaKyudichi/outbox/` 가 생깁니다. 읽지 못하는 파일은 콘솔 SEVERE 로그 후 그대로 둡니다(수동 확인용). 서버 종료 중에 진행되던 기록은 다음 시작 때 "이미 기록됨"으로 처리되고 파일만 지워집니다.

**테스트**
- 단위: `ResultOutboxTest` (직렬화 왕복, 저장·목록·삭제).
- 수동(SQLite 또는 MariaDB): 같은 경기 ID로 `record` 2회 → 두 번째도 non-null, 레이팅 1회만 반영. commit 뒤 챌린저 SELECT 실패 주입 → non-null.
- 실게임: 테스트 경기 아닌 매칭 경기 1판 → 결과 창 표시, `outbox/` 비어 있음. DB를 잠시 끊고 1판 → "저장하는 중" 알림 + 파일 남음 → DB 복구 후 1분 내 파일 삭제·전적 1회 반영.

## 2. 큐디치 상태 머신

**고친 것**
- **60초 지나 재접속**: `Matches.joined` → `rejoin` 실패 + 이탈 처리된 선수면 `Match.lateReturn`: 보스바 숨김·복원(`restore`)·알림("경기에 다시 들어갈 수 없어요") 후 `link().done()` 으로 원래 서버로. `Link.done` 은 경기 목록에 남아 있어도 이탈 처리된 사람은 막지 않음(`Match.playing`).
- **양 팀 모두 이탈**: `reconnectTimeouts` 에서 전원 이탈이면 `end(null, VOID)` → 기존 VOID 규칙대로 기록·감점 없음 (예전: A팀 먼저 검사해 B팀 승리 + 양쪽 탈주 감점).
- **한 번도 오지 못한 선수**: `MatchPlayer.neverArrived` (addAbsent 에서 true, rejoin 에서 false). `Results` 에서 이 선수는 delta 를 만들지 않음 → 탈주 감점·전적 기록 없음. 결과 화면(접속해 있으면)은 그대로.
- **원래 서버 복귀 실패** (Velocity `KyudichiQueue.sendHome`): 원래 서버 → 2초 뒤 한 번 더 → `return-fallback-server`(기본 school) 순서로 시도. 다 실패하면 기존 안내.
- **결정 10 정리**: 안 쓰는 `match.flight-fall-cooldown-seconds`(config·`Rules.flightFallLockTicks`), `MatchPlayer.mounted/falling/flightLockUntil` 삭제. 매달림(broomHangMode)은 그대로.

**바뀐 파일**: `Match.java`, `Matches.java`, `MatchPlayer.java`, `Link.java`, `Results.java`, `Rules.java`, `config.yml`, `chacademi-party-velocity/.../KyudichiQueue.java`

**실게임 확인**
- 매칭 경기 중 한 명 접속 끊고 70초 뒤 접속 → 경기장에 머물지 않고 6초 안에 원래 서버로, 그 선수는 결과에 탈주로 기록.
- 3:3 경기에서 6명 모두 끊기 → 60초 뒤 경기 무효(로그 `사유=VOID`), 전적·레이팅 변화 없음.
- 한 명이 경기장 이동 실패(예: 이동 직전 접속 끊기) 후 끝까지 안 옴 → 경기 끝나고 그 사람 leaves·losses 증가 없음.
- 원래 서버를 잠시 내린 상태에서 경기 종료 → 2초 뒤 재시도 후 school 로 이동.

## 3. 큐디치 성능

- **공 엔티티 순간이동**: `BallVisual.Throttle` — 시뮬레이션은 매 틱 그대로, 엔티티 이동만 2틱마다(`MOVE_EVERY_TICKS`), 0.01블록 미만 움직임은 생략, 8블록 넘는 점프(라인업 리셋)는 즉시. 아이템 디스플레이 `teleportDuration` 1→2 로 보간. ModelEngine 기반(아머스탠드)도 같은 규칙. 모드 클라의 5Hz `Balls` 보간은 영향 없음. → 공 이동 패킷 약 절반, 정지 중 0.
- **clearCooldown 매 틱**: 리플렉션 Method 는 이미 캐시돼 있었음. 매 호출 정규식 컴파일(`String.matches`)을 `ManaService` 의 정적 `Pattern` 으로 교체(`magic-codex-paper/.../ManaService.java`). 동작 동일.
- **공 물리 `getBlockAt`**: `Ball.solid` 에 `isChunkLoaded` 가드 — 안 불러온 청크는 벽으로 취급(튕김), 동기 청크 로드 없음.

**확인**: 경기 중 공 움직임이 끊겨 보이지 않는지(특히 모드 없는 클라 + 블러저 돌진), 퀘이플 소유 중 손 위치 따라오는지. 끊김이 보이면 `MOVE_EVERY_TICKS` 를 1로 되돌리면 예전과 같음.

## 4. 프록시 레이팅 읽기 (MariaDB 아님 → 조용한 1000점 매칭)

- `KyudichiDb.open`: MariaDB 설정이 없으면 큰 ERROR 배너 로그.
- 새 `rankedStandings`: DB 없음 또는 읽기 실패면 null. `KyudichiQueue.join` 이 이걸 써서 **신청 자체를 거절**("매칭을 지금 신청할 수 없어요 …관리자에게") + ERROR 로그. 금지(ban) 우회·전원 1000점 매칭이 더는 일어나지 않음.
- 로비·랭킹·신청 창 표시는 예전처럼 빈 화면/기본값 (표시만, 매칭 아님).
- **배포 주의**: Velocity `plugins/chacademi-party/kyudichi-database.properties` 가 경기장과 같은 MariaDB가 아니면 이제 큐디치·1:1 신청이 막힙니다(의도). 배포 전 확인.

## 5. A06 마법 발견 친구 신호 (오너 결정 4)

- Bridge `SocialBridge`: `friend.made +1` 신호 삭제. 친구 목록을 읽거나 바꿀 때마다(접속 로드, 추가·수락·삭제, 다른 서버 변화를 감지하는 8초 pulse, LIST) **현재 등록된 서로 다른 친구 수**를 `state.friends` 상태값으로 전송. 발견 세션이 아직 안 불러와졌으면 2초 간격 5회 재시도.
  - 끊었다 다시 맺어도 늘지 않음(현재 수). 상대가 오프라인·다른 서버·사망·크리에이티브여도 그 사람이 다음에 목록을 불러올 때(접속/서버 이동/pulse) 따라잡음. 같은 값 재전송은 멱등.
  - `friend.added`(첫 친구 영수증) 경로는 그대로.
- MagicDiscovery: `state()` 허용 키·자동완성에 `state.friends` 추가. `guardian_oath` 조건 `"state.friends": 20`. **서버에 이미 있는 discoveries.yml 은 덮어쓰지 않으므로**, `Definitions` 가 예전 키 `friend.made` 를 `state.friends` 로 읽음(파일 수정 없이도 적용).
- 바뀐 파일: `magic-codex-paper/.../SocialBridge.java`, `magic-discovery-paper/.../MagicDiscovery.java`, `Definitions.java`, `discoveries.yml`, 새 테스트 `FriendStateTest.java`
- 기존 `friend.made` 진행도는 무시됨(새 기준은 현재 친구 수).
- 실게임: 친구 19명 → 20번째 수락 → 수호자의 서약 습득. 오프라인 상대가 수락됨 → 상대 접속 시 그 사람 친구 수 반영. 1명 끊고 다시 맺기 → 수 그대로.

## 6. A05 방구경 친구 검사

- `HousingCommands` `/방구경`: `visit-command` 가 더는 친구 검사를 건너뛰지 않음. 내 방·OP 는 항상 허용, 그 외는 친구만.
- 친구 정보를 읽지 못하면(null — SQLite `housing.db` 에는 친구 테이블이 없음) 콘솔 경고 + "지금은 친구 정보를 확인할 수 없어요" 로 거절. **하우징이 MagicCodexBridge 와 같은 MariaDB를 써야** 친구 구경이 됩니다.
- `visit-command` 설정은 남겨 둠(주석에 친구 규칙 명시). 현재 코드에는 위치 제한 기능이 없어 이 값은 사실상 효과 없음.
- **인기도(POPULARITY)**: 지시대로 구현하지 않음. 현재 버튼은 "준비 중" 기본 문구로 끝남(사양 미정 — 오너 결정 필요: 하루 1회 투표 등).
- 확인: 친구 아닌 사람 `/방구경 <닉>` → 거절, 친구 → 열림, 자기 이름 → 열림, OP → 열림.

## 7. A15 ChacaNPC 예산 (오너 결정 6)

- `budget.limit-enabled` (기본 **false**, 옵션 유지): false 면 전체·하루 금액 한도로 막지 않음(`allowance()` 무한, 상태 항상 NORMAL). 예약·정산·사용량(비용) 기록은 그대로. true 면 예전 방식.
- **1인 하루 60회**: `cnpc_usage` 의 (오늘, 플레이어) 행을 `INSERT IGNORE`/`INSERT OR IGNORE` 로 만든 뒤 `UPDATE … SET calls=calls+1 WHERE … AND calls<60` 한 문장 → 1행이면 허용. MariaDB·SQLite 모두 원자적, 같은 DB를 쓰는 서버끼리 합산. 되돌리기는 `calls>0` 조건부 -1.
  - `DialogueService`: 횟수 확인이 DB 스레드 왕복이 되어, "생각 중" 표시 뒤 DB 응답을 받고 진행. 한도 도달 → `tired` 고정 대사, DB 오류 → `busy` 고정 대사(+경고 로그). 그사이 기한 초과·대화 종료면 올린 횟수를 돌려줌. 실제로 올라간 횟수만 돌려줌(`TurnState.counted`).
- `start-date` 기본 `2026-10-26`. `/서버관리 AI 예산` 표시를 "금액 한도 없음"에 맞춤.
- 검열·도배 제한·고정 대사 등 기존 기능 그대로.
- 바뀐 파일: `chaca-npc-paper/.../BudgetService.java`, `Storage.java`, `Settings.java`, `DialogueService.java`, `NpcCommands.java`, `config.yml`, 새 테스트 `DailyCallLimitTest.java` (SQLite 파일 하나를 두 Database 가 공유 → 합쳐서 60회)
- **배포 주의**: 운영 `plugins/ChacaNPC/config.yml` 은 덮어쓰지 않으므로 `start-date: "2026-10-26"` 과 (없으면 기본 false라 괜찮지만) `limit-enabled: false` 를 직접 확인. 학교·아레나가 **각자 SQLite** 면 횟수가 서버별(최대 120)이 됩니다 → 같은 MariaDB 권장.
- 확인: 한 계정으로 60회 대화 → 61번째 `tired`. 두 서버에서 번갈아 대화해도 합쳐서 60.

## 8. C02 Velocity 파티 — 아레나

- `party.properties` 새로 만들 때 `trusted-servers=school,wild,arena`, 키가 없을 때 기본값도 같음.
- 전체·기숙사 채팅 서버 이름 하드코딩(`NetworkChat` `Set.of("school","wild","arena")`) 제거 → `chat-servers`(기본 `school,wild,arena`). 채팅 서버는 trusted 이기도 해야 함(아니면 시작 시 경고).
- 채팅 접두 아이콘: `school-server`·`wild-server`(기본 school·wild)로 판별, 그 밖의 채팅 서버는 `[아레나]`.
- 보안 검사(신뢰 서버 + 그 플레이어의 현재 연결로 보낸 것만)는 그대로.
- **배포 주의(필수)**: 기존 `plugins/chacademi-party/party.properties` 는 그대로라 `trusted-servers` 에 아레나 서버 이름을 **직접 추가**해야 합니다(B0 호스트 작업). 아레나 이름이 `arena` 가 아니면 `chat-servers` 에도 그 이름을 넣고, `kyudichi.properties` 의 `arena-server` 와 같게.
- 테스트: 새 `NetworkChatServersTest`, 기존 `NetworkChatTest`(1인자 생성자는 예전 목록 유지).

## 9. C12 통합 채팅(전체·기숙사)이 Paper 채팅 이벤트를 우회

**조사 결과**: 이 저장소에는 Paper 쪽 뮤트·욕설 필터·채팅 로그 플러그인 코드가 없음(ChacaNPC 입력 필터는 NPC 대화 전용). 뮤트는 Velocity 권한 `chacademi.chat.muted` 하나뿐(LuckPerms 필요). 관전자 격리는 `kyudichi-paper GameListener.chat`(AsyncChatEvent) 에만 있음.

**최소 수정(이번)**
- 모든 통합 채팅을 Velocity 로그에 기록: `[통합채팅] [전체] 닉네임 (계정) @서버[ 관전/연습]: 내용`.
- 큐디치 관전자 격리: 경기장 서버에 있지만 매칭 경기 참가자가 아닌 사람(관전·연습)의 통합 채팅은 **경기 중인 선수에게 전달하지 않음**(다른 서버 사람·다른 관전자에게는 감). `KyudichiQueue.arenaBystander/inMatch`.
- Velocity 에 LuckPerms 가 없으면 시작 시 "뮤트 권한이 동작하지 않음" 경고.

**한계(남은 것)**
- 서버 쪽 외부 채팅 플러그인(뮤트·필터)은 여전히 이 경로를 못 봅니다. 근본 해결은 06 감사안대로 클라 채팅 모드가 '전체' 탭을 바닐라 채팅으로 보내고(Paper `AsyncChatEvent` 통과), 서버 간 공유가 필요하면 Paper 플러그인이 이벤트 통과 후 프록시로 넘기는 방식 — 클라 모드·Paper 플러그인 동시 변경이라 이번 범위에서 제외.
- 관전 여부는 프록시가 아는 "매칭 경기 참가자" 기준. 관리자가 경기장 서버에 직접 들어가 `/테스트` 로 만든 경기는 프록시가 모르므로 격리 대상 아님.
- 실게임: 관전자가 전체 탭으로 말함 → 경기 중 선수에게 안 보임, 학교 서버 사람에게는 보임, Velocity 로그에 기록.

---

## 못 한 것 / 보류
- **인기도**: 사양 미정이라 구현 안 함(지시대로). 버튼 숨김도 안 함.
- **RatingStore DB 통합 테스트**: 큐디치 모듈 테스트에 SQLite 드라이버·Bukkit 스케줄러가 없어 단위 테스트 대신 위 수동 절차로 남김.
- **C12 근본 해결**(바닐라 채팅 경유): 위 한계 참고.
- 운영 설정 파일(party.properties, ChacaNPC config.yml, kyudichi-database.properties)은 코드로 바꾸지 않음 — 위 배포 주의 참고.

## GPT 가 돌릴 테스트
- `:kyudichi-paper:test` (새 `ResultOutboxTest`, 기존 테스트)
- `:magic-discovery-paper:test` (새 `FriendStateTest`, `ExpansionDefinitionsTest` 등)
- `:chaca-npc-paper:test` (새 `DailyCallLimitTest`)
- `:chacademi-party-velocity:test` (새 `NetworkChatServersTest`, `NetworkChatTest`, `NetworkChatIntegrationTest`)
- `:magic-codex-paper:test`, `:housing-paper:test` (컴파일 확인)
