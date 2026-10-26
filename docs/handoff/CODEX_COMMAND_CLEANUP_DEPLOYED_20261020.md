# 명령 정리 통합·실제 배포 완료 — 2026-10-20

## 완료 상태
- 코드 통합 커밋: `8e8cc23f173e68a7d60671369eb969c5df1c8b30`, 브랜치 `codex/command-cleanup-integrated-20261020-task12` (origin push 완료).
- Claude `f6c7ce8f5b0ddbdae18ccbe31776e1f4c9e69c77`까지 기준 `ef55623` 위 14커밋을 원격에서 확인했다. `docs/ADMIN_COMMANDS_KO.md`와 `docs/handoff/CLAUDE_COMMAND_CLEANUP_20261020.md`를 읽었다. 전달된 `claude/command-cleanup-handoff.md`는 해당 원격 트리에 없다.
- 소스 작업 경로는 기존 최신 운영 작업본 `C:\Chacademi\staging\duel-lobby-polish-20261019`. `06753da`부터 분기하여 기숙사 분류·트랜잭션·종료 placeholder를 보존했다. 오래된 브랜치로 reset하지 않았다.
- 학교·야생·아레나의 기존 설치 대상 JAR 각 11개(33개)와 Velocity party JAR 1개, 합계 34개를 실제 교체했다. 설치되지 않았던 모듈을 새 서버에 임의로 추가하지 않았다. 서버별 파일명·before/after SHA256은 `deployment-plan.json`과 `final-deployment.json`에 있다.
- 운영 YAML은 키/항목 단위로 기존 명령 값 2개만 변경했다: school `ChacademyStory/dialogues/server_command.yml`의 `commands.ch1-4[0]`, wild의 같은 파일 `commands.ch1-3[0]`. 컷신 재생 명령을 `대화관리 재생 {player} 컷신 ...`으로 바꿨고 나머지 키·대사·파일은 보존했다.
- 런처 feed 커밋: `b721b32b108a9c41a55dc82c08b103f7d3b4e08e` (main push 완료). 콘텐츠 전용 릴리스 `content-commands-20261020`에 MagicCodex·chat layout·ChacademyStory·PortableVFX 클라이언트 네 JAR과 distribution/payload JSON을 공개했다. 실행기 자체 코드는 바꾸지 않았고 최신 설치판은 v0.1.21 그대로다.
- 공개 여섯 파일의 실제 다운로드 바이트 수·SHA256 및 stable main feed 일치를 확인했다. `public-verification.json` 참고.

## 보존 및 제외
- 기숙사 `06753da`, 기믹 `005b977/80cfb69/2883264`, 기존 UI 이미지·오디오·모델·VFX 바이너리 보존. 기숙사 계산/DB 저장 소스는 06753da와 동일함을 검사했다. 새 허브와 같이 `/기숙사분류`를 유지했고 내부 스토리 dispatch만 새 문법으로 변경했다.
- 기숙사 대사는 나중에 연결한다는 사용자 요청에 따라 생성·연결하지 않았다. ch1-14 대사 13종과 종료 매핑의 별도 준비가 여전히 필요하다.
- 아티팩트 CC `7882171`은 별도 승인 대상이므로 포함하지 않았다. 기존 반응 CC/VFX는 유지했다.
- 오늘 정상화한 client-assets ZIP의 URL·크기·MD5·SHA256을 그대로 유지했다. 다른 모드·개인 설정·리소스·펫 모델 묶음도 유지했고 네 클라이언트 모듈만 바꿨다.
- 기존 제삼자 JAR 66개 해시 일치. DB 초기화·기록 삭제·권한 저장소/보안 설정 변경은 수행하지 않았다.

## 검증
- 전체 서버 verifyServer/assembleServer, 독립 Story/Motion 플러그인, PortableVFX common/paper/client, MagicCodex client, Story client, chat client 빌드 성공.
- 테스트 1,583개: 실패 0, 오류 0, 건너뜀 2(호스트 실제 DB 전용), 통과 1,581. 기존 초기 시작 테스트의 lifecycle 대역과 교화 plugin.yml 검사만 새 허브 구조에 맞게 수정했고, 공유 갈래 병합·비OP 차단·유저 명령/구 별칭 제거·한글 인수 자동완성 계약 테스트 4개를 추가했다.
- 런처 자산 손상·경로탈출·개인 옵션 보존 등 7개 시나리오 통과. 스토리 콘텐츠 45파일 공개 해시와 기존/신규/반복 실행 및 진행 보존 검사 통과.
- 사용자에게 접속 중 2명으로 재시작 필요를 설명한 후 `재시작 해` 승인을 받았다. 기존 제어기로 프록시/백엔드를 정상 저장 종료하고 해시를 검사한 뒤 시작했다. 첫 시작과 최종 시작 모두 세 백엔드 Done 확인.
- 배포 검증 중 기존 감독기가 stdout/stderr만 UTF-8이고 stdin은 지정하지 않아 한글 콘솔 입력이 깨지는 문제를 발견했다. Supervisor.ps1을 백업하고 `StandardInputEncoding = $utf8` 한 줄만 추가했다. 적용을 위해 정상 재시작을 한 번 더 수행했으며, 이후 세 서버의 `관리`, `help 마법`, `help 기숙사분류`, `서버관리 상태`, 아레나 `큐디치관리 목록`이 모두 정상 실행됐다. 실제 테스트 경기/마법 시전/플레이어 기록 변경 명령은 실행하지 않았다.
- 최종 학교·야생·아레나·Velocity 상태 응답 정상, 시작 ERROR/Exception 0. 대단원 15개 등록, 유저 명령 school 21/wild 18/arena 20개. 프록시의 새 `chacademi-party ...commands.1` 로드 확인.

## 현재 큐디치 테스트 문법 (OP, 게임 안에서)
```text
/테스트 큐디치 시작 경기장1 준비:0 시간:3 스니치:0 무적
/테스트 큐디치 참가 경기장1 파랑 [닉네임]
/테스트 큐디치 공 경기장1
/테스트 큐디치 종료
```
- 옵션으로 `블러저끔`을 쓴다. 구 문법 `/큐디치관리 테스트`·`공테스트`·별칭 qa는 이번 패치에서 제거됐다.
- 현재 실제 목록: `경기장1` [3:3, 켜짐], `결투장1` [1:1, 켜짐]. 시작한 사람은 테스트 팀에 들어가며 레이팅 미반영이다.

## 백업·산출물·롤백
- 백업 루트: `C:\Chacademi\backups\command-cleanup-20261020-task12` (작업 전 source HEAD tar/patch/미추적 파일, operating JAR·변경 YAML, Supervisor.ps1).
- 산출물 루트: `C:\Chacademi\artifacts\command-cleanup-20261020-task12`. `payload/`에 서버 빌드 14종과 클라이언트 4종, `release-assets/`에 공개 클라이언트/metadata, `config-stage/`에 키 병합된 YAML.
- `deployment-plan.json`, `config-key-merge-plan.json`, `file-apply-result.json`, `preservation-proof.json`, `console-encoding-fix.json`, `public-verification.json`, `final-deployment.json`, build 로그, 최종 한글 콘솔 검증 로그를 보존했다.
- 파일 롤백은 정상 종료 후 `C:\Chacademi\tools\python\python.exe C:\Chacademi\artifacts\cleanup-deploy-files.py rollback`. 기존 JAR/설정만 복원하며 DB 기록을 되돌리거나 지우지 않는다. 필요하다면 감독기 백업은 별도로 원위치 복사 후 재시작한다.
- 런처 롤백은 main에서 `b721b32`의 두 metadata 파일 변경을 일반 revert 커밋으로 되돌려 push한다. reset/force push를 사용하지 않는다. 복구 시 서버·클라이언트 프로토콜 버전을 같이 맞춘다.

## 남은 실게임 확인
- 게임을 완전히 종료하고 기존 런처에서 다시 실행하여 새 네 모듈을 받는다. 이번 작업에서는 실제 사용자 클라이언트를 실행하지 않았다.
- 첫 클라이언트 시작의 한글 Brigadier mixin 로그, 한글 인수 입력, UI 알림/미리보기, 파티 한글 초대, 관전 나가기, 실제 마법 시전과 스토리 종료 연결은 실게임 QA가 남아 있다. 실제 테스트 경기는 실행하지 않았다.
- 기숙사 대사 연결과 아티팩트 CC 업데이트는 별도 작업으로 남긴다.
- 상태 조회상 wild 초상화는 키 미설정, arena 초상화는 style-reference.png 부재로 생성 제한이 있다. 이번 배포에서는 해당 비밀 설정/레퍼런스를 변경하지 않았다.
