# NPC 보존 통합 및 재검증 (2026-10-21)

## 후보와 선택 근거

- 새 후보 브랜치: `codex/verify-integrated-npc-20261021-task15`.
- 기반: 통합 수정본 검증 커밋 `c6364d955aa36f6023d4bb1c91bc905aa6704912` (원격 통합본 `45962cac` 포함).
- 요청 저장소에서 실제 fetch한 `claude/npc-model-20261021` 최신 HEAD는 `debb072d`; 부모 `874e6f7b`, 기준 `0cd1454f` 확인. 이 두 NPC 커밋을 merge하여 조상을 보존했다.
- 호스트 이전 후보 `7c105c7`의 이력은 Story `9c23e0e` → NPC `c5af0dd` → 검토 수정 `70b12b4` → Story 마커 높이 수정 `7c105c7`이다. 원격 `debb072d`는 이 중 NPC 기능·검토 수정에 해당한다.
- 원격 NPC 모듈/프로토콜/Appearance 파일 44개를 호스트 이전 후보 체크아웃과 SHA256 대조: 차이 0. 추정 SHA를 맹목적으로 cherry-pick하지 않았다.
- 일반 후보에는 최신 NPC 프로토콜, Fabric 모델 클라이언트, 서버 Appearance 서비스/명령, 생명주기·대화/인사 연결을 병합했다. 자동 merge 충돌 없음.
- `DialogueService`는 통합본의 비동기 공유 일일 60회 제한을 유지하면서 대화 NPC 목록과 인사 연결만 추가되었음을 diff 확인. 기존 Build-Server.ps1 경로 수정, Tornado Paper 의존성, A06 테스트 수정은 그대로다.

## Story 범위

- 이번 일반 후보의 `chacademy-story/`는 `c6364d95`와 차이 0. 새 Story 단계·설정은 활성화하지 않았다.
- 준비된 Story `9c23e0e`와 마커 보정 `7c105c7`은 기존 호스트 후보에 그대로 보존. 일반 후보에 Story 전체를 섞지 않았다.
- NPC 브랜치의 `docs/handoff/patches/story-marker-npc-model-height.patch`도 보존했다. 이 패치는 향후 Story 후보에 적용할 자료이며 이번 일반 후보에서는 적용하지 않았다.
- 새 Story 후보를 빌드하거나 운영 Story 상태를 변경하지 않았다. 이번 결과를 Story 포함 후보의 빌드 통과로 해석하면 안 된다.

## 검증 결과

- 호스트에서 `Build-Server.ps1 -Verify`: exit 0, `BUILD SUCCESSFUL in 20s`, 56 tasks (5 executed, 51 up-to-date).
- 서버 JUnit 결과: 788개, 실패/오류 0, skipped 2. ChacaNPC는 31개, 실패/오류/skip 0 (프로토콜 3개 포함).
- NPC Fabric 프로젝트에서 `gradlew.bat --no-daemon --console=plain --max-workers=2 test build`: exit 0, `BUILD SUCCESSFUL in 1m 29s`.
- NPC 클라이언트 JUnit 14개, 실패/오류/skip 0. 호스트의 실제 luka.glb/ria.glb를 격리 후보에 복사하고 manifest SHA256 일치 확인. 따라서 realModelsLoad를 파일 없이 지나간 결과가 아니다.
- 실제 새 ChacaNPC jar와 remapped Fabric jar를 서로 다른 URLClassLoader에 로드하여 검증: 클라이언트 HELLO → 서버, 서버 HELLO_ACK/APPEAR/STATE/CLEAR/RESET → 클라이언트, 동일 바이트 재인코딩, 버전 불일치·남는 바이트 거부 모두 통과.
- 프로토콜 v1, S2C `chacademi:npc_model`, C2S `chacademi:npc_model_c`. 두 빌드 모두 동일한 공통 프로토콜 소스 폴더를 사용한다. jar 내 프로토콜 class 10개 존재 확인. class 파일 자체의 SHA는 서로 다르므로 바이너리 동일성을 주장하지 않으며 실제 교차 패킷 검증으로 호환성을 확인했다.
- 기존 통합본에서 검사한 운영 overlay를 NPC merge가 변경하지 않았음.

## 산출물과 증거 (SSH 호스트)

작업 트리: `C:/Chacademi/staging/integrated-verify-20261021-task15`.

- 서버: `chaca-npc-paper/build/libs/chaca-npc-0.3.0-npcmodel.1.jar`, 1,109,154 bytes, SHA256 `76096b77dd80d0c15ad8337f58db27bd7e6929f0428c07d8bc3322c02a20265e`.
- 클라이언트: `client-mods/chacademi-npc-model/build/libs/chacademi-npc-model-0.1.0-npcmodel.1.jar`, 136,750 bytes, SHA256 `eb83c24c1dcd417da74d64e28ccbbd778e4c18b04a09525b109812933691ea82`.
- `verification/npc-server-verify.log`, `npc-client-verify.log`, `npc-protocol-compatibility.log`, `npc-preservation-audit.json`.
- `verification/VerifyProtocolCompatibility.java`, `npc-preservation-audit.py`는 재실행용 검증 자료.
- 각 모듈 `build/test-results/test/TEST-*.xml`.
- GLB, 검증 로그, DB/설정 복사본은 git 커밋하지 않음.

## 남은 범위와 운영 미변경

- 게임 클라이언트를 실행하지 않았으므로 실제 GPU/Iris 렌더, mixin 주입의 시작 시 동작, 클릭/이동·서버 이동·NPC 재생성·성능 실측은 이번 빌드/프로토콜 검증 범위 밖이다.
- school 플러그인 디렉터리에서 확인한 NPC jar 파일명은 `chaca-npc-0.2.0-commands.1.jar`였다. 파일 목록만으로 현재 로드된 클래스·배포 상태를 확정하지 않았다. 새 후보를 운영 경로에 복사하지 않았다.
- 이전 C01 격리 MariaDB 접근 거부와 정확한 스폰 패치 수치 미확보는 그대로 남아 있다. 권한/자격증명 생성·변경이나 우회 없음.
- 운영 DB/설정 변경, jar 교체, 서버 정지·재시작, Story 활성화, 런처 변경, push/deploy 모두 수행하지 않았다.
