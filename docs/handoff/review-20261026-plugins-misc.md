# 코드 리뷰 2026-10-26 — plugins-misc (70044a79..c8e27c44)

범위: chaca-npc-paper, chaca-portrait-paper, housing-paper, chacademy-story/plugin, chacademy-motion-plugin,
chacademi-party-velocity, ops-dashboard 의 바뀐 Java 전부 + deployment/operating-config/ChacademyStory/steps.yml + deployment/story-dialogues/.
리뷰 기준: 오너 규칙 (1) 서버 과부하 금지 (2) 치명 버그. 코드를 쓴 사람이 아닌 입장에서 읽음.

## 결론

- **치명 / 높음: 없음.** 고친 코드 없음 (커밋은 이 문서뿐).
- 중간 4건, 낮음 8건은 아래 표 — 보고만 (수정 안 함).
- 스토리 steps.yml · 대화 파일 형식 검사: 통과 (아래 "스토리 형식 검사").

## 발견 표

| # | 심각도 | 위치 | 내용 (무엇이 어떻게 되나) | 고칠 방법 | 상태 |
|---|---|---|---|---|---|
| 1 | 중간 | chaca-portrait-paper `net/PortraitChannel.java:98-126` (`other`) | 상대 일러스트 요청은 한 사람 250ms 간격·같은 대상 3초 간격·자리 2개·줄 32명으로 막혀 있지만, 한 사람이 **서로 다른 대상**을 250ms 마다 바꿔 요청하면 `dropOther` 가 앞 요청의 자리를 바로 비워 주므로 DB 읽기(sha → PNG 최대 8MB)가 초당 최대 4번 DB 스레드에 쌓일 수 있음 (앞 읽기는 취소되지 않고 결과만 버림). 실제 PNG 는 수백 KB 라 큰 사고는 아니지만 한 클라가 DB 스레드를 점유할 수 있음. | `OTHER_GAP_MS` 를 1000 정도로 올리거나, DB 읽기 중인 자리는 `dropOther` 로 비우지 말고 완료 때 새 요청으로 이어 가기. | 보고 |
| 2 | 중간 | ops-dashboard `AuditSearch.java:56-79` | 기록 검색이 `LIKE '%글자%'` 로 공유 MariaDB(게임 서버가 쓰는 DB) audit 표를 풀스캔. 쿼리 타임아웃 10초·LIMIT 은 있지만 표가 커지면 검색 한 번이 게임 서버 DB 를 몇 초 느리게 할 수 있음 (운영자만 누름). | 기간(from/to) 없이 글자 검색은 거부하거나 기본 기간 7일 강제, time 컬럼 인덱스 확인. | 보고 |
| 3 | 중간 | ops-dashboard `Auth.java:26,53` | 로그인 실패 기록 `failures` 가 IP 마다 쌓이고 성공할 때만 지워짐. 대시보드가 외부에 열려 있으면 스캐너 IP 수만큼 메모리가 늘어남 (항목당 작음). | `login` 안에서 `failures.values().removeIf(lockedUntil·firstAt 이 오래됨)` 한 줄 추가. | 보고 |
| 4 | 중간 | ops-dashboard 전반 (`Web.java`, `Control.java`) | 콘솔 명령·시작/정지가 비밀번호 1개+쿠키로 열림. TLS 없이(`tls.keystore` 비움) 외부망에 열면 비밀번호·쿠키가 평문. 코드 문제가 아니라 운영 주의. | 외부 접속이면 TLS 켜기 또는 localhost/VPN 만 바인드. | 보고(운영 메모) |
| 5 | 낮음 | chacademy-story `QuestEngine.java:202,243` | `transferAt` (서버이동 알림 10초 간격) 이 접속 종료 때 지워지지 않음. 지금은 `서버이동:` 단계가 없어 실제로는 안 쌓임. | `onQuit` 에 `transferAt.remove(uuid)` 추가. | 보고 |
| 6 | 낮음 | chaca-npc-paper `cmd/NpcCommands.java:163-169` (`장소관리 전부삭제`) | 장소마다 `removePlace` → `save()` 라 장소 N개면 메인 스레드에서 파일 저장 N번. 관리자 명령·장소 수 적어 체감 없음. | 전부 지운 뒤 한 번만 저장하는 `removeAll()` 추가. | 보고 |
| 7 | 낮음 | chaca-npc-paper `DialogueService.java:166,990` (`stat`) | 대화 시작·말 한 번마다 Bridge `stat` 을 리플렉션으로 호출. 확인 결과 Bridge 쪽은 `AuditStats.add` → ConcurrentHashMap 더하기만(DB 는 5분 주기), 예외는 모두 삼킴, 메인 스레드(`plugin.sync` 안)에서만 호출됨. 비용 문제 없음. | — | 확인만 |
| 8 | 낮음 | chaca-npc-paper `DialogueService.java:1117-1122` (`spellHint` 폴백) | 메인 스레드에서 Bridge `spellHint` 호출 — Bridge 쪽은 메모리 맵 조회(`SpellHints.line`)와 문자열 조합뿐, IO 없음. 힌트 고를 때 한 번. 문제 없음. | — | 확인만 |
| 9 | 낮음 | chaca-portrait-paper `config.yml` `queue.workers: 12` (이번 범위 밖, 이전 커밋) | 동시 12개 OpenAI 이미지 호출. 메모리는 응답 PNG 몇 MB × 12 수준이라 괜찮고, CPU 배경 제거는 1칸 고정. API 분당 한도(이미지 모델 RPM)에 걸리면 `CircuitBreaker`/`AutoBackoff` 가 줄여 줌 — 오픈 당일 60명 몰릴 때 429 로그만 지켜볼 것. | 429 가 잦으면 8 로. | 보고 |
| 10 | 낮음 | housing-paper `Showcases.java` | 방 열 때마다 DB 읽기 1번(`showcase`), 주인이 같은 서버면 쓰기 1번(값이 바뀐 때만). 클릭마다가 아니라 "방 입장"마다라 괜찮음. 패킷은 빗자루 id·이름 64자 + 아티팩트 8개×64자 상한 → 1KB 미만. `written` 은 접속 종료 때 지움. | — | 확인만 |
| 11 | 낮음 | chacademi-party-velocity `TutorialRouter.java:48-51` | 주석은 기본값 `tutorial` 이라 하지만 새로 만드는 properties 기본값은 빈 문자열. 둘 다 `enabled()=false` 라 동작 차이 없음(빈 목록이면 `preConnect` 가 바로 return, NPE 없음). 문서 불일치만. | 주석 또는 기본값 한쪽 맞추기. | 보고 |
| 12 | 낮음 | deployment/story-dialogues/README.md "넣는 곳" 표 | tutorial 서버에 ch1-7 을 넣으라고 돼 있지만 steps.yml 머리말·오너 결정은 "튜토리얼 서버 없음, 전부 school". 문서만 낡음. | README 표를 school 하나로 고치기 (문서라 이번엔 안 건드림). | 보고 |

## 항목별 확인 메모 (브리프에서 특히 보라고 한 것)

- **ChacaNPC `stat()`**: 위 #7. 메인 스레드, 예외 삼킴, Bridge 는 메모리 카운터. `key.length()>64` 면 Bridge 가 버림 (NPC id 가 길어도 안전).
- **NPC 힌트 폴백 `spellHint`**: 위 #8. 메인 스레드지만 IO 없음.
- **같은 NPC 여러 명 대화 (`newestPerNpc`)**: 1초 틱에 세션 수만큼만 돎, O(세션). 문제 없음.
- **선물 리본 ×1.2**: 서버 PDC 로만 판단, 상한·퀘스트 판정은 Bridge. 중복 지급 경로 없음.
- **일러스트 자동 생성 (workers 12/16)**: 위 #9. 이번 diff 에서 바뀐 건 `auto.server-ids`·`reroll.item-id` 뿐.
- **상대 일러스트 채널**: 패킷 decode 는 `IllegalArgumentException` 만 잡고 return (`PortraitProtocol.decode` 가 길이·문자열 한도를 검사), `uuid()` 는 정규식 검사 뒤 파싱 → 잘못된 패킷이 netty 로 올라가지 않음. 조각 192KB 를 2틱마다, 동시 2명 → 초당 ≤ 1.9MB. 접속 종료 때 모든 맵 정리됨. 남은 걱정은 #1.
- **하우징 전시(Showcases)**: 위 #10. `HousingProtocol.Showcase` 생성자가 길이를 다시 검사하므로 DB 값이 깨져도 `clip/split` 이 먼저 자름 → 예외 없음.
- **스토리 QuestEngine `서버:/서버이동:`**: 모든 단계가 `서버: school` 이고 `quest-server-id: school` 이라 `elsewhere()` 는 항상 false → 기존 흐름 그대로. `서버이동` 키는 어디에도 없음 (파서는 `서버` 없이 `서버이동: true` 면 경고 후 무시). BungeeCord 채널 등록만 추가됨(보내는 일 없음). `transferAt` 은 #5.
- **대기대화 쿨다운 맵**: `waitingShown` 은 4096 넘으면 오래된 것 정리 → 무한 증가 아님.
- **장 공개 잠금**: `enter()` 에서 잠긴 장이면 `heldByGate` 에 넣고 단계는 기록(`st.enter`)함 → 틱에서 열리면 HUD 다시 보내고 시작명령은 `startedRun` 검사로 한 번만. 다시 잠그면 `heldByGate` 재진입. `chapter-release.open-through: 1` + `chapters.2.open: false` 설정은 파서가 받는 형식.
- **길잡이 별 NPC 따라가기 (`followTick`)**: 2초마다 따라가는 사람만(보통 0~몇 명), 리플렉션 몇 번. 이동 1.5블록 넘거나 상태 바뀔 때만 패킷. 접속 종료·단계 바뀜·끄기 때 모두 `follows` 에서 제거.
- **Velocity TutorialRouter 빈 목록**: `servers=List.of()` → `enabled()=false` → `preConnect` 즉시 return, 스케줄러·파일도 안 만듦. NPE 없음. (#11)
- **ops-dashboard 폴링**: 서버 상태 핑 5초(서버당 1번, 상태 핑이라 가벼움), 공유 DB 하트비트 `SELECT * FROM 상태표` 5초 1번(표 작음, 타임아웃 5초), 로그 따라 읽기 1초(append 된 바이트만, 최대 `MAX_READ`), 화면은 3초/콘솔 1.5초로 대시보드 프로세스만 침. **RCON 은 폴링에 쓰지 않음** — 운영자가 명령/spark 버튼을 누를 때만 연결→인증→실행→닫기 1회. 위험 명령은 서버 쪽에서도 `confirm` 검사, 정지/재시작은 서버 이름 재입력 검사, 서버당 조작 1개. POST 는 `X-Ops: 1` 헤더 + SameSite=Strict 쿠키.

## 스토리 형식 검사 (steps.yml + story-dialogues)

스크립트로 확인 (PyYAML 파싱 → 단계 체인·도달성·대화 폴더·장면 그래프):

- 단계 25개, 시작 `tutorial` → … → `ch1-24` 에서 `다음: ''` 로 끝남. **도달 못 하는 단계 없음.** 체인 끊김 없음.
- 대화 폴더: ch1-7 ~ ch1-24, 분류 결과 12개, 대기대화 7개 모두 `dialogue.yml` 있음. **ch1-1·ch1-3·ch1-4·ch1-5·ch1-6 은 저장소에 없음** — README 가 "실서버본 그대로(학교 서버에 이미 있음)" 라고 밝힘. 빌드 없이 실서버 존재 여부는 확인 못 함 → 배포 전 school `plugins/ChacademyStory/dialogues/` 에 다섯 폴더가 있는지 `/대화관리 단계 점검` 으로 볼 것.
- 폴더 안 `server_commands.yml` 은 선택 (없으면 루트 `server_command.yml` 만 씀) — 플러그인 로더가 `dialogue.yml` 만 있어도 받음.
- 대화 그래프 37개 전부: 시작 장면 존재, `next`/`to`/`redirect` 가 모두 있는 장면을 가리킴, 도달 못 하는 장면 없음, 선택지 ≤6, 대사 ≤1500자, 모르는 화자 없음. ch1-17 결말 `p_00~p_33` 16개 = `기숙사분류 결정 {player} {ending}` 이 받는 형식(`p_[0-3][0-3]`).
- **NPC: -1 자리표시**: ch1-16(루카/정문), ch1-18(로웰), ch1-20(베르나), ch1-21(엘레나 교실), ch1-22(이솔데) — 머리말에 "그 단계에서 멈춤, `/대화관리 단계 <단계> 호출 NPC클릭`" 으로 문서화됨. 또 ch1-19·ch1-23 대기대화는 NPC 번호 정한 뒤 추가하라는 주석 있음.
- **시작명령 존재 여부**: `유저관리 <닉> 닉네임 창열기`(NicknameBridge), `아이템관리 낚시장비 <장비> <개수> <닉>`(FishingBridge), `유저관리 <닉> 마법조건 주기 guiding_star`(AdminCommandBridge, `guiding_star` 는 manual-conditions.yml 에 있음), `유저관리 <닉> 튜토리얼상점 시작|끝`(ShopBridge), `기숙사분류 시작 <닉>`(SchoolBridge) — 모두 있음. **`essentials:warp harbor {player}`** 는 EssentialsX 명령이라 소스로 확인 불가 → school 서버에 Essentials 설치 + `harbor` 워프 등록 여부를 배포 전에 확인 (이전 버전은 `항구 {player}` 자체 명령이었음).
- `{player}` 는 계정 이름인데 `/유저관리 <닉네임>` 칸에 들어감 — 이전 steps.yml 도 같은 방식으로 써 왔고 Bridge `resolvePlayer` 가 계정 이름도 받음.

## 빌드 없이 확인 못 한 것

- Gradle 컴파일 (Java 파일은 파서 수준 문법 검사만 통과: Showcases, PortraitChannel, QuestEngine, DialogueService, TutorialRouter).
- 리플렉션으로 부르는 상대 메서드 시그니처 일치 (`MagicCodexBridge.nicknameOf(OfflinePlayer)`, `equippedArtifactIds(Player)`, `ChacademyMotion.equippedBroomId/brooms()`, `NpcSocialFacade.stat/spellHint/openLesson`, `ChacaApi.stat/audit`) — 소스상 이름·인자는 맞아 보이나 실제 로드된 jar 버전이 낮으면 조용히 폴백(예외 삼킴)이라 기능만 빠짐.
- Essentials `harbor` 워프, school 서버의 ch1-1~ch1-6 대화 폴더 존재.
