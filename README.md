# resources — 콘텐츠와 리소스 목록

## 들어 있는 것
| 경로 | 내용 | 실제 위치 |
|---|---|---|
| `deployment/story-dialogues/` | 스토리 대화 (dialogue.yml), `server_command.yml` | 학교 `plugins/ChacademyStory/dialogues/` |
| `deployment/operating-config/` | 운영 설정. `ChacademyStory/steps.yml` 은 **학교 서버 실제 파일**(게임 안에서 정한 위치·NPC 포함, 2026-10-27) | 각 서버 `plugins/` |
| `deployment/cutscenes/` | 컷신 (cutscene.yml) | 런처 → `config/chaca_cutscene/<id>/` |
| `deployment/mythicmobs/`, `worldguard/`, `tab/`, `donation/`, `structures/` | 서버 플러그인 설정·구조물 | 각 서버 |
| `binary-assets.tsv` | 소스 폴더 안의 그림·폰트·소리·모델 목록 (경로, 크기, git blob). 파일 자체는 옛 저장소 `chacademi-magiccodex-source@4548861` 에 있음 | 모드 jar 안 |
| `launcher-content.tsv` | 런처가 플레이어에게 까는 파일 전체 목록 (설치 경로, 크기, SHA256, 다운로드 주소) — 2026-10-27 배포 기준 | 런처 `distribution.json` |

## 규칙
- NPC 대화 그림: `resourcepacks/chacademia_npc.zip` 한 곳 (ItemsAdder `contents` 에 넣지 않음).
- 큰 파일(100MB 넘음)은 GitHub 에 못 올린다 → 런처 릴리스에 올리고 `launcher-content.tsv` 로 추적.
- 런처에 새 리소스를 넣거나 바꾸면 이 목록도 갱신한다.
