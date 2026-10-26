# 기숙사 분류 빌드 및 기존 업데이트 확인 — 2026-10-20

## 기준과 백업
- 운영 서버 소스: `C:\Chacademi\staging\duel-lobby-polish-20261019`, 작업 전 `ef55623675a29ef965868878ae58c1500bcfd2b2` (`codex/operating-final-20261019`). 추적 파일 미커밋 변경은 없고 미추적 도구 파일은 백업했다.
- 백업: `C:\Chacademi\backups\house-sorting-20261020-task12` (`source-head.tar`, `tracked.patch`, `untracked.tar`, HEAD·status·SHA256 기록).
- 별도 기존 `build-workspace`의 채팅/귓속말 미커밋 변경은 건드리지 않았다. 해당 경로는 최신 운영 소스가 아니므로 병합 기준으로 삼지 않았다.
- 기존 운영 파일 교체, 프로세스 재시작, DB 배정 변경, 공개 배포, 아티팩트 새로고침은 수행하지 않았다.

## 기믹 업데이트: 이미 반영
- `005b977`, `80cfb69`, `2883264`는 전부 `ef55623`의 조상이다. 인계 문서를 확인했다. 중복 cherry-pick하지 않았다.
- 기존 배포 계획의 운영 파일 615/615 SHA256 일치, 보호 파일 151/151 일치. 세 서버의 JAR·설정·기믹/VFX 배포 파일을 포함한다. `existing-gimmick-operating-hashes.json` 참고.
- 오늘 런처 manifest 정상화는 별도 런처 작업본 `C:\Chacademi\staging\launcher-assets-manifest-fix-20261020\source`의 `887d660`이다. 해당 파일들의 기준 해시를 보존했으며 수정하지 않았다.

## 이번 구현
- Story 종료 신호 action 1: codec의 non-null/ID 검증 → 플레이어·토큰·ready·만료 검사 및 세션 소진 → `{ending}` 리터럴 치환 후 명령 실행. 허용 문자 `[a-z0-9_-]{1,64}` 때문에 공백·개행·중괄호·정규식 replacement 주입이 불가능하다. `String.replace`를 사용한다.
- `/기숙사분류 시작 <현재 서버 접속 유저>`: DB 소속이 있으면 `ch1-14f-<house>`, 없으면 `ch1-14`.
- `/기숙사분류 결정 <유저> <p_TD>`: T,D 각각 0~3. 아르케온=T+D, 루미나=T+3-D, 베스티아즈=3-T+D, 노크세르=6-T-D. 기존 소속은 재저장/재신호 없이 r 대사. 새 배정은 1순위 유지 시 r, 변경 시 m 대사.
- 4개 기숙사 0명 포함. 점수 내림차순, 인원 오름차순, 최종 동점은 arkeon→lumina→bestiaz→noxer. 16개 정수 프로필의 최상위 점수는 항상 유일함을 테스트했다.
- 하나의 IO 작업 안에서 DB 트랜잭션을 열고 공용 잠금 행 확보 → 기존 소속 재확인 → 전체 집계 → 저장 → commit. 기존 관리자 소속 저장도 같은 잠금을 사용한다. 단일 Executor만으로는 여러 서버를 보호할 수 없어 추가한 안전성 수정이다.
- 배정 전 인원 <= 최소인원+허용치 정책 유지. 기본 2이면 배정 직후 차이 3이 가능하다. 배정 후 2명 제한으로 바꾸지 않았다.
- `/기숙사분류 현황`: 전체 DB 인원과 최대-최소 차이, 차이>5 경고.
- `/기숙사분류 허용치 <정수>`: 0~1,000,000; config `house-sorting.population-tolerance`, 저장 실패 시 메모리 값 복원. config의 소수/문자열은 정수로 몰래 변환하지 않고 오류 처리한다.
- 관리자 권한 `magiccodex.school.admin`과 콘솔만 허용하며 명령 블록은 제외. 유저·프로필·하위 명령 탭 완성. 오프라인 유저는 새 명령 접수 거부. 작업 도중 로그아웃하면 저장된 결과를 알리고 재시도를 안내한다.
- DB commit 이후 메인 스레드에서 배정 신호와 Bukkit 명령 실행. 저장 후 스냅샷 갱신 실패는 저장 실패로 잘못 알리지 않도록 보완했다. 대사 명령 실패 시 이미 저장된 소속을 유지하고 결정 재시도를 안내한다.

## 검증과 제한
- Bridge build, Story build, 전체 서버 `verifyServer assembleServer` 성공 (결과 JSON 및 로그 참조).
- 신규 배정 테스트 6개: 16개 프로필, 배정 전 허용치, 0명 포함, 기존 배정 유지·재접속 보존, SQLite 독립 연결 4개(중복 1명+신규 80명), 실패 주입 롤백, H2 MySQL 호환 경로 독립 연결 동시성. 종료 세션 테스트 5개 중 신규 placeholder/주입/재전송 테스트 포함.
- H2는 실제 MariaDB가 아니다. MariaDB row-lock 경로를 코드 검토했고 호환 SQL 동시성을 테스트했지만 이번에는 운영 DB 쓰기나 실서버 게임 QA를 하지 않았다.
- 구버전 서버나 외부 SQL 도구가 새 잠금을 따르지 않고 동시에 소속을 변경하면 그 쓰기까지 직렬화할 수 없다. 활성 배정 writer를 모두 업데이트한 후 분류를 사용해야 한다.
- DB commit과 대사 재생은 하나의 트랜잭션이 될 수 없다. dispatch 반환 true는 클라이언트에서 대사를 끝까지 재생했다는 보장이 아니다. 로그아웃/스토리 busy/자산 누락/플러그인 비활성화/프로세스 종료로 대사가 미실행이면 저장 소속을 기준으로 결과 대사를 재시도한다. durable outbox는 이번 범위에 추가하지 않았다.
- 현재 저장소에 `ch1-14*` 대사 파일이 없고, 서버에는 `server_command.yml`이 따로 있다. 실제 클라이언트에 시작/기존/r/m 대사가 준비되어 있는지 확인해야 한다. payload의 두 YAML은 전체 덮어쓰기용이 아닌 병합 참고 조각이다. 실제 `commands.ch1-14` 종료 매핑과 클라이언트 분기 ending `p_00~p_33`을 확인한 후 병합해야 한다.
- 설정 허용치 변경은 서버별 config에 저장된다. 공유 DB를 쓰더라도 각 서버의 허용치는 자동 동기화하지 않는다.

## 추가 질문: 아티팩트 CC 7882171
- 지정 브랜치 fetch와 인계 문서 읽기만 수행했다. `7882171`은 현재 HEAD 조상이 아니고 실제 소스에도 `MagicStatusEvent`/`controlTaken`이 없다.
- 학교·야생·아레나의 운영 Bridge에는 새 listener/method가 없고 MagicMythic에는 `MagicStatusEvent.class`가 없다. 실제 `artifacts.yml`도 이전 균형의 저울 문구이며 굳센 심장/닻 장식/잠깨기 종이 없다.
- 따라서 **7882171 업데이트는 미반영**이다. 기존 원소 반응 CC/VFX는 보존했다. 이번 질문만으로 통합·배포하지 않았다.
- 당일 및 latest 로그에 기존 MagicMythic 연결 기록은 있지만 `/아티팩트관리 새로고침` 기록은 없다. 새로고침 여부는 불명이며 실행하지 않았다. `artifact-cc-readonly-audit.json` 참고.

## 배포 승인 전에 준비할 것
- 이번 신규 JAR은 payload 폴더의 Bridge `...houses.1.jar`, Story `...houses.1.jar`이며 SHA256은 `build-validation.json`에 있다.
- 승인 후 기존 운영 파일 백업, 관련 서버의 Bridge와 Story 설치, 두 YAML의 필요한 항목 병합, 모든 분류 대사/ending 확인, 서버 재시작 및 게임 QA가 필요하다. 이번 작업은 이 단계들을 실행하지 않았다.
- 아티팩트 CC 업데이트는 별도 검토·통합·테스트·설정 병합 후 MagicMythic/Bridge 동시 배포와 아티팩트 새로고침이 필요하다.
