# 이번 인계 검증

## 완료

- 새 브랜치는 기존 40e2f694를 조상으로 하며 13개 통합 수정 커밋을 보존한다. 원래 통합 worktree는 수정하지 않았다.
- VFX 원본 출처: 로컬 운영 보존 worktree d2f2f61dfd59468bafa72b354123bd5915ca5418. DropRarity/DropRarityVfx와 PortableVfxClient의 생성/tick 연결만 정규 소스에 복구했다.
- VFX `:client:compileJava` 정규 Gradle 오프라인 집중 컴파일 PASS (18s). 초기 수동 classpath 실패 및 ResourceFingerprint 정규 컴파일 실패는 해결 결과와 구분한다.
- ResourceFingerprint는 현재 Snapshot.valid API가 없어 정규 빌드 대상에서 제외했다. 원본과 EffectLibrary/EffectRenderer를 tools/live-handoff-20261025/vfx-hotfix-reference 에 보존한다. 광범위 캐시/GPU hotfix는 미통합이며 바이너리 baseline 필요.
- OpeningHooks는 기존 helper-source 원문과 동일하게 정규 소스에 복구했다. 외부 API 입력 준비 후 정규 Gradle `:magic-codex-paper:compileJava` 오프라인 집중 컴파일 PASS (21s).
- 패키징 도구 Python 7개 문법, 입력 SHA gate, network/launcher 출력 차단, source inventory 원문 해시 확인 PASS. 전체 재패키징은 실행하지 않았다.
- 운영 파일 60개를 읽어 manifest를 생성했다. 기존 배포 이후 학교 steps.yml 한 파일 변화는 현재 snapshot/해시로 기록했다. 자격키·이메일·UUID·개인키·접근토큰 검사 검출 0.
- 임시 런처 공개 distribution/payload가 main 3aa870a의 Git blob과 바이트 동일하다. 공개 파일/개인 런처/운영 프로세스는 변경하지 않았다.

## 이번에 다시 실행하지 않은 것

전체 1976개 소스 테스트, 64개 실제 Bridge JAR 회귀 및 운영 부팅/인게임 검수를 재실행하지 않았다. 이들은 이전 배포 때의 결과다. 현재 인계의 소스 집중 컴파일을 운영 바이너리 완전 재현이나 실게임 통과로 해석하지 않는다.

## 빌드 의존 입력

JDK21.0.10, 기존 Gradle wrapper, GRADLE_USER_HOME의 캐시를 사용했다. 새 checkout의 plugins/는 Git에서 제외돼 API 입력이 없어 최초 Bridge 컴파일이 실패했다. 기존 검증 worktree의 동일 API JAR만 ignored plugins/에 준비한 뒤 집중 컴파일을 통과했다. 정확한 파일명/해시는 local-api-dependencies.json 참조. 이 JAR이나 DB/backup은 Git에 포함하지 않는다. 기존 magic-codex-database/lib의 MariaDB JDBC와 libraries의 테스트 의존도 실제 빌드 작업에 따라 별도 필요하다.
