# source — 차카데미 소스 코드

서버 플러그인(Paper/Velocity), 클라이언트 모드(Fabric), 운영 대시보드의 소스.
그림·폰트·소리·모델 같은 바이너리는 여기 없다 → `resources` 브랜치의 `binary-assets.tsv` 목록 참고.
옛 저장소의 변경 기록·인계 문서는 `planning` 브랜치 `docs/handoff/`.

## 출발점 (2026-10-27)
- `chacademi-magiccodex-source` 의 `claude/opening-final-20261025` 커밋 `4548861` (정문 3단계 + 희망 기숙사 가산점)
- 그 위에 2026-10-27 수정:
  - **가방 코드 (`magic-codex-paper/.../Bag*.java`)**: 실제 서버에서 도는 버전(호스팅 `staging/build-ready-20261026-task4`, `BagInventoryProof` 포함)으로 교체하고,
    `BagHuskSnapshotLink` 에 "가방 확인 실패·지연 시 플레이어 잠금을 풀고 가방만 잠금" 수정을 넣음.
  - **대화창 초상화 (`magic-codex-fabric/.../DialogueScreen.java`, `NpcTalkScreen.java`)**: 그림 크기와 상관없이 통째로 그림 (512×768 초상화가 안 보이던 문제).
  - **운영 대시보드 (`ops-dashboard/`)**: `server.<id>.manage-url` → "관리 열기" 버튼, 하트비트 DB 가 없으면 핑으로만 켜짐/꺼짐 판단, `scripts/Server-Control.ps1` (콘솔 창 없이 시작·RCON 정지).

## ⚠️ 실제 배포본과 다른 점 (중요)
**최신 기준은 실제 서버·런처에 깔린 파일이다.** 이 소스로 통째로 빌드해 덮어쓰지 말 것.
서버 쪽 jar 는 그동안 여러 곳에서 고친 조각이 섞여 있어서, 이 소스와 정확히 같지 않다.

| 배포본 | 이 소스와의 관계 |
|---|---|
| 학교 MagicCodexBridge (`build` 브랜치 참고) | 운영 jar 에 정문 분류 클래스(`HouseSorting`·`SchoolBridge`·`SchoolStore`)와 가방 클래스만 이 소스로 바꿔 끼움. 나머지 클래스는 운영 jar 그대로이고, 이 소스와 다른 곳이 많음 |
| 아레나·야생 MagicCodexBridge | 운영 jar 에 가방 수정 클래스만 바꿔 끼움 |
| 학교 ChacademyStory | 운영 jar 에 `QuestEngine` 만 이 소스로 바꿔 끼움 |
| MagicCodex 클라이언트 모드 | 운영 jar(소스 없음, nameplate-nullguard 6cfa5a95)에 초상화 그리기 숫자 2곳·상자 화면 접근 범위 2곳만 바이트 수정 |

다음 작업도 같은 방식: **실제 배포본을 기준으로, 바뀐 클래스만** 이 소스에서 빌드해 끼우고 `build` 브랜치 목록을 갱신한다.
빠진 소스(운영 jar 에만 있는 수정)는 찾는 대로 여기에 채운다.
