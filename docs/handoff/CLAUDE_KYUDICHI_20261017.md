# 큐디치 (3:3 공중 PvP) — Claude 인계 2026-10-17

브랜치 `claude/kyudichi-20261017` (기반: `claude/party-ui-20261016` @ 8e9bbad → 파티 UI 브랜치를 먼저 또는 함께 통합).
커밋: d3a2242 경기장 플러그인 · 3a806bb 클라이언트 HUD · 595cd38 매칭 대기열 · 2085219 클라이언트 창/세팅/조준.
**빌드·배포 안 함.** Claude 환경에서는 Gradle/Minecraft jar를 받을 수 없어 Fabric 클라이언트는 컴파일하지 못했다 (Paper·Velocity 쪽은 javac 소스 대조로 오류 0, 순수 로직 테스트 통과). GPT가 호스팅에서 빌드하고 컴파일 오류가 나면 고쳐 달라.

기획 전체: 프로젝트 문서 `claude/quidditch-plan.md`. 이름은 **큐디치** (퀴디치 아님).

## 구성

| 모듈 | 무엇 | 비고 |
| --- | --- | --- |
| `kyudichi-paper` (새 플러그인 **ChacaKyudichi**, jar `chaca-kyudichi`) | 경기장·경기 엔진 (퀘이플/블러저 2/스니치, 성소 4종, 골·처치·스니치 점수, 골든골, 포기 투표, 재접속 60초, 관전, 연습, 레이팅·전적 DB), 관리자 명령 `/큐디치관리`, 플레이어 `/큐디치`, 프록시 링크(`Link`) | **경기장/PvP 전용 서버에만** 설치 |
| `magic-codex-protocol` | `KyudichiProtocol` (`chacademi:kyudichi`, 경기장↔클라), `KyudichiQueueProtocol` (`chacademi:kyudichi_queue` 프록시↔클라, `chacademi:kyudichi_link` 프록시↔경기장) | GOLDEN 테스트: magic-codex-fabric `KyudichiProtocolTest`, `KyudichiQueueProtocolTest` |
| `magic-codex-paper` (Bridge) | 새 이벤트 `MagicCastAttemptEvent`(시전 거부), `ArtifactUseAttemptEvent`, `MagicAimPointEvent`(공중 조준점) — `SpellRuntime.prepare`에서 AIM 경로가 아무것도 안 맞았을 때만 발생, 사거리 안 같은 월드 점만 받음 | 큐디치가 없으면 동작 변화 없음 |
| `magic-codex-fabric` (클라 모드) | 경기 HUD(점수판·피드·F키·공/성소/핑 표시·결과 카드), Alt 자석 핑+방향 메뉴, 대기열 카드(매칭 중/수락 확인), 대시보드(`/큐디치`), 준비 60초 마법 세팅 창, 경기용 6칸(비행 고정) 덮어쓰기, Alt/채팅창에서 우측 아래 마법 칸 끌어서 자리 바꾸기, 큐디치 한정 공중 조준(휠로 거리) | 아트는 모드에 내장 (`textures/gui/kyudichi/`) |
| `chacademi-party-velocity` | `/큐디치` 대기열·매칭(`KyudichiMatchmaker`, `KyudichiQueue`), 레이팅/전적 읽기(`KyudichiDb`, **MariaDB 읽기 전용**) | jar에 mariadb 드라이버와 protocol/database 소스 포함하도록 build.gradle 수정됨 |
| `chacademy-motion` (플러그인+모드) | `chacademy:broom_fx` 채널: 안정성 성소 = 안정성 가득, 가속 성소 = 기본 가속과 같은 효과 (쿨타임 안 씀/안 돎) | 모드·플러그인 같이 배포 |
| PortableVFX 팩 | ShrineHealth / ShrineMana / ShrineStability / ShrineHaste (idle 반복, pickup 1회) | 오너 PC `차카데미 vfx_Claude\Shrine*` zip |
| ModelEngine | `kyudichi_quaffle / _bludger / _snitch` (LittleRoom 팩 bbmodel, 블러저 재색) — 플러그인이 처음 켤 때 `plugins/ModelEngine/blueprints/kyudichi/`에 복사 | `/meg reload` + ItemsAdder 리소스팩 반영 필요. 없으면 아이템 모양으로 표시 |

## 배포 체크리스트 (GPT)

1. 경기장 서버 새로 만들기 (Velocity 이름 기본값 `arena`). Paper 1.21.4, Bridge(MagicCodexBridge)·MagicMythic·ChacademyMotion·ModelEngine·PortableVFX·WorldGuard 등 학교 서버와 같은 마법 스택. 경기장 리전은 **WorldGuard pvp allow**.
2. `ChacaKyudichi` 설치 + `plugins/ChacaKyudichi/database.properties` (Bridge와 같은 형식, **공용 MariaDB** — 테이블 `codex_kyudichi_rating / _match / _match_player` 자동 생성).
3. Velocity: 새 party jar. `plugins/chacademi-party/kyudichi.properties` 첫 실행 시 생성 → `arena-server`, `return-fallback-server`(기본 school), `arena-slots`(기본 3), `tier-floors`(경기장 config `rating.tiers`와 같게). `kyudichi-database.properties`에 **같은 MariaDB** (없으면 모두 1000점으로 매칭, 랭킹/전적 빈 화면). `party.properties`의 `trusted-servers`에 경기장 서버도 추가 (파티 명단·닉네임·파티 HUD가 경기장에서도 동작하도록; 대기열 신청 자체는 경기장 서버에서도 따로 허용됨).
4. Bridge(magic-codex-paper) 새 jar를 **학교·야생·경기장 모두**에 (이벤트 클래스 추가, 기존 동작 동일).
5. 클라 모드(magic-codex-fabric) + chacademy-motion 모드/플러그인 같이 배포.
6. PortableVFX Shrine 4팩 경기장 서버에 설치. ModelEngine `/meg reload` 후 리소스팩 재생성.
7. 경기장 세팅은 오너가 직접: `/큐디치관리 막대` → pos1/pos2 → `/큐디치관리 경기장 ...` (탭 완성 참고). `/큐디치관리 경기장 <이름> 검사`로 빠진 항목 확인 후 켜기. 혼자 테스트: `/큐디치관리 테스트 <경기장> [준비:0] [스니치:0] [무적]`.

## 주의사항

- **`/큐디치` 명령 분담**: Velocity가 `신청·취소·수락·거절·대기열·관전·랭킹·정보·연습·도움말`과 인수 없는 `/큐디치`(모드 사용자는 대시보드 열림)를 처리하고, `hasPermission=false`로 나머지(`포기`, `준비`, `관전 나가기`, 경기장 서버 안의 `연습`)는 경기장 서버로 넘긴다. Velocity가 권한 없는 명령을 백엔드로 넘기는 동작을 **실제로 확인**해 달라. 안 넘어가면 해당 하위 명령을 프록시에서 링크로 전달하도록 바꿔야 함.
- 링크 채널 `chacademi:kyudichi_link`은 프록시가 모든 방향에서 handled 처리 (클라이언트 위조 차단). 경기장 서버 이름이 `arena-server`와 다르면 경기장 메시지를 무시한다.
- 경기장 서버 Paper가 프록시 플러그인 메시지를 받으려면 플레이어가 있어야 한다 → 배정은 도착한 플레이어의 HELLO(입장 1초 뒤)에 대한 응답으로 전달. 6명이 다 오면 즉시, 아니면 첫 배정 30초 뒤 온 사람끼리 시작(안 온 사람은 접속 끊김 → 60초 안 재접속 시 합류), 한 팀 전원 미도착 = 무효.
- 경기 끝 6초 뒤(결과 카드) 원래 서버로 돌려보냄. 위치는 그 서버가 접속 종료 때 저장한 자리.
- 진행 중 경기 추적은 LIVE 보고(2초마다)가 2분 끊기면 정리. 경기 중 끊긴 사람이 다시 접속하면 프록시가 첫 서버를 경기장으로 지정.
- 공중 조준: 클라가 시전 직전 C2S AIM 좌표를 보내고, 경기장 플러그인이 1초(20틱) 안의 다음 `MagicAimPointEvent`에서 한 번만 사용 (경기장 bounds 안만). **AIM 경로 마법만 해당.** 블록이 필요한 마법(소환 등)은 여전히 바닥이 필요하다.
- 경기용 6칸: `config/magiccodex/kyudichi-loadout.yml`. 키는 평소 6칸 순서의 키, 평소 칸에 키가 없으면 Q·E·R·T·G·V·C 중 빈 키를 빌린다. **F(손 바꾸기)에 지정된 마법은 경기 중 큐디치 키에 밀려 못 쓴다** (세팅 창에 빨간 키로 경고).
- 평소 마법 칸 끌기 = HUD 순서만 바꿈(마법+키 함께 이동, `key-settings.yml`의 registration-order 저장). 경기 중 끌기 = 경기용 마법만 바꾸고 키는 자리 그대로.
- 금지 마법/아티팩트는 경기장 `config.yml` `banned-spells` / `banned-artifacts` (기본 `rewind_hourglass`). 클라 세팅 창은 금지 목록을 모른다 (시전 시 서버가 거부).
- 레이팅 숫자(K=60, MVP 배율, 티어 범위)는 오너 최종 승인 대기. 바뀌면 경기장 config와 프록시 `tier-floors`를 같이 바꿀 것.
- **랜덤 팀 = 파티가 아님**: 파티 대상 마법(파티원 힐·버프)이 파티 명단으로 대상을 고르면 랜덤 팀원에게는 안 들어간다. 경기장 플러그인은 적 힐을 0으로, 팀원 힐을 통계에 넣을 뿐 대상 선택은 바꾸지 않는다 → 실제 테스트 후 필요하면 경기 중 팀 = 파티로 취급하는 처리 추가.
- 보안: DB 비밀번호·forwarding secret을 로그에 내지 말 것. 두 플러그인 모두 DB 설정은 연결 대상(host:port/db)만 로그에 남긴다.

## 테스트 상태

- 순수 테스트 통과: RatingMath, Box/기간/핑, KyudichiWire, KyudichiProtocol(3), KyudichiQueueProtocol(5), KyudichiMatchmaker(5).
- javac 소스 대조: kyudichi-paper, chacademi-party-velocity, Bridge 새 이벤트 — 자기 파일 오류 0. Bridge `SpellRuntime` 수정분과 Fabric 클라이언트 전체는 **미컴파일**.
- 실제 서버/게임 테스트 0회. 우선 확인할 것: 혼자 테스트 경기(F키·공·성소·결과), 2계정 매칭(수락→이동→복귀), 재접속, 관전, 대시보드, 세팅 창, 공중 조준.


## 2026-10-17 오후: UI 재제작 (커밋 0f4c798, 06afebc)

- 혼자 테스트·연습 경기는 한 팀이 비어도 끝나지 않음 (모두 나가야 종료).
- 오너 PNG `CHACADEMI_QUIDDITCH_FULL_UI_REBUILD` 를 `textures/gui/kyudichi/ui/<폴더>/` 에 넣음 (layouts 제외, keys 256px로 축소,
  recent_table 의 예시 승패 배지 제거, scrollbar 를 track/thumb 로 분리, 오너가 따로 준 frame_ranking·frame_record 추가). 약 23MB.
- 화면 코드: `client/kui/*View.java` (마인크래프트 의존 없음, 레퍼런스 1672×941 좌표 그대로) + 게임용 `KyudichiCanvas`.
  같은 코드를 `src/test/.../kui/PreviewMain` 이 Java2D 로 그려 레퍼런스와 비교 이미지를 만든다 (`AwtCanvas`, `Samples`).
  레이아웃을 고칠 때는 View 만 고치고 PreviewMain 으로 확인.
- 새 창: 신청(`/큐디치`, 프록시 APPLY) · 매칭 대기 카드(HUD 오른쪽 위) · 수락 팝업(자동) · 준비(자동, F 로 다시 열기) · 마법 선택 · 결과(자동) ·
  대시보드(`/큐디치 현황|랭킹|정보`). `/큐디치미리보기 신청|매칭|수락|준비|경기|결과|대시보드|끄기` 로 서버 없이 확인.
- 프로토콜 추가: 대기열 S2C 5 APPLY, C2S 11 APPLY, DASH 는 LiveCard(얼굴), Rank·PlayerRecord 에 skin 해시. 경기장 S2C 8 ROSTER(기숙사·빗자루·레이팅).
- 새 공개 메서드: Bridge `MagicCodexBridge.houseOf(Player)`, 모션 플러그인 `ChacademyMotionPlugin.equippedBroomId(Player)` (경기장 플러그인이 리플렉션으로 사용).
- F키·퀘이플 타이머는 레퍼런스 위치(화면 맨 아래)가 핫바와 겹쳐 핫바·액션바 바로 위로 올림.
- 오너 EXTRA_PNG (2026-10-17) 적용: 탭별 창 `dashboard/frame_live·frame_ranking·frame_record` (1672×941 캔버스 좌표, 탭 구워짐),
  `dashboard/waiting_party_pill` (알파 영역만 잘라 700px), `icons/*` 10개, `medals/rank_1~3`.
  아이콘 위치는 레퍼런스에서 잰 값이고 `Theme.icon()` 이 PNG 투명 여백을 빼고 그림 부분만 맞춘다.
  - frame_ranking 에 구워진 스크롤 손잡이를 잘라 `dashboard/rank_thumb` 로 분리 (프레임 그 자리는 트랙으로 채움).
  - match_card 의 "◇ ─ ◇" 가운데 줄이 "진행 중" 글자를 가로질러 그 줄만 지움 (양옆 줄·마름모는 그대로).
  - 랭킹: 맨 위에서는 1~3위 금색 행 + 메달, 스크롤하면 고른 높이의 일반 행 8줄. 내 순위 행은 목록 판 안쪽 아래.
  - 대기 파티 알약 그림이 레퍼런스보다 가로로 길어 297×44 로 그림 (레퍼런스 235×48).
  - 이제 안 쓰는 부품: dashboard/main, tab_idle·tab_selected, search_field, profile, stat_card, recent_table, scrollbar* (지우지 않음).
- 클라이언트는 여전히 이 환경에서 컴파일 못 함 → 호스팅 빌드에서 오류 나면 수정.


## 2026-10-17 오너 테스트 피드백 반영

- HUD 커서 키 Alt → **Ctrl** (핑·HUD 끌기·카드 클릭·대기 카드 이동 전부). 키 설정에서 Ctrl(좌·우) 지정 불가. Alt 는 여전히 지정 불가(예약).
  ⚠ 바닐라 달리기 키 기본값이 왼쪽 Ctrl 이라, HUD 가 켜진 동안 Ctrl 은 달리기·빗자루 부스트로 안 들어감 (오너에게 알림).
- 매칭 대기 카드: Ctrl(또는 채팅창) 누른 채 끌어서 이동, 우클릭 = 원래 자리. config/magiccodex/kyudichi-queue-card.txt.
- 스니치 상태(날개 자물쇠) 14px 아래로, F 키 70% 크기 + 24px 위로, 기록 창 티어 84px 로 칸 안에.
- 미리보기 데이터가 실제 /큐디치 랭킹 등에 남던 문제: 미리보기 끝날 때 가짜 현황·랭킹·기록·신청 데이터 지움, 서버에서 진짜 데이터가 오면 미리보기 종료.
- 닉네임: 경기장 플러그인은 `MagicCodexBridge.nicknameOf(OfflinePlayer)` (새 공개 메서드) 로 한글 닉네임 사용 (경기 화면·킬피드·결과·DB 이름),
  기존 기록은 접속할 때 DB 이름을 한글 닉으로 갱신. 프록시는 파티 플러그인 identity(한글 닉) 사용.
- 공: 퀘이플 중력(던진 공·놓친 공 모두 떨어져 바닥에서 멈춤, 킥오프 때 위로 던짐), F 던지기·쳐내기는 화면 조준점 방향 (클라이언트가 F 누를 때 C2S AIM 전송),
  블러저 30블록/초·급회전·예측 추적·한 번 노린 사람 계속 추적, 스니치 30블록/초·돌진·지그재그·위치 표시/발광 없음 (서버가 위치를 안 보냄).
  빠른 공이 벽을 뚫지 않게 이동을 잘게 나누고, 맞았는지는 이동 경로 전체로 판정.
  config.yml config-version 2: 서버의 예전 config 는 켜질 때 바뀐 키만 새 값으로 바꿈.
- 사운드: 모드 assets `magiccodex:kyudichi.*` (호루라기 짧게/길게/종료, 골 나팔+함성, 스니치 등장, 블러저 타격, 쳐내기, 던지기, 잡기).
  tools/kyudichi_sound_synth.py 로 합성한 임시 소리 — 같은 이름 ogg 로 바꾸면 됨. 모드 없는 사람은 바닐라 소리.
- 낙마: 공중에서 빗자루를 잃고(균형·피격·비행 종료) 땅/물에 닿으면 사망 → 5초 뒤 부활. 땅에 서서 내리는 건 낙마 아님.
- 빗자루(모션 모드): Shift = 하강, 스페이스 = 상승 (목표 속도로 빠르게 반응, W 누른 채로도 키가 우선), 오를 때 속도 손실 줄임.
  내리기 = 비행 마법 키 다시 누르기 (Bridge ManaBridge 가 타고 있으면 마나·쿨 없이 stopBroom).


## 2026-10-17 키 설정 개편 (오너)

- 바닐라 기본 키(달리기 Shift · 앉기 C · 인벤토리 Tab · 접속자 목록 `)는 런처 config 로 바꿈 — 모드에서는 안 바꿈.
  빗자루: 하강 = 앉기 키, 부스트 = 달리기 키 (따로 키 없음).
- UI 단축키 `UiKeys` (config/magiccodex/ui-keys.properties): P 파티창 · I 내 정보 · O 장비창 · K 마법도감 · L 친구창 · \ 키 설정 ·
  왼쪽 Ctrl 마우스 포인터(HUD 커서) · Z 마법 모드 · X 아티팩트. 처음 켤 때 파일을 만들고, 이미 마법이 있는 키는 그 단축키를 비워 둠.
  도감·내 정보·장비·마법 모드·아티팩트는 기존 바닐라 KeyBinding 에 키를 넣어 주는 방식, 파티·친구·키 설정·포인터는 UiKeys 가 직접 처리.
- 키 설정 창: WASD 만 지정 불가. 키를 누르면 왼쪽에 UI 단축키 메뉴, 오른쪽(작게)에 마법 목록. 한 키에는 하나만 — 겹치면 서로 맞바꿈.
  키보드 그림에 UI 단축키 이름 표시, 아래에 "마법 순서: [포인터 키] 키를 누른 채 화면 우측 하단 마법 칸을 끌어서 바꿀 수 있습니다" (키 이름 자동).
- 큐디치 경기 빈 칸 예비 키 C → B (C 는 앉기·빗자루 하강).
- /큐디치미리보기: 채팅창이 닫히면서 창이 같이 닫히던 문제 → 창을 다음 틱에 엶.


## 2026-10-18 결투장 (1:1 대전 + 새 로비 + 시즌)

오너 결정: 1:1 = 3판 2선승, 라운드 3분(시간 끝나면 남은 체력 % 높은 쪽 승, 같으면 둘 다 1점), 처치하면 둘 다 스폰으로, 지상전(비행 금지),
맵은 등록된 1:1 경기장 중 랜덤, 성소는 회복·마나만. 레이팅·랭킹·전적·마법 세팅은 모드별로 따로. 시즌은 모드별, 끝나면 완전 초기화(레이팅·전적),
큐디치는 1주일 주기.

- 경기장 서버(kyudichi-paper)
  - `경기장 생성 <이름> 1대1` → 1:1 경기장 (arenas.yml `kind: duel`). 필요한 것: 경계 + A·B 시작 위치 1개씩. 성소는 회복/마나만 허용.
  - `Match.duel`: 공 없음, 라운드마다 체력·마나 회복·효과 제거 후 시작 위치, 처치 = 라운드 승, `config.yml duel:` (wins 2, round 180초, 준비 30초,
    라운드 사이 3초, banned-spells). 비행은 항상 금지. 경기 메시지 flag 16 = 1:1 (클라이언트가 F키·공 표시 숨김).
  - DB: `kyudichi_season(mode, season, started_at, ends_at)` 새 테이블, `kyudichi_match_player.damage/rating_after`, `kyudichi_match.name_a/name_b/house_a/house_b`
    컬럼 추가 (있으면 무시). 레이팅 mode 키 `kyudichi` / `duel`.
  - 명령: `/큐디치관리 시즌시작 <큐디치|1대1> [일, 기본 7]` (지금부터 새 시즌, 번호+1, 초기화는 안 함),
    `/큐디치관리 시즌초기화 <큐디치|1대1>` (지금 바로 레이팅·전적 완전 초기화, 시즌 번호·끝나는 때 유지).
    1분마다 끝난 시즌을 확인 → 초기화 + 같은 길이로 다음 시즌 자동 시작 (서버가 꺼져 있던 기간도 따라잡음). 처음엔 시즌이 없으니 시즌시작을 한 번 해야 함.
- 프록시(chacademi-party-velocity)
  - 모드별 대기열: 1:1 은 혼자만(파티에 있어도 각자), 팀 1명. `KyudichiMatchmaker.find(queue, now, size)`.
  - kyudichi.properties 에 `duel-slots` (기본 3, 1:1 동시 경기 수) 추가.
  - 새 메시지 처리: LOBBY(두 모드 시즌·레이팅·티어 구간·대기 인원·파티) · BOARD(진행 중 경기 + 선수 레이팅/티어) · RANKS(6명씩 페이지, 닉네임 검색 →
    그 페이지로 이동 + 강조, 내 순위) · PROFILE(이번 시즌 기록 + 최근 10경기, 1:1 상대 얼굴). `/큐디치` = 로비, `현황|랭킹|정보 [닉]` = 해당 탭,
    `신청|연습 [1대1]`.
- 프로토콜: KyudichiQueueProtocol 모드·로비 메시지 (S2C 6~9, C2S 12~17), STATUS·ASSIGN 끝에 mode, PRACTICE·LIVE 확장. 골든 갱신.
- 클라이언트
  - `KyudichiLobbyScreen` + `kui/LobbyView` (레퍼런스 8장 좌표). 배경 `lobby/bg_kyudichi·bg_duel` = 오너 그림을 가우시안 5px 블러 후 1280×720 저장,
    화면 전체를 덮게 그림. 패널·탭·알약은 이미지 대신 둥근 반투명 도형(Pen.round/outline/panel).
  - 매칭 시작 버튼 = 오너 MATCH_START_BUTTON 4상태. 선 아이콘(연필·핀·사람·돋보기·꺾쇠 `lobby/icon_*`, 64px) = 오너 CHACADEMI_DUEL_LOBBY_ICONS_8.
  - 둥근 얼굴 `Canvas.avatar`: 나는 내 일러스트 얼굴(그림 위쪽 정사각형을 원으로 자름, `PlayerPortraitTexture.drawFace` 비율 조정 가능), 남은 스킨 얼굴.
    다른 사람 일러스트는 일러스트 플러그인이 남의 그림을 주지 않아 스킨 얼굴.
  - 마법 세팅 모드별: `kyudichi-loadout.yml` / `kyudichi-duel-loadout.yml` (1:1 은 비행 없음, 목록에서도 뺌). 로비 연필 → 그 모드 세팅.
  - 1:1 경기 중: F키 칸 숨김, F 가 일반 키로 돌아감(준비 중엔 세팅 창), 점수판에 이름과 라운드 승수. 준비·결과 창은 아직 큐디치 것 재사용.
  - `/큐디치미리보기 로비 | 로비1대1 | 결투` 추가 (신청·대시보드는 로비로).
  - 옛 신청 창·대시보드 코드는 남겨 둠 (프록시가 더는 열지 않음).
- 검증: 프로토콜 테스트 5/5, 매치메이커 6/6, paper·velocity javac 자기 파일 오류 0, 로비 화면 Java2D 미리보기 비교. 클라이언트 전체는 미컴파일.


## 2026-10-19 결투장 로비 오너 피드백 (브랜치 `claude/duel-lobby-polish-20261019`, 기반 codex/operating-status-hud-20261019 c5a2ccf)

- 얼굴: 일러스트 대신 항상 마크 스킨 얼굴(얼굴+모자 층, 원형). `PortraitClient.drawFace` 는 남겨 두었지만 안 씀.
- 넓은 창: 결투장·탭·모드 전환·큐디치 제목·연습 링크·마법 세팅은 화면 왼쪽 끝 기준, 시즌 알약·티어 바·파티·내 정보·매칭 시작·전적 검색창은
  화면 오른쪽 끝 기준 (`LobbyView.edges`, 캔버스 밖 여백만큼 이동). 목록 탭 내용(카드·표)은 가운데 그대로.
- 제목 108 → 78px, 1:1 제목 "1:1 대전" 한 줄. 마법 세팅 묶음 70% (왼쪽 아래 기준, 1:1 도 같은 칸 크기).
- 특수 키: 로비의 F 칸을 누르고 키 입력 → 바닐라 손 바꾸기 키 변경 (ESC 취소, UI 단축키·마법 키와 겹치면 거절).
- 파티창: 로비의 초대 버튼·파티창 키(P)로 열면 로비 위에 열리고 닫으면 로비로 돌아옴 (`PartyScreen(Screen parent)`).


## 2026-10-19 오후 (브랜치 `claude/duel-lobby-fix-20261019`, 기반 codex/operating-duel-lobby-polish-20261019 802adf7)

- 마법 선택 창 목록이 비던 문제: 배운 마법 조건에 `cast.command` 가 있어야 했는데 지금 마법 파일엔 없음 → 키 설정 창과 같은 조건(발견 + 권한 확인)으로.
- 70% 축소한 마법 세팅에서 칸 아래 테두리가 사라지던 문제: `KyudichiCanvas.fill` 이 화면 1픽셀보다 얇은 선을 1픽셀로 그림.
- 티어 정보: 로비 티어 바 왼쪽 (i) → 브론즈~챌린저 점수 구간 표 (지금 티어 강조). 구간은 `LobbyView.TIER_FLOORS` (900·1100·1300·1500·1700,
  경기장 config rating.tiers · 프록시 tier-floors 기본값과 같음 — 바꾸면 셋 다 같이).


## 2026-10-19 밤: 마법 세팅 목록 (브랜치 `claude/duel-spells-fix-20261019`, 기반 codex/operating-duel-lobby-fix-20261019)

- 진짜 원인: 마법 목록의 "배운 마법"(발견+권한 확인) 정보는 서버 권한 응답으로 채워지는데, PermissionClient 는 도감·키 설정·내 정보 창이
  열려 있을 때만 전체 마법 권한을 묻고 평소엔 지금 칸에 있는 마법만 묻는다 → 큐디치/1:1 마법 세팅 창에서는 목록이 비었음.
  `KyudichiSpellPickScreen` 도 전체 권한을 묻는 창에 추가. 목록이 비어 있으면 "불러오는 중 (상태)" / "배운 마법이 없어요" 표시.
- 경기장 서버에서도 목록이 나오려면 Bridge(권한 연동)가 경기장 서버에 있어야 함.


## 2026-10-19 밤: 큐디치 전투 개선 (브랜치 `claude/kyudichi-combat-20261019`)

- 공 크기 2배: config `models.scale` 2.0 (아이템 대체 모양도 2배). F 범위 `key.range` 3 → 5 (퀘이플·블러저, 스니치는 `snitch.catch-range` 그대로).
  config-version 3: 서버의 예전 config 는 켜질 때 이 두 값이 옛 기본값이면 새 값으로 바꿈.
- 비행: 큐디치 경기 중엔 비행 쿨타임 없음(매 틱 ManaService.clearCooldown). 공중에서 빗자루를 놓치면(낙마) `match.flight-fall-cooldown-seconds` 5초
  동안 비행 시전 거부. 타고 있을 때 비행 키 = 내리기는 퀘이플을 들고 있어도 허용.
- 클라이언트: 빗자루를 타고 있으면 비행 키는 쿨타임·대기와 상관없이 서버로 보냄(내리기). `CastingState.ridingCheck`.
- 쳐낸 블러저: 쳐낸 방향으로 날아간 뒤(또는 맞힌 뒤) `bludger.after-deflect-rest-seconds` 2초 동안 아무도 쫓지 않고 흘러감.
- 렌더 거리: 경기 중 플레이어·디스플레이(ModelEngine 공 뼈대)·갑옷 거치대는 거리 상관없이 그림 (`KyudichiRenderDistanceMixin`).
  ⚠ 서버도 보내 줘야 함: 경기장 서버 spigot.yml `world-settings.default.entity-tracking-range` players·misc·display·other 를 크게(예 512),
  server.properties `view-distance` 도 경기장 크기를 덮게(예 16). 안 바꾸면 기본 추적 거리(플레이어 48, misc 32블록) 밖은 안 보임.
- 1:1 화면 정리 (오너 2026-10-19 "공은 없는데 스니치 UI가 있음"): 점수판 아래 스니치 잠김/등장 표시 숨김(`HudView.Score.duel`),
  결과 창은 이름 대 이름 · "라운드 승리" · 처치/데스 열(골·스니치·MVP 없음, `ResultView.Data.duel` / 마지막 경기 플래그 `KyudichiClient.lastDuel`),
  준비 창은 빗자루 칸 숨김, 모드 없는 사람의 채팅 결과도 1:1 형식. 1:1 전용 디자인은 레퍼런스가 오면 다시.
- F3+B 히트박스: 경기(관전 포함) 중엔 켤 수 없고, 켜 둔 채 경기가 시작되면 꺼짐 (`KyudichiHitboxMixin`). 모드를 고친 클라이언트까지 막으려면 경기장 월드에 gamerule reducedDebugInfo true (F3 좌표도 숨겨짐).

## 2026-10-20 저녁: 큐디치 조정 2차 (브랜치 `claude/kyudichi-tune-20261020`, 기반 claude/command-cleanup-20261020)

경기장(kyudichi-paper)
- 공 크기 2.0 → 1.4 (`models.scale`, 아이템 대체 모양 1.4→1.0 / 스니치 0.9→0.7). 블러저 타격 범위 `bludger.hit-radius` 1.5 → 1.0.
- 퀘이플: 던지면 중력 없이 직선 (`quaffle.flight-seconds` 동안), 그 뒤·놓친 퀘이플은 블러저처럼 떠다님 (`quaffle.drift-speed` 12, 중력 안 씀).
- config-version 4: 켜질 때 위 세 값이 옛 기본값(2.0 / 1.5 / 3.0)이면 새 값으로 바꿈.
- 낙마 없음: 공중에서 내리거나 균형을 잃어도 죽지 않음 · 비행 잠금 없음 (이전 `fallCheck` 삭제). 대신 경기 중 2초마다
  ChacademyMotion `setBroomHangMode(p, true)` (끝나면 false) → 아래 매달림.
  → "비행 키로 내린 뒤 다시 누르면 '빗자루에서 내린 뒤 사용할 수 있어요'"는 공중 하차가 낙마로 잡혀 5초 잠금이 걸리던 것 (ManaClient 는
  서버 거부(UNAVAILABLE)를 모두 이 문구로 보여 줌). 잠금 자체를 없앴음.
- teleport-guard: 경기장 경계 안 · 64블록 이내 순간이동은 허용 (빗자루 순간이동 마법). 경계 밖(귀환·집·다른 월드)은 그대로 막음.

모션 (chacademy-motion 모드 + 플러그인, 둘 다 같이 배포)
- 균형 최대치가 오래 탈수록 줄던 피로 감소 제거 (`BroomPhysics.maximumBalance()` = 안정성 단계 최대치). 테스트 수정.
- 매달림 모드(broom_fx 3 켜기 / 4 끄기): 균형 0 → 그 자리에 멈춰 오른팔로 머리 위 빗자루에 매달림(`broom_hang`, 흔들흔들 반복),
  3초에 걸쳐 가득 회복(맞으면 깎임) → `broom_climb` 0.8초 다시 올라타기 → 정상 비행. 매달린 동안 이동 키 무시, 빗자루는 손 높이로 올라감.
- 남에게도 보임: 모드 → 서버 `EVT_HANG 7 / EVT_CLIMB 8` → 주변에 broom_state `STATE_HANG 6 / STATE_CLIMB 7`, 나중에 시야에 들어온 사람에겐
  매달린 상태로 보냄.
- 남의 탑승 자세가 안 보이던 문제 (오너: 죽었다 살아나거나 순간이동하면 안 보임): 그 플레이어 엔티티가 새로 만들어지면 애니메이션 레이어가
  비는데 "이미 broom_ride 틀었음" 기록만 보고 건너뛰었음 → 레이어가 비어 있으면 다시 틀고, 시야 밖으로 빠진 사람은 기록도 지움.
  ⚠ 받는 쪽(보는 사람) 모드가 새 버전이어야 고쳐짐.

클라이언트 (magic-codex-fabric)
- 마법 모드: 죽거나 관전 상태가 되면 꺼졌다가, 살아나면 그 전에 켜져 있었으면 다시 켜짐 (`CastingState.suspend/resume`, 큐디치 밖에서도 동일).
- 블러저 위치 표시 크기 0.62 → 0.40 (화살표·거리 글씨도 작게).

빌드 확인 못 함: Fabric 모드 두 개(magic-codex-fabric, chacademy-motion-mod)는 여기서 컴파일 불가 → GPT 빌드 필요.
`ModifierLayer.getAnimation()` / `IAnimation.isActive()` (playerAnimator) 사용. 서버 두 개는 javac 로 우리 파일 오류 없음, BroomEnduranceTest 4/4.

## 2026-10-20 밤: 큐디치 조정 3차 (같은 브랜치 `claude/kyudichi-tune-20261020`)

- 마법 세팅 창(큐디치 / 1:1): 칸 아래 키 이름을 누르고 원하는 키(또는 마우스 옆 버튼)를 누르면 그 칸의 경기 키가 바뀜. 이미 다른 칸에 있는 키면 서로 바뀜.
  처음 바꿀 때 지금 쓰는 6개 키를 그대로 복사해 두고 그 칸만 바꿈. 키 이름 우클릭 = 원래(평소 6칸) 키로. WASD·UI 단축키·마법 모드 키(Z)는 거부.
  저장: kyudichi-loadout.yml / kyudichi-duel-loadout.yml 에 `keys:` (없으면 예전처럼 평소 키).
- 마법 세팅 창 툴팁: 이름 · 재사용/마나 · 도감 설명(최대 5줄).
- 공이 경기장 벽 근처로 안 감: 스니치·블러저(배회·접근)·떠다니는 퀘이플은 벽에서 `ball-edge-margin` 8블록(위아래 `ball-edge-margin-y` 3) 안으로
  들어가면 안쪽으로 밀려남. 던진 퀘이플·쳐낸 블러저·돌진 중인 블러저는 예외.
- 블러저 돌진: 접근(`bludger.approach-speed` 18) → `dash-range` 14블록 안이면 0.4초 멈칫(연기·소리 예고) → 그 사람이 갈 곳으로 일직선 돌진
  (`dash-speed` 44). 부딪혀도 멈추지 않고 지나가며(맞은 사람은 돌진 방향으로 밀림) 10블록 더 날아간 뒤 1.2초 쉼. chase-speed 는 안 씀.
- 성소 이펙트 크기 `shrine.scale` 0.75 (PortableVFX scale). 킬로그(우측 위 피드) 80%.
- 경계 제한선: 서버가 경기 시작·재입장·관전·성소 변경 때 새 메시지 BOUNDS(9) 로 경기장 `경계` 박스를 보냄 → 클라이언트가 벽 10블록 안으로
  다가가면 그 벽에 반투명 하늘색 격자(내 주변 9블록 원, 멀수록 흐림)를 그림. 벽 4면 + 천장, 바닥 없음 (`KyudichiBoundary`,
  textures/kyudichi/boundary.png). ⚠ 프로토콜 추가: 경기장 플러그인과 클라이언트 모드를 같이 배포 (옛 클라이언트는 모르는 메시지를 무시하지 않고
  decode 실패 → 그 메시지만 버림, 경기 진행엔 영향 없음).
- 서버 javac 확인 방법 바뀜: `-XDshould-stop.ifError=FLOW` 를 줘야 의존성(롬복) 오류 뒤에도 우리 파일을 검사함. 이번 두 커밋 다 이 방식으로 오류 없음.
