# 차카데미 스토리 모드·플러그인 작업 기록 (2026-10-10)

- **브랜치**: `claude/chacademy-story-20261010` — `claude/first-nickname-20261010` 위에 쌓음 (이 브랜치를 받으면 최초 닉네임 작업도 같이 들어옴)
- **작성**: Claude (사용자 요청)
- **위치**: 저장소 루트의 `chacademy-story/` (MagicCodex Gradle 빌드와 **별개**. 루트 `settings.gradle` 에 넣지 않음)
- **빌드 상태**: 준우 PC 에서 `chacademy-story/build-auto.bat` 으로 빌드 성공 (모드 jar, 플러그인 jar). 모드는 준우 런처 `mods` 에 설치함.
  **서버 플러그인 설치, 게임 안 테스트는 아직.**

## 1. 무엇인가

메인 스토리용 Fabric 1.21.4 클라 모드(`chaca_story`) + Paper 1.21.4 플러그인(`ChacademyStory`) + HTML 편집기 2개.

| 부분 | 내용 |
| --- | --- |
| 컷신 | 레터박스, 줌, 반복 프레임, 잉크 전환, 자막(KoreanCNM), 타자기 소리, MASTER 음량 배경음, 2배속, 두 번째부터 SPACE 2초 건너뛰기. `/cutscene play <플레이어> <id>` |
| 스토리 대화 | AI 아님, 정해진 대사. 비주얼노벨 화면, 표정 일러스트, 선택지, 호감도 갈림길. `/storydialogue <플레이어> <id>` (콘솔 가능) |
| 편집기 | `editor/cutscene-editor.html`, `editor/dialogue-editor.html` (zip 으로 내보내서 클라 config 에 풀기) |
| 형식 | `FORMAT.md` |

대사·그림은 **클라** `config/chaca_dialogue/<id>/`, 실행할 명령어·호감도 값은 **서버** `plugins/ChacademyStory/dialogues/<id>.yml`.

## 2. 이번에 들어간 것 (사용자 요구)

1. **한글 닉네임**: 대사의 `{player}` = MagicCodex 한글 닉네임. `{account}` = 마인크래프트 닉네임. config `dialogue-placeholders` 의 PlaceholderAPI 값도 대사에 사용 가능. 서버 명령어에서는 `{player}` = 마인크래프트 닉네임, `{nickname}` = 한글 닉네임.
2. **"나"의 일러스트**: 화자 `me` 가 말하면 MagicCodex 모드가 받아 둔 ChacaPortrait 내 일러스트를 MagicCodex "내 차례"와 같은 배치로 그림.
3. **호감도 올리기·내리기**: 선택지(여러 명), 장면 들어올 때, 결말 장면에서 끝날 때, 대화가 끝나면 항상. **실제 점수는 서버 파일 `affinity:` 값만 적용** (클라 yml 을 고쳐도 안 바뀜).
4. **닫을 수 없음**: 스토리 대화는 끝날 때까지 ESC 무시, 다른 화면이 덮어도 다시 열림.
5. **이어서 보기**: 서버가 연 대화는 장면·대사 번호를 `plugins/ChacademyStory/progress.yml` 에 저장. 접속 끊김·서버 재시작 후 다시 접속하면 3초 뒤 그 대사부터. 이미 실행된 이벤트(명령·호감도)는 다시 안 함.

## 3. 주의사항 (MagicCodex 쪽과 맞물리는 곳)

이 프로젝트는 MagicCodex 코드를 **고치지 않고 읽기만** 한다. 아래 이름·시그니처를 바꾸면 스토리 쪽이 조용히 기능을 끈다 (오류로 멈추지는 않음). 바꿀 때는 인계 문서에 적어 주세요.

| 쪽 | 쓰는 것 | 방식 |
| --- | --- | --- |
| 클라 | `school.magiccodex.client.NicknameClient.display(String)` | 리플렉션 (public static) |
| 클라 | `PortraitClient.ready()`, `PortraitClient.drawTurn(DrawContext)` | 리플렉션 + setAccessible (패키지 전용 static). 1600x900 좌표계라고 가정 |
| 서버 | ServicesManager 의 `school.magiccodex.paper.NpcSocialFacade` — `apiVersion()==1`, `playerName(Player)`, `affinity(UUID,String)`, `addAffinity(UUID,String,String,int)` | 리플렉션 (ChacaNPC `MagicCodexLink` 와 같은 방식) |
| 서버 | 호감도 출처 이름 `dialogue` | MagicCodex `affinity.yml` 의 `daily-caps.dialogue` (기본 0 = 한도 없음) |
| 서버 | PlaceholderAPI `%user_nickname%` | MagicCodexBridge 가 없을 때 닉네임 대체 |

- 최초 닉네임 작업의 `first-nickname.on-complete` 기본값이 `storydialogue {player} ch1-2` 다. 이 명령은 이 플러그인 것이고, 대화 `ch1-2` 는 **아직 만들지 않았다** (클라 config 에 넣어야 함).
- 예전 ChacaNPC 의 `ChacaNpcApi.setAffinityProvider` 연결은 지웠다 (최신 ChacaNPC 에 없음, 호감도 주인은 MagicCodexBridge).
- 채널: `chacademy:cutscene_play|stop|done`, `chacademy:dialogue_open|stop|event|done|progress`. MagicCodex 채널과 겹치지 않음.
- 폰트(KoreanCNM)는 라이선스 확인 전이라 저장소에 없음 → `chacademy-story/FONTS.md`.
- 빌드는 각 폴더의 Gradle wrapper (`mod/`, `plugin/`). Windows 는 `build-auto.bat` 이 둘 다 빌드해서 `out/` 에 jar 를 모음.

## 4. 확인할 것

- [ ] 서버에 `chacademy-story-plugin-0.1.0.jar` 설치 (예전 ChacademyCutscene 플러그인은 제거)
- [ ] `/storydialogue <나> ch1_wakeup` → `{player}` 가 한글 닉네임, "나" 대사에 내 일러스트
- [ ] 선택지 호감도 → `/affinity <나> teacher` 로 값 확인 (MagicCodex 점수)
- [ ] 대화 도중 접속 종료 → 재접속 시 같은 대사부터, 이미 고른 선택지 명령이 다시 실행되지 않음
- [ ] 대화 도중 서버 재시작 → 같은 결과


## 5. Codex 통합·검증 (2026-10-10, 현재 결과)

- 기준: 947ec46a4f5fccc2967c48b90c8f1e7ae5d27b73.
- Claude 원격: a1938b526f08e44e055ca9682ec0938f9138d3d5.
- no-ff 병합: 3779d577db1b2d7b61a7223e38f9d35ae386ecdc.
- 호스팅 staging: C:\Chacademi\staging\chacademy-story-20261010-task5\source.
- 충돌은 CLAUDE_INDEX 한 파일. 최초닉네임 받음 상태를 보존하고 Story 행을 추가했다. 최초닉네임 안전수정 2ea204c와 운영주의 8절은 그대로 유지했다.
- 하위 AGENTS.md는 없었으며 루트 호스팅 우선 지침을 적용했다. Story는 루트 Gradle에 추가하지 않았다.

### 실제 결함과 최소 수정

1. 구형 fallback이 클라 npc/add 값을 실제 점수에 반영하던 문제: 제거. 서버 events/affinity.events에 선언한 유효 이벤트만 처리한다.
2. 실행 이벤트를 최대 5초 뒤 기록하던 문제: fired를 먼저 원자적 저장하고 성공 후 효과 실행. 저장 실패 시 claim을 되돌리고 실행하지 않는다. 시작/완료/관리자 중단도 영속화 성공을 확인한다.
3. 누락된 클라 대화 파일을 정상 완료로 보고하던 문제: 완료를 보내지 않고 서버 pending 유지. 서버는 현재 보고된 장면과 일치하는 유효 완료만 인정한다.
4. 이어보기 시 장면 진입 redirect/호감도/이벤트를 재실행하던 문제: 저장 지점 직접 복원.
5. 폰트가 없는데 JSON에서 해당 TTF만 참조하던 문제: 기본 글꼴 reference를 제공하고 실제 로컬 TTF가 있을 때만 빌드에서 선택적으로 추가한다.
6. 서버 명령 executor/자동완성에 명시적인 admin 권한 검사, 비정상 패킷 trailing bytes 거절, 오래된 접속으로 비동기 열기 전달 방지.

### 연동 확인

- NpcSocialFacade API 1의 실제 기존 Bridge JAR 공개 메서드와 새 link의 정확한 시그니처가 일치한다. nickname, affinity, addAffinity(source=dialogue) 회귀테스트 통과.
- 새 Story JAR에는 과거 Providers$AffinityProvider 참조가 없다. 이전 클래스 누락 경고의 원인 참조가 제거된 것은 확인했으나 운영 서버의 새 JAR 로딩은 아직 실행하지 않았다.
- 클라 public NicknameClient.display와 package-private PortraitClient.ready/drawTurn reflection 테스트 통과. 기존 배포 UI의 drawTurn 인자는 intermediary net.minecraft.class_332이며 Story remap과 대응한다.
- me 그림은 1600x900 좌표 변환 뒤 기존 텍스처 drawTurn만 호출한다. 새 생성·유료 API 호출은 하지 않는다. 실제 화면의 크기/가림/렌더 품질은 실게임 미검증이다.
- 기존 개인 Story JAR에 KoreanCNM 3종 존재 및 로컬 원본 존재만 확인. 새 공개 소스/산출물에 TTF/WOFF를 복사하지 않았다.
- ch1-2는 school/wild 서버 정의와 개인 클라 config 모두 없음(파일 존재 여부 확인). 샘플 생성/복사로 대체하지 않았다.

### 테스트와 산출물

- 호스팅 Java 21.0.10, 각 프로젝트 Gradle wrapper 8.14.3.
- plugin: test build 성공, JUnit 13개 (실패/오류/스킵 0).
- mod: test remapJar 성공, JUnit 5개 (실패/오류/스킵 0).
- 실제 디스크 원자적 교체/저장 실패, 재로드 중복 방지, 서버 API1, 관리자 권한 선언, 패킷 유효성 및 양쪽 호환, 한글닉네임, 실제 이어보기 화면 생성, 클라 reflection을 확인했다.
- 초기 빌드와 추가 회귀테스트 후 최종 빌드 모두 성공. deprecated API/Gradle 및 SnakeYAML semver 경고는 있으나 실패 없음.
- 기존 MagicCodex/Portrait 및 최초닉네임 문서는 기준과 동일함을 diff로 확인했다. 이번에는 변경 없는 루트의 기존 540개 테스트를 재실행하지 않았다.
- final-ready: C:\Chacademi\staging\chacademy-story-20261010-task5\final-ready
- 정확한 파일 해시/크기/소스 SHA는 해당 폴더 manifest.json. 테스트 가짜 클래스와 KoreanCNM이 배포 JAR에 없음을 확인했다.
- HTML 편집기 코드는 수정하지 않았으며 브라우저 상호작용 테스트는 미실행.

### 별도 배포 승인 후 교체 대상 (이번에는 미배포)

- school: C:\Chacademi\network\school\plugins\chacademy-story-plugin-0.1.0.jar
- wild: C:\Chacademi\network\wild\plugins\chacademy-story-plugin-0.1.0.jar
- 개인 클라: C:\Users\matil\AppData\Roaming\.zzunwoo\instances\launcher_v2-1.21.4\mods\chacademy-story-0.1.0.jar
- 기존 Bridge/UI 쌍은 변경하지 않는다. 예전 ChacademyCutscene 플러그인/chaca_cutscene 모드가 실제 있으면 중복 여부를 별도로 확인한다.
- 이 두 Story 산출물은 기본 글꼴 빌드이며 기존 개인 JAR의 KoreanCNM 외형과 다를 수 있다.
- ch1-2 작성 전 최초닉네임→해당 후속대화 종단 테스트 불가. ch1_wakeup 예시도 이번에 운영 config로 설치하지 않았다.
- 서버/런처 교체, 시작/종료/재시작, 운영 DB/키/실제 API 호출은 하지 않았다. Control.ps1 Stop 사용 금지(정확한 사고 경위는 최초닉네임 인계 8절).

### 남은 한계

- fired 선저장은 중복 방지를 위한 at-most-once 방식이다. 기록과 외부 명령/비동기 호감도 적용 사이의 프로세스 중단·효과 실패에 대한 완전한 트랜잭션/자동 재실행은 제공하지 않는다.
- 서버에는 전체 클라 대화 그래프가 없으므로 허용된 이벤트 중 실제 선택 조건/순서까지 검증하지는 않는다. 같은 대화 id를 즉시 새 세션으로 열 때 이전 세션 패킷을 구분하는 nonce도 없다.
- 진행 파일은 서버별이며 school/wild 간 공유되지 않는다. 미저장 대사 위치는 강제 종료 시 되돌아갈 수 있다.
- 기존 로컬 호감도 파일과 MagicCodex DB 사이의 데이터 이전은 하지 않았다.


## 6. 기존 글꼴 개인 배포 준비 (2026-10-10 후속 승인)

- 사용자 승인: 기존 글꼴 사용, school/wild Story 플러그인 및 개인 Story 모드 교체·재시작.
- 이전 5절의 기본 글꼴 빌드와 별도인 final-ready-personal-fonts 산출물을 사용한다.
- 설치된 개인 Story JAR의 subtitle_l/m/b JSON은 cnm_l/m/b.ttf를 primary TTF로 지정했다. 이 세 파일만 추출·호스팅 전송했으며 원본과 빌드 JAR의 해시 일치를 확인했다.
- private-fonts는 Git 저장소 밖에 있다. 공개 변경은 빌드의 storyFontDir 입력 처리와 문서뿐이며 폰트 파일은 커밋하지 않는다.
- Java21 plugin test build 및 private-font mod test remapJar 성공. 기존 18개 회귀테스트 결과 모두 통과. 이후 공개 processResources 전환에서 사설 TTF가 남지 않는 것도 검증했다.
- TTF primary provider 설정: size 11.0, oversample 8.0, shift [0.0,1.0]. minecraft:default는 후순위 폴백이다.
- 폰트 SHA256:
  - light: dd228889895d946c2d93608bd8174216b00ee12b834929cbc3975d3ae09a2d96
  - medium: 58a865ab94d245fdf5624fbb2906502e2ae7a5d055bae51c530f9b223318b2a8
  - bold: b3497c9e851fc18fd8f342980d8e5ebaf8977aae244c8a8907f147dba6c229be
- 배포 준비 JAR:
  - plugin: 88f578f657c858ddf07a63b98395be4da8595614209741c9dcb3c3d24a92f6f9
  - private-font mod: 60ac2c7e3c05f5a60f29482874b52c74eb58febe3c284ef9ec361cd17bfbce17
- 경로: C:\Chacademi\staging\chacademy-story-20261010-task5\final-ready-personal-fonts.
- 실제 배포 결과는 다음 기록에서 별도로 확인한다. ch1-2 미존재 및 실게임 미검증 상태는 유지한다.


## 7. 기존 글꼴 배포 완료 (2026-10-10)

- 빌드 소스 커밋: 47f804aaea6e80560d8b3abd4e332dd10d156997. 이 절 기록 커밋은 문서만 변경한다.
- school/wild의 chacademy-story-plugin-0.1.0.jar를 6절 plugin SHA로 교체했다.
- 개인 .zzunwoo\instances\launcher_v2-1.21.4\mods\chacademy-story-0.1.0.jar를 6절 private-font mod SHA로 교체했다. 교체 직전 읽기 가능한 Java 명령줄 검사에서 게임 프로세스가 없음을 확인했다.
- 개인 모드의 fabric.mod.json ID 검사: chaca_story 1개, 예전 chaca_cutscene 없음. 각 서버 Story JAR 1개. 배치 해시 일치.
- Bridge/UI는 그대로 유지했다: Bridge 52e350de404cc19323ad0735238d59b098fc3f4c62ede2e1b032e852212ddf21, UI fee99eed783e2959be42d3e8cdcc4b8a1699d3d8674358487fc0c53726461fcb.
- 개별 정상 종료 경로를 먼저 읽어 확인한 뒤 Stop-Network.ps1 -Server school 및 -Server wild만 사용했다. 두 대상 alive=false를 확인한 후 교체하고 Control.ps1 -Action Start -Server school/wild로 시작했다. Control.ps1 -Action Stop은 사용하지 않았다.
- school PID 5736, wild PID 10372. 각각 Done 48.907초 / 47.227초와 Story 활성화를 확인했다.
- 양쪽 새 부팅 로그에서 ChacademyStory의 'MagicCodexBridge 연결 완료'를 확인했다. 이전 AffinityProvider 클래스 누락 경고 및 Story 활성화 오류는 발견하지 못했다.
- Velocity는 배포 전후 PID 3740, alive=true를 유지했다. Velocity/DB에 시작·종료·변경 명령을 보내지 않았다.
- 운영 설정, 기존 대화/컷신 데이터, 다른 모드/리소스를 수정하지 않았다. ch1-2는 생성하지 않았다.
- 실제 게임의 글꼴 렌더·대화·초상화 동작은 미검증이며 유료 API 호출을 실행하지 않았다.
- 개인 폰트 산출물과 배포 영수증은 final-ready-personal-fonts에만 있고 공개 Git에 폰트/JAR를 추가하지 않았다.
