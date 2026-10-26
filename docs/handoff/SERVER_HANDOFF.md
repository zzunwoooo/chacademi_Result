# 차카데미 서버 시스템과 호스팅 운영 인수인계

이 문서는 차카데미 서버 작업을 이어받는 dot을 위한 인수인계다. 기준일은 2026년 10월 1일이며, 현재 상태는 같은 날 23시대 Asia/Seoul에 호스팅에서 조회한 소스·설정·파일 목록·프로세스 상태·Minecraft 상태 응답을 근거로 정리했다. 사용자와 합의한 기획은 현재 호스팅에서 검증한 구현 상태와 구분한다.

**현재 호스팅은 완성된 기존 서버를 그대로 이전한 상태가 아니다.** 학교·야생·Velocity의 실행 기반은 구축했지만, 실행 서버에 설치된 플러그인 JAR는 0개다. 자체 플러그인 4개는 빌드 결과만 준비되어 있다. 기존 월드, 커스텀 바이옴 데이터팩, 몹·펫·모델·리소스, 클라이언트와 운영 데이터 이전은 아직 남아 있다.

작업 환경 원칙은 [dot 클라우드와 호스팅 SSH 작업 인수인계](https://chatgpt.com/space/page_bc2f085a03c081919c231fac14709796)를 함께 따른다. 호스팅 파일은 `C:\Chacademi\handoff\DOT_HANDOFF.md`다. 서버의 상세 실행 상태를 설명하는 이 문서는 그 환경 문서를 대체하지 않는다.

## 반드시 유지할 작업 방식

dot이 자기 클라우드에서 직접 코딩하고, 클라우드에서 시작한 SSH로 호스팅의 소스와 서버 파일을 읽고 수정한다. 자료 검색, 의존성 다운로드, 빌드, 테스트, DB 처리와 서버 실행은 호스팅에서 수행한다. 노트북은 채팅과 작은 결과 확인에만 사용하고 대용량 로그·월드·리소스를 자동으로 가져오지 않는다.

**사용자의 명시적 허가 없이 Codex 작업을 생성하거나 재개하거나 기존 작업에 지시하거나 Codex CLI를 실행하지 않는다.** 일반적인 서버 수정 요청, SSH 접속 허가와 개발 요청은 Codex 사용 허가가 아니다. 필요한 도구가 없거나 직접 접속이 막히면 제약을 보고한다.

`magic-codex-paper`, `MagicCodexBridge` 등의 이름은 우리가 만든 Minecraft 플러그인 이름이다. 이 소스를 직접 수정하거나 Gradle로 빌드하는 것은 AI 제품인 Codex에 위임하는 것과 다르다. 이름만 보고 프로젝트를 건너뛰거나 Codex CLI가 필요하다고 판단하지 않는다.

기본 SSH 계정은 `chacademi-codex`이고, 주소는 `211.53.196.231:22`다. `chacademi-host` 별칭은 실행 환경마다 설정해야 한다. 일상 작업에 administrator를 사용하지 않는다. 현재 이 대화에서 조사한 연결은 노트북의 SSH 제어를 이용한 원격 조회이며, dot 클라우드의 직접 SSH 접속 성공을 증명하지 않는다.

## 서버 기획과 작업 범위

차카데미는 마법학교 생활 서버다. 저주받은 전생의 대마법사가 현생에서 마법을 다시 발견한다는 설정을 바탕으로, 마법 발견·도감·기숙사 기여·장비 성장·야생 탐험·교화와 펫 수집·NPC 대화와 이야기 분기를 연결하려 한다.

지금 합의한 서버 구성은 학교와 야생이다. 학교를 첫 접속 서버로 사용하며 던전 서버는 아직 구축하지 않는다. 기존 월드와 데이터를 그대로 가져왔다는 가정으로 개발하지 않는다.

사용자는 기능 개발을 진행하는 동안 단일 서버에서도 테스트할 수 있는 구성을 원했고, 이후 학교에서 받은 의뢰를 야생에서 진행하는 등 서버 이동을 고려하도록 요청했다. 공용 데이터는 MariaDB를 활용하는 방향이다. 다만 서버 분리와 공용 DB 지원 코드가 있다는 사실만으로 실제 연동 검증이 끝난 것은 아니다.

이번 작업은 인수인계 문서 작성과 상태 확인이다. 플러그인 설치, 서버 재시작, 신규 DB 설치, 데이터 마이그레이션이나 미완료 기능 수정은 수행하지 않았다.

## 현재 네트워크 구성

```text
Minecraft 클라이언트
  └─ 211.53.196.231:25565
       └─ Velocity
            ├─ school  → 127.0.0.1:25566
            └─ wild    → 127.0.0.1:25567
```

| 항목 | 현재 구성 | 의미 |
| --- | --- | --- |
| 기본 접속 서버 | school | 학교로 먼저 연결 |
| 프록시 외부 바인딩 | 0.0.0.0:25565 | 게임 접속 입구 |
| 학교 바인딩 | 127.0.0.1:25566 | 외부에서 직접 접속하지 않는 백엔드 |
| 야생 바인딩 | 127.0.0.1:25567 | 외부에서 직접 접속하지 않는 백엔드 |
| Velocity 인증 | online-mode=true | 정품 계정 인증을 프록시에서 처리 |
| 백엔드 인증 | online-mode=false | modern forwarding 구성과 함께 사용하는 값 |
| 전달 방식 | MODERN | 프록시와 백엔드 설정·비밀키가 맞아야 함 |
| 게임 내 이동 | /server school, /server wild | 실제 정품 계정 이동은 미검증 |
| 던전 | 없음 | 미래 확장 대상으로만 취급 |

백엔드의 online-mode=false만 따로 보고 true로 바꾸거나 학교·야생 포트를 공개하지 않는다. forwarding secret의 실제 값은 이 문서에 적지 않았다. 기존 구축 기록에는 비밀키 일치 검증이 기록되어 있지만, 이번 문서 작업에서는 비밀키 내용을 다시 출력하지 않았다.

## 버전과 서버 자원 설정

| 구성 요소 | 현재 확인한 버전 또는 설정 |
| --- | --- |
| Minecraft 백엔드 | 1.21.4 |
| 서버 구현체 | Purpur 2416, Paper 호환 기반 |
| Velocity 소스 | 3.6.0-SNAPSHOT |
| Velocity 고정 커밋 | 843a47e2a38325309cd66133149fc9a984f76bb8 |
| Java 경로 | C:\Program Files\Java\jdk-21.0.10\bin\java.exe |
| PowerShell 7 | 7.6.6 |
| Gradle Wrapper 캐시 | 8.12.1 계열 |
| 학교 힙 | Xms512M, Xmx6G |
| 야생 힙 | Xms512M, Xmx8G |
| Velocity 힙 | Xms128M, Xmx512M |
| GC | G1GC |
| 학교 시야·시뮬레이션 거리 | 8 / 6 |
| 야생 시야·시뮬레이션 거리 | 10 / 6 |
| 백엔드 network-compression-threshold | -1 |

Purpur는 당시 Paper 다운로드 서비스 문제로 선택한 구성이다. Velocity는 공식 소스의 지정 커밋을 호스팅에서 빌드했으며, 정식 배포 JAR를 그대로 설치한 것으로 설명하면 안 된다. 향후 업데이트는 사용자 요청과 의존 플러그인·클라이언트 호환성을 확인해 수행한다.

Minecraft 상태 응답의 Velocity 버전 문자열은 지원 프로토콜 범위를 표시할 수 있다. 이번 조회에서 나온 `Velocity 1.7.2-26.2`를 프록시 소프트웨어 버전으로 기록하지 않는다. 세 엔드포인트의 조회 프로토콜은 769였고 접속 인원은 0명이었다.

Xmx는 최대 힙 설정이며 실제 사용 메모리와 동일하지 않다. 동시 빌드·서버 실행을 고려해 호스팅에서 여유 자원을 확인한다.

## 현재 상태를 구분해서 읽기

| 대상 | 확인한 상태 | 아직 확인하지 않은 부분 |
| --- | --- | --- |
| 학교·야생·Velocity 프로세스 | 모두 alive=true | 장시간 운영과 부하 시험 |
| 세 엔드포인트 상태 패킷 | 호스팅에서 응답 확인 | 이번 작업에서 외부 회선 접속을 재검증하지 않음 |
| 예약 작업 | 기존 구축 기록에 등록·운영 확인 | 운영체제 실제 재부팅 후 시작 검증 |
| 학교 월드 | 임시 flat world | 기존 학교 건축 월드 이전 |
| 야생 월드 | 임시 normal world | 커스텀 야생 월드 이전 |
| 월드 데이터팩 | school·wild 모두 bukkit 폴더만 존재 | Terralith와 chacademia 구조물 데이터팩 적용 |
| 실행 플러그인 | 세 서버의 plugins/*.jar 모두 0개 | 자체·외부 플러그인 설치와 로드 |
| 자체 플러그인 | JAR 4개 준비 | 실제 서버에서 기능 검증 |
| 기존 테스트 보고서 | 합계 131개 성공 | 실제 MariaDB·인게임·모델 연동 검증 |
| 호스팅 DB | 운영 문서상 설치·이전 미완료 | 서비스 목록은 일반 계정 권한으로 확인 불가 |
| 클라이언트·런처·리소스 | 이번 호스팅 이전 대상에 포함하지 않음 | 최신 클라이언트와 서버 프로토콜 대조 |

DB 관련 추가 조회에서 알려진 일부 설치 경로는 없었고 mariadbd/mysqld 프로세스도 발견하지 못했다. 이것만으로 모든 경로와 서비스를 완전 조사했다고 주장하지 않는다. 관리자 권한으로 추가 점검하지 않았다.

서버가 떠 있다는 사실은 기존 게임 기능이 배포되었다는 뜻이 아니다. 특히 `C:\Chacademi\build-workspace\plugins`에 있는 JAR는 컴파일 의존성이고 실행 서버의 plugins 폴더와 다르다.

## 호스팅의 주요 경로

| 경로 | 용도 |
| --- | --- |
| C:\Chacademi\AGENTS.md | 호스팅 우선 작업 지침 |
| C:\Chacademi\handoff\DOT_HANDOFF.md | dot 실행 환경 인수인계 |
| C:\Chacademi\handoff\SERVER_HANDOFF.md | 이 서버 인수인계 문서 |
| C:\Chacademi\build-workspace | 현재 원격 서버 소스 |
| C:\Chacademi\build-workspace\Build-Server.ps1 | 4개 서버 모듈 빌드·검증 |
| C:\Chacademi\artifacts\server | 준비된 플러그인 JAR |
| C:\Chacademi\network | 실행 서버 전체 |
| C:\Chacademi\network\network.json | 서버 경로·JAR·힙 설정 |
| C:\Chacademi\network\school | 학교 서버 |
| C:\Chacademi\network\wild | 야생 서버 |
| C:\Chacademi\network\velocity | 프록시 |
| C:\Chacademi\network\scripts | 상태·시작·종료·콘솔 제어 |
| C:\Chacademi\network\logs | 감독·각 서버 콘솔 로그 |
| C:\Chacademi\network\OPERATIONS.txt | 기존 관리 명령과 구축 기록 |
| C:\Chacademi\network\setup-receipt.json | 기존 구축 확인 기록 |
| C:\Chacademi\tools\gradle-cache | 호스팅 Gradle 캐시 |
| C:\Chacademi\tools\Velocity-source | 프록시 소스 |
| C:\Chacademi\downloads | 호스팅 직접 다운로드 보관 |

`build-workspace\.git`는 이번 점검에서 존재하지 않았다. 이 작업 폴더를 기존 Git 저장소나 원격 저장소와 자동으로 연결된 상태로 취급하지 않는다. 후속 코드 변경 전에는 원격에서 기준 파일과 변경 범위를 기록하고 복구할 수 있는 사본을 준비한다.

## 서버 소스 모듈 지도

| 모듈 | 준비된 산출물 | 역할 |
| --- | --- | --- |
| magic-codex-paper | magic-codex-bridge-0.29.0.jar | 마나·능력치·장비·감정·강화·재구성·학교·교화·펫·칭호·의뢰·대화·사회 기능의 중심 플러그인 |
| magic-discovery-paper | magic-discovery-0.2.0.jar | 마법 발견 조건·획득·최초 발견 기록·보상 연결 |
| creature-spawns-paper | chacademia-wildlife-0.3.0.jar | MythicMobs 커스텀 서식지 조건·무리·구조물·계절 연결 |
| tornado-event-paper | chacademia-tornado-0.2.0.jar | 회오리 이벤트·소환 제한·계절 마법 보조 |
| magic-codex-protocol | 별도 배포 플러그인 아님 | 서버와 클라이언트가 공유하는 패킷 정의 |
| magic-codex-database | 별도 배포 플러그인 아님 | MariaDB/SQLite 설정과 연결 처리 |

Java 소스 기본 위치는 각 모듈의 `src\main\java`, 기본 설정은 `src\main\resources`다. 중심 플러그인 패키지는 `school.magiccodex.paper`, 발견 플러그인은 `school.magiccodex.discovery`, 야생은 `school.chacademia.wildlife`다.

`MagicCodexBridge.java`가 시스템 초기화와 공개 API를 연결한다. 주요 대응은 다음과 같다.

| 기능 | 우선 읽을 클래스 |
| --- | --- |
| 마나·시전·피해 | ManaBridge, ManaService, ManaCasting, ManaSpells, SpellRuntime, SpellRules |
| 능력치·장비 | StatsBridge, StatsService, EquipmentBridge |
| 서클 | AscensionBridge, AscensionGate |
| 감정·강화 | AppraisalBridge, AppraisalRoll, EnhancementBridge, EnhancementRules |
| 재구성 | ReconfigurationBridge, ReconfigurationRules |
| 학교·기증 | SchoolBridge, SchoolStore, SchoolExpansion |
| 계절·온도 | ClimateService, ClimateMath, SeasonClock, TemperatureBridge |
| 교화·펫·이로치 | TamingBridge, TamingRules, TamingModelFx, PetBridge, ShinyBridge, CustomShinyModels, CosmeticPetSafety |
| 의뢰 | QuestBridge, QuestDefinition, QuestStore, QuestAdminDocument |
| 대화·이야기 | DialogueBridge, DialogueDefinition, DialogueStore |
| 칭호 | TitleBridge, TitleDefinition, TitleStore, TitleExpansion |
| 친구·사회 | SocialBridge, FriendStore, WhisperTickets |
| 공용 플레이어 원본 | PlayerStateBridge, PlayerStateStore |
| 표시명 | DisplayNames |
| 권한·경제 조회 | PermissionSubscriptions, LuckPermsHook, WalletBridge, WalletSubscriptions |

## 빌드와 의존 플러그인

빌드는 호스팅의 프로젝트 루트에서 다음 명령으로 수행한다. 이 문서 작성 과정에서는 새 빌드를 실행하지 않았다.

```powershell
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\build-workspace\Build-Server.ps1' -Verify
```

`-Verify`는 `verifyServer`와 `assembleServer`를 실행한다. 검증을 생략한 호출은 `assembleServer`만 수행한다. 스크립트는 Java 21과 원격 Gradle 캐시를 사용하고, 결과를 `C:\Chacademi\artifacts\server`로 복사하며 해시를 출력한다. 실행 서버의 plugins 폴더에는 자동 설치하지 않는다.

루트 `build.gradle`은 wildlife와 tornado의 중심 플러그인 의존성을 프로젝트로 연결한다. 하위 모듈 파일에 남아 있는 이전 버전 JAR 경로만 보고 독립 빌드를 수행하지 않는다. 기본 통합 스크립트를 사용한다.

| 플러그인 | plugin.yml 의존성 |
| --- | --- |
| MagicCodexBridge | softdepend: LuckPerms, Vault, PlaceholderAPI, MCPets, MythicMobs, ModelEngine, Citizens |
| MagicDiscovery | softdepend: LuckPerms, MagicCodexBridge |
| ChAcademiaWildlife | depend: MythicMobs / softdepend: MagicCodexBridge, WorldGuard |
| ChAcademiaTornado | depend: MythicMobs, WorldGuard, ModelEngine / softdepend: MagicCodexBridge |

원격 컴파일 폴더에는 ModelEngine 4.0.8, MythicMobs 5.9.0, PlaceholderAPI 2.11.6 JAR가 있다. MariaDB JDBC 3.5.10은 두 중심 산출물에 포함하도록 빌드가 구성돼 있다. 이 버전 목록은 현재 소스의 컴파일 기준이며 모든 외부 플러그인이 호스팅에 설치되었다는 뜻이 아니다.

softdepend라고 해서 해당 외부 플러그인 없이 모든 기능을 사용할 수 있다는 뜻은 아니다. 예를 들어 교화 보유 권한 지급은 LuckPerms와 MCPets 연결이 필요하고, 모델 연출에는 실제 ModelEngine 모델 등록이 필요하다. 구매 에셋과 상용 플러그인은 기존 사용 권한을 유지하고 공개 저장소에 재배포하지 않는다.

## 실행 서버 관리 명령

다음 명령은 호스팅에서 실행한다. 관리 스크립트는 요청 파일과 감독 프로세스를 통해 콘솔에 명령을 전달한다.

```powershell
# 상태 조회
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\network\scripts\Control.ps1' -Action Status

# 상태 패킷 조회
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\network\scripts\Check-Network.ps1'

# 전체 시작
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\network\scripts\Control.ps1' -Action Start

# 전체 정상 종료와 월드 저장
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\network\scripts\Control.ps1' -Action Stop

# 학교 콘솔에 조회 명령 전달
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\network\scripts\Control.ps1' -Action Console -Server school -Command 'list'

# 야생 콘솔에 조회 명령 전달
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File 'C:\Chacademi\network\scripts\Control.ps1' -Action Console -Server wild -Command 'list'
```

전체 재시작은 Stop → 모든 서버 alive=false 확인 → Start 순서다. 일상 종료에 taskkill이나 강제 프로세스 종료를 사용하지 않는다. 설치나 변경을 요청받지 않은 점검에서는 Status와 조회 명령만 사용한다.

예약 작업 이름은 `Chacademi-Network`, 실행 계정은 `chacademi-codex`다. 로그오프 상태 실행을 위한 SeBatchLogonRight는 기존 승인을 받아 구성했다. 비정상 종료 시 감독 프로세스는 개별 서버를 최대 3회 재시작한다. SSH가 종료되어도 서버는 예약 작업과 감독 프로세스로 계속 실행되는 구성을 이전에 확인했다. 운영체제 재부팅 시험은 아직 남아 있다.

로그 전체를 노트북으로 가져오지 않는다. 호스팅에서 최근 오류와 필요한 구간만 추출하고, 서버 ID·종료 코드·시간을 함께 기록한다.

## 서버와 클라이언트의 책임

모드는 계속 클라이언트 모드로 유지한다. 서버는 Paper 계열 플러그인으로 통신하고 권한, 마나, 쿨타임, 강화 판정, 코어 소비, 보상과 플레이어 상태를 판단한다. UI와 렌더링만 클라이언트가 담당한다.

클라이언트가 보낸 대상·수치·진행 상태를 그대로 신뢰하지 않는다. 서버는 요청 크기, 권한, 세션·순서·리비전, 대상 거리와 시야, 보유 아이템과 원본 값을 확인하는 방향으로 구현돼 있다. 변경 시 기존 검증을 제거하지 않는다.

PNG UI, NPC 일러스트, 고화질 아이콘, 모델과 효과음은 서버 JAR만으로 완성되지 않는다. 클라이언트 리소스와 공유 프로토콜이 맞아야 한다. 이번 호스팅 소스에는 클라이언트 모드 전체와 런처 리소스가 포함되지 않았으므로 정확한 클라이언트 파일 경로를 추측하지 않는다.

사용자는 커스텀 인벤토리 기능을 제외하도록 지시했다. 장비·칭호·HUD가 있다는 이유로 폐기한 전체 인벤토리 UI를 다시 켜지 않는다. 새 클라이언트 빌드, 노트북 런처 교체와 게임 실행은 해당 작업을 요청받을 때만 진행한다.

## 데이터 저장 원칙

MariaDB에는 자체 시스템의 지속되어야 할 원본과 기록을 저장한다. 매 틱 능력치 계산, 장비·계절 보정 결과, 화면 표시값과 효과 연출 상태를 DB에 쓰지 않는다. 계산은 서버 메모리에서 수행한다.

| 데이터 | 소유하거나 처리하는 곳 |
| --- | --- |
| 서클, 기본 마나·회복·가속, 쿨타임, 액세서리 원본, 재구성 사용 횟수 | 자체 player state |
| 발견 진척·획득·최초 발견 기록 | MagicDiscovery |
| 친구, 기숙사 소속, 기증과 점수 | 자체 저장소 |
| 의뢰 정의·진척·수령 기록 | QuestStore |
| 대화 정의·스토리 플래그·외부 동작 기록 | DialogueStore |
| 칭호 보유·접두사·접미사 선택 | TitleStore |
| 돈의 실제 잔액 | 기존 경제 플러그인과 Vault 연결 |
| 권한·발견 권한·펫 사용 권한 | LuckPerms 등 기존 권한 시스템 |
| 펫 자체 데이터 | MCPets의 기능과 DB 연동을 사용하려는 계획 |
| 바닐라 인벤토리와 일반 플레이어 데이터 | 별도 동기화 수단이 필요하며 현재 미완료 |
| 장비 아이템 자체의 강화 원본 | 아이템 PDC와 아이템 이전 경로 |
| 계절 시계 상태 | 현재는 서버별 climate-state.yml와 월드 시간 |

기존 외부 플러그인이 관리하는 데이터는 임의로 자체 테이블에 중복 구현하거나 해당 플러그인을 수정하지 않는다. 특히 MCPets DB 기능을 이용하려는 사용자 의도를 유지한다.

기숙사 점수는 사용자가 말했던 “학점”의 실제 의미다. 별도의 학업 학점 시스템으로 해석하지 않는다.

## DB 설정과 테이블 지도

공통 설정 로더는 `magic-codex-database\src\main\java\school\magiccodex\database\DatabaseSettings.java`다. `database.properties`가 없거나 `mode=sqlite`면 각 플러그인의 로컬 SQLite를 사용한다. MariaDB 모드는 `mode=mariadb`와 `host`, `port`, `database`, `user`, `password`를 읽는다. 실제 암호는 문서·채팅·로그에 쓰지 않는다.

실제 배포 이후의 설정 위치는 학교와 야생 각각의 `plugins\MagicCodexBridge\database.properties` 및 `plugins\MagicDiscovery\database.properties`다. 이 파일들이 현재 호스팅 실행 서버에 만들어져 있다고 가정하지 않는다. 공용 DB를 사용할 때는 같은 공용 DB를 바라보게 하고, 외부 플러그인의 설정과 혼동하지 않는다.

JDBC 접속 제한은 connectTimeout=5000, socketTimeout=10000이다. SQLite는 WAL, synchronous=FULL, busy_timeout=5000을 설정한다. 스키마는 각 Store 초기화의 CREATE TABLE 코드에서 관리하며, 테이블 이름은 다음과 같다.

| 영역 | MariaDB 테이블 |
| --- | --- |
| 자체 플레이어 원본 | codex_player_state |
| 친구와 최초 친구 신호 | codex_friend_owners, codex_friends, codex_first_friend_signal |
| 기숙사·기증·점수 | codex_houses, codex_donations, codex_house_points, codex_quest_house_awards |
| 발견 | codex_discovery_progress, codex_discovery_acquisitions, codex_discovery_firsts |
| 의뢰 | codex_quest_catalog, codex_quest_progress, codex_quest_lock |
| 대화·메인 이야기 | codex_dialogue_catalog, codex_story_state, codex_dialogue_effect |
| 칭호 | codex_title_player, codex_title_owned |

SQLite 파일명은 중심 플러그인의 `friends.db`, `school.db`, `quests.db`, `dialogue.db`, `titles.db`와 발견 플러그인의 `discoveries.db`다. 일부 SQLite 테이블은 MariaDB의 codex_ 접두사와 다른 이름을 쓴다.

**모드 변경은 기존 데이터 이전이 아니다.** SQLite 파일과 플레이어 PDC, 아이템 원본, 기존 플러그인 DB를 확인한 후 필요한 데이터만 이전해야 한다. 접속 계정·DB 생성·데이터 이전·실제 학교↔야생 검증은 호스팅에서 아직 완료되지 않았다.

## 플레이어 상태와 서버 이동

`PlayerStateStore.State`는 서클, 현재 마나, 기본 최대 마나·회복·가속, 유효한 쿨타임, 액세서리 슬롯 4개의 직렬화 원본, 재구성 사용 횟수 3종을 저장한다. 바닐라 전체 인벤토리나 MCPets 전체 데이터를 저장하는 시스템이 아니다.

MariaDB 모드에서 PlayerStateBridge는 전용 IO 작업자에서 저장소를 처리한다. 메인 스레드에서 플레이어 상태를 캡처하고 적용하며, 주기 저장은 600틱이다. 정상 TPS 기준 약 30초지만 실제 지연은 TPS와 DB 상태에 따라 달라진다. 접속 시 읽고 소유권을 확보하며 퇴장·종료 시 저장과 소유권 해제를 시도한다.

플레이어 행에는 lease_token, lease_until, revision이 있다. 현재 lease는 60초이고 저장으로 갱신한다. 새 서버가 소유권을 얻지 못하면 5틱 간격으로 최대 약 40회 재시도하는 코드가 있다. 상태 로드나 소유권이 깨지면 해당 플레이어의 접속을 종료하는 처리도 있으므로, 장애를 무시하고 기본값을 덮어쓰게 바꾸지 않는다.

갑작스러운 서버 종료에서는 이전 소유권이 남을 수 있다. 서버 이동 직후, 종료 중 저장 실패, DB 끊김, 재접속과 이중 요청을 실제로 시험해야 한다. 131개 단위 테스트만으로 이 경로를 검증했다고 판단하지 않는다.

일반 인벤토리, 경험치·엔더 상자·기타 바닐라 상태의 서버 간 동기화는 별도 과제다. 자체 player state가 있다는 이유로 아이템 이전이 이미 된다고 설명하지 않는다. 외부 인벤토리 동기화 플러그인의 최종 선택과 적용도 이 문서에서 확정하지 않는다.

## 마법 발견과 실제 마법 제작 상태

마법 발견은 MagicDiscovery의 `Definitions`, `NativeConditions`, `DiscoveryService`, `DiscoveryStore`가 담당한다. `discoveries.yml`의 조건·선행 조건·획득 기록과 권한 지급을 연결한다. 기본 title-scope는 server-first이며 같은 DB를 공유하면 그 DB 기준 최초 발견 기록을 고려해야 한다.

사용자는 도서관에서 얻는 마법이 너무 많지 않게 하고, 마법 설명으로 획득 조건을 어느 정도 유추할 수 있게 하기를 원한다. 사용자가 직접 수정한 약 60개를 우선 제작하되, 화려한 대형 연출은 뒤로 미루고 MythicMobs를 중심으로 구현하는 방향이었다.

현재 원격 기본 리소스의 두 칸 들여쓰기 ID를 정적으로 집계하면 다음과 같다. 이는 YAML 전체 의미 검증이나 완성된 마법 개수와 다르다.

| 파일 | ID 항목 수 | 용도 |
| --- | --- | --- |
| donation-spells.yml | 300 | 기증·도감 관련 마법 목록 |
| mana-spells.yml | 200 | 마나·시전 설정 목록 |
| discoveries.yml | 62 | 발견 조건 정의 |
| spell-runtime.yml | 31 | 실제 MythicMobs 스킬 연결 |

**마법 300개가 모두 제작·발견·시전 연결·검증까지 끝난 것으로 넘기면 안 된다.** ID 대응과 사용자 수정 원본, MythicMobs 스킬·모델·아이콘을 함께 대조해야 한다.

SpellRuntime은 서버 마력을 읽고 MythicMobs 시전 데이터에 `magic_power`, `damage`, `magic_haste` 변수를 넣는다. 피해 설정은 기본 피해와 마력 비율을 연결하는 구조다. 마나와 쿨타임은 기존 서버 시전 경로를 사용한다. MythicLib 사용은 과거 검토한 방향이며, 현재 모든 마법에 MythicLib 변수를 쓰는 것으로 설명하지 않는다.

사용자의 마지막 관련 지시에는 이전 시연용 마법 폐기와 마법진 크기 확대가 있었다. 원격 spell-runtime에는 기존 연결 ID가 남아 있고, 해당 MythicMobs·모델 리소스는 새 실행 서버에 설치하지 않았다. 후속 작업자는 이를 운영용 완성본으로 되살리지 말고 실행 연결과 배포 리소스의 정리 상태를 먼저 대조해야 한다. 도감 기획 300개 자체를 자동 삭제하는 지시로 확대하지 않는다.

## 마나와 능력치 및 장비

마나 기본 설정은 최대 100, 회복 5, 가속 0이다. 마나 회복과 시전 판단은 서버에서 처리하고, 플레이어 원본은 PDC 또는 MariaDB player state 경로와 연결한다. 장비·계절로 계산한 최종값을 실시간 DB 저장 대상으로 만들지 않는다.

스텟창, 장비와 액세서리 UI, 서클 승급, 강화 가능한 마법봉 원본 등록 기능은 서버 소스에 존재한다. 실행 서버 설치와 클라이언트 최신 UI 검증은 아직 남아 있다.

표시명은 `config.yml`의 `display-name-placeholder: "%user_nickname%"`와 DisplayNames로 읽는다. 내부 식별은 UUID다. 다른 닉네임 플레이스홀더를 추측해서 교체하지 않는다.

장비 미리보기는 아이콘, 남은 강화 `5/10` 형태, 아이콘 아래 남은 횟수, 능력치와 lore 기반 설명을 요구했다. 설명이 비어 있어도 아래 구분선을 유지하고 불필요한 하단 여백을 줄이기를 원했다. 이러한 화면 위치는 클라이언트 리소스와 코드에서 확인해야 하며, 서버 장비 계산을 수정해서 UI 정렬을 맞추지 않는다.

능력치 표시에서는 마력·마나·마법 가속 아이콘과 글자의 세로 중심을 맞추되 기본 왼쪽 정렬을 유지하고, 수치는 우측에 읽기 좋게 표시하는 방향이었다.

## 코어 감정과 강화 및 재구성

| 기능 | 주요 설정 | 유지할 요구 |
| --- | --- | --- |
| 마력코어 감정 | core-appraisal.yml | 초반 1~3단계는 쉽고 4단계 고비, 5~6단계 회복, 7단계 고비 |
| 장비 강화 | enhancement.yml | 성공·실패 모두 코어 소비, 표시 타이밍과 판정 일치 |
| 강화 초기화 | reconfiguration.yml | 횟수와 기존 강화 내용 모두 초기화 |
| 실패 복구 | restore-count: 1 | 사용 1회당 실패 횟수 1회만 복구, 성공 능력치 유지 |
| 코어 성향 변경 | ReconfigurationRules | 코어 개수와 상승분 유지, 어떤 능력치가 중심인지 변경 |

현재 감정 formation 설정은 1~10단계 순서로 100%, 95%, 90%, 50%, 70%, 60%, 30%, 55%, 35%, 4%다. 설정 주석의 평균은 파괴 판정 전 형성 개수 약 3.8056코어다. break-chance 0.005는 일반 형성 실패 시 조건부 파괴 확률이다. 감정 확률과 장비 강화의 성공 확률을 혼동하지 않는다. 실제 분포 계산은 AppraisalRoll과 설정을 기준으로 재검증한다.

감정 비용은 현재 기본 설정 1000이다. 성공 사운드 개선 요구는 최종적으로 “강화”가 아니라 “마력코어 감정”을 뜻하도록 정정됐다. 자수정과 신호기 기본 사운드의 피치 조절을 선호했다. 코어 성공 빛 연출, 은은한 움직이는 발광과 프레임 부드러움도 요청했다. 이 부분의 최종 클라이언트 결과는 호스팅 서버 소스만으로 확인하지 못했다.

실제 주문서 아이템 연결은 나중에 한 번에 하기로 했다. 현재 재구성 사용 횟수 지급 명령과 서버 API가 있으므로 아이템과 자동 연동된 것으로 취급하지 않는다.

```text
/마법봉 give <닉네임>
/마법봉 register <마력> <추가 마나> <마법 가속>
/마법봉 reload
/마력코어 give <유저> [개수]
/마력코어 reload
/코어감정
/강화
/마력재구성
/재구성관리 지급 <닉네임> <초기화|복구|변경> <횟수>
/재구성관리 원본 <마력> <추가 마나> <마법 가속>
/재구성관리 reload
```

위 명령은 plugin.yml과 소스 기준이다. 현재 새 실행 서버에 플러그인이 없으므로 곧바로 사용할 수 있는 상태라고 안내하지 않는다.

## 펫과 교화 및 이로치

펫은 기본적으로 꾸밈과 수집 요소이며, 추가 기능으로 고려한 것은 라이딩 정도다. 무분별한 전투·생산 기능을 추가하지 않는다. 바닐라 동물·몬스터와 커스텀 생명체를 교화 대상으로 삼고 화살·투사체·기타 비생명체는 제외한다.

별 등급은 기존 펫 시스템의 1~3성 구분을 이용한다. 독립적인 두 번째 별 등급 시스템을 새로 만들지 않는다. MCPets 설정, `pets.yml`, taming 대상과 실제 pet-id의 대응을 확인한다.

기본 교화 설정은 range=12, 마나 15, 쿨타임 8초, 시전 4000ms, 보스 유예 30초, 동시 활성 한도 64다. 서버는 레이 트레이스, 거리·시야, 대상 유효성, 마나·쿨타임·권한을 검증한다. 포획 확률은 대상별 chance와 체력 보정을 사용하므로 한 숫자로 모든 몹 확률을 설명하지 않는다.

ModelEngine 연출은 일반 `chacademia_taming_circle`, 보스 `chacademia_taming_seal` 모델을 별도 앵커에 붙이는 구조다. 실제 모델과 애니메이션 파일은 별도로 등록해야 한다. 특정 보스의 바닐라 사망 애니메이션을 그대로 쓰는 것으로 보장하지 않는다.

추가 몹 대상 설정은 `taming.d\*.yml`, 이로치 설정은 `shiny.d\*.yml`처럼 CreatureConfigFiles의 분할 파일 경로를 사용한다. 기존 base 설정과 같은 key가 나오면 조용히 덮어쓰는 대신 오류를 내는 코드다. 구매 몹 팩의 MythicMobs·MCPets·ModelEngine 파일 묶음은 이번 서버 소스 이전에 포함되지 않았다.

이로치는 이름 앞에 “이로치”를 붙이지 않고 원래 이름을 노란색으로 표시한다. 커스텀 모델은 원본을 복제한 전용 모델과 텍스처를 사용하며, 몹별 개성을 살려 색을 섞는다. 전부 보라색 또는 전부 검은색으로 바꾸는 방향은 사용자가 거부했다. 블랙·화이트 조합은 일부만 사용한다. 바닐라 몹의 색 표현은 클라이언트 모드와 서버 통신 방향을 유지한다.

반짝임 파티클과 주기적 알림 소리, 낮은 스폰 확률을 요구했다. 야생 애드온 기본 shiny-denominator는 4096이지만, 실제 MythicMobs RandomSpawns와 대상 정의가 적용되어야 운영 스폰 확률로 검증할 수 있다.

**최신 미완료 점검:** 사용자는 교화 대상 UI를 작게 줄이고 이름·확률 위주로 보이게 하며, 교화 스킬을 착용 중이고 가까운 대상일 때만 표시하도록 요청했다. 현재 원격 QUERY 코드는 대상과 권한을 검사하지만 착용 여부를 직접 검사하는 항목은 해당 경로에서 확인하지 못했다. 클라이언트 소스는 조사하지 않았으므로 요구가 완료됐다고 표시하지 않는다.

## 커스텀 바이옴과 야생 스폰

야생 애드온은 MythicMobs의 RandomSpawns에 사용할 `cahabitat` 커스텀 조건을 등록한다. 몹을 애드온만으로 전부 독자 랜덤 스폰시키는 것으로 설명하지 않는다. MythicMobs의 몹·스킬·RandomSpawns 정의가 함께 필요하다.

현재 `creature-spawns-paper\src\main\resources\habitats.yml`에는 서식지 138개, 고유 Terralith 바이옴 66개, 계절 영향 서식지 26개, 구조물 조건 서식지 10개가 있다. .yml 파일 일부는 JSON 문법으로 작성돼 있으며 YAML 로더가 읽을 수 있는 형태다. 파일 확장자만 보고 임의로 내용을 다시 생성하지 않는다.

조건은 바이옴 namespace ID, 높이, 지상·수중, 시간대, 빛, 수변·자연 지면, 공간 여유, 근처 개체 수, 무리 크기, 번개·보름달, 계절, 구조물과 거리 등을 포함한다. 모든 몹에 계절 조건을 강제하지 않는다.

기본 전역 설정은 world, 플레이어와 24~80블록 거리, 지역 한도 24, 원점 반경 96 스폰 제외다. 무리 설정은 검증 코드상 최소 1, 최대 6이며, 개별 cap과 중복 제한도 함께 적용한다. 배치 기획과 최종 운영 확률은 서로 구분한다.

구조물은 나무·작은 자연 장식이 아니라 대형 수호자·폐허·문 등으로 제한하기를 원했다. StructureIndex는 이미 로드된 청크의 `chacademia:` 구조물 시작과 실제 piece 경계 상자만 캐시한다. 스폰 조건에서 locate나 강제 청크 생성을 수행하지 않는다. 시작 청크가 로드되지 않은 구조물은 인덱스에서 보이지 않을 수 있고, 실제 생성·탐색 시험이 필요하다.

현재 야생 world에는 Terralith와 구조물 데이터팩이 없다. 이 상태에서 해당 몹의 자연 스폰이 정상인지 검증할 수 없다. 기존에 분리한 대형 구조물 원본과 스키메틱·템플릿은 필요한 작업을 요청받으면 기존 자료에서 찾아야 하며, 불필요하게 사용자에게 다시 제작하게 요구하지 않는다.

## 희귀 몹의 구조물 조건

이 표는 현재 habitats.yml의 구조물 조건 10개를 그대로 요약한 것이다. 표의 계절이 비어 있으면 구조물 조건 외에 계절 제한은 지정하지 않은 것이다. 나머지 바이옴·시간·수량 조건은 원본 설정을 따른다.

| 몹 | 서식지 ID | 구조물 ID의 chacademia: 뒤 이름 | 거리 | 계절 |
| --- | --- | --- | --- | --- |
| 약초 수호골렘 | lostasset_mob3_herbgolem | forest_guardian, colossal_guardian | 64 | 없음 |
| 산울림 오우거 | ogre_hammer | ancient_ruins | 64 | 없음 |
| 달그늘 고양이 | nocsy_cat_fallen | ruined_gate, mushroom_guardian | 48 | 없음 |
| 유니콘 | horse_unicorn | colossal_guardian, ruined_gate | 64 | spring |
| 키츠네 | kitsune_normal | colossal_guardian, ruined_gate | 64 | 없음 |
| 왕실 히포그리프 | hippogryph_royal | colossal_guardian | 72 | 없음 |
| 악몽 랩터 | raptor_nightmare | ancient_ruins, colossal_guardian | 64 | 없음 |
| 룬 영록 | stag_rune | forest_guardian, colossal_guardian | 64 | autumn |
| 왕실 고대 골렘 | golem_royal | colossal_guardian | 72 | 없음 |
| 만월 울프호크 | wolfhawk_fullmoon | ancient_ruins, colossal_guardian | 72 | 없음 |

구조물 addon 원본의 누락 여부와 실제 생성 완료는 이번 원격 소스 조회로 증명하지 않았다. 파일·구조물 정의가 호스팅에 존재하는지 먼저 확인한 뒤 배치와 가까운 판정을 검증한다.

## 계절과 온도 및 회오리

ClimateService는 계절 상태와 계절 보정을 서버에서 계산한다. `climate.yml`과 `climate-state.yml`, 지정 `clock-world`의 fullTime을 사용한다. 상태를 별도의 공용 MariaDB 계절 테이블에서 가져오는 코드는 이번 확인 대상에 없었다.

학교와 야생의 월드 시간이 각각 다르면 계절도 달라질 수 있다. 시즌 기준 시간을 어떻게 통일할지는 서버 분리 이후 검증할 과제다. 실시간 계산값을 DB에 계속 기록하는 방식으로 해결하지 않는다.

온도와 계절 HUD 관련 서버 통신 코드가 있으며, config.yml의 temperature 주석에는 바이옴 계산 연결을 추후 작업으로 적은 흔적도 있다. 해당 주석만으로 전체 온도 시스템이 미구현이라고 단정하지 않고 ClimateService·TemperatureService의 계산과 실제 설정을 함께 읽는다.

ChAcademiaTornado는 MythicMobs·WorldGuard·ModelEngine을 필요로 한다. 활성 수, 시간, 거리, 중복·쿨타임, 스캔 수, 파괴 블록과 debris 한도 등이 설정돼 있다. 랜덤 이벤트나 블록 파괴 기능을 켤 때는 실제 월드와 보호 영역 설정을 확인한다. 현재 실행 서버에 설치돼 있지 않다.

## 학교와 기숙사 및 기증

기숙사 점수·소속, 마법 기증 기록과 랭킹은 SchoolBridge·SchoolStore가 담당한다. 현재 기숙사 권한 ID는 `arkeon`, `lumina`, `bestiaz`, `noxer`다. 표시명과 소속에 연결하는 기존 규칙을 유지한다.

마법 도감에서 발견한 마법을 학교에 기증하고 해당 기록과 기숙사 기여를 연결하는 기획이다. 최초 기증과 점수 갱신을 같은 저장 경로에서 처리하며, 의뢰 기숙사 보상은 별도 보상 기록으로 중복 적용을 방지하는 경로가 있다.

학교·야생에서 공용 기증과 점수를 보려면 같은 DB와 관련 권한·클라이언트 연결을 적용해야 한다. 학교가 flat 임시 월드인 현재 상태를 완성된 기숙사·학교 콘텐츠 배치로 설명하지 않는다.

사용자는 닉네임을 기존 표시명 플레이스홀더에서 가져오기를 원했다. UUID와 표시명을 분리하고 표시명 변경이 기증자·친구·칭호 표시에도 반영되는지 확인한다.

## 의뢰 게시판과 관리자 편집

의뢰는 일반 반복 임무이며 메인 이야기의 대화·분기와는 구분한다. 학교에서 수락하고 야생에서 진행하도록 설계한 공용 정의·진척 저장소가 있다. `quests.yml`의 `server-id`는 각 백엔드에서 school, wild로 다르게 설정한다. 기본 파일은 school이다.

조건에는 KILL, MYTHIC_KILL, SUBMIT, NPC, BIOME, EVENT가 있다. 대상, 수량과 표시문구 외에 server·world 조건도 설정할 수 있다. 이 조건을 비워두면 특정 서버로 제한하지 않는 방향으로 사용한다. NPC는 Citizens, Mythic 처치는 MythicMobs 연결을 사용하며, 사용자 정의 이벤트는 신뢰된 서버 API 또는 관리자 명령으로 전달한다.

관리 문서는 title, description, rank, completion-limit, daily, enabled, opens-at, permission, reward.label, reward.money, reward.house-points, reward.items, reward.commands와 목표 수·목표별 값을 다룬다. 원본 정의에는 F~A 랭크와 완료 횟수·시간 공개가 포함돼 있다.

일반인도 제목·서술형 설명·랭크·조건·보상을 편집하는 관리자 UI를 원했다. 명령어 보상과 함수 연동은 사용자가 직접 설정할 수 있게 한다. 목록은 수락 가능·완료·진행중 탭과 페이지를 제공하는 방향이다.

완료 의뢰는 별도 “완료” 글자를 반복하기보다 차카데미 도장을 찍고 종이를 약간 어둡게 표시하기를 원했다. 제목 아래 같은 디자인의 구분선을 일관되게 넣고, 랭크는 종이의 배경 레이어로 두되 가독성을 유지해야 한다. “다른 서버에서도 이어집니다”, “서버 의뢰 기능을 사용할 수 없습니다” 같은 설명 문구는 제거하라는 지시가 있었다. 화면 최종 결과는 클라이언트에서 대조한다.

```text
/의뢰
/의뢰관리
/의뢰관리 create <id>
/의뢰관리 set <id> <title|description|daily|enabled|permission|reward.label> <내용>
/의뢰관리 goal <id> <번호1~6> <KILL|MYTHIC_KILL|SUBMIT|NPC|BIOME|EVENT> <대상> <수량> <표시문구>
/의뢰관리 rewarditem <id> <MATERIAL:수량>
/의뢰관리 rewardcommand <id> <명령어>
/의뢰관리 reload
/의뢰관리 event <플레이어> <이벤트ID> [수량]
/의뢰관리 audit <UUID>
/의뢰관리 resolve <UUID> <id> <cycle> <delivered|retry>
```

소스의 도움말 명령과 관리자 UI 문서 필드는 완전히 동일한 범위를 노출하지 않을 수 있다. rank·보상 금액 등의 고급 필드는 QuestAdminDocument와 UI 또는 YAML 반영 경로에서 확인한다. 임의의 명령 구문을 만들어 안내하지 않는다.

`/의뢰관리 reload`는 로컬 YAML을 공용 카탈로그에 반영하는 경로다. 다른 서버나 관리자 UI에서 바꾼 내용을 덮어쓸 수 있으므로 사본과 변경 차이를 확인한다. 카탈로그는 정기 갱신하고, 수락 시 정의를 진척에 기록하는 구조다. 기존 수락자의 조건을 어떻게 유지할지는 QuestRevisionTest와 Store를 기준으로 검증한다.

현재 정의 한도는 64개, 목표는 최대 6개다. 더 큰 규모로 확장하려면 프로토콜·관리 UI·DB 정의 한도를 함께 확인한다.

## 의뢰 보상과 중복 지급 복구

의뢰 보상은 기숙사 점수, 경제 잔액, 아이템과 콘솔 명령을 연결한다. 기숙사 점수의 의미를 학업 학점으로 바꾸지 않는다. SUBMIT은 실제 보유·수량·소비와 보상을 서버에서 처리하고 클라이언트 진행 숫자로 완료시키지 않는다.

QuestStore에는 카탈로그·플레이어 잠금·진척·보상 상태와 토큰이 있다. 외부 플러그인 명령과 아이템 지급까지 DB 트랜잭션 하나로 완전히 되돌릴 수 있는 것은 아니다. 장애 직후 보상이 지급됐는지 모호하면 자동으로 다시 지급하지 않고 audit 기록과 실제 보상을 확인한다.

`delivered` 또는 `retry` 처리 전에는 해당 UUID, 의뢰 ID, cycle과 지급 로그를 함께 검토한다. 토큰은 일회성 보상 추적용 값이며 DB 암호와는 다르지만, 불필요하게 개인 데이터와 함께 공유하지 않는다. UI에는 이런 운영용 내부 설명을 늘어놓지 않는다.

서버 이동 직전 제출, 중복 클릭, 돈 지급 실패, 명령 실행 실패, 아이템 슬롯 부족과 서버 종료 중 지급을 실제 연동 테스트에 포함한다.

## NPC 대화와 메인 이야기

대화는 NPC 일러스트와 미연시형 선택지·빠른 타자기 표시·소리를 갖춘 클라이언트 UI를 사용한다. 서버는 DialogueDefinition의 장면·선택지·조건·동작, 스토리 상태와 세션을 관리한다.

예시 설정은 `dialogues\elena.yml`, `dialogues\arden.yml`이며, 서버 데이터 폴더의 `dialogues` 디렉터리에서 YAML을 편집한다. portrait는 리소스 ID이며 기본 예시 중 `elena-neutral`가 있다. 파일명·리소스 ID 대응은 실제 클라이언트 소스에서 확인하고, 서버에 일러스트를 넣기만 하면 렌더링된다고 안내하지 않는다.

NPC 연결 형식은 `citizens:<번호>` 또는 `tag:<태그>`다. 조건에는 permission, flag, not-flag, story, quest active/completed와 custom이 있다. 동작에는 flag, story, quest, event, command와 custom이 있다. 콘솔 명령은 슬래시 없이 설정하고 `{player}`, `{uuid}` 치환을 지원한다.

현재 정의 제한은 장면 1~32개, 선택지 0~6개, 장면 text 최대 1600자, 조건·동작 최대 12개씩, DB 저장 대화 문서 최대 약 26KB다. 연결 대상과 다음 장면이 존재하는지도 검증한다. 거대한 메인 이야기는 대화를 나누어 구성하는 편이 현재 한도에 맞다.

```text
/대화 <ID>
/대화관리
/대화관리 preview <ID>
/대화관리 reload <ID>
/대화관리 pull <ID>
/대화관리 audit <UUID>
/대화관리 resolve <UUID> <토큰>
/메인퀘스트
```

reload는 YAML을 카탈로그에 반영하고 pull은 카탈로그와 로컬 파일을 맞추는 용도로 소스를 확인해 사용한다. 편집 리비전 충돌 시 다시 읽고 변경을 대조한다. 인게임 관리자 편집과 외부 YAML 편집을 모두 유지한다.

대화 상태는 codex_story_state의 revision으로 충돌을 확인한다. 외부 명령·의뢰 수락 등의 동작은 codex_dialogue_effect의 pending/done 기록을 사용한다. pending 상태가 남으면 재실행을 강제로 밀어붙이지 않고 관리자가 audit 후 resolve한다. 임의 외부 동작의 중복 방지가 완전한 분산 트랜잭션이라고 주장하지 않는다.

메인 퀘스트는 일반 의뢰와 구분하되 대화에서 의뢰 수락과 이벤트를 연결할 수 있고, 별도 이야기 기록 창이 있다. 최초 기획의 게시판 “[메인 퀘스트]” 표기를 현재 모든 의뢰 UI에 구현한 것으로 단정하지 않는다.

## 칭호와 표시명

칭호는 접두사와 접미사를 따로 보유 목록에서 선택하는 시스템이다. TitleStore는 보유와 선택을 저장하고, title revision으로 오래된 변경 요청을 확인한다. `titles.yml`에서 ID, side, 이름, 색과 관련 설정을 관리한다.

닉네임 원본은 `%user_nickname%` 설정이다. TitleExpansion의 PAPI identifier는 `chacademiatitle`다.

| 플레이스홀더 | 의미 |
| --- | --- |
| %chacademiatitle_prefix% | 선택 접두사 |
| %chacademiatitle_suffix% | 선택 접미사 |
| %chacademiatitle_nickname% | 표시 닉네임 |
| %chacademiatitle_full% | 접두사·닉네임·접미사 조합 |
| %chacademiatitle_full_colored% | 색을 포함한 조합 |
| %chacademiatitle_prefix_id% | 선택 접두사 ID |
| %chacademiatitle_suffix_id% | 선택 접미사 ID |

기존 학교 플레이스홀더와 충돌하지 않게 별도 identifier를 쓰는 구현이다. /칭호, /칭호관리와 발견 보상 연결이 있으며, 실제 설치 후 PlaceholderAPI 등록과 다른 서버에서 선택 유지·보유 회수를 시험한다.

## 공개 서버 API와 확장 지점

MagicCodexBridge의 신뢰된 서버 스레드 API를 이용해 후속 NPC·마법·아이템 연결을 확장할 수 있다. 클라이언트가 임의 이벤트 ID로 보상을 얻는 진척 API를 노출하지 않는다.

```java
grantReconfiguration(Player player, int mode, int count);
openReconfiguration(Player player, int mode);
grantTitle(UUID player, String id, Consumer<Boolean> result);
revokeTitle(UUID player, String id, Consumer<Boolean> result);
registerDialogueAction(String id, BiConsumer<Player, String> action);
registerDialogueCondition(String id, BiPredicate<Player, String> condition);
questEvent(Player player, String eventId, int amount);
castTaming(Player player);
```

재구성 mode는 0 초기화, 1 복구, 2 성향 변경이다. 칭호 지급·회수 콜백은 DB 커밋 뒤 결과를 전달하는 경로다. 마법 시전에는 castMagic과 마력·가속 조회 API가 존재한다. 실제 함수 반환 타입과 접근 조건은 변경하려는 버전의 Java 소스로 확인한다.

새 목표 조건이 필요하면 EVENT와 서버 API를 활용하고, custom 대화 조건·동작을 등록해 분기할 수 있다. 함수를 추가할 때 메인 스레드 플레이어 조작과 비동기 DB 처리의 경계를 지킨다.

## 통신 프로토콜과 호환성

공유 소스는 `magic-codex-protocol\src\main\java\school\magiccodex\protocol`다. 다음은 중심 통신의 확인된 채널이다. 일반적인 요청/응답 대칭이 아닌 발견·이로치는 별도 표기를 유지한다.

| 기능 | 요청 채널 | 응답 채널 |
| --- | --- | --- |
| 권한 | magiccodex:permissions | magiccodex:permission_state |
| 잔액 | magiccodex:wallet_request | magiccodex:wallet_response |
| 마나 | magiccodex:mana_request | magiccodex:mana_response |
| 감정 | magiccodex:core_request | magiccodex:core_response |
| 강화 | magiccodex:enhance_request | magiccodex:enhance_response |
| 재구성 | magiccodex:reconfig_request | magiccodex:reconfig_response |
| 장비 | magiccodex:equipment_request | magiccodex:equipment_response |
| 교화 | magiccodex:taming_request | magiccodex:taming_state |
| 의뢰 | magiccodex:quest_request | magiccodex:quest_response |
| 의뢰 관리자 | magiccodex:quest_admin_request | magiccodex:quest_admin_response |
| 대화 | magiccodex:dialogue_request | magiccodex:dialogue_response |
| 대화 관리자 | magiccodex:dialogue_admin_request | magiccodex:dialogue_admin_response |
| 칭호 | magiccodex:title_request | magiccodex:title_response |
| 발견 | magiccodex:discovery_ack | magiccodex:discovery |
| 이로치 | 단일 송신·상태 채널 | magiccodex:shiny |

추가로 Ascension, Stats, Pet, School, Season, Social, Temperature 프로토콜이 있다. 패킷 필드를 바꾸면 해당 서버와 클라이언트가 동시에 같은 정의를 사용하도록 맞춘다. Protocol 클래스가 여러 모듈 산출물에 포함되므로 한쪽 빌드만 오래된 상태가 되지 않게 확인한다.

권한 구독, 지갑 구독과 다른 UI 세션은 열려 있는 사용자 위주로 갱신하는 방향이다. 권한 조회 기본 보조 주기는 200틱, 조회 상한은 초당 2048, 세션 타임아웃 45초다. 지갑은 기본 500ms 갱신, 초당 최대 128회 조회를 설정한다. 값이 바뀌지 않았는데 매 프레임 전체 데이터를 재전송하는 방식으로 되돌리지 않는다.

## 기존 테스트와 추가 검증

| 모듈 | 기존 테스트 보고서 | 실패·오류·건너뜀 |
| --- | --- | --- |
| magic-codex-paper | 121개, 23 suites | 0 / 0 / 0 |
| magic-discovery-paper | 8개, 1 suite | 0 / 0 / 0 |
| tornado-event-paper | 2개, 1 suite | 0 / 0 / 0 |
| creature-spawns-paper | JUnit 보고서 없음 | 테스트 성공으로 처리하지 않음 |
| 합계 | 131개 | 0 / 0 / 0 |

이는 원격 build/test-results/test의 기존 XML을 읽은 결과다. 이번 문서 작업에서 테스트를 다시 실행한 것은 아니다. DatabaseSmoke·QuestDatabaseSmoke 등의 소스 파일 존재가 실제 MariaDB 시험 완료를 뜻하지 않는다.

후속 검증은 변경한 기능에 맞춰 좁혀서 수행한다. 배포 후에는 외부 플러그인 로드·API 버전, 정품 로그인, 학교↔야생 이동, 데이터 유지, UI 프로토콜, 모델·사운드 등록을 별도로 시험한다. 해당 테스트가 통과하기 전에는 “파일 준비”, “설치”, “로드”, “인게임 검증”을 하나로 합쳐 완료 보고하지 않는다.

## 배포와 롤백 절차

후속 배포 요청을 받으면 호스팅 안에서 다음 순서로 진행한다. 지금 문서를 읽었다는 이유로 자동 배포하지 않는다.

1. 실행 서버·소스·설정·리소스 버전과 변경 범위를 확인한다.
2. 호스팅에 기존 JAR·설정·데이터의 복구 가능한 사본을 만든다. DB와 월드 사본을 노트북으로 자동 전송하지 않는다.
3. 외부 의존 플러그인·데이터팩·모델·MCPets·MythicMobs 설정과 대상 서버별 필요 기능을 정한다.
4. 통합 빌드와 필요한 검증을 수행하고 산출물 해시를 기록한다.
5. 플레이어와 데이터 저장을 고려해 서버를 정상 종료한다.
6. school·wild 중 실제로 필요한 곳에 JAR와 설정을 배치한다. 프록시에 Paper 플러그인을 복사하지 않는다.
7. 두 백엔드의 DB 연결과 quests server-id를 맞추고, 기존 데이터 이전이 필요한지 확인한다.
8. 시작 후 플러그인 로드, 오류와 상태 응답을 확인한다.
9. 실제 클라이언트에서 해당 기능과 서버 이동을 검증한다.
10. 적용 위치·버전·해시·검증 범위를 보고한다.

실패 시 서버를 정상 종료하고 이전 JAR·설정으로 복구한다. 새 스키마나 데이터 변경이 이전 버전과 호환되는지 확인하지 않은 채 DB를 덮어쓰거나 데이터부터 삭제하지 않는다. 월드 생성 데이터팩 변경은 이미 생성된 청크와 신규 청크에 서로 다르게 영향을 줄 수 있으므로 원본 월드와 적용 시점을 기록한다.

모든 서버에 모든 기능을 무조건 켜는 방식은 피한다. 학교와 야생에서 공유 데이터·통신이 필요한 부분, 야생 전용 스폰·회오리 기능, NPC 배치 등을 역할별로 확인하되 실제 설정으로 선택 가능한지도 소스에서 검증한다.

## 남은 작업과 우선순위

다음은 아직 남아 있거나 실제 검증이 필요한 항목이다. 체크되지 않은 항목을 기획만으로 완료 처리하지 않는다.

- [ ] dot 클라우드에서 호스팅 직접 SSH 접속과 파일 수정 권한 확인.
- [ ] Codex 사용 금지 원칙과 실행 위치 기록 유지.
- [ ] 기존 학교·야생 월드와 필요한 최소 설정·리소스의 이전 범위 결정.
- [ ] Terralith와 대형 chacademia 구조물 데이터팩 원본 확보·적용·생성 검증.
- [ ] 외부 플러그인 및 자체 플러그인의 서버별 설치·로드 검증.
- [ ] 호스팅 MariaDB 설치·제한된 사용자·공용 DB 설정.
- [ ] 자체 SQLite·PDC·기존 데이터의 필요한 부분만 마이그레이션.
- [ ] LuckPerms·경제·MCPets의 자체 DB 및 공용 권한 연동.
- [ ] 일반 인벤토리와 플레이어 상태 동기화 수단 적용.
- [ ] 정품 로그인과 학교↔야생 실제 이동 시험.
- [ ] NPC·의뢰 편집과 수락·진척·보상·대화 분기 시험.
- [ ] 칭호 선택·기숙사 점수·발견·기증의 서버 간 유지 시험.
- [ ] 마법 목록·발견 조건·마나 설정·실행 ID 대조와 폐기 시연본 정리.
- [ ] 교화 UI 크기·착용 중 표시·가까운 대상 표시 요구 확인.
- [ ] 교화·이로치·별 등급·커스텀 몹 모델·애니메이션 검증.
- [ ] 실제 MythicMobs RandomSpawns와 138개 서식지 설정 대응 확인.
- [ ] 10개 희귀 구조물 조건과 26개 계절 조건의 실제 시험.
- [ ] 학교·야생의 계절 시계 기준 통일 여부 결정.
- [ ] 감정·강화·재구성 소모·확률·사운드·효과와 아이템 연결.
- [ ] 운영체제 재부팅 후 예약 작업 자동 시작 확인.
- [ ] 백업·복구와 장애 시 보상·플레이어 lease 처리 시험.

일반적인 추천 순서는 환경 연결 확인 → 서버 원본과 의존 리소스 확보 → DB·동기화 기반 → 작은 기능부터 통합 검증이다. 사용자가 특정 기능을 우선 요청하면 해당 범위의 선행 조건을 확인하고 진행한다. 던전 서버, 음성 마법 인식, HTML WebView 전환과 Distant Horizons 추가 조정은 이 문서에서 완료된 시스템으로 분류하지 않는다.

## 이번 점검의 근거와 빌드 지문

원격에서 읽은 핵심 근거는 AGENTS.md, network/OPERATIONS.txt, network.json, 서버의 안전한 설정 항목, plugin.yml, Gradle 설정, 시스템 Java 소스와 기본 리소스, 기존 테스트 XML, 실행 플러그인 목록, world/datapacks 목록, Control Status와 Check-Network 응답이다.

노트북의 서버 원본 전체, 월드·모델·백업이나 클라이언트 프로젝트를 읽어서 다시 업로드하는 방식은 사용하지 않았다. 아래 값은 이번 점검 당시 artifacts/server의 SHA256이며 이후 빌드하면 달라질 수 있다.

| 산출물 | SHA256 |
| --- | --- |
| magic-codex-bridge-0.29.0.jar | 8CA8B1622A0D400843625E051727FDEA923961B2CF1EFEB51148758B9F450B22 |
| magic-discovery-0.2.0.jar | ED829612713168F9B6BCC97D3D786E76B5195FCAFE7CDC468FB89C44FED0CD55 |
| chacademia-wildlife-0.3.0.jar | 4A717A8886F2789E8A72CB84553F5C9782B0CB7720B04FA30CE54DFF4134A855 |
| chacademia-tornado-0.2.0.jar | 8F7FE1FA59B13A428ED0E5A71FD24146534D3051C5D32770EAFB1A515D99D405 |

파일 해시가 있다는 것은 서버에 설치되었다는 증거가 아니다. 설치 폴더의 파일과 실제 로드 버전을 확인해야 한다.

## dot에게 바로 전달할 서버 작업 지시문

```text
환경 인수인계와 이 서버 인수인계를 함께 읽어줘.

기본 수행자는 너 자신이야. 네 클라우드에서 직접 코딩하고, 클라우드에서 시작한 SSH로 호스팅의 소스와 서버 파일을 읽고 수정해. 다운로드·빌드·테스트·DB·서버 실행은 호스팅에서 처리해. 노트북으로 파일과 작업을 우회하지 마.

내가 명시적으로 허가하기 전에는 Codex 작업을 새로 만들거나 재개하거나 기존 작업에 지시하거나 호스팅 Codex CLI를 실행하지 마. magic-codex라는 이름의 Minecraft 소스 모듈을 직접 수정하는 것은 가능하지만 AI Codex 위임은 별도 허가가 필요해.

현재 호스팅에는 학교·야생·Velocity 기반만 실행되고, 실제 설치 플러그인은 0개야. 자체 JAR 4개는 준비만 된 상태이고 기존 월드·리소스·DB 이전은 아직 남아 있어. 도감 목록 300개를 실제 마법 300개 완성으로 해석하지 마.

먼저 C:\Chacademi\AGENTS.md와 network\OPERATIONS.txt, 이 문서의 현재 상태를 다시 확인해. 그 뒤 내가 요청한 기능과 선행 조건만 작업해. 읽었다는 이유만으로 서버 재시작·배포·DB 이전이나 폐기한 클라이언트 기능 재활성화를 진행하지 마.

결과는 소스 수정 완료, 빌드 완료, 호스팅 설치 완료, 실제 로드 완료, 인게임 검증 완료를 나눠 알려줘. 막힌 부분과 아직 하지 않은 시험도 짧게 보고해줘.
```


## 2026-10-02 Git 및 작업 자료 이전

비공개 저장소: https://github.com/zzunwoooo/chacademi-server

호스팅 Git 작업 폴더는 C:\Chacademi\build-workspace, 기본 브랜치는 main입니다. 서버 소스와 편집기/기획 자료를 같은 저장소에서 관리합니다. 자료 안내는 reference/MATERIALS.md, 자료 첫 화면은 reference/output/index.html입니다. 외부 빌드 라이브러리는 build-support/dependencies.json을 확인하세요.

마법 분류/획득 조건 편집기는 과거 238/200종 기준 목록을 보존합니다. 최신 마법 기획/설정 스냅샷은 reference/output/spell-finalization-300-v1에 있습니다. 원본 대형 마법 아이콘과 전체 게임 리소스는 아직 이전하지 않았습니다.

Git 등록은 실제 학교/야생 서버 설치나 클라이언트 갱신을 뜻하지 않습니다. dot의 클라우드 SSH 접속은 별도 확인이 필요합니다. dot은 계속 직접 코딩하며 사용자 허가 없이 Codex 작업을 실행/전달하지 않습니다. 반드시 필요한 최초 파일 전송에는 속도를 인위적으로 제한하지 않습니다.
