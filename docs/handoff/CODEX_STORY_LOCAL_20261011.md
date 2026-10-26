# 클라이언트 단일 스토리 + 한정 보상 연결 배포 완료 — 2026-10-11

기준 SHA: 210d09868f863e6f61fc755f08169afd546103be. 브랜치: codex/story-usability-20261011-task5. 최신 사용자 승인으로 school/wild와 개인 런처까지 적용했다. 공유 소스는 codex/hires-item-icons-20261006에 반영한다. 이번 Git 동기화는 운영/런처 재배포 및 재시작을 포함하지 않는다.

## 최종 구현

- 기본 /story dialogue는 클라이언트 dialogue.yml 및 계정별 로컬 완료/이벤트/호감도 기록으로 동작한다. 새 경로는 서버 Story 파일·완료 ledger를 검사하지 않는다. 기존 서버 파일·기록은 삭제하지 않았다.
- 두 고정 보상만 본인에게 서버 API로 적용한다. ch1-2.e_s11 → NpcSocialFacade 경유 teacher_ellna -1. ch1-3.end → MagicDiscovery.signal(접속자 UUID, jump.sprinting, 30). 대상 UUID·수치·명령 문자열을 클라이언트에서 받지 않는다. OP나 임의 콘솔 명령 통로 없음. 일반 플레이어가 별도 관리자 권한 없이 이 두 효과를 요청할 수 있다.
- 중복 방지는 로컬 완료/이벤트 기록에서 수행한다. 서버는 이 한정 클라이언트 요청을 신뢰하며 스토리 ledger를 추가하지 않았다. 일반 명령은 계속 플레이어 권한으로 실행된다. 발견 API의 기존 게임모드·선행조건 등은 유지된다.
- 첫 닉네임은 ch1-2를 유지하고 실제 파일 로드·화면 수락 ACK 후 기존 닉네임 first_done을 확정한다. 실패/timeout/접속 종료는 재시도 가능하며 중복 요청은 합친다.
- 제목 패널 제거·플레이어 초상화 하단 앵커·고유 화면 세션 유지. 다른 UI/펫/이로치/Portrait 생성·분석 코드는 덮어쓰지 않았다. 개인 폰트 3개 바이트 일치 확인.

## 콘텐츠 적용

개인 런처의 해당 인스턴스 config/chaca_dialogue/에 적용했다. 개인 설정 파일과 대사·이미지는 공개 Git에 포함하지 않는다.

기존 대사·장면·그림 경로를 그대로 보존하고 dialogue.yml 끝에 commands 절만 추가했다.
- ch1-1: endings.wake → 최초닉네임설정
- ch1-2: events.e_s11 → chaca_reward ch1-2.e_s11 (원래 s11의 teacher_ellna -1·e_s11 확인)
- ch1-3: end → chaca_reward ch1-3.end

Story/UI 모드 2개 교체 완료. 교체 직전 게임/Java 프로세스 없음을 재확인했고 강제 종료하지 않았다. 모든 설치 파일 해시 일치. 콘텐츠 3개를 실제 빌드된 클라이언트 파서로 검증했다(장면 수 1/4/9).

## 서버 적용과 검증

Stop-Network.ps1 -Server school/wild로 각 서버만 정상 종료했다. Story/Bridge JAR을 각각 교체하고 Control.ps1 -Action Start -Server로 시작했다. 공개 완료 알림은 각 서버 ChacademyStory/config.yml에서 정확한 say 한 줄만 제거했다. 컷신 완료 이벤트는 유지한다.

당시 school/wild: Done 및 Story→MagicCodexBridge 연결 확인. school ChacaNPC API 연결도 확인. Velocity 프로세스 유지. DB 종료/재시작 명령 없음. 별도 DB 스키마/스토리 ledger 신설 없음. 새 유료 API 호출 없음.

호스팅 Java21 테스트 574개 통과: Story plugin 44, Story mod 33, Bridge 272, UI 225. 최종 JAR 해시와 콘텐츠 전후 해시는 비공개 배포 인계 묶음의 manifest.json에 보관한다. 원본 복구는 변경 파일만 보관하고 중복 서버 JAR은 해시별 한 번만 보관했다.

실제 플레이어로 새 대화를 끝까지 조작하거나 보상 수령을 발생시키는 실사용 테스트는 수행하지 않았다. 배포·기동·API 연결·콘텐츠 파싱·파일 해시 검증까지 완료한 상태다. 시작 로그의 서버 graph 관련 경고는 보존된 구형 서버 재생 설정의 경고이며 새 클라이언트 기본 경로의 필수 조건이 아니다.

## 사용법과 리로드

- 클라이언트 `/story dialogue <id>`: 같은 클라이언트 dialogue.yml의 commands를 읽는다. 별도 클라이언트 reload 명령은 없으며 새로 재생할 때마다 다시 읽는다. 재생 중인 내용은 유지되고, 종료 후 다시 열면 파일 수정이 반영된다.
- 이미 완료한 대화는 파일을 다시 읽어도 commands가 재실행되지 않는다. `/story resetseen`은 컷신 시청 기록용이며 대화 완료 기록 초기화가 아니다.
- 서버 `/storydialogue <player> <id> [replay]`와 openDialogue API도 로컬 재생 요청을 보낸다. 서버 `/cutscene reload`는 서버 설정·구형 정의용이며 클라이언트 파일 리로드가 아니다.
- 현재 기본 대화 경로는 서버 endings를 호출하지 않는다. 서버의 `dialogues/<id>/dialogues.yml`(복수형)은 구형 로더에서도 지원하지 않는 이름이다. 구형 로더는 `<id>/dialogue.yml`, `<id>/server_commands.yml`, `<id>.yml`을 사용한다. 구형 서버 흐름·종료 핸들러는 호환용으로 보존했으나 새 기본 경로와 혼동하지 말 것.
- `/최초닉네임설정 <온라인 계정명> 강제`: 관리자에게만 허용. 기존 닉네임은 보존하고 DB first_done을 0으로 되돌려 창을 연다. 저장 후 ch1-2 화면 수락 시 다시 완료한다. 로컬 대화/보상 기록은 초기화하지 않는다.

## 공개 소스 범위

MagicCodex UI(제목/초상화), Bridge(닉네임 ACK), Story mod/plugin(로컬 실행·기록·두 고정 보상), 회귀 테스트, 일반 샘플과 문서만 포함한다. ChacaNPC/ChacaPortrait/PortableVFX에는 이번 추가 변경이 없다. 개인 런처 설정, DB 설정·비밀, 개인 폰트, 생성 컷신 이미지, portrait reference, 상용 자산, JAR 및 별도 HuskSync 작업은 포함하지 않는다.

컷신 공개 완료 알림 제거는 운영 config의 해당 say 한 줄 삭제로 적용했다. 공개 기본 config는 원래 on-finish: {}이며, 혼동 방지를 위해 같은 공개 알림의 주석 예시도 제거한다. 컷신 완료 이벤트 및 후속 대화 연결 기능은 유지한다.
