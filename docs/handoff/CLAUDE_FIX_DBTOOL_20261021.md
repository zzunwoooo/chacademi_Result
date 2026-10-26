# CLAUDE_FIX_DBTOOL_20261021 — 일반 DB 도메인 SQLite → MariaDB 이관 (C01)

- 작성: Claude, 2026-10-21. 브랜치 `claude/fix-dbtool-20261021` (base `0cd1454`).
- **빌드 미검증: 문법 검사만 통과** (`parsecheck.sh` PARSE OK). Paper·Maven 저장소가 막혀 Gradle 빌드·테스트를 돌리지 못했다.
  - 이관 도구(`tools/db-migrate/GenericDomainMigration.java`)는 `magic-codex-database` 소스와 함께 `javac -Xlint:all`로 **컴파일 성공**, `--self-test`(병합 규칙 16항목) **통과**.
  - SQLite·MariaDB 드라이버가 이 환경에 없어 **실제 DB 읽기·쓰기 경로는 실행해 보지 못했다.** 첫 실행은 반드시 dry-run(기본값)으로.
- 손대지 않은 것: `ArtifactOwnershipService`(config 묶음), `BudgetService`(progress 묶음).

---

## 0. 배경 (한 줄)

학교·야생에 `plugins/MagicCodexBridge/database.properties`가 없어서 닉네임·친구·칭호·기숙사 배정·기부·기숙사 점수·의뢰·대화·NPC 호감도(+계절 공유)가 서버별 SQLite(`friends.db, titles.db, school.db, quests.db, dialogue.db, affinity.db`)에 저장되고 있다(`deployment/database-readonly-verification.json`). 아레나는 빈 SQLite를 읽는다. 이것을 공유 MariaDB로 옮긴다.

---

## 1. 이관 도구 (항목 1)

### 무엇을 만들었나
- `tools/db-migrate/GenericDomainMigration.java` — Gradle 모듈이 아니라 **단일 파일 Java 프로그램**(Java 21 소스 실행 `java File.java`). 빌드 구성(settings.gradle)은 건드리지 않았다.
- MariaDB 드라이버·`DatabaseSettings`는 **Bridge jar 안에 이미 있다** (`magic-codex-paper/build.gradle`의 `jar { from zipTree('../magic-codex-database/lib/mariadb-java-client-3.5.10.jar') }`, `sourceSets ... magic-codex-database`). 그래서 classpath = Bridge jar + SQLite 드라이버 jar(Paper 서버의 `libraries/org/xerial/sqlite-jdbc/3.47.2.0/sqlite-jdbc-3.47.2.0.jar`, 빌드 스크립트도 같은 경로를 쓴다).
- **표는 플러그인 저장소 클래스를 그대로 생성해서 만든다.** `--apply`일 때 도메인마다 `NicknameStore / FriendStore / TitleStore / SchoolStore / QuestStore / DialogueStore / AffinityStore`를 대상 설정으로 리플렉션 생성 → 닫기. 그래서 열·인덱스(닉네임 UNIQUE 키 포함)·추가 열(`nickname_key`, `first_done`, `completions`, `stage`)이 플러그인과 정확히 같다. 이관 도구에는 SQLite 쪽 표 이름·열 목록만 들어 있다.
- 비밀번호는 출력하지 않는다. 대상은 `DatabaseSettings.target()`(host:port/db)만 보고서에 쓴다.

### 실행 형식
```
# Linux/macOS (구분자 :)
java -Dstdout.encoding=UTF-8 -cp magic-codex-bridge-<ver>.jar:sqlite-jdbc-3.47.2.0.jar tools/db-migrate/GenericDomainMigration.java \
  --source school=<school MagicCodexBridge 사본 폴더> --source wild=<wild 사본> [--source arena=<arena 사본>] \
  --target <player-state-database.properties 경로> \
  [--apply] [--merge-into-existing] [--domains nickname,friend,title,school,quest,dialogue,affinity] [--report report.md]

# Windows PowerShell (구분자 ;)
java -Dstdout.encoding=UTF-8 -cp "C:\...\magic-codex-bridge-<ver>.jar;C:\...\libraries\org\xerial\sqlite-jdbc\3.47.2.0\sqlite-jdbc-3.47.2.0.jar" tools\db-migrate\GenericDomainMigration.java --source school=... --source wild=... --source arena=... --target ... --report C:\Chacademi\backups\<날짜>\migration-dryrun.md

# DB 없이 병합 규칙만 점검
java -cp magic-codex-bridge-<ver>.jar tools/db-migrate/GenericDomainMigration.java --self-test
# 대상 없이 원본만 읽어 보기
... --source school=... --source wild=... --no-target
```
- `--source`를 **먼저 적은 것이 우선 원본**(school을 맨 앞에).
- 기본은 **점검만(dry-run)**: 아무것도 쓰지 않고 한국어 보고서(원본별 행 수, 병합 결과 행 수, 충돌 목록, 참고 사항)를 출력. `--report`로 파일 저장(콘솔 인코딩 문제를 피하려면 파일로 볼 것).
- `--apply`: 도메인마다 **트랜잭션 하나**. 한 도메인이 실패하면 그 도메인만 롤백하고 다음 도메인을 계속한다(종료 코드 1).
- 종료 코드: 0 정상, 1 실패 있음, 2 일부 도메인 거부, 64 사용법 오류.

### 안전장치
- **대상 표에 이미 플레이어 데이터가 있으면 거부**(dry-run에서는 "적용 시 거부됨"으로 표시). `--merge-into-existing`을 주면 기존 DB 데이터를 **최우선 원본**으로 넣고 병합한다. 저장소가 시작할 때 만드는 기본 행(0점 기숙사 점수 4행, 잠금 행, 의뢰·대화 카탈로그)은 "데이터"로 세지 않는다.
- **멱등**: 적용 시 `codex_generic_migration(domain, source, checksum, row_count, applied_at)`에 원본별 체크섬(SHA-256)을 같은 트랜잭션으로 기록. 다시 실행하면 같은 라벨·같은 체크섬 원본은 "이미 이관됨"으로 건너뛴다(점수가 두 번 더해지지 않음). 같은 라벨이 **다른 내용**으로 오면 그 도메인을 거부한다.
- 동시 실행 방지: `GET_LOCK('codex_generic_migration')`.
- 원본 SQLite는 SELECT만 한다. 그래도 **원본 파일이 아니라 사본 폴더**를 가리킬 것(런북 참고).

### 병합 규칙 (보고서 머리에도 그대로 출력됨)
| 도메인 | 규칙 |
|---|---|
| 닉네임 `codex_nicknames` | 같은 UUID에 다른 닉네임: **표에 수정 시각이 없으므로 우선 원본(school)이 이김**, 보고. `first_done`은 OR, `revision`은 최댓값. 같은 닉네임(정규화 키 = NicknameStore.key와 같은 NFKC+소문자)을 다른 UUID가 쓰면 **가장 먼저 저장된 쪽**(우선 원본 → 원본 안 rowid 순)을 남기고, 다른 쪽은 닉네임은 그대로 두고 `nickname_key`를 NULL로 넣는다. NicknameStore가 시작할 때 이미 이 상태를 "중복 닉네임 발견"으로 경고하도록 되어 있다(기존 코드 `backfill`). 관리자가 `/유저관리 <계정명> 닉네임 설정 <새닉네임>`으로 바꾼다. 다른 플레이어 계정 이름과 같은 닉네임은 참고로만 보고. |
| 친구 `codex_friends` 등 | 관계(owner→target) **합집합**, 표시 이름·기숙사는 우선 원본. 50명 초과 시 보고(모두 유지). 친구 신청 합집합(같은 쌍은 최근 것), **7일 지난 신청과 이미 서로 친구인 쌍의 신청은 제외**. 재신청 대기(declines)는 큰 값, 지난 것 제외. 첫 친구 신호 `delivered`는 OR. |
| 칭호 | 보유 **합집합**. 선택(앞·뒤 칭호)은 **시각 기록이 없어 우선 원본**, revision 최댓값. |
| 기숙사 배정 | 배정 시각이 없어 **우선 원본(school)**, 보고. 병합 후 기숙사별 인원 표시. |
| 기부 | 마법마다 **가장 이른 기부(created)**, 같으면 우선 원본. |
| 기숙사 점수 | 점수 원천(관리자 수동 조정 등)이 기록되지 않아 완전 재계산은 불가. **원본별 점수의 합 − 탈락한 중복 기부가 그 서버에서 받은 점수**(그 원본 폴더의 `donation-spells.yml` points, 기본 1). yml이 없는 원본이면 차감하지 못하고 합만 쓴 뒤 보고. 의뢰 점수 지급 토큰은 합집합(중복 토큰은 차감). 기증 금지(policy)는 OR. |
| 의뢰 `codex_quest_progress` | 같은 (플레이어, 의뢰, 주기): **지급 중(paying)** 기록 우선(관리자 확인 대상, 보고) → 완료 횟수 → 상태(claimed > active > ready) → 진행도 합 → 갱신 시각 → 우선 원본. 의뢰 카탈로그는 대상이 비어 있을 때만 첫 원본 것. |
| 대화 `codex_story_state` | 키마다 병합: 한쪽에만 있으면 그 값. 다르면 `journal.*`는 claimed > active > hidden, 둘 다 숫자면 큰 값, 그 밖에는 그 플레이어 revision(진행 횟수)이 큰 원본. 512개 한도 초과 시 보고. 실행 기록(`codex_dialogue_effect`)은 끝나지 않은 쪽(running/review/pending > queued > done). 대화 카탈로그는 대상이 비어 있을 때만. |
| 호감도 | 같은 (플레이어, NPC): **updated가 최근인 행**, 하트 단계는 최댓값. 하루 한도 기록은 큰 값. 선물 기록은 토큰 합집합(미확정 건수 보고 — 서버 시작 시 기존 복구 로직이 처리). |

판단 메모: 닉네임·칭호 선택·기숙사 배정 표에는 수정/배정 시각 열이 없다. 지시서의 "시각이 없으면 school"을 따랐다.

---

## 2. 시작 시 가시성 + require-mariadb (항목 2)

- `MagicCodexBridge.databaseModeAccepted()`
  - 세 설정을 각각 한 줄씩 로그: **일반 도메인**(아래 3절의 실제로 읽는 파일), **캐릭터 저장·아티팩트·연금술·낚시**(전용 → 없으면 일반, 실제 저장소와 같은 순서), **상점·우편**.
  - SQLite로 동작하는 도메인이 있으면 **WARNING 한 줄**: `SQLite(이 서버 파일)로 동작하는 도메인: 닉네임·친구·칭호·…. 이 데이터는 다른 서버(학교·야생·아레나)와 공유되지 않습니다. … tools/db-migrate …`
  - `/서버관리 상태`에 **"DB 공유"** 줄 추가: `정상 · 모든 도메인 MariaDB 공유` 또는 `주의 · SQLite(서버 로컬): …` (기존 `CommandHub.status` 사용).
- `database.require-mariadb`: **기본값 false 그대로**. 검사 대상이 위 세 설정(실제로 읽는 파일 기준)으로 바뀌었다. 예전에는 `database.properties`·상점우편만 봤고 캐릭터 저장 파일은 안 봤다. 이관을 마치고 확인한 뒤에만 `true`로 바꾼다(런북 9단계). config.yml 주석 갱신.
- 바뀐 파일: `MagicCodexBridge.java`, `config.yml`.

## 3. 한 파일로 전부 전환: `use-for-all-domains=true` (항목 3)

- 새 `GenericDatabaseFile.java`: 일반 도메인이 읽을 파일을 정한다.
  1. `database.properties`가 있으면 그것(예전과 같음, 명시 파일이 이김).
  2. 없고 `player-state-database.properties`에 `use-for-all-domains=true`가 있으면 **그 파일**.
  3. 둘 다 아니면 `database.properties`(없음 → SQLite, 예전과 같음).
- 적용한 곳(한 줄씩 교체): `NicknameBridge`, `SocialBridge`, `TitleBridge`, `SchoolBridge`, `QuestBridge`, `DialogueBridge`, `NpcSocialService`, `ClimateService`(계절 공유 모드).
- **기본 꺼짐**: 키를 넣기 전에는 동작이 같다. 테스트 `GenericDatabaseFileTest`.
- 주의: 키를 켜면 **계절 공유 모드도 켜진다**(`ClimateService` shared). 시작 후 세 서버 계절이 같은지 확인.
- 주의: 공유 모드가 되면 `SchoolBridge`의 2초 주기 전체 조회, 닉네임 45초 전체 조회가 실제로 돈다(감사 3-5). 이번 범위 밖.
- `deployment/player-state-database.properties.example`에 `#ssl-mode`, `#use-for-all-domains=true` 예시 추가.

## 4. utf8mb4 (항목 4)

- MariaDB일 때만 CREATE TABLE에 ` ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_unicode_ci` 추가 (SQLite는 그대로):
  - `ArtifactOwnershipStore` 5개 표(이 저장소는 MariaDB 전용. 테스트는 H2 `MODE=MySQL`인데 H2는 MySQL 표 옵션 ENGINE/CHARSET/COLLATE를 읽고 무시함 — **GPT 빌드에서 `ArtifactOwnershipStoreTest` 통과 확인 필요**)
  - `AlchemySharedConfig`(`codex_alchemy_config`), `SharedConfigText`(`codex_shared_config`) — 둘 다 MariaDB일 때만 실행되는 코드
  - `FishTankSql`(3개, `maria`일 때), `FishingStore`(2개), `FishingGearStore`(1개)
  - `kyudichi-paper/RatingStore` 4개
- `CREATE TABLE IF NOT EXISTS`는 **이미 있는 표를 바꾸지 않는다.** 기존 표는 아래 SQL로 변환:
```sql
-- 1) 확인: utf8mb4가 아닌 표
SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_COLLATION NOT LIKE 'utf8mb4%'
   AND (TABLE_NAME LIKE 'codex\_%' OR TABLE_NAME LIKE 'cport\_%' OR TABLE_NAME LIKE 'cnpc\_%');
-- 2) 변환 (서버 정지·백업 후. 위 결과에 나온 표만)
ALTER TABLE codex_artifact_accounts       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_artifact_ownership      CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_artifact_operations     CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_artifact_delivery       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_artifact_migration      CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_alchemy_config          CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_shared_config           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_fish_tank               CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_fish_lock               CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_fish_mutex              CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_fishing                 CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_fishing_claim           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_fishing_gear            CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_kyudichi_rating         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_kyudichi_match          CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_kyudichi_match_player   CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE codex_kyudichi_season         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 3) 앞으로 만들어질 표의 기본값
ALTER DATABASE `<db이름>` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```
  - latin1 열에 한글을 넣으려던 기록은 이미 오류(strict) 또는 `?`로 저장됐을 것이라 변환으로 되살아나지는 않는다. ASCII 데이터는 그대로 보존된다. 키 길이는 모두 InnoDB 한도(3072바이트) 안.

## 5. 큐디치 ALTER 오류 (항목 5)

- `RatingStore.open`: ALTER 실패 시 **"이미 있는 열"(MariaDB 1060 / SQLState 42S21 / SQLite "duplicate column name")만 무시**, 그 밖의 오류(권한 부족 1142 등)는 `SEVERE` 로그 `큐디치 DB 열 추가 실패 …`. 시작은 계속한다(예전 동작 유지, 로그만 추가).
- 테스트 `RatingStoreSchemaTest`.

---

## 6. 배포 런북 (GPT 운영)

서버: school, wild, arena + Velocity. 경로는 `C:/Chacademi/...` 기준 예시. 각 단계 결과를 기록해 둘 것.

**0. 준비**
- 새 빌드 `magic-codex-bridge-*.jar`(이 브랜치 포함)와 `chaca-kyudichi-*.jar`를 준비만 한다(아직 교체 안 함).
- 대상 MariaDB 설정 파일: 학교의 `plugins/MagicCodexBridge/player-state-database.properties`(이미 운영 중인 공유 연결)를 그대로 `--target`으로 쓴다.

**1. 정지 순서**
1. 공지 → Velocity에서 접속 막기(화이트리스트 또는 프록시 정지).
2. arena → wild → school 순으로 `stop` (정상 종료라야 SQLite WAL이 본 파일에 합쳐진다). 프로세스 종료 확인.

**2. 백업** (`C:/Chacademi/backups/c01-<날짜시각>/`)
- `mysqldump --single-transaction --routines --default-character-set=utf8mb4 -h <host> -u <user> -p <db> > db-before.sql` (비밀번호는 프롬프트로 입력, 명령줄·로그에 남기지 않기)
- 세 서버의 `plugins/MagicCodexBridge/` 에서 `*.db`, `*.db-wal`, `*.db-shm`, `donation-spells.yml`, `config.yml`, `*.properties`, `mailbox-origin.txt` 를 `backups/.../<server>/` 로 **복사**. 이 사본 폴더를 이관 원본으로 쓴다.
- 해시 기록: `Get-FileHash backups\...\*\*.db`.

**3. 새 jar 배치** (서버는 아직 끔): Bridge·큐디치 jar 교체.

**4. 점검(dry-run)**
```
java -Dstdout.encoding=UTF-8 -cp "<새 Bridge jar>;<school>\libraries\org\xerial\sqlite-jdbc\3.47.2.0\sqlite-jdbc-3.47.2.0.jar" tools\db-migrate\GenericDomainMigration.java ^
  --source school=C:\Chacademi\backups\c01-...\school --source wild=C:\Chacademi\backups\c01-...\wild --source arena=C:\Chacademi\backups\c01-...\arena ^
  --target C:\Chacademi\school\plugins\MagicCodexBridge\player-state-database.properties --report C:\Chacademi\backups\c01-...\dryrun.md
```
- 보고서 확인: 원본별 행 수, 병합 결과, **충돌 목록**(닉네임 중복·기숙사 다름·기부 중복·점수 계산식), "적용 시 거부됨"이 있는지.
- "적용 시 거부됨"이 나오면 대상에 이미 데이터가 있는 것 → 그 데이터가 어디서 왔는지 확인 후 `--merge-into-existing` 판단(기존 DB가 최우선).
- 결과를 오너(준우)에게 공유: 특히 **중복 닉네임 목록**(이관 후 이름 변경 안내 필요)과 **기숙사 다름 목록**.

**5. 적용**: 같은 명령에 `--apply`(+ 필요하면 `--merge-into-existing`), `--report ...\apply.md`. 결과가 "적용 완료"인지, 도메인마다 "적용됨" 줄이 있는지 확인.
- 같은 명령을 한 번 더 실행(dry-run): 모든 원본이 "이미 이관됨(같은 체크섬)"으로 나와야 정상(멱등 확인).

**6. 설정 전환** (school, wild, arena 모두 같은 대상)
- 권장: 각 서버 `plugins/MagicCodexBridge/player-state-database.properties` 끝에 `use-for-all-domains=true` 한 줄 추가. `database.properties`는 **만들지 않는다**(있으면 그 파일이 이긴다).
- arena: `player-state-database.properties`(school과 같은 대상 + 위 키), `shop-mailbox-database.properties`(school과 같은 대상)를 둔다. `mailbox-origin.txt`는 **세 서버가 서로 달라야 한다**(arena 폴더를 school에서 복사했다면 arena의 파일을 지우고 새로 생성되게 할 것 — 감사 10-db-ops 7절).
- Velocity `plugins/chacademi-party/party.properties`: `trusted-servers=school,wild,arena`.
- (참고, 이번 코드 범위 밖) `ChacaKyudichi/database.properties`, `ChacaHousing/database.properties`, `ChacaPortrait`·`ChacaNPC`도 같은 MariaDB를 가리키는지, 하우징 `server-name`·초상화 `server-id`가 서버마다 다른지 확인.

**7. (선택) utf8mb4 변환**: 4절 SQL의 1)을 실행해 나온 표만 2) 실행, 3) 실행.

**8. 시작 순서**: school → 로그 확인 → wild → arena → Velocity.
- 로그에서 확인:
  - `DB 설정 player-state-database.properties: 파일 있음, 모드=MariaDB (host:port/db) ← 닉네임·친구·칭호·… (use-for-all-domains=true)`
  - **`SQLite(이 서버 파일)로 동작하는 도메인` WARNING이 없어야 한다.**
  - NicknameStore 경고 `중복 닉네임 발견 …` → 보고서의 중복 닉네임 목록과 같아야 한다.
- 게임 안 `/서버관리 상태` → `DB 공유: 정상 · 모든 도메인 MariaDB 공유`.

**9. 확인 쿼리**
```sql
SELECT domain, source, row_count, FROM_UNIXTIME(applied_at/1000) FROM codex_generic_migration ORDER BY domain, source;
SELECT 'nicknames' t, COUNT(*) FROM codex_nicknames UNION ALL
SELECT 'nicknames_dup_nokey', COUNT(*) FROM codex_nicknames WHERE nickname_key IS NULL UNION ALL
SELECT 'friends', COUNT(*) FROM codex_friends UNION ALL
SELECT 'friend_requests', COUNT(*) FROM codex_friend_requests UNION ALL
SELECT 'title_player', COUNT(*) FROM codex_title_player UNION ALL
SELECT 'title_owned', COUNT(*) FROM codex_title_owned UNION ALL
SELECT 'houses', COUNT(*) FROM codex_houses UNION ALL
SELECT 'donations', COUNT(*) FROM codex_donations UNION ALL
SELECT 'quest_progress', COUNT(*) FROM codex_quest_progress UNION ALL
SELECT 'story_state', COUNT(*) FROM codex_story_state UNION ALL
SELECT 'dialogue_effect_open', COUNT(*) FROM codex_dialogue_effect WHERE status<>'done' UNION ALL
SELECT 'affinity', COUNT(*) FROM codex_affinity;
SELECT house, points FROM codex_house_points ORDER BY house;
SELECT house, COUNT(*) FROM codex_houses GROUP BY house ORDER BY house;
```
- 숫자가 apply 보고서의 "병합 결과"와 같아야 한다.
- 실게임: 같은 계정으로 school → wild → arena 이동하며 ① 한글 닉네임(머리 위·HUD·채팅·큐디치) ② 칭호 ③ 친구 목록·하우징 친구 방 ④ 기숙사·기숙사 채팅(arena 포함) ⑤ 기부 기록·기숙사 점수 ⑥ 의뢰 진행 ⑦ NPC 호감도 ⑧ 계절이 세 서버에서 같은지.
- 중복 닉네임 플레이어: `/유저관리 <계정명> 닉네임 설정 <새닉네임>` 후 재시작 시 경고가 사라지는지.

**10. require-mariadb 켜기** (위 확인이 끝나고 하루 정도 안정적이면): 세 서버 `config.yml`에 `database: require-mariadb: true` → 재시작. 이후 설정 파일이 빠지면 SQLite로 조용히 돌지 않고 시작을 거부한다.

**롤백**
- 전환 직후(플레이어 접속 전): 각 서버 `player-state-database.properties`에서 `use-for-all-domains=true` 줄 삭제 → 재시작. 일반 도메인이 원래 SQLite 파일로 돌아간다(이관 도구는 원본 파일을 쓰지 않았고, 사본을 원본으로 썼다). MariaDB에 들어간 행은 남아 있어도 읽히지 않는다.
- MariaDB를 이관 전 상태로: 서버 정지 → `mysql ... < db-before.sql` (또는 이관 도메인 표만 `DROP`/`DELETE` + `DELETE FROM codex_generic_migration`).
- 전환 후 플레이가 진행된 뒤 롤백하면 **그사이 MariaDB에 기록된 닉네임·친구·칭호·기부 등은 SQLite로 돌아가지 않는다**(잃는다). 롤백 시점을 오너와 정할 것.
- 큐디치 jar 롤백은 독립(ALTER 로그만 바뀜).

---

## 7. GPT가 돌릴 테스트

- `./gradlew :magic-codex-paper:test --tests '*GenericDatabaseFileTest' --tests '*DatabaseSettingsTest' --tests '*ArtifactOwnershipStoreTest' --tests '*FishTankSqlTest'`
- `./gradlew :kyudichi-paper:test --tests '*RatingStoreSchemaTest'`
- 전체 `verifyServer`.
- 이관 도구: `java -cp <bridge jar> tools/db-migrate/GenericDomainMigration.java --self-test` → `자체 점검 통과`.
- 스테이징 리허설 권장: 테스트용 빈 DB(별도 database)를 가리키는 properties로 운영 SQLite 사본을 `--apply` → 9절 쿼리 → 같은 명령 재실행이 "이미 이관됨"인지 → `--merge-into-existing` 없이 새 라벨로 실행하면 "거부"인지.

## 8. 못 한 것 · 판단 보류

- **실제 DB 경로 미실행**: SQLite·MariaDB 드라이버가 없어 `read`/`write`/리플렉션 표 생성은 실행해 보지 못했다. 스테이징 리허설 필수.
- 리플렉션으로 저장소 생성자를 부르는 방식은 Bridge jar 안 클래스(`school.magiccodex.paper.*Store`)가 Bukkit 없이 로드된다는 전제다. 이 클래스들은 Bukkit 타입을 상속·필드로 쓰지 않는 것을 확인했지만(TitleDefinition·QuestDefinition·DialogueDefinition은 record, HouseSorting은 일반 클래스), 만약 `NoClassDefFoundError: org/bukkit/...`가 나면 classpath에 Paper 서버 jar(또는 paper-api)를 추가하면 된다.
- 닉네임·칭호 선택·기숙사 배정에는 시각 기록이 없어 "최근 것"을 고를 수 없다 → 우선 원본(school). 기숙사 점수의 관리자 수동 조정분은 원천 기록이 없어 서버별 합으로 보존된다.
- 이관은 일반 도메인 7개만. 빗자루 보유(LuckPerms H2)·숙련도(yml), 마법 발견(`MagicDiscovery/database.properties`), 스토리 플러그인 `progress.yml`은 범위 밖.
- `ArtifactOwnershipStore`의 DDL 줄을 고쳤다. 다른 묶음이 같은 줄(연결 재사용 3-2b 등)을 고친다면 병합 시 충돌 가능.
- `SocialBridge`·`SchoolBridge`·`NicknameBridge` 등은 각 1줄만 바꿨다(설정 로드 줄). 다른 묶음과 줄이 겹치면 `GenericDatabaseFile.load(...)`로 맞추면 된다.
- `require-mariadb` 기본값은 지시대로 false 유지.
