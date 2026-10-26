> 후속 NPC 보존 통합 및 재검증 결과는 [NPC_PRESERVATION_VERIFICATION_20261021_TASK15.md](NPC_PRESERVATION_VERIFICATION_20261021_TASK15.md)를 참조. 아래는 c6364d95 기준 최초 검증 기록이다.

# 통합 수정본 빌드 및 격리 리허설 결과

## 기준과 보존

- 저장소: zzunwoooo/chacademi-magiccodex-source
- 요청 원격 브랜치: claude/fixes-integrated-20261021
- 확인한 원격 HEAD: 45962cac
- 검증 브랜치: codex/verify-integrated-20261021-task15
- 호스트 작업 트리: C:/Chacademi/staging/integrated-verify-20261021-task15
- 종합 인계 docs/handoff/CLAUDE_FIX_INDEX_20261021.md 및 DBTOOL 런북 전체 확인.
- 기존 운영 통합 기준 0cd1454f는 HEAD의 조상임을 확인.
- 별도 NPC 모델 작업 트리 HEAD 7c105c7e의 이전 패치(c5af0dd, 70b12b4 포함)는 이번 통합본에 보존되지 않음. chaca-npc-model-protocol, client-mods/chacademi-npc-model, AppearanceService.java, AppearanceCommands.java가 없음. Story QuestWorld 마커 보정 대상 파일도 이번 통합본에서 찾지 못했고 Story mod gradle.properties는 NPC 기준과 다름.
- NPC 기준 브랜치 codex/npc-model-integrated-20261021-task13는 요청 저장소 원격에 없음. 호스트 내부 fetch는 partial clone의 누락 객체로 실패. 위 누락 판정은 실제 체크아웃 파일 존재 여부와 기준 작업 트리 커밋/파일 목록으로 확인했음.
- Story는 조건부 활성화 요청이므로 별도 Story 브랜치 빌드/활성화/병합을 수행하지 않음. 현재 결과는 최신 Story/NPC까지 포함한 배포 후보가 아님.

## 빌드 및 최소 수정

1. 원본 Build-Server.ps1이 과거 duel-lobby-polish 작업 트리를 빌드하고 기존 공용 산출물 폴더로 복사하도록 고정되어 있었음. 이번 검증 트리와 verification/artifacts로 경로 변경, 현재 Bridge 버전의 jar 이름 반영, Mythic 입력도 검증 트리 의존성 경로로 수정.
2. 새 작업 트리에 git 미추적 JDBC와 플러그인 jar가 없어 첫 빌드 실패. 기존 호스트의 MariaDB/SQLite JDBC, ModelEngine/MythicMobs/PlaceholderAPI, Tornado lib jar를 검증 트리에만 복사. 운영 jar 교체 없음.
3. Tornado build.gradle은 로컬 lib만으로 Paper 전이 의존성이 없어 컴파일 실패. Paper 1.21.4 compileOnly를 명시.
4. EventIndexTest는 A06 guardian_oath의 state.friends 전환(f7d985e4)을 반영하지 않아 실패. 실제 카탈로그에 맞게 stateOnly 기대 목록에 guardian_oath 추가. 게임 동작 변경 없음.
5. 인계 4장 위험과 비교 후 재검증. 최종 Build-Server.ps1 -Verify: BUILD SUCCESSFUL in 21s, exit 0, 56 tasks(6 executed, 50 up-to-date).
6. JUnit XML 집계: 785 tests, failures/errors 0, skipped 2. 이전 실패 테스트는 재실행으로 통과.
7. tools/operating_overlay.py check: 차이 없음, exit 0. git diff --check 통과.

Build-Server.ps1/settings.gradle 범위는 서버 프로젝트다. Fabric 클라이언트, portable-vfx-runtime의 별도 빌드, 새 Story/NPC 모델 클라이언트 빌드 통과로 해석하면 안 된다.

## 격리 리허설

- 새 Bridge jar로 GenericDomainMigration.java --self-test: exit 0(병합 규칙 자체 점검 통과).
- 운영 SQLite를 직접 이관 원본으로 사용하지 않음. Python sqlite3의 읽기 전용 URI 연결 및 backup API로 WAL을 포함한 일관된 복사본 생성. school/wild/arena 각 6개, 총 18개 DB. 모든 복사본 PRAGMA integrity_check=ok. 운영 프로세스 중지 없음.
- 복사본 school → wild → arena 우선순위로 --no-target 및 report 실행: exit 0. 보고서에는 플레이어 정보가 있어 git에 넣지 않음.
- party.properties, 야생 Wildlife config, 학교/아레나 ChacaNPC config는 verification/rehearsal/config 아래 복사본에서만 패치. trusted-servers=school,wild,arena; budget.start-date=2026-10-26; budget.limit-enabled=false; crop-awakening.cancel-drops=false. 기존 설정에 없는 키는 복사본의 올바른 섹션에 추가. 운영 원본 해시는 전후 비교.
- 설정 복사본 리허설은 텍스트/구조 준비 및 원본 해시 확인이며, 서버를 띄운 게임 내 동작 검증은 아님.

## 미완료 및 차단 사유

- C01 실제 MariaDB dry-run/apply/검증 쿼리/멱등성/기존 데이터 거부 리허설 미실행. C:/Chacademi/tools/mariadb-11.4.13/mariadb-11.4.13-winx64/bin 접근이 호스트 계정에서 Access denied. 격리 대상 DB 미초기화. 운영 DB 연결로 대체하지 않음. 권한 있는 담당자가 격리 DB 도구 접근 또는 별도 staging 전용 DB를 준비해야 함.
- SpawningLimit/MaxLocalMobs 패치의 정확한 승인 값이 종합 인계에 없고 확인한 config 묶음 인계에도 없음. 현재 wild SpawningLimit=48, LocalSpawningLimit=24 확인. 수치를 추정하지 않았으며 스폰 패치 리허설은 보류. 10/20의 정확한 패치 또는 승인 값을 확보해야 함.
- ChacaNPC 서버 간 일일 60회 통합은 공유 MariaDB 대상 설정 및 실제 연결 검증이 남음. 설정 복사본 패치만으로 공유가 확인된 것은 아님.
- NPC 모델 및 Story 보존을 해결하고 관련 클라이언트/서버를 별도로 검증한 뒤 배포 후보를 다시 구성해야 함.

## 증거 위치(호스트)

- verification/build-verify.log, build-verify-2.log, build-verify-3.log
- 각 서버 모듈 build/test-results/test/TEST-*.xml
- verification/migration-self-test.log
- verification/rehearsal/summary.json, settings-copy-validation.json
- verification/rehearsal/migration-no-target.md 및 migration-no-target.log
- verification/artifacts/ 및 각 프로젝트 build/libs/

DB 복사본, 연결정보를 포함할 수 있는 설정 복사본, 플레이어 보고서, 빌드 로그 전체는 커밋하지 않음.

## 운영 미변경

운영 DB write, 운영 설정 변경, 운영 jar 배치, 서버 정지/재시작, Control.ps1 Stop, 스토리 활성화, 런처 수정, push/deploy 모두 수행하지 않음. 모든 큰 빌드/데이터 작업은 SSH 호스트에서 수행.

자동 승인 검토가 최초 원격 목록 출력 명령을 연결정보 노출 우려로 실행 전 거부했다. 해당 명령은 실행되지 않았고, 이후 연결 URL 출력 없이 요청 저장소 일치 여부만 확인했다. 연결정보·비밀번호·토큰 출력 없음.
