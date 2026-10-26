# 1장 정문 3단계 + 희망 기숙사 가산점 (Claude, 2026-10-27, 오너 요청)

브랜치 `claude/opening-final-20261025`. **빌드 필요: MagicCodexBridge(magic-codex-paper) + ChacademyStory(chacademy-story/plugin).** 이 환경은 Gradle 저장소에 못 붙어 빌드·JUnit 을 못 돌림 — HouseSorting 은 javac 로 따로 확인함.

## 바뀐 코드
- `SchoolBridge`: 분류 대화 ID `ch1-17` → `ch1-19` (6문항 `ch1-19`, 결과 `ch1-19r-/m-/f-<집>`).
  새 하위 명령 `/기숙사분류 희망 <유저> <w_arkeon|w_lumina|w_bestiaz|w_noxer>` — 메모리 맵에 저장, `결정` 때 꺼내 쓰고 지움 (재시작 때 사라지면 가산점 없이 분류).
  탭 완성·usage·plugin.yml usage 에 `희망` 추가. 배정 기록(AuditLog)에 "· 희망 ○○".
- `HouseSorting`: `scores/preferred/choose(..., wish, bonus)` 추가 (기존 시그니처는 wish=-1 로 위임). 가산점은 점수에 더하고, 인원 허용치 필터가 그대로 먼저 적용.
- `SchoolStore.assignHouse(id,t,d,tolerance,wish,bonus)` 추가.
- `config.yml` `house-sorting.wish-bonus: 1` (0~6, 잘못된 값이면 1).
- `QuestEngine.SORTING_DIALOGUE` → `ch1-19`.
- `HouseSortingTest` 에 가산점 테스트 1개.

## 같이 배포 (학교 서버, 파일)
- `plugins/ChacademyStory/dialogues/`: `ch1-16`~`ch1-26`, `ch1-19r/m/f-*` 12개, `ch1-21w-lowell`, `ch1-25w-isolde`, `server_command.yml`
  - 서버에 남은 옛 `ch1-17r/m/f-*`, `ch1-19w-lowell`, `ch1-23w-isolde` 폴더는 삭제
- `plugins/ChacademyStory/steps.yml` (deployment/operating-config/ChacademyStory/steps.yml)
- **새 jar 와 새 파일은 같이 올려야 함**: 옛 jar + 새 파일이면 분류가 옛 `ch1-17`(지금은 그레고르 대화)을 띄움.

## 주의
- 진행 중인 유저의 저장된 단계 이름(예: ch1-18 로웰)이 번호 밀기로 다른 단계를 가리키게 됨. 오픈 전이라 문제 없지만, 테스트 계정은 `/유저관리 <닉> 스토리진행 …` 로 맞출 것.
- ch1-16·17·18 호출 위치는 월드가 비어 있음(게임 안에서 오너가 지정) → 지정 전에는 그 단계에서 멈춤.
