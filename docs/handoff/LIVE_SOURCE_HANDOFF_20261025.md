# 현재 운영 소스 인계 — 2026-10-25

현재 운영 배포 기준의 소스와 재현자료다. 최신 개발 브랜치를 모두 합친 것이 아니다. 이 인계 작업은 운영 서버, 개인 런처, 공개 임시 런처 파일과 프로세스를 변경하지 않는다.

## 기준

- 소스: `zzunwoooo/chacademi-magiccodex-source` / `review/live-source-handoff-20261025-task3`.
- 기존 통합본 `review/opening-prep-safe-integration-20261025-task3` / `40e2f6944bd15a2c23f0d08b42c8dba4dcdc0e31`은 그대로 보존했다.
- `227ce1e0..40e2f694`의 수정 13커밋을 조상으로 보존했다. [정확한 목록](live-20261025/local-integration-commits.txt).
- 실제 임시 런처: `zzunwoooo/chacademi-launcher` / `main` / `3aa870a2314dcb7f42a19350e8b25ee071fda278`. 실행기 최신 설치 버전 v0.1.28은 변경하지 않았다.
- 원격 opening-prep의 `f897040676ed9d583292ac3b8d19e486e72e91b1`은 별도 개발 상태다. 승인 입력 `1c02a563` 뒤 29커밋은 현재 운영에 포함하지 않았다. 투명도 되돌림, 큐디치 전투 보정, 운영 AI 도움말, 아이콘 교정, 마법 효과음 등이므로 임의 병합하지 않는다.

## 현재 운영 파일

[운영 manifest](live-20261025/operating-manifest.json)에 서버 JAR 22개, 콘텐츠/설정 38개, 임시 런처 클라 3개의 경로와 SHA256 및 입력 커밋을 기록했다. 현재 파일 60개를 직접 해시했다. 비밀번호, DB, 플레이어 데이터, 대용량 백업 JAR은 포함하지 않는다.

원래 배포 후 마법 설정 9개와 메테오 JAR 3개 보완이 있었다. manifest의 serverFiles가 최종 상태이며 baselineJars의 initialCandidateSHA256은 과거 중간값이다. 이전 Mythic JAR로 되돌리지 않는다.

배포 이후 학교 steps.yml에는 ch1-11의 school/NPC12 클릭 연결이 추가됐다. 현재 원문을 `deployment/operating-config/ChacademyStory/steps.yml`에 보존했다. 운영 파일은 수정하지 않았다. 당시 미완 보고한 다른 NPC/위치/책장 연결의 현재 완료 여부는 별도 현장 확인이 필요하다.

## 소스와 운영 바이너리의 차이

OpeningHooks와 DropRarity/DropRarityVfx 및 직접 호출부를 정규 소스에 복구했다. ResourceFingerprint는 현재 정규 Snapshot API와 맞지 않아 빌드를 깨지 않도록 원본 참고 소스로 분리했다. 합성/검증 도구를 `tools/live-handoff-20261025/`에 보존한다. 정확한 출처와 집중 검증은 [이번 검증](live-20261025/verification.md) 및 도구 README를 따른다.

**정규 Gradle 빌드만으로 운영 JAR와 바이트가 동일해진다고 보장하지 않는다.** 운영본은 이미 배포된 서버별 JAR에 승인된 메서드만 합성하고 다른 live hotfix를 보존한 결과다. 정확한 재현에는 manifest SHA로 식별된 기존 바이너리 baseline이 필요하다. baseline과 대용량 산출물은 Git에 올리지 않는다.

특히 VFX EffectRenderer의 residentFingerprint/PreloadBatch/prepareFresh/apply 재사용/track cleanup/GPU disposal 구현은 기존 운영 바이너리에 결합돼 있다. ResourceFingerprint 클래스 복구만으로 해당 캐시 구현 전체를 이식했다고 보지 않는다. 기존 소스·클래스의 존재나 단위 테스트 통과를 모든 메서드의 완전한 소스 동일성으로 해석하지 않는다. 완전한 source-only 통합은 후속 작업이다.

## 빌드·재현·검증 순서

1. 호스팅에서 AGENTS.md에 따라 JDK21, 기존 Gradle wrapper/cache를 사용한다.
2. 40e 기준 과거 소스 테스트는 1976개, 실패/오류0, skip7. 실제 최종 Bridge JAR 회귀64개 통과. 이번 인계의 집중 검증 결과와 구분한다.
3. 도구 README에 명시된 순서로 소스 산출물과 해시가 맞는 운영 baseline을 준비하고 오프라인 합성한다. 도구가 요구하는 입력을 생략하지 않는다.
4. 최종 JAR 기준 삭제0, 미수정 항목 바이트, 메서드/필드 보존, JVM 링크 및 프로토콜을 확인한다. YAML 메커닉20종과 소스 등록명21종을 직접 대조한다.
5. 현재 인계 자체는 새 배포 승인이 아니다. 결과 해시·운영 기능 보존 확인 없이 서버 파일이나 프로세스를 변경하지 않는다.

## 운영 상태·주의·미완

- 기동 후 학교/야생/아레나/프록시 상태 응답, 칭호 새로고침, 신규 표4개, CHAMETEOR 오류 해소를 확인했다. 실게임 시각 검수는 미수행이다.
- 메테오 원인: 원 소스에는 타입과 등록이 있었으나 운영 보존 합성 JAR에서 누락. 실제 JAR 등록 실행과 21타입 JVM 링크, 배포 후 두 번의 로딩에서 CHAMETEOR 오류0으로 보완했다.
- 개별 종료는 `Stop-Network.ps1 -Server school|wild|arena` 또는 Console stop. `Control.ps1 -Action Stop -Server ...`는 선택값을 무시하고 프록시까지 전체 종료하므로 사용하지 않는다.
- 239~250 기믹 아이콘은 현재 공개 UI JAR에 1254×1254 원본12개가 포함돼 있다. 첨부 SHA와 모두 일치하고 서버팩 덮어쓰기 없음. 추가 배포하지 않았다.
- 항구 컷신 ch1_harbor_arrival 요청은 취소. 런처 manifest에 추가/공개 업로드하지 않았다.
- 던전 보류. 존재하지 않는 튜토리얼2개는 새 설치하지 않았다. 개인 런처와 별도 미승인 배낭 polish 제외.
- 당시 미완: ItemsAdder bag_expansion/return_ticket 정의, pipi_tutorial 상점/상품, 1장 연결, TAB/WorldGuard 설정 결정. 이를 완료했다고 주장하지 않는다.
