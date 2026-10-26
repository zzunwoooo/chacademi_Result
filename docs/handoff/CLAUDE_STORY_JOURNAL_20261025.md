# 인계: 퀘스트창 메인 스토리 = 서버 단계 기록 · 컷신 "본 기록" 서버 저장 (Claude 2026-10-25)

브랜치 `claude/story-journal-20261025` (기준 `claude/opening-prep-20261025`).

## 왜
퀘스트창 메인 스토리와 컷신 건너뛰기 기록이 런처 파일(`config/chaca_dialogue/_progress/*.properties`, `config/chaca_cutscene/_seen.txt`)에 있어서,
런처를 바꾸거나 폴더를 지우면 사라지고 서버 단계(steps.yml 진행)와 어긋났다.

## 바뀐 것
- **ChacademyStory**: `public List<Map<String,String>> journalRows(UUID)` — 단계 정의 순서대로 끝낸 단계(history) = claimed, 지금 단계 = active.
  제목·장은 서버 dialogue.yml 의 title/chapter (대화 없는 단계는 설명·장). 지금 단계는 조건 진행(표시 n / m)을 details 로.
  `QuestProgress.MAX_HISTORY` 64 → 600.
- **컷신 본 기록**: `PlayerStory.seen` (progress 저장에 `seen:` 목록). 끝까지 보면(건너뛰기·파일 없음 제외) 기록.
  AUTO 모드 재생은 서버가 정함: 본 적 있으면 MODE_SKIP, 없으면 MODE_NOSKIP. 런처 `_seen.txt` 는 서버 재생에 안 쓰임.
- **MagicCodexBridge JournalBridge**: 메인 스토리 줄을 `storyRows()` (ChacademyStory 리플렉션)로 만들어 단계 순서대로 붙임. 장 이름은 Entry.group.
  클라가 보내던 스토리 목록(kind 5)은 무시. 관리 명령으로 story 기록을 바꾸면 `/유저관리 <닉> 스토리진행 단계 <단계>` 안내.
- **MagicCodex(Fabric)**: `JournalClient.rows()` 서버 기록만 (런처 대화 기록 안 섞음). `JournalTree` 메인은 서버 순서 그대로, 장 = group.

## 같이 배포
ChacademyStory · MagicCodexBridge · MagicCodex(Fabric). Entry.group(JournalProtocol v2)은 affinity-gifts 브랜치에서 이미 들어옴.

## 확인 못 한 것
이 환경에 Paper/Fabric 의존성이 없어 전체 빌드 못 함 (문법 검사, StoryState seen 저장·복원, 일지 트리 순서 단독 테스트만 통과).
