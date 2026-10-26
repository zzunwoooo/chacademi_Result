# 오픈 최종 통합 (Claude 2026-10-25 밤) — 운영본 + 검수 승인분 + 스토리 3갈래

브랜치 `claude/opening-final-20261025`, 기준 = 운영 최종본 `review/live-source-handoff-20261025-task3` @ `c96c5ae3` (GPT 인계 [LIVE_SOURCE_HANDOFF_20261025](LIVE_SOURCE_HANDOFF_20261025.md)).
**빌드·테스트 안 함** (이 환경엔 Paper/Fabric 의존성 없음) — 문법 검사만. 호스팅에서 빌드 + 전체 테스트 필수.

## 들어간 것 (순서대로 merge)
1. `claude/opening-prep-20261025` @ `580ba3cd` — 오픈 전 메모 오너 검수 승인분 전부 (운영본에 없던 29+커밋).
   - 운영본에 GPT 가 이미 넣은 칭호(#21, `40e2f694`)는 **운영본 파일 그대로 유지** (칭호 파일 8개 = 운영본과 동일). 중복 테스트 1개·검수표 중복 줄 정리.
   - 새로 들어가는 것: #29 투명도 셰이더 보정 되돌림 + 원소 반응·기믹·큐디치 성소 투명도 제외 / #33 큐디치 전투 보정(MagicMythic QuidditchCombat + ChacaKyudichi MagicMatchEvent) /
     #38 운영자 AI 도우미 `/관리 질문` (Bridge OperatorHelp + ChacaNpcApi.ask) / #40 마법 아이콘 경로 26개 / #47 마법 효과음(Bridge SpellSounds + spell-sounds.yml 237개, 클라 예전 시전음 끔) /
     #51 하우징 랭킹 `/하우징랭킹` (새 채널 chacademi:housing_rank, 친구 아니어도 방 이동) / 검수표·인계 문서.
2. `claude/story-journal-20261025` @ `b87b9870` — 퀘스트창 메인 스토리 = 서버 단계 기록, 컷신 본 기록 서버 저장, `/유저관리 <닉> 스토리진행 단계 <단계>`. 인계 [CLAUDE_STORY_JOURNAL_20261025](CLAUDE_STORY_JOURNAL_20261025.md).
3. `claude/ch1-ole-guide-20261025` @ `7d30b93f` — 길잡이 별 발견 = 1장 올레에게 배움(manual-conditions.yml, ManualConditionsTest 90→91), 1장 대화 번호 = 단계 번호(분류의 거울 ch1-15 → **ch1-17**).
   - 충돌 2곳 정리: `132_guiding_star.yml` = 운영본 설명(최신) + 발견 문구만 올레, `known-hashes.tsv` = 양쪽 합침 + 운영본·올레 132 해시 추가(클라 자동 갱신).
4. `claude/rod-lore-20261025` @ `8d8eb489` — 낚싯대 설명에서 찌·낚싯줄·미끼 줄 제거.

들어가지 않은 것: #14 상점 교환 기능(작업 중 중단, 로컬 stash 에만 있음, 브랜치 없음).

## 같이 배포할 것
MagicCodexBridge · MagicCodex(Fabric 모드) · MagicMythic · ChacaKyudichi(아레나) · ChacaNPC · ChacaHousing · PortableVFX(클라) · ChacademyStory(플러그인+모드) · MagicDiscovery.
프로토콜이 바뀐 채널: chacademi:housing_rank(새), 칭호 v2(운영본에 이미 있음). 서버 jar 와 클라 모드는 같은 릴리스로.

## 운영 파일 (자동으로 안 바뀜 — 직접 반영)
- **1장 대화 번호 ch1-15 → ch1-17**: 운영 서버 `ChacademyStory/dialogues/` 의 분류의 거울 대화 `ch1-15`, 결과 `ch1-15r-/m-/f-<기숙사>` 파일 이름·ID 를 `ch1-17…` 로, `dialogues/server_command.yml` 키 `ch1-15:` → `ch1-17:`, 운영 `steps.yml` 의 해당 단계 대화 번호도 같이. 안 맞추면 기숙사 분류가 안 뜸.
- MagicDiscovery: 운영 `manual-conditions.yml` 에 길잡이 별(guiding_star) 수동 조건, 운영 `discoveries.yml` 아이콘 26개(87·88·300~323) 경로.
- PortableVFX: 운영 `spell-catalog.yml` 아이콘 26개 경로.
- MagicCodexBridge: `spell-sounds.yml` 은 없으면 jar 기본이 깔림. 칭호 파일은 운영본 그대로라 추가 작업 없음.
- MagicMythic `config.yml` `quidditch-combat:` 칸은 없어도 기본값으로 동작.
- ChacaNPC: `/관리 질문` 은 ChacaNPC config 의 OpenAI 키를 씀.

## 확인할 테스트 (새로 생김)
QuidditchCombatTest(MagicMythic) · OperatorHelpTest · SpellSoundsTest(Bridge) · HousingRankProtocolTest(Fabric) · TitleAwardsTest/TitleTest(운영본) · ManualConditionsTest(91) · GuidingStarDescriptionMigrationTest · DefaultSpellMigrationTest.
