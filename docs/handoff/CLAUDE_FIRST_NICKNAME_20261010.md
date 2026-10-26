# 최초 닉네임 설정 + 스토리 연동 작업 기록 (2026-10-10)

- **기준**: 브랜치 `codex/hires-item-icons-20261006`, 커밋 `9a6263a`
- **브랜치**: `claude/first-nickname-20261010` (GPT 브랜치와 분리. 받는 순서는 `CLAUDE_INDEX.md`)
- **작성**: Claude (사용자 요청)
- **빌드·설치 상태**: 코드만 반영. **빌드, 서버 설치, 재시작 모두 하지 않음.** AGENTS.md대로 호스팅에서 빌드해 주세요.
  `NicknameProtocol` 변경은 `javac`로 단독 컴파일하고 FIRST/FIRST_DONE 왕복을 확인했습니다. 나머지 모듈은 컴파일하지 않았습니다.

## 1. 사용자 요구

1. 기존 `/닉네임설정`이 아니라 **`/최초닉네임설정`** 으로 들어온 경우에는 취소와 창 닫기가 안 된다.
2. 그 상태에서 서버가 꺼지거나 유저가 접속을 끊으면, 다음 접속 때 자동으로 다시 닉네임 창을 띄운다.
3. 닉네임 설정이 끝나면 `/storydialogue <플레이어> ch1-2` 를 실행한다 (차카데미 스토리 플러그인의 대화 명령).

## 2. 변경 범위

| 위치 | 변경 |
| --- | --- |
| `magic-codex-protocol` `NicknameProtocol` | 서버 → 클라 알림 종류 `FIRST=3`(최초 창 열기), `FIRST_DONE=4`(저장 확인, 창 닫기) 추가. `sequence=0`으로 보냄. 응답 kind 검사 범위를 4까지 |
| `magic-codex-paper` `NicknameBridge` | `/최초닉네임설정` 실행기, 대기 목록 `first-nickname.yml`, 접속 시 다시 보내기, 저장 성공 시 완료 처리 |
| `magic-codex-paper` `plugin.yml` | 명령어 `최초닉네임설정`, 권한 `magiccodex.nickname.admin`(기본 op) |
| `magic-codex-paper` `config.yml` | `first-nickname.on-complete` (기본 `storydialogue {player} ch1-2`) |
| `magic-codex-fabric` `NicknameClient` | FIRST / FIRST_DONE 처리. 최초 모드 동안 화면이 비면 1초마다 다시 엶. 접속·끊김 시 초기화 |
| `magic-codex-fabric` `NicknameScreen` | `first` 모드: X·취소 버튼 숨김, ESC 무시, `close()` 무시, 저장 버튼 가운데, 안내 문구 |
| `magic-codex-paper` 테스트 | `NicknameProtocolTest.firstNicknamePushRoundTrip` |

바꾸지 않은 것: 기존 `/닉네임`, `/닉네임설정` 흐름(닫기 가능), 닉네임 저장소·DB 스키마, OPEN/SAVE/CLOSE 요청 형식.

## 3. 동작

- `/최초닉네임설정 <플레이어>`: 콘솔·`magiccodex.nickname.admin`. 대상 UUID를 `plugins/MagicCodexBridge/first-nickname.yml` 의 `pending` 에 넣고 FIRST 를 보낸다.
- `/최초닉네임설정` (인자 없이): 플레이어 본인. 아직 저장된 닉네임이 없거나 이미 대기 중일 때만 (닉네임을 정한 사람이 스토리 대화를 다시 여는 것을 막음).
- 클라 채널이 아직 등록되지 않았으면 1초 간격으로 15번까지 다시 시도. 끝까지 안 되면 "MagicCodex UI 모드가 필요합니다" 메시지.
- 접속(`PlayerJoinEvent`) 2초 뒤, 대기 목록에 있으면 다시 FIRST 를 보낸다 → 서버 재시작·재접속 후에도 창이 다시 뜬다.
- 저장(SAVE)이 실제로 반영되면(revision +1, 닉네임 일치): 대기 목록에서 빼고 파일 저장 → FIRST_DONE 전송(클라가 창을 닫음) → 10틱 뒤 `first-nickname.on-complete` 명령을 콘솔로 실행.
  자리 표시: `{player}` 마인크래프트 닉네임, `{nickname}` 방금 정한 닉네임, `{uuid}`.
- 구버전 클라는 kind 3·4 를 검증 단계에서 버리므로 깨지지 않는다 (창이 안 뜰 뿐).

## 4. 주의사항

- **프로토콜 변경이 서버·클라 양쪽에 걸쳐 있다.** MagicCodexBridge 와 magic-codex-ui 를 같은 커밋으로 함께 배포해야 한다. 한쪽만 새것이면 최초 창이 안 뜰 뿐 기존 닉네임 기능은 그대로 동작한다.
- `NicknameScreen(Screen parent)` 공개 생성자는 그대로 두고, 최초 모드는 패키지 전용 `NicknameScreen(Screen,boolean)` 로 추가했다. 기존 호출부는 바꿀 필요 없다.
- `first-nickname.on-complete` 가 config 에 없으면 코드 기본값 `storydialogue {player} ch1-2` 를 쓴다. 기존 서버 config 에는 이 항목이 없으므로, 명령을 바꾸려면 config 에 직접 추가해야 한다.
- `storydialogue` 는 이 저장소가 아니라 차카데미 스토리 플러그인(ChacademyStory) 명령이다. 그 플러그인이 없으면 콘솔에 "Unknown command" 만 남고 다른 문제는 없다.
- 최초 모드 동안 클라는 화면이 비면 1초마다 창을 다시 연다. 다른 서버 화면(컷신 등)이 떠 있는 동안은 열지 않는다.
- `first-nickname.yml` 은 서버 하나의 파일이다. school·wild 처럼 서버가 여러 개면 대기 상태가 공유되지 않는다 (최초 설정은 한 서버에서만 여는 것을 권장).

## 5. 확인할 것

- [ ] 호스팅 빌드 (`Build-Server.ps1 -Verify`): MagicCodexBridge, magic-codex-ui 둘 다 새 jar 필요
- [ ] `/최초닉네임설정 <나>` → X·취소 없음, ESC 무시
- [ ] 창이 열린 채 접속 종료 → 재접속 시 다시 열림. 서버 재시작 후에도 동일
- [ ] 저장 → 창 닫힘 → `storydialogue <나> ch1-2` 실행 (스토리 플러그인 ChacademyStory 와 대화 `ch1-2` 가 있어야 함)

## 6. 같이 알아 둘 것 (차카데미 스토리 쪽, 이 저장소 밖)

차카데미 스토리 모드·플러그인(`chacademy-story`, 별도 프로젝트)이 이 저장소의 공개 경로를 **읽기만** 합니다. 이름·시그니처를 바꿀 때 알려 주세요.

- 클라: `school.magiccodex.client.NicknameClient.display(String)` (한글 닉네임), `PortraitClient.ready()` / `drawTurn(DrawContext)` (스토리 대화의 "나" 일러스트). 리플렉션, 없으면 조용히 건너뜀.
- 서버: `NpcSocialFacade.playerName / affinity / addAffinity` (출처 `dialogue`). 호감도의 주인은 계속 MagicCodexBridge.


## 7. Codex 통합 검증 (2026-10-10)

- 통합 기준: 9a6263aeab7e3aacfbe942b5778c021c6c5aa883.
- Claude f940d8b를 독립 호스팅 staging에서 --no-ff 병합: 1a42349.
- 경로: C:\Chacademi\staging\first-nickname-20261010-task5\source.
- 기존 build-workspace에는 미커밋 작업이 있으므로 이 패치에 사용하지 않았다.
- 위 3절의 최초 구현과 달리, 저장 성공 후 10틱 뒤에도 **같은 온라인 접속/닉네임 세션**일 때만 대기 상태를 제거한다. 로그아웃, 재접속, 교체된 세션의 콜백은 대기를 유지하며 다음 접속에서 다시 설정한다.
- 대기 YAML을 성공적으로 저장한 뒤에만 상태를 변경한다. 기존 nickname I/O executor에서 임시 파일을 쓰고 원자적 교체한다. 저장 실패 시 대기는 유지되며 FIRST_DONE/스토리 명령을 보내지 않는다. 시작 상태 저장 실패 시 창도 열지 않는다.
- 상태 제거와 FIRST_DONE 및 스토리 명령 전달은 같은 메인 스레드 실행 안에서 순서대로 수행한다. 메인 스레드는 작은 YAML I/O 완료를 기다린다. I/O executor가 지연되면 이 처리도 지연될 수 있다.
- 스토리 명령과 YAML은 하나의 트랜잭션이 아니다. 상태 제거 직후 프로세스가 비정상 종료되면 스토리 전달을 잃을 수 있으며, 여러 완료 명령 중 일부만 실행되고 예외가 나는 경우도 자동 재실행하지 않는다. 정상 실행 중 반복 완료 콜백은 한 번만 처리한다.
- 최초 창 열기는 예약 실행 시점에도 빈 화면인지 확인한다. 컷신 등 다른 화면이 있으면 유지하고, 빈 화면이 된 뒤 다시 시도한다.
- 기존 공개 생성자, display/Portrait API, 자동완성, 일반 닉네임 흐름과 DB 스키마는 유지했다.
- 실제 파일에 손상된 YAML/UUID가 있으면 조용히 대기 상태를 버리지 않고 초기화를 실패시킨다.

### 실행한 검증

- Java 21.0.10, 호스팅 staging Build-Server.ps1 -Verify: 성공.
- 서버 테스트 326개: Bridge 239 (신규 상태 전이 5개 포함), ChacaNPC 15, ChacaPortrait 53, Discovery 17, Tornado 2. 실패/오류/스킵 0.
- Fabric test remapJar: 214개, 실패/오류/스킵 0, JAR 성공.
- 최초 전체 빌드는 staging의 tornado-event-paper/lib 의존성 누락으로 실패했다. 기존 호스팅 빌드 의존성 JAR만 복사한 후 전체 검증 성공. 빌드 코드/타 기능 변경 없음.
- 신규 상태 테스트: 정상 시작/저장, 중복 시작/완료, 시작·완료 영속화 실패, 오래된/끊긴 세션과 재접속 재구성, 일반 닉네임 저장의 스토리 미실행.
- 기존 프로토콜·세션 및 전체 관련 회귀 테스트 통과. 실서버 결합 테스트를 실행했다는 뜻은 아니다.
- 빌드 스크립트는 공용 artifacts/server에 산출물을 복사하지만 운영 plugins나 클라이언트에는 설치하지 않는다. 공용 폴더의 다른 버전 JAR를 새 결과로 오인하지 않는다.

### 배포 전 남은 실게임 확인

- X/취소 숨김, ESC 차단, 컷신 유지 후 창 재개, 일반 닉네임 취소/닫기.
- 저장 도중 접속 종료·재접속, 서버 재시작 후 대기 복원, 실제 디스크 쓰기 실패 안내.
- ChacademyStory 설치 및 ch1-2 존재, 저장 뒤 실제 스토리 시작, 두 JAR 동시 배포.
- school/wild 대기는 파일 단위이며 공유되지 않는다.
- 실게임/운영 배포/재시작/런처 교체/운영 DB/키/실제 유료 API 호출 미실행.
- HolyTaming은 이번 범위에 포함하지 않았다.


## 8. 배포 및 운영 제어 사고 기록 (2026-10-10)

- 사용자 승인으로 school/wild Bridge와 개인 런처 UI를 교체했다. 두 서버의 새 Bridge 활성화 및 Done, 각 대상 JAR 1개와 배치 SHA256 일치를 확인했다.
- Bridge: 52e350de404cc19323ad0735238d59b098fc3f4c62ede2e1b032e852212ddf21
- UI: fee99eed783e2959be42d3e8cdcc4b8a1699d3d8674358487fc0c53726461fcb
- 배포 코드 기준은 2ea204cc3e0ca8f1ed6d3f8b26e3497ed5ae9f42이며 이후 이 절 추가는 문서 변경뿐이다.

### 잘못 사용한 경로와 영향

Codex가 아래 Control.ps1 경로에 개별 Server 인자를 지정하면 개별 종료할 것으로 잘못 판단했다.

    & 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\network\scripts\Control.ps1' -Action Stop -Server school
    & 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\network\scripts\Control.ps1' -Action Stop -Server wild

실제로 이 Stop 경로는 전체 종료로 처리되어 school/wild뿐 아니라 Velocity도 종료됐다. 작업자의 제어 경로 선택 오류다. 개별 종료에는 위 명령을 사용하지 않는다. DB 프로세스를 제어하지 않았다.

### 올바른 개별 정상 종료

    & 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\network\scripts\Stop-Network.ps1' -Server school
    & 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\network\scripts\Stop-Network.ps1' -Server wild

Stop-Network.ps1은 개별 대상에 대해 Control.ps1의 Console 액션으로 stop 명령을 전달하고 해당 대상 종료를 기다린다. all은 전체 종료이므로 개별 작업에서 사용하지 않는다.

### 복구 및 마지막 확인의 구분

- Codex는 school/wild의 교체와 재시작을 완료했고, 두 서버가 새 Bridge로 부팅 완료된 것을 도구로 확인했다.
- Velocity 복구 시작은 기존 'Velocity는 건드리지 마세요' 범위와 충돌한다는 자동 승인 검토로 차단됐다. Codex가 Velocity 복구 시작을 실행한 것은 아니다.
- 이후 사용자가 '내가 켰어'라고 직접 기동을 알렸다. 이는 사용자 보고이며, 그 뒤 Codex가 실행 상태를 다시 검증한 것은 아니다.
- 사용자 요청에 따라 추가 시작/종료/재시작 및 복구 승인 요청을 중단했다.

### 부팅에서 별도로 관찰한 경고

- school ChacademyStory: kr.chacademy.npc.integration.Providers$AffinityProvider 클래스 누락 경고.
- wild ItemsAdder: generated.zip 누락.
- 양쪽 MythicMobs: SkeletalKnight 로딩 경고.
- 새 Bridge 활성화 오류는 발견하지 못했다. 양쪽 ChacademyStory 활성화와 JAR의 storydialogue 명령 선언은 확인했지만 실제 대화 실행은 미검증이다.
- 이 기록 작업에서는 해당 경고나 다른 기능을 수정하지 않았다.
