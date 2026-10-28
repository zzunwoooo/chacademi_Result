# feature/ch1-cedric-ascension-20261028 — 1장 추가 스토리 (세드릭 · 1:1 결투 · 클래스 승급) + 끝난 스토리 이어 주기

신기능 세션 작업. 빌드·서버 반영은 하지 않았다 → 규칙 세우기 세션이 반영.
**`feature/story-resume-20261028` 의 이어 주기 기능도 이 브랜치에 들어 있다** (같은 QuestEngine 을 바꾸므로 하나로 합침). 그 브랜치는 따로 반영하지 말 것.

## 스토리 흐름 (오너 기획 2026-10-28)
| 단계 | 퀘스트 알림 | 진행 |
|---|---|---|
| ch1-28 (대화 수정) | (기존) | 하르바 "…역시 재능이 있군" + 끝에 "다음은 세드릭 교수를 찾아가 봐라" |
| ch1-29 | 세드릭 교수를 찾아가자. | NPC 37(세드릭) 클릭 → 큐디치·1:1 결투 설명, "실전 경험", "결투 한 판 해 보고 와라" |
| ch1-30 | 1:1 결투를 한 판 해 보고 세드릭 교수님께 돌아가자. | 조건 `권한 chacademi.story.duel` (결투 한 판, 승패 무관). 전에 누르면 ch1-30w-cedric. 채우면 클릭 → 심장의 마나 대화 |
| ch1-31 | 마나를 심장에 모아 클래스를 승급하고 … (창을 닫았다면 /클래스승급) | 들어갈 때 시작명령 `유저관리 {player} 서클 승급창` → 2서클 조건 열고 승급 창 띄움. 조건 `신호 승급성공`. 전에 누르면 ch1-31w-cedric. 채우면 클릭 → "저 해냈어요!" |
| ch1-32 | 2장을 기다려 주세요. 2장은 29일 오후 6시에 오픈됩니다. | 호출 없음 (멈춤). 2장 자동 공개 설정은 안 함 (오너: 문구만) |

지금 ch1-29("2장을 기다려 주세요")에 있는 유저는 같은 ID 가 "세드릭 찾아가기"가 되므로 그대로 이어진다.

## 바꾼 파일
### 서버 설정 (학교) — `story-ch1-cedric/school/plugins/ChacademyStory/`
- `steps.yml` — 서버 것(2026-10-28 16:50, ch1-27 다음=ch1-28 반영된 것)에서 ch1-29 를 바꾸고 ch1-30·31·32 추가. 나머지 그대로.
- `dialogues/ch1-28/dialogue.yml` (수정), `ch1-29`, `ch1-30`, `ch1-30w-cedric`, `ch1-31`, `ch1-31w-cedric` (새로). 초상화 `tc_cedric-neutral.png` (런처 NPC 팩에 있는 이름).

### 스토리 플러그인 (학교) — `chacademy-story/plugin/.../QuestEngine.java`, `QuestDefs.java`
- 조건 종류 **권한** 추가: 대상 = 권한 노드, `p.hasPermission` 이면 채움. 명령 `/대화관리 단계 <id> 조건 추가 권한 <노드> 1 <표시>` 도 됨.
- 신호 목록에 **승급성공** 추가 (점검 경고·자동완성).
- 이어 주기 (`resumeFinished`): 지금 단계가 비었고 마지막으로 끝낸 단계의 `다음` 이 생겼으면 그 단계로. 접속·새로고침·단계 편집 때. ch1-27 에서 멈춘 유저 약 148명용.
- ⚠ **배포 jar 와 소스 차이 2곳을 소스에 옮김** (안 옮기면 바꿔 끼울 때 사라짐): `flatNear` 의 높이 3칸 검사(`GUIDE_NEAR_HEIGHT`), `SORTING_DIALOGUE = "ch1-17"`.
  옮긴 뒤 원래 소스를 배포 jar(`chacademy-story-plugin-final-guide-20261024.jar`)와 javap 로 비교 → 이번 변경 말고는 같음.

### 마법 플러그인 (학교 + 아레나1·2) — `magic-codex-paper/.../`
- `AscensionBridge.java`: 승급 성공 때 `StoryProgressEvent` **승급성공** 발사. `/유저관리 <닉> 서클 승급창` 추가 (다음 서클 승급 권한을 주고, 반영되면 승급 창 띄움).
- `StoryProgressEvent.java`: `ASCEND = "승급성공"`.
- `ChacaApi.java`: `stat(…, "결투참가", …)` 가 오면 LuckPerms `chacademi.story.duel` 도장. 아레나 큐디치(배포 0.1.2-presets-api.2)가 결투가 끝날 때 이미 "결투참가" 통계를 보낸다 (경기장에 온 사람, 무효 경기 제외, 승패·나감 무관) → **큐디치 jar 는 안 바꿈**.
  (배포 큐디치는 소스보다 오래돼서 소스로 바꿔 끼우면 안 됨.)
- 원래 소스 3개 파일 = 배포 `magic-codex-bridge-final-20261024-school.jar` · `-arena.jar` 와 javap 비교로 같음.

## 반영 순서 (규칙 세우기 세션, 다음 정기 점검)
1. 학교 `MagicCodexBridge` jar: `AscensionBridge*.class`, `StoryProgressEvent.class`, `ChacaApi*.class` 교체.
2. 아레나1·2 `MagicCodexBridge` jar: `ChacaApi*.class` 교체 (도장은 아레나에서 찍힘). 나머지 둘도 같이 바꿔도 무해.
3. 학교 스토리 jar: `QuestEngine*.class`, `QuestDefs*.class` 교체.
4. 학교 `plugins/ChacademyStory/steps.yml` 과 `dialogues/` 6개 폴더 반영. **steps.yml 은 반영 직전 서버 것과 다시 대조** (그사이 게임 안 명령으로 바뀌었을 수 있음).
5. 재시작 뒤 확인: `/대화관리 단계 점검`, 아무 계정으로 ch1-29 → 결투 → 승급 한 번.

## 확인한 것 / 못 한 것
- ✔ 바뀐 클래스 전부 배포 jar 기준 javac 컴파일 (학교 스토리·학교 마법·아레나 마법).
- ✔ 새 steps.yml 을 바뀐 `QuestDefs.parse` 로 읽음: 경고 0, ch1-27→28→29→30→31→32 연결, 조건·호출·대기대화·시작명령 정상.
- ✔ 대화 YAML 6개 읽힘.
- ✘ jar 빌드·게임 안 확인 안 함. 승급 창이 시작명령으로 잘 뜨는지(권한 반영 타이밍)는 게임에서 봐야 함 — 안 뜨면 HUD 문구대로 `/클래스승급`.
- 세드릭 NPC 37 = 오너가 2026-10-28 16:35 배치 (Citizens 저장 파일엔 아직 없었음).
