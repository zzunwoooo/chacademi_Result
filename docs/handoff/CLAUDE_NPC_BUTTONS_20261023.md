# ChacaNPC NPC별 추천 버튼 (2026-10-23)

- 브랜치 `claude/npc-buttons-20261023` (← `claude/npc-model-20261021` @ e00ab67, NPC 3D 외형 포함). `claude/story-steps-20261020` 의 ChacaNPC 한 줄(`NpcManager.onRightClick ignoreCancelled`)과 함께 통합할 것.
- 캐릭터 yml `buttons:` (3개까지, id `[a-z0-9_-]{1,32}`, 같은 NPC 안 중복 불가) → 그 NPC 만 이 버튼. 없으면 `config.yml dialogue.buttons` 공통.
- 바뀐 곳: `CharacterSheet`(+`buttons`, `buttonsOr`), `CharacterRepository.buttons`(읽기·검사·경고), `DialogueService` 4곳(창 열기 2 · 버튼 입력 · 미리 만들기 warmup) → `c.buttonsOr(st.buttons)`.
- 미리 만드는 대답(button-cache)은 원래 NPC id·버튼 id 별 키라 그대로 동작. NPC 마다 버튼 글이 달라도 비용 구조는 같음 (버튼 3개 × 변형 수).
- 콘텐츠: chacademi-server `claude/story-ch1-20261020` `npc/ChacaNPC/npcs/` 85명 (버튼 추가) → 학교 서버 `plugins/ChacaNPC/npcs/`, 초상화 `resourcepacks/chacademia_npc` (95장) → 런처.
- 확인 못 함: 빌드. 실서버에서 NPC 별 버튼 표시·눌렀을 때 대답·캐시 대답.

## 2차 — 버튼 역할·마법 힌트 (오너 결정 2026-10-23)

- 버튼 1 daily = NPC 마다 다른 인사 · 2 help = "도와줄 일 있어?"(말투만 맞춤, 나중에 의뢰·호감도 퀘스트 연결) · 3 magic = 마법 힌트.
- 마법 힌트: 호감도 **모르는 사이·아는 사이 = 힌트 없음** → 프롬프트에 "음… 잘 모르겠는데?"처럼 얼버무리라는 지시 (`Context.magicAsked`). **친한 사이 이상** = hints.yml 목격담을 "그러고 보니 예전에 어떤 마법사가 마법을 쓰는 걸 봤어… 그때 ~해서 ~하더라" 식으로 돌려 말함 (마법 이름 금지, 목격담 밖 내용 지어내기 금지).
- 바뀐 곳: `DialogueService.pickHint`(낮은 단계 null, `magicAsked` 분리), `generateGeneric`(마법 버튼 캐시 대답 = 얼버무림), `PromptBuilder`(지시문, `hintText` "목격담 ="). 기존 테스트(`hintText` 에 materials 포함)는 그대로 통과해야 함.
- 콘텐츠: chacademi-server `npc/ChacaNPC/hints.yml` 170개 (85명 × 2, topic = NPC id, 실제 마법 59종 — 궁극·수업 습득·몹 처치·서클 조건 제외, 생활·직업 관련 위주), 캐릭터 파일 `hint_topics: [<id>]`. 운영 hints.yml 의 예시 항목(moon_water 등, 없는 마법)은 교체.
