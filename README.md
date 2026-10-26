# inbox — 다른 세션·GPT 작업 받는 곳

여기서 새 브랜치를 만든다: `git switch -c inbox/<주제>-<YYYYMMDD> origin/inbox`

- 작업은 그 브랜치에만 커밋·푸시한다.
- 맨 위에 `NOTE.md` 로 무엇을·왜·어디에 영향이 가는지 적는다.
- 빌드·서버 반영·런처 배포는 하지 않는다. Claude 가 대조 후 반영하고, 반영이 끝난 브랜치는 정리한다.

규칙: `rules` 브랜치 `RULES.md`, `AGENTS.md`.
