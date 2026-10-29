# feature/dungeon-lich-20261029 — 1인 던전 (리치의 지하 연구실)

이 브랜치 = 아래 2장 브랜치 + 1인 던전. 던전 설명·반영 순서·확인 결과는 **`chaca-dungeon-paper/README.md`**.
2장 steps.yml 의 ch2-21·ch2-22 에 던전 입장 권한 시작명령을 더했다.

---

# feature/ch2-story-20261029 — 스토리 2장 (세드릭 큐디치 → … → 지하 던전) + 서버 간 스토리 신호

신기능 세션 작업. 빌드·서버 반영은 하지 않았다 → 규칙 세우기 세션이 반영.
바탕 = `feature/ch1-cedric-ascension-20261028` (2026-10-29 17시 기준 서버에 이미 반영된 것과 javap 비교로 같음).

## 스토리 흐름 (오너 기획 2026-10-29)
| 단계 | NPC | 완료 판정 |
|---|---|---|
| ch1-32 | – | (바뀜) 호출 자동 → ch2-1. 2장이 잠겨 있으면 그대로 기다림 |
| ch2-1 | 세드릭 37 | 큐디치 설명 |
| ch2-2 | 세드릭 37 | 신호 `큐디치참가` 1 → "역시 재능 있는 학생이구만! 1:1 대전 승리는 해 봤나?" |
| ch2-3 | 세드릭 37 | 신호 `결투승리` 3 |
| ch2-4 | 하르바 25 | 교화 20 과제 |
| ch2-5 | 하르바 25 | 값 `%chacastory_petdex%` ≥ 20 (펫 도감 20 이상이면 바로 통과) → "벌써 잡아 왔나? 변색종…" |
| ch2-6 | 하르바 25 | 신호 `변색종교화` 1 |
| ch2-7 | 엘레나 19 | 원소 반응 설명 |
| ch2-8 | 엘레나 19 | 신호 `원소반응` 30 |
| ch2-9 | 베르나 18 | 마력코어 과제 |
| ch2-10 | 베르나 18 | 신호 `연금술성공` 1 + 아이템 `미감정 마력코어` 1 (아래 ⚠) |
| ch2-11 | 그레고르 15 | 조사 의뢰 |
| ch2-12 | 그레고르 15 | 랜덤제출 `monster_loot` 1 (유저마다 다른 전리품, HUD 에 이름) → "역시 뭔가 문제가… 학교 밖 조심" |
| ch2-13 | 루카 14 | "이솔데 사서님은 뭘 알지 않을까?" |
| ch2-14 | 이솔데 21 | "몬스터 책이 하나 있어요" |
| ch2-15 | 이솔데 21 | 신호 `몬스터기록` 1 (책을 끝까지 읽고 닫음) → 대마법사(이름 가림)·교화 / 루카 "구스타프 할아버지라면?" |
| ch2-16 | 구스타프 38 | 두 학생·9클래스·악마 봉인 / 루카 "너도 모든 원소…" / "직접 가서 물어보는 게" |
| ch2-17 | 그레고르 15 | "말썽쟁이군… 샘플 세 가지 더" |
| ch2-18 | 그레고르 15 | 랜덤제출 ×3 → "배운 마법이 %chacastory_spells%개라… 조사를 맡기겠네" |
| ch2-19 | 그레고르 15 | 시작명령 `유저관리 {player} 서클 승급창` + 신호 `승급성공` (3클래스) → 지하 던전·루카 "재밌어 보여!!"·"아직 준비가… 장비 다 장착" |
| ch2-20 | 그레고르 15 | 값 `%chacastory_accessories%` ≥ 3 (반지·귀걸이·목걸이 장착) → "조심히 다녀오게" |
| ch2-21 | 그레고르 15 | 신호 `던전클리어` 1 → "뭐? 언데드 리치?? … 출입 금지다. 가지 마!" |
| ch2-22 | – | "3장을 기다려 주세요. 3장은 2주차에 오픈됩니다." (멈춤) |

## 바꾼 코드
### 스토리 플러그인 (학교) — `QuestEngine.java`, `QuestDefs.java`, `QuestProgress.java`
- 조건 **값**: 대상 = PlaceholderAPI 자리표시자, 숫자 ≥ 개수면 채움 (2초 캐시).
- 조건 **랜덤제출**: 대상 = `random-pools.yml` 후보 목록 이름. 단계에 들어갈 때 유저마다 하나 뽑음(같은 단계 안에서는 안 겹침), 진행 기록 `picks` 로 저장, 제출처럼 걷음, HUD = "<표시>: <아이템 이름>".
- 신호 목록·점검에 새 신호 (결투승리·큐디치참가·변색종교화·원소반응·마력코어제작·던전클리어·몬스터기록).

### 마법 플러그인 (학교·아레나1·2·야생1·2, 던전 서버가 생기면 거기도) — `magic-codex-paper/.../`
- **새 `StorySignals.java`** (+ 안쪽 클래스 `Expansion`, `Row`):
  - **서버 간 전달**: 학교가 아닌 서버의 `StoryProgressEvent` 를 5초마다 묶어 공유 MariaDB 표 `chaca_story_signal` 에 적고, 학교는 3초마다 접속 중인 유저 몫을 읽어 같은 신호로 다시 발사한 뒤 지움 (그 유저가 학교에 와야 받음, 14일 지난 줄은 지움). 받는 서버 = config `story-relay.receiver`(기본 school), 이 서버 이름 = `story-relay.server-id` → server-heartbeat.yml server-id → 서버 폴더 이름.
  - **원소반응**: MagicMythic `MagicReactionEvent` (시전자가 유저) — MagicMythic jar 는 안 바꿈.
  - **변색종교화**: LuckPerms 에 `chacademia.pet.…shiny` 권한이 이 서버에서 새로 붙을 때 (교화 저장) — TamingBridge 는 안 바꿈.
  - **결투승리·큐디치참가**: 아레나 큐디치가 이미 보내는 `ChacaApi.stat` 에서 — 큐디치 jar 는 안 바꿈.
  - **명령** `/유저관리 <닉> 스토리신호 <신호> [개수]` — 책 끝 명령·던전 클리어에서 씀 (학교가 아니면 학교로 전달).
  - **PlaceholderAPI** `%chacastory_petdex%` (chacademia.pet.* 권한 수), `%chacastory_accessories%` (반지·귀걸이·목걸이 장착 칸 수), `%chacastory_spells%` (magic.learned.* 권한 수).
- `AscensionBridge.java`: StorySignals 를 만들고 닫음 (본체 MagicCodexBridge.class 를 안 바꾸려고).
- `StoryProgressEvent.java`: relayed 표시(4인자 생성자) 추가. 기존 3인자 그대로.
- `ChacaApi.java`: `stat` 에서 `StorySignals.stat` 호출.
- ⚠ **MagicCodexBridge·TamingBridge·AlchemyBridge 는 배포 jar 가 소스와 달라서 안 건드렸다** (javap 비교: 수백 줄 차이). 그래서 마력코어는 "연금술 1회 + 미감정 마력코어 소지"로 판정.

## 서버 설정·파일 — `story-ch2/school/plugins/`
- `ChacademyStory/steps.yml` — 서버 것(10-29 17:08)에서 ch1-32 를 바꾸고 ch2-1~22 추가.
- `ChacademyStory/dialogues/` — 2장 대화 33개 (ch2-1 ~ ch2-21, 대기 대사 포함).
- `ChacademyStory/random-pools.yml` (새 파일) — `monster_loot` 142개 = 야생 ItemsAdder `chacademi_loot.yml` 의 일반 55·고급 64·유일 23 (전설 제외, 이름 중복 1개 정리, 전부 MythicMobs 드롭에 있음).
- `MagicCodexBridge/books-add-ch2.yml` — 새 책 `record_monsters_100y` 「100년 전 몬스터에 관한 기록」 (끝 명령 = 스토리신호 몬스터기록). 학교 books.yml 의 `books:` 아래에 붙일 것.

## 반영 순서 (규칙 세우기 세션, 정기 점검)
1. 마법 플러그인 jar (학교·아레나1·아레나2·야생1·야생2 각자 자기 jar): `AscensionBridge.class`, `StoryProgressEvent.class`, `ChacaApi*.class`, `StorySignals*.class` 교체·추가. 서버마다 jar 가 다르니(-school/-arena/-wild) 각 jar 에 넣을 것. 바뀐 4파일은 세 jar 모두 기준으로 컴파일 확인함.
2. 학교 스토리 jar: `QuestEngine*.class`, `QuestDefs*.class`, `QuestProgress*.class` 교체 (`QuestEngine$ValueRead` 새로 생김).
3. 학교 `ChacademyStory/`: steps.yml(반영 직전 서버 것과 다시 대조) · dialogues 33개 · random-pools.yml.
   config.yml: `dialogue-placeholders:` 에 `- '%chacastory_spells%'` 추가 (없으면 ch2-18 대사에 글자 그대로 나옴).
4. 학교 `MagicCodexBridge/books.yml` 에 책 붙이기 → 재시작 뒤 대도서관 책장 보고 `/컨텐츠관리 책 연결 record_monsters_100y` (**오너가 위치 정함**).
5. 재시작 (MagicCodexBridge 가 있는 5대 전부 — 전달이 양쪽 다 있어야 함).
6. 2장 열기: `/대화관리 장공개 열기 2` (config 는 지금 2장 open:false). 안 열면 ch1-32 유저는 잠금 문구로 기다림.
7. 확인: `/대화관리 단계 점검`, 서버 로그 `스토리 신호: 이 서버 school = 받는 서버` / `→ school 로 전달`.

## 아직 안 된 것 / 오너가 할 것
- **지하 던전·리치는 아직 없음** → ch2-21 에서 기다림. 던전 보스 처치(또는 클리어) 때 콘솔 명령 `유저관리 <플레이어> 스토리신호 던전클리어` 를 실행하게 만들면 된다 (MythicMobs 죽음 스킬 command 등). 던전이 다른 서버면 그 서버에도 이번 마법 플러그인 반영 필요.
- 1층 로비 왼쪽 통로 열기 = 맵 작업 (스토리와 무관).
- ch2-15 책장 위치.

## 확인한 것 / 못 한 것
- ✔ 바뀐 클래스 전부 배포 jar(학교 스토리·학교/아레나/야생 마법) 기준 javac 컴파일.
- ✔ 바탕(ch1 브랜치) 소스 = 지금 서버 jar (스토리 QuestEngine/QuestDefs, 마법 ChacaApi/AscensionBridge 같음; StoryProgressEvent 는 서버 쪽에 ASCEND 상수만 없음 — 값이 인라인돼 무해).
- ✔ steps.yml 을 바뀐 QuestDefs 로 읽음: 경고 0, ch1-31→…→ch2-22 연결, 대화·대기대화 파일 전부 있음. 랜덤 기록 저장→읽기 왕복 OK. YAML 36개 읽힘.
- ✘ jar 빌드·게임 안 확인 안 함. DB 전달·원소반응·변색종 신호는 실서버에서만 확인 가능.
- 펫 도감 20 = "도감 20마리 이상"으로 해석 (교화는 도감에 없는 펫만 성공하므로 20마리 교화 = 도감 +20 이지만, 오너 말대로 이미 20 이상이면 바로 통과).
