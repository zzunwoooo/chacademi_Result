# build — 지금 실제로 배포된 것

**이 브랜치가 "최신 기준"의 기록이다.** 서버·런처에 무엇이 깔렸는지 해시로 남긴다. (2026-10-27 서버 재시작 직후 기준)

| 파일 | 내용 |
|---|---|
| `server-plugins.tsv` | 서버 6곳(velocity, school, arena1, arena2, wild1, wild2)의 `plugins/*.jar` 전체 — 이름, 크기, SHA256 |
| `launcher-mods.tsv` | 런처가 까는 Fabric 모드 — id, 크기, SHA256, 다운로드 주소 (리소스 파일 전체 목록은 `resources` 브랜치 `launcher-content.tsv`) |
| `jars/` | 2026-10-27 Claude 가 만들어 배포한 서버 jar 원본 |

## jars/
| 파일 | 배포 위치 | 내용 |
|---|---|---|
| `magic-codex-bridge-school.jar` | school `magic-codex-bridge-final-20261024-school.jar` | 운영 jar + 가방 잠금 수정 + 정문 3단계·희망 기숙사(+1) |
| `magic-codex-bridge-arena.jar` | arena1·arena2 `magic-codex-bridge-final-20261024-arena.jar` | 운영 jar + 가방 잠금 수정 |
| `magic-codex-bridge-wild.jar` | wild1·wild2 `magic-codex-bridge-final-20261024-wild.jar` | 운영 jar + 가방 잠금 수정 |
| `chacademy-story-school.jar` | school `chacademy-story-plugin-final-guide-20261024.jar` | 운영 jar + 새 `QuestEngine` (분류 대화 ch1-19) |
| `chacademi-ops-dashboard-0.1.1.jar` | 호스팅·저쪽 `C:\Chacademi\ops-dashboard\` | 운영 대시보드 |

클라이언트 MagicCodex 모드(433MB)는 너무 커서 여기 없다 → 런처 릴리스 `content-lootbox-access-20261027` (`launcher-mods.tsv` 의 주소).

## 갱신 방법
배포할 때마다 두 목록을 다시 뽑고, 새로 만든 서버 jar 를 `jars/` 에 넣어 커밋한다.
되돌리기가 필요하면 이 브랜치의 이전 커밋에서 해시·파일을 찾는다.
