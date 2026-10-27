# build — 지금 실제로 배포된 것

**이 브랜치가 "최신 기준"의 기록이다.** 서버·런처에 무엇이 깔렸는지 해시로 남긴다. (2026-10-27 오전 두 번째 재시작 직후 기준)

| 파일 | 내용 |
|---|---|
| `server-plugins.tsv` | 서버 6곳(velocity, school, arena1, arena2, wild1, wild2)의 `plugins/*.jar` 전체 — 이름, 크기, SHA256 |
| `launcher-mods.tsv` | 런처가 까는 Fabric 모드 — id, 크기, SHA256, 다운로드 주소 (리소스 파일 전체 목록은 `resources` 브랜치 `launcher-content.tsv`) |
| `jars/` | 2026-10-27 Claude 가 만들어 배포한 서버 jar 원본 |

## jars/
모두 "운영 jar 에 바뀐 클래스만 갈아 끼운 것"이다. 같은 수정이 `source` 브랜치 소스에도 들어가 있다.

| 파일 | 배포 위치 | 내용 |
|---|---|---|
| `magic-codex-bridge-school.jar` | school `magic-codex-bridge-final-20261024-school.jar` | 운영 jar + 가방 잠금 수정 + 정문 3단계·희망 기숙사(+1) + 상태 보고 전용 DB 설정 + 공통 수정(아래) |
| `magic-codex-bridge-arena.jar` | arena1·arena2 `magic-codex-bridge-final-20261024-arena.jar` | 운영 jar + 가방 잠금 수정 + 상태 보고 전용 DB 설정 + 공통 수정 |
| `magic-codex-bridge-wild.jar` | wild1·wild2 `magic-codex-bridge-final-20261024-wild.jar` | 운영 jar + 가방 잠금 수정 + 상태 보고 전용 DB 설정 + 공통 수정 |
| `magic-mythic-school.jar` · `-arena` · `-wild` | 각 서버 `chacademia-magic-mythic-final-20261024-<역할>.jar` | 운영 jar + 질풍 연격 다음 타 입력 2초 → 5초 (`NovaMid`) |
| `chaca-housing-school.jar` · `-wild` | school·wild1·wild2 `chaca-housing-0.1.2-usersearch.1-<역할>.jar` | 운영 jar + 창 없이 온 방문 요청 처리 (정보창 "내 방 가기") |
| `chaca-npc-school.jar` | school `chaca-npc-0.3.0-npcmodel.3.jar` | 운영 jar + NPC 이동 수정 (사람 있으면 직선 이동, 안 불러온 청크는 다시 소환, 1칸 겹침 분산) |
| `chacademy-story-school.jar` | school `chacademy-story-plugin-final-guide-20261024.jar` | 운영 jar + 새 `QuestEngine` (분류 대화 ch1-19) |
| `chacademi-ops-dashboard-0.1.1.jar` | 호스팅·저쪽 `C:\Chacademi\ops-dashboard\` | 운영 대시보드 |

Bridge 공통 수정 (2026-10-27):
- `equipmentPower` 의 null 지능 언박싱 NPE 수정 — 길잡이 별 등 마법 시전 실패의 원인
- 발견한 마법 수 × 지능 0.2 (`DiscoveryIntel`, 발견 수 = 스탯창 값, 10초 캐시)
- 수업: 내 멘트가 끝난 뒤(1.5초) 수업 창, 상단 제목 없음, 선택지 "[중급] 이름 - 지능 N", "(지능 부족)" 없음
- Z 일반 프리셋이 실제로 바뀔 때 책장 소리

클라이언트 MagicCodex 모드(433MB)는 너무 커서 여기 없다 → 런처 릴리스 `content-client-titlebar-20261027` (`launcher-mods.tsv` 의 주소).
이 빌드는 `content-lootbox-access-20261027` 모드에서 `DialogueScreen` 하나만 바꿨다 (제목이 비면 상단 막대를 그리지 않음).

## 서버 쪽 설정 (jar 밖)
- 각 서버 `plugins/MagicCodexBridge/heartbeat-database.properties`: 상태 보고(TPS·MSPT 등) 전용 DB 접속. 같은 폴더 `player-state-database.properties` 를 복사한 것 (공유 MariaDB `chacademiacodex`). 비밀번호가 들어 있어 저장소에는 올리지 않는다.
  `database.properties` 는 일부러 만들지 않는다 — 다른 기능의 저장 위치까지 바뀐다.
- `titles.yml` 에 기숙사 칭호 4개(`prefix_house_*`) 추가 (2026-10-27) → `resources` 브랜치 `deployment/operating-config/MagicCodexBridge/titles/`.
- school `books.yml`: 비전 수업 6개 필요 지능 0 → 60 (2026-10-27, 호감도 30 은 `lessons.yml` 에 원래 있음) → `resources` 브랜치.
- 5대 `PortableVFX/spell-bindings.yml`·`spell-catalog.yml`: `guiding_star` 의 노란 빔(`external:form` 단계 3개) 삭제 (2026-10-27). 하얀 홀로그램 화살표는 스토리 모드 쪽이라 그대로.

## 갱신 방법
배포할 때마다 두 목록을 다시 뽑고, 새로 만든 서버 jar 를 `jars/` 에 넣어 커밋한다.
되돌리기가 필요하면 이 브랜치의 이전 커밋에서 해시·파일을 찾는다.
