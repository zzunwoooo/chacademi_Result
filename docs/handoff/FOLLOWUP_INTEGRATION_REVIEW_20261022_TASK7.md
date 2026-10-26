# 최신 통합 비교·UI 수정 검증 (배포 전)

기준: 명찰 자유 문구/포인터 6084988c, suffix 색 fef50180, Story cdca568 및 콘텐츠 edb56ad. 작업 트리 `C:/Chacademi/staging/story-nameplates-bundle-20261022-task7`, 브랜치 `fix/menu-ranking-followup-20261022-task7`. 모델·피피 공개 런처 기준은 2ef9f1c이며 이번 작업에서 운영/실제 프로필/피피는 수정하지 않았다.

| 옛 수정 묶음 | 비교 결과 | 처리 |
|---|---|---|
| economy / progress / combat / client / config / dbtool | 운영 통합 45962cac가 HEAD 조상, `git cherry HEAD ref` 미반영 0 | 재적용하지 않음 |
| Story 수정 | Story 통합 e73ddb26도 HEAD 조상, 미반영 0 | 최신 cdca568·명찰·NPC 높이 연동 유지 |
| launcher-assets | b72a2f9, 3c147b5만 미반영 | 최신 런처 2ef9f1c 위 별도 트리에 해당 7개 코드/문서 파일만 적용 |
| 런처 버전 | 현재 0.1.22 | package.json/lock만 0.1.23 준비, 게시 안 함 |
| 운영 설정/DB | 코드 포함과 별개로 미적용 값 존재 | 복제본 리허설만, 운영 변경 없음 |

## 메뉴·아이콘·툴팁

순서: 마일리지샵 → 내 정보 → 소셜 → 이동 → 도감 → PVP → 퀘스트 → 우편함 → 설정. 기존 간격/크기/애니메이션과 내 정보·소셜·도감 하위 기능을 유지한다. 이동은 승인 나침반 아이콘을 표시하되 비활성, PVP는 기존 `/큐디치` 서버 명령으로 연결한다. 마일리지샵은 기존 캐시샵 명령을 유지한다.

Library 승인 ZIP libfile_7589973d940c8191a19a042790ae3089의 원본 PNG 4개를 그대로 사용했다. travel-compass→travel-menu, pvp-wands→pvp-menu, codex→codex-menu, fish-codex→fish-codex-menu. 모두 1254×1254, 실제 알파 경계를 측정하고 기존 22px 광학 크기에 맞춰 UV만 설정했다. 승인 preview와 사용자 랭킹 스크린샷도 실제로 열어 확인했다. 새 이미지는 생성하지 않았다.

채팅 화면에서 메뉴 툴팁은 HUD 단계에서 중복 출력하지 않고 ScreenEvents.afterRender에서 채팅 위 Z로 출력한다. 다른 GUI의 툴팁 경로와 채팅 입력/클릭 라우팅은 바꾸지 않는다. 기존 Ctrl 포인터는 그대로다. 실제 인게임 화면 검수는 미실시다.

## 큐디치·1:1 닉네임

원인: 랭킹/상세/전적/상대 표시가 경기 DB에 기록된 이름 스냅샷을 그대로 보냈다. 기존 UUID·mode·경기 키·전적 행은 변경하지 않았다. 프록시의 기존 DB IO worker에서 공용 `nicknames`를 읽어 현재 한글 닉네임을 표시하고, 빈 닉네임은 계정명으로 폴백한다. 검색도 현재 닉네임/계정→UUID로 해석한다. 렌더 루프/메인 스레드 DB 조회는 없다.

운영 닉네임은 현재 school/wild/arena별 SQLite이고 `use-for-all-domains=false`; 큐디치만 MariaDB라 공용 SQL만으로는 현재 운영을 고칠 수 없다. 따라서 기존 신뢰 서버 identity 검증을 통과한 이름을 프록시 IO worker에서 `ranking-display-names.properties` 파생 표시 캐시에 원자적으로 저장한다. 오프라인/프록시 재시작 후에도 사용하며 원본 닉네임·소유권·경기 기록을 변경하지 않는다. 아직 관측되지 않은 과거 오프라인 사용자는 공용 DB 또는 검토된 캐시 초기 자료가 없으면 기존 계정명을 유지한다. 완전한 서버 간 원본 통합은 C01 운영 이관이 별도 필요하다. 클라이언트 입력은 이름 권위로 사용하지 않는다.

## 빌드·테스트

`Build-Server.ps1 -Verify`를 실제 실행했다. 스크립트가 예전 task15 경로를 고정 참조하여 `$PSScriptRoot` 기준으로 수정했고, 새 트리에 빠진 토네이도 로컬 의존 JAR은 기존 호스트 검증 트리에서만 가져왔다. 운영 JAR은 교체하지 않았다. 최종 전체 검증 성공. 서버 JUnit 824건, Fabric 456건, 최신 Story plugin81/mod57건: 합계1418건, 실패/오류0, 기존 DB 제외2건. Fabric build 성공, Story 최신 빌드 성공, operating_overlay.py check 차이0. 기존 명찰 테스트도 유지된다.

런처 격리 트리 `C:/Chacademi/staging/launcher-assets-review-20261022-task7`: 지정 Node 테스트 clientassets 7개, clientassets-stamp 11개 모두 통과. 실제 런처 manifest/모델 파일 목록은 2ef9f1c 기준과 동일하다. 0.1.23 설치 프로그램은 게시하지 않았고 Electron 실기 검수는 미실시다.

## 호스트/DB 스테이징 및 남은 항목

`C:/Chacademi/staging/menu-followup-20261022-task7/c01-rehearsal`: 운영 SQLite 18개를 읽기 전용 URI와 SQLite backup API로 일관성 복제, 전부 integrity_check=ok. 병합 self-test 및 실제 복제본 --no-target dry-run 통과. 운영 MariaDB 쓰기·설정 전환·삭제는 안 했다. 실제 MariaDB 대상 apply/멱등/부분 실패 리허설은 아직 못 했다. 이전 인계에서 격리 MariaDB 엔진 초기화가 호스트 Access denied로 막힌 상태였으며, 권한 있는 격리 DB가 필요하다. 이 차단을 우회하지 않았다.

설정 복제본에서 trusted-servers=school,wild,arena, ChacaNPC budget.start-date=2026-10-26/limit-enabled=false, Wildlife crop-awakening.cancel-drops=false를 검증했다. 현재 운영은 trusted-servers만 이미 맞고, school/arena NPC 시작일은 2026-12-01, limit-enabled 미설정, wild cancel-drops=true다. 운영 해시는 그대로 유지했다. NPC 60회 서버 간 합산은 별도 공유 DB 조건이 필요하다.

스폰 현재값은 SpawningLimit48/LocalSpawningLimit24. 인계에는 승인된 새 수치나 정확한 10/20 패치가 없어 임의 수정하지 않았다. 스폰 패치 리허설은 정확한 승인 값 전달 대기다. 관련 현재값/복제본 증거는 config-rehearsal/verification.json에 있다.

추가로 요청된 GUI 개별 편집·프리셋1/2·셰이더 명찰 수정은 이 검증 커밋 이후 별도 커밋으로 처리한다. 운영 적용/게시/재시작은 별도 승인 전까지 보류한다.
