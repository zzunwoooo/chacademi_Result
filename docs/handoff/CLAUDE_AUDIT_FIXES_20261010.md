# Claude 인계: 코드 점검 수정분 + 스토리 대화창 통합 (2026-10-11)

- 브랜치: `claude/audit-fixes-20261010`
- 기준: `codex/hires-item-icons-20261006` @ `310512b`
- 규모: 188개 파일, +14,953 / −2,776
- **빌드·서버 설치·게임 실행은 하지 않았다.** 작업 환경에 네트워크가 없어 Paper/Fabric/Minecraft 의존성을 받을 수 없었다. 아래 "검증 수준"과 "빌드 때 확인할 API"를 먼저 본다.

사용자 지시로 진행한 두 가지 작업이 들어 있다.

1. **스토리 대화창 통합 (최우선 지시)**: 차카데미 스토리 모드가 자체 대화창을 그리던 것을 없애고, MagicCodex 의 기존 대화창(`DialogueScreen`)을 그대로 쓰게 했다.
2. **코드 점검 수정**: 전체 소스 점검에서 나온 항목 중 사용자가 승인한 것. 항목 번호(A-1, C-3 …)는 점검 문서의 번호다.

---

## 0. 주의사항 (먼저 읽기)

### 0.1 반드시 같이 배포해야 하는 것

| 묶음 | 이유 |
| --- | --- |
| MagicCodex 모드 + MagicCodexBridge 플러그인 | 친구 프로토콜이 v2 로 올라갔다. 한쪽만 올리면 친구 화면이 응답 없음으로 멈춘다 (충돌은 없음). |
| 스토리 모드 + 스토리 플러그인 + MagicCodex 모드 | 스토리 프로토콜 2. 옛 모드는 "모드를 업데이트해 주세요" 안내만 받고 스토리가 열리지 않는다. 스토리 모드는 이제 `magiccodex` 에 `depends` 로 묶여 있어 MagicCodex 없이는 로드되지 않는다. |
| 학교 서버 + 야생 서버 | 양쪽 플러그인을 같은 빌드로. 옛 빌드는 새 상태값(`paying`, `refunding`, `queued`, `STARTED`, `CONSUMED`)을 모른다. |

### 0.2 배포 전에 운영 설정에서 손으로 확인할 것

운영 중인 `config.yml` 은 새 jar 가 덮어쓰지 않는다. 아래는 사람이 봐야 한다.

1. **ChacaPortrait `server-id`**: 두 서버가 서로 다른 값이어야 한다(`school` / `wild`). 예전 기본값이 `school` 이라 둘 다 `school` 이면, 한쪽 재시작이 다른 쪽에서 생성 중인 리롤을 "소모됨"으로 만든다. 값이 비어 있고 MariaDB 모드면 생성 기능이 꺼지고 SEVERE 가 찍힌다.
2. **스토리 `dialogues/<id>/dialogue.yml`**: 서버 플러그인 폴더에도 넣어야 한다 (사용자 결정). `require-server-graph` 기본값이 true 라, 서버에 파일이 없는 대화는 열리지 않는다. `first-nickname.on-complete` 가 부르는 `ch1-2` 도 해당된다. 편집기가 내보낸 zip 폴더를 `plugins/ChacademyStory/dialogues/<id>/` 로 그대로 복사하면 된다. 클라 파일과 서버 파일은 **같은 내용**이어야 한다.
3. **스토리 `finish-if-missing-mod`**: 새 기본값은 false 지만 운영 파일에 true 가 남아 있으면 그대로 true 다. 원하는 값으로 직접 고친다.
4. **PortableVFX `config.yml`**: `config-version` 이 없는 옛 파일에서 값이 옛 기본값(512 / 1024 / 128)과 같으면 새 기본값으로 읽고 WARNING 을 남긴다. 첫 부팅 때 그 경고 3줄이 찍히는지 확인한다.
5. **ChacaNPC `filter.yml`**: 서버에 이미 있는 파일은 그대로다. 새 `chatter-banned` 목록을 쓰려면 키를 추가한다 (없으면 코드 기본값 사용).
6. **MagicCodexBridge 닉네임 중복**: 이미 중복된 닉네임이 있으면 시작 시 `[닉네임]` WARNING 으로 목록이 찍힌다. 정리한 뒤 재시작하면 UNIQUE 인덱스가 만들어진다.
7. **DB 권한**: 아래 스키마 변경에 `ALTER`, `CREATE INDEX` 가 있다. 운영 DB 계정에 권한이 없으면 ChacaPortrait 은 시작 시 SEVERE 로 멈춘다.

### 0.3 검증 수준

- Gradle 빌드와 실제 서버·클라이언트 실행은 **한 번도 하지 않았다.**
- 한 것: 손으로 쓴 Bukkit/Minecraft 스텁과 JUnit 대용 하네스로 순수 Java 부분을 컴파일·실행했다. 스텁은 기억으로 쓴 것이라 실제 API 시그니처를 보증하지 않는다.
  - PortableVFX 릴레이 테스트 65개, 주문 패키지 테스트 27개 통과
  - 스토리 플러그인 테스트 36개, 스토리 모드 테스트 17개 + 컷신 로더 5개 통과
  - MagicCodex `ExternalFrameTest` 등 12개 통과
  - ChacaNPC `CoreTest` 15개, OpenAiClient 로컬 HTTP 하네스 22개 확인 통과
  - 친구 `SocialTest` 18/19 (1건은 하네스의 int/double 비교 문제)
  - 서버 NPC 대화 경로: 변경 전후 `DialogueScreen` 을 같은 입력 6만 세션으로 돌려 출력이 동일함을 확인
  - 스토리 클라 러너 ↔ 서버 검증기: 예제 대화 363가지 경로 전부 통과, 무작위 그래프 100만 건 퍼징
- **실행하지 못한 것**: SQLite JDBC 가 필요한 저장소 테스트(`ShopStoreTest`, `MailboxStoreTest`, `DialogueTest`, `QuestStoreTest`, `NicknameStoreTest`, `PortraitStorage*Test` 등), MariaDB 방언 SQL 전부, Bukkit 이 필요한 테스트 전부. 새 SQL 은 Python sqlite3 로 SQLite 문법만 확인했다.
- 따라서 **호스팅에서 전체 빌드 + 전체 테스트를 먼저 돌려야 한다.** 컴파일 오류가 나면 대부분 아래 "빌드 때 확인할 API"에 있는 줄일 것이다.

---

## 1. 스토리 대화창 → MagicCodex 대화창 통합

### 무엇이 바뀌었나

- 스토리 모드의 자체 대화창 `kr.chacademy.story.dialogue.DialogueScreen` 을 **삭제**했다. 스토리 모드에 남은 `Screen` 은 `CutsceneScreen` 뿐이다.
- 스토리 모드는 내용과 흐름만 가진다: `DialogueRunner`(순수 Java, 장면·대사·선택지·need·redirect·이어보기), `DialoguePresenter`(화면 연결), `DialogueParser`(검증).
- 화면은 MagicCodex 가 그린다. 새 공개 창구 `school.magiccodex.client.api.ExternalDialogue` (API_VERSION 1):
  - `int apiVersion()`
  - `boolean show(Map<String,Object> frame, BiConsumer<String,String> onChoice, Consumer<String> onClosed)`
  - `void close(String session)`, `boolean isShowing(String session)`
  - 시그니처에 JDK 타입만 쓴다. 스토리 모드(Mojang 매핑)는 `compat/MagicCodexClientLink` 에서 **리플렉션으로만** 부른다. `chacademy-story/mod/src/main` 에 `import school.magiccodex` 는 없다.
- 프레임 키: `session`, `sequence`, `title`, `speaker`, `text`, `portrait`, `portraitFile`(PNG 경로), `playerPortrait`, `preload`, `choices`([id, 글] 최대 6개), `message`, `closable`, `last`.
- MagicCodex 쪽 구현: `ExternalDialogueHost`, `ExternalPortraits`(리소스팩 밖 PNG 를 렌더 스레드 밖에서 읽음), `DialogueScreen` 의 "외부 드라이버" 모드.

### 동작

- 타자기, SPACE/클릭, 3줄 페이지 넘김, 선택지 버튼과 숫자키, 기록, 소리 토글, 선택지 2개 이상일 때 "내 차례"가 서버 NPC 대화와 동일하다.
- 스토리 대화는 닫을 수 없다(`closable=false`). "닫기 ×"가 없고 ESC 는 바닐라 게임 메뉴를 연다. 메뉴를 닫으면 같은 대사가 다시 뜬다.
- 스토리 대화 중에 온 서버 NPC 대화 응답은 보류했다가 스토리 대화(와 마지막 "내 차례")가 끝난 뒤 연다.
- MagicCodex 가 없거나 API 버전이 낮으면 자체 창으로 대체하지 않는다. 채팅에 한 번 안내하고 서버에 `story_fail(magiccodex)` 를 보내 스토리가 넘어가지 않는다.

### 없어진 것 (MagicCodex 대화창 설정을 따름)

- 대화별 `dim`, `type_speed`, `type_sound`, `type_sound_volume` (yml 에 있어도 오류 없이 무시)
- 대화용 스토리 글꼴, 자체 클릭·타자 소리, ENTER 로 넘기기, 선택지 앞 번호
- 초상화 위치: MagicCodex 슬롯(1600×900 기준 65,-5 에 700×1050)에 비율 맞춤. **기존 스토리 그림이 새 자리에서 잘려 보이지 않는지 게임에서 확인 필요.**

### 새 제한 (로더 오류로 알려 줌)

- 장면 id·이벤트 이름: `[a-z0-9_\-]{1,64}`
- 한 장면 선택지 최대 6개, 대사 한 줄 최대 1500자, 숫자에 NaN/Infinity 금지

### 작가 규칙 (중요)

서버가 대화 흐름을 검증한다. **호감도가 붙은 선택지에는 반드시 고유한 `event` 이름을 준다.** 편집기로 내보낸 파일은 자동으로 그렇게 된다. 손으로 쓴 파일에서 이벤트 없는 선택지 둘이 같은 장면으로 가면서 호감도만 다르면 서버가 구분하지 못한다(로드 시 WARNING). 그 경고는 오류로 취급한다.

---

## 2. 모듈별 변경

### 2.1 MagicCodexBridge (magic-codex-paper)

| 항목 | 내용 |
| --- | --- |
| A-1 | 상태 저장 실패 시 "다른 서버가 점유"로 확인된 경우(`LeaseLostException`)에만 추방. 연결 오류는 추방하지 않고 재시도. 종료 시 저장은 2/4/6/8초 재시도. 접속 시 점유 대기를 75초로 늘림. |
| C-9 | 시전마다 저장하던 것을 dirty 표시 후 100틱마다 묶어 저장. 플레이어당 진행 중 저장 1건. 장비·사망·승급 등은 즉시 저장 유지. |
| A-2 | `NicknameStore` 를 `ConnectionHolder` 로 전환 (재접속). |
| A-3 | 접속 시 닉네임 행을 다시 읽고 45초마다 갱신. `first_done` 컬럼으로 최초 닉네임 완료를 서버 간 1회만 처리. `/최초닉네임설정 [플레이어] [강제]`. |
| D-8 | 닉네임 중복 금지 (대소문자·전각 무시, NFKC). `nickname_key` 컬럼 + UNIQUE 인덱스. 다른 플레이어 계정명과 같은 닉네임도 거부. |
| C-22 | 최초 닉네임 상태 기록을 비동기로. |
| A-6 | 상태 적용 전 사망 시 액세서리를 떨어뜨리지 않음. |
| A-8 | PlaceholderAPI `magiccodex` 확장을 하나로 합침 (닉네임 + 마나). |
| A-9 | 계절을 MariaDB 로 공유 (`codex_shared_state`). 벽시계 기준 진행. 1일 = 24000틱 = 실제 20분. SQLite 모드는 예전 그대로. |
| D-22 | 시작 시 DB 모드 로그. `database.require-mariadb` (기본 false). |
| A-5 | 상점·우편 멈춘 거래: 자동 복구 + 관리자 명령 (아래). 구매에 `paying` 상태 추가(출금 전에 기록). |
| A-4 | 대화 효과: 의뢰 수락 가능 여부를 먼저 확인. 미실행 효과는 다음 접속 때 자동 재개. 잠금은 대화별로. |
| A-10 | 보상 명령 실패가 수령을 멈추지 않음. `stage` 컬럼으로 지급 단계 기록, `retry` 는 남은 단계만. |
| C-10 | 처치 진행도를 메모리에 모아 60틱마다 한 번에 기록. |
| C-11 | 구매가 카탈로그 버전을 올리지 않음. `prepare` 는 해당 상품만 조회. |
| C-12 | 인덱스 4개 추가, 우편 N+1 제거, 대화·의뢰 정의는 변경 시에만 재로딩. |
| C-14 | 대화 리비전을 적재 시 한 번만 계산. |
| D-10 | `/상점 <id>` 는 `magiccodex.shop.admin` 전용. NPC 클릭 경로는 그대로. |
| D-9 | 친구 신청제 (아래 2.2). |

새 관리자 명령 (모두 기본 op, `docs/ADMIN_COMMANDS_KO.md` 에 상세):

- `/상점관리 기록 <유저|UUID>`, `/상점관리 처리 <거래ID> 완료|환불|취소`
- `/우편관리 기록 <유저|UUID>`, `/우편관리 처리 <유저|UUID> <수령번호> 완료|반환` — 새 권한 `magiccodex.mailbox.admin`
- `/대화관리 audit <유저|UUID>`, `/대화관리 resolve <유저|UUID> <토큰> rerun|done`
- `/의뢰관리 resolve <유저|UUID> <id> <cycle> delivered|retry|reset`

**동작이 바뀐 기존 명령**: `/대화관리 resolve` 는 방법(`rerun|done`)을 주지 않으면 처리하지 않고 물어본다. `/의뢰관리 resolve … retry` 는 "남은 단계만 지급"으로 뜻이 바뀌었고, 예전 retry 동작은 `reset` 이다.

### 2.2 친구 신청 (paper + protocol + fabric)

- 친구 추가가 신청제로 바뀌었다. 상대에게 HUD 카드("○○ 님이 친구 신청을 보냈습니다", 수락/거절)가 뜨고, 친구 화면의 "신청 N" 탭에서도 처리할 수 있다.
- 규칙: 거절·취소 후 같은 상대에게 10분 재신청 불가, 보낸 신청 최대 20건, 7일 만료, 서로 신청하면 즉시 친구.
- 귓말은 **양쪽 모두 친구**여야 보낼 수 있다. 예전 일방 친구 행은 목록에 남지만 상대가 수락하기 전에는 귓말이 안 된다.
- 친구 삭제는 양방향으로 지운다.
- `SocialProtocol` v2 (`0x534F4302`). 새 동작 ACCEPT/DECLINE/WITHDRAW, 새 응답 FRIEND_REQUEST/INCOMING/OUTGOING.
- 채팅 레이아웃 모드가 리플렉션으로 쓰는 `replyFromChat`, `cancelChatReply`, `installChatListener` 는 그대로다.
- UI 는 단순하게만 만들었다. 위치·크기는 게임에서 보고 다듬어야 한다.

### 2.3 PortableVFX 서버 (portable-vfx-runtime/paper)

| 키 | 예전 | 지금 |
| --- | --- | --- |
| `limits.max-active-handles` | 512 | 4096 |
| `limits.packets-per-tick` | 1024 | 8192 |
| `limits.play-requests-per-tick` | 128 | 1024 |
| `limits.max-active-casts` (신규) | 고정 128 | 512 |
| `limits.max-casts-per-player` (신규) | 고정 8 | 12 |
| `limits.finish-drain-ticks` (신규) | 고정 1200 | 100 |
| `limits.play-retry-ticks` (신규) | 없음 | 10 |
| `limits.max-pending-phases` (신규) | 고정 256 | 256 |

- 끝난 이펙트는 핸들을 100틱만 잡고, 그동안 활성 한도에 세지 않는다.
- 이동 갱신(POSE)은 전역 예산의 50%까지만 쓴다.
- 예산 때문에 못 보낸 PLAY 는 다음 틱에 재전송하고, 시전은 **성공** 처리한다. "볼 사람이 아예 없음"일 때만 거부한다.
- 끝난 투사체가 시전 슬롯을 TTL 끝까지 잡던 문제를 고쳤다.
- 수신자별 스냅샷·채널 플래그·카탈로그 준비 여부를 캐시한다.
- `catalog_ready` 와 hello 패킷에 플레이어별 횟수 제한.
- 거부 로그는 10초에 한 줄로 묶는다.
- 종료 시 예외와 아머스탠드 잔류 수정, 타겟 필터(숨은 플레이어·마커 아머스탠드 제외), 카탈로그는 깨진 주문만 건너뜀, 되돌아오는 투사체가 벽에서 멈추던 문제 수정.
- `max-active-casts` 를 512 보다 크게 올리면 틱 시간이 눈에 띄게 든다 (100명 기준 이동 핸들 1000개에서 약 45ms/틱, 릴레이 단독 측정).
- 주문 내용(`spell-catalog.yml`, `spell-bindings.yml`, `mana-spells.yml`)과 클라이언트는 건드리지 않았다.

### 2.4 ChacaPortrait

- **리롤 아이템 소모 규칙 (사용자 결정)**: 생성 요청이 나가기 전 실패(금칙어, 예산 소진, 대기열 초과 등)는 환불, 첫 유료 호출 직전에 `STARTED` 를 기록한 뒤의 실패는 소모(`CONSUMED`).
- 프롬프트 창과 채팅에 안내 문구: "생성 시도가 실패할 경우에도 아이템은 사라집니다. 관련 문의 사항은 관리자를 찾아주세요."
- 쿨다운과 일일 한도는 시작된 시도를 센다.
- 관리자: `/portrait rerolls <player>`, `/portrait refund <player>`, `/portrait budget adjust <usd>`.
- 실패 분류: 연결 실패·429·4xx 는 과금 0, 5xx·읽기 타임아웃은 보수적으로 과금하되 자동 생성 3회 한도에는 넣지 않음. 자동 생성은 30초/2분/10분 재시도. 연속 실패 5회면 60초부터 10분까지 일시 정지.
- 투명 검사 완화(위쪽 모서리 기준), 서버 간 결과 전달(20초 주기), 작업 우선순위(관리자 > 리롤 > 자동), 접속할 때마다 재실행되던 실패 차단.
- 예시 문구는 "표정·포즈·분위기"로 바꿨다. `prompt.request` 가 의상 변경을 금지하고 있어 의상은 넣지 않았다.

### 2.5 ChacaNPC

- **선물 기능은 건드리지 않았다** (`GiftService.java` 변경 없음, 선물 분기 동일).
- D-6: `promise`·`memo` 는 저장 전에 정리·필터. 잡담에 쓰는 소문은 서버가 만든 문장만 사용하고 플레이어 이름과 원문은 넣지 않는다. 잡담 출력도 필터. `social.public-rumors` (기본 true).
- A-27: "생각 중" 상태에 전체 시한. C-17: AI 대기열 상한(`openai.max-queue` 기본 40), 이미 포기한 요청은 보내지 않음. D-20: 실패 요약 로그와 차단기(`openai.circuit.*`), `/cnpc budget` 에 실패 수 표시.
- A-28: 힌트는 서버가 프롬프트에 넣고 대사가 전달되면 차감. 낮은 호감도에는 `hints.yml` 의 `vague:` 문장만 모델에 준다.
- A-29, A-30, A-31 묶음(분위기 요약 고착, 중복 NPC 연결, 장소 파일, `/cnpc ai off` 저장 등).
- `OpenAiClient.stream(...)` 은 `player(...)` / `background(...)` 로 바뀌었다.

### 2.6 스토리 플러그인·컷신 (chacademy-story)

- A-37: 열기 요청은 채널 등록을 기다린 뒤 판단 (접속 후 15초까지).
- A-38/A-40: 플레이어당 진행 항목 1개 + 대기열(최대 16). `progress.yml` 에 저장하고 재접속 시 다시 재생. 컷신이 중단되면 `cutscene_abort` 를 받아 다시 보낸다.
- A-39/D-4: 서버가 `dialogue.yml` 로 흐름을 검증하고 완료 기록을 남긴다. 완료한 대화를 다시 열면 아무것도 지급하지 않는다(`replay` 인자나 `allow-replay` 로만 다시 보기). 잘못된 패킷 20건이면 대화를 멈춘다. 잘못된 `done` 은 즉시 멈추고 보호를 푼다.
- D-7: 스토리 중 피해·몹 타겟·허기·수평 이동을 막는다 (`protect-during-story`, 최대 `protect-max-seconds` 900초).
- C-6/C-7: 파일 쓰기는 전용 스레드, 효과는 기록이 디스크에 남은 뒤 실행. 패킷 경로에서 파일을 읽지 않는다. 플레이어별 초당 20건 제한.
- 프로토콜 2: `chacademy:story_v2`(S2C), `cutscene_abort`, `story_fail`(C2S). 기존 패킷 형식은 그대로.
- 컷신: 자산을 렌더 스레드 밖에서 읽음, 장면 단위 텍스처, ESC 로 게임 메뉴(시계와 BGM 일시정지).
- 편집기: 불러오기 확인과 "이전 초안 복구", YAML 값 전부 인용, 알 수 없는 키 보존, 서버 명령 파일 없이 불러온 경우 경고.
- `progress.yml` 형식 2. 옛 파일은 시작 시 변환하고 `progress.yml.v1-backup` 으로 복사한다.
- 새 명령: `/cutscene clear <p>`, `/storydialogue <p> <id> [replay]`, `/storydialogue ledger <p> [id]`, `/storydialogue reset <p> <id|all>`.
- 설정 키 변경: `affinity-max-per-dialogue` 는 없어지고 `affinity-gain-cap-per-dialogue` (기본 0 = 끔). 그 밖의 새 키는 `config.yml` 주석 참고.

### 2.7 MagicDiscovery / 야생 / 빌드 스크립트

- `DiscoveryStore` 를 `ConnectionHolder` 로 전환. 로드·저장 재시도.
- 진행도는 큰 값 유지 방식으로 병합(`GREATEST` / `MAX`). `state.*`, `trace.*` 키만 덮어쓰기.
- LuckPerms 는 플레이어당 한 번, 바뀐 노드만. 저장은 바뀐 키만.
- `/마법발견관리 보상 <닉네임|UUID> [번호 재수령|완료]`.
- 야생: 서식지를 로드 시 한 번만 파싱, 인구 스캔은 32블록 셀당 2초 캐시, 구조물 조회는 필요할 때만, 정리 작업 예외 가드.
- `Build-Server.ps1`: 6개 플러그인 모두 복사·해시. jar 가 없는 모듈이 있으면 실패한다.
- 발견 트리거·주문 내용·토네이도는 건드리지 않았다.

---

## 3. DB 스키마 변경 (모두 추가만)

| 테이블 | 변경 |
| --- | --- |
| `codex_nicknames` | `first_done`, `nickname_key` 컬럼 + UNIQUE 인덱스(`…_key`) |
| `codex_shared_state` | 신규 (계절 공유) |
| `codex_friend_requests`, `codex_friend_declines` | 신규 |
| 상점 주문 | `origin` 컬럼, 인덱스 (owner, state). 새 상태 `paying`, `refunding` |
| 상점 상품 | 인덱스 (shop) |
| 우편 / 우편 아이템 | 인덱스 (owner, deleted, created) / (token) |
| `codex_quest_progress` | `stage` 컬럼 |
| `cport_reroll` | `fail_reason`, `notified` 컬럼. 새 상태 `STARTED`, `CONSUMED` |
| `cnpc_rumors` | UNIQUE 인덱스 (event_id, heard_by). 기존 중복 행은 최초 1회 정리 |

`CREATE INDEX IF NOT EXISTS` 는 MariaDB 전용 문법이다 (MySQL 이면 조용히 건너뜀). MariaDB 방언은 한 번도 실행하지 않았으니 스테이징에서 먼저 확인한다.

---

## 4. 빌드 때 확인할 API (컴파일 오류 후보)

**Fabric / Yarn (magic-codex-fabric)**

- `FriendRequestToast.initialize()`: `ScreenEvents.AFTER_INIT.register((client,screen,width,height)->…)`, `ScreenMouseEvents.allowMouseClick(screen).register((s,x,y,button)->…)` — 이 모드에서 처음 쓰는 API. 안 되면 `HudCursorScreen.mouseClicked` 에 `FriendRequestToast.click(x,y,button) ||` 한 줄을 넣는 방법이 있다.
- `DialogueScreen`: `new GameMenuScreen(true)`, `shouldCloseOnEsc()`, `removed()` 오버라이드.
- `ExternalDialogueHost`: `MinecraftClient.isOnThread()`, `ClientPlayConnectionEvents.DISCONNECT`, `ClientTickEvents.END_CLIENT_TICK`.
- `ExternalPortraits`: `PlayerPortraitTexture.decode(byte[],int)`, `upload(NativeImage,Bounds)`, `draw`, `width`, `height` (현재 소스와는 일치).

**Fabric / Mojang (chacademy-story/mod)**

- `CutsceneScreen`: 색 인자가 있는 `GuiGraphics.blit(RenderType::guiTextured, …, int color)` 오버로드 (페이드가 알파를 따르는지), `new PauseScreen(true)`.
- `CutsceneTextures`: `NativeImage.read` / `resizeSubRectTo` 를 워커 스레드에서 호출 (렌더 스레드 단언이 없어야 함).
- `CutscenePackets`: `ByteBufCodecs.stringUtf8(int)` 를 `StreamCodec.composite` 에 섞어 쓰기, `ClientPlayNetworking.canSend(payload.type())`.
- `BgmPlayer`: `ALC10.alcGetCurrentContext()`, `AL10.alIsSource`, `alSourcePause`.
- `DialoguePresenter`: `displayClientMessage(Component, boolean)`.

**Paper 1.21.4**

- `Bukkit.getOfflinePlayerIfCached(String)`, `Bukkit.getCommandMap().getCommand(label)`, `Bukkit.getCurrentTick()`
- `Player.canSee(Entity)`, `ArmorStand.isMarker()`, `Entity.addScoreboardTag/getScoreboardTags`, `Entity.getLocation(Location)`
- `PlayerRegisterChannelEvent` / `PlayerUnregisterChannelEvent` 의 `getChannel()`
- `EntityDeathEvent` / `PlayerDeathEvent` 에 `ignoreCancelled=true`
- `PlayerRespawnEvent.getRespawnLocation()`, `PlayerMoveEvent.setTo`
- `YamlConfiguration.options().pathSeparator(char)`, `createSection(String, Map)`, `getValues(false)`
- `ServiceRegisterEvent` / `ServiceUnregisterEvent` 의 `getProvider().getService()`
- `FileConfiguration.getInt("config-version", 1)` 이 키 없는 파일에서 1 을 돌려주는지 (PortableVFX 설정 이전이 여기에 의존)

**외부 라이브러리**

- LuckPerms 5.4: `getUserManager().getUser(UUID)`, `user.data().toCollection()`, `node.hasExpiry()`, `node.getContexts()`
- PlaceholderAPI: `PlaceholderExpansion.register()` / `unregister()` 의 boolean 반환
- JDK 21: `HttpClient.shutdownNow()` (ChacaPortrait). 빌드·실행 JDK 가 21 인지 확인.

**테스트**

- `CatalogCastBehaviourTest` 는 `java.lang.reflect.Proxy` 로 Bukkit 인터페이스를 흉내 낸다. 실제 Paper 가 목록에 없는 메서드를 부르면 `AssertionError(메서드명)` 이 난다. 그 메서드 케이스를 추가하면 된다.
- 하네스에서 `assertEquals(int, double)` 꼴로 실패하던 기존 테스트 몇 개는 하네스 문제로 판단했다. 실제 JUnit 에서 다시 본다.

---

## 5. 게임에서 확인할 것

1. **스토리 대화**: MagicCodex 대화창으로 열림, "닫기 ×" 없음 / 긴 대사 페이지 넘김 / 선택지 1개는 "내 차례" 없음, 2개 이상은 있음 / NPC 그림은 `config/chaca_dialogue/<id>/` PNG, "나" 대사는 내 일러스트 / ESC → 메뉴 → 돌아오면 같은 대사 / 대화 중 재접속 시 같은 자리 / 스토리 대화 중 NPC 대화가 오면 끝난 뒤 열림 / 컷신 → 대화 → 컷신 연결 / 일반 NPC 대화가 예전과 같은지.
2. **스토리 서버**: 접속 직후 트리거가 건너뛰어지지 않는지 / 컷신 중 사망·GUI 후 다시 재생 / 완료한 대화가 다시 지급되지 않는지 / 스토리 중 피해·몹·밀림 / 모드 없는 클라이언트와 옛 모드.
3. **두 서버**: 학교에서 정한 닉네임이 야생에서 바로 보이는지 / 같은 닉네임을 양쪽에서 동시에 저장 / 계절이 같은지 / 친구 신청이 서버를 넘어 도착(약 8초) / 초상화 결과가 다른 서버에서 도착(약 20초).
4. **DB 장애**: MariaDB 를 잠깐 내렸다 올려도 아무도 추방되지 않고 복구되는지, 닉네임·마법 발견이 재시작 없이 돌아오는지.
5. **VFX**: 여러 명이 모여 연속 시전할 때 거부가 없는지, 10초 요약 로그, 플러그인 재시작 후 아머스탠드가 남지 않는지, `/pvfxserverdebug reload` 에 깨진 주문을 넣었을 때.
6. **거래**: 구매·판매 도중 종료 후 재접속 / 단계 사이에 서버를 죽이고 `/상점관리 기록` / 우편 수령 중단 후 다시 열기 / 의뢰 3개 찬 상태에서 의뢰를 주는 대화 선택.
7. **리롤**: 금칙어 거절은 환불, 생성 시작 후 실패는 소모와 안내 문구, `/portrait refund`.
8. **+체력 액세서리**: 최대 체력으로 서버 이동·재접속했을 때 하트가 줄지 않는지. 줄면 준비 완료 후 체력을 복원하는 처리가 추가로 필요하다 (아래 6-3).

---

## 6. 알려진 한계 (고치지 않은 것)

1. **DB 가 60초 넘게 느리거나 죽은 상태에서 서버를 옮기면** 마지막 저장 이후 진행이 사라지고, 그 사이 바꾼 액세서리가 복제되거나 없어질 수 있다. 기준 커밋보다 나빠지지 않았지만 닫으려면 점유 인계 설계를 바꿔야 한다.
2. **관리자 처리 경합**: 같은 주문을 두 서버에서 동시에 `환불` 하면 두 번 입금될 수 있다. 진행 중인 다른 서버의 거래를 처리해도 꼬인다. 한 주문은 한 관리자가, 플레이어가 거래 중이 아닐 때 처리한다.
3. **+체력 액세서리**: 준비 전에는 속성을 건드리지 않게 했지만, 수정자가 transient 라 Paper 가 플레이어 파일을 읽을 때 체력을 깎으면 막지 못한다.
4. **스토리 효과는 "적어도 한 번"**: 효과 실행과 기록 사이에 서버가 죽으면 다음 접속 때 한 번 더 실행된다. 보상 명령은 중복 실행에 안전하게 쓴다.
5. **스토리 진행·완료 기록은 서버별 파일**: 같은 대화를 학교와 야생 양쪽에서 열면 서버마다 한 번씩 지급된다. 보상 있는 스토리 트리거는 한 서버에만 둔다.
6. **스토리에 응답 없는 클라이언트 감시가 없다**: done/abort/fail 을 안 보내면 재접속 전까지 그 요청이 남는다. `/cutscene stop|clear`, `/storydialogue stop` 으로 푼다. 보호는 15분 뒤 자동 해제.
7. **VFX**: 한 시청자에게 FINISH/STOP 이 256건 넘게 밀리면 CLEAR 로 합쳐져 그 시청자의 진행 중 이펙트가 모두 사라진다 (기존 동작). 플러그인이 꺼질 때 CLEAR 를 보낼 수 없어 이펙트는 TTL 까지 남는다.
8. **NPC 힌트**: 서버가 힌트를 프롬프트에 넣은 턴은 모델이 언급하지 않아도 차감된다. 필터의 `시발` 은 "다시 발견" 같은 문장에도 걸린다 (기존 목록).
9. **친구**: 받은 신청은 오래된 50건만 보인다. 신청은 같은 서버에 있는 상대에게만 시작할 수 있다 (수락·알림은 서버를 넘는다).
10. **리롤**: `STARTED` 기록과 첫 호출 사이의 아주 짧은 구간에 서버가 내려가면 유료 호출 없이 소모된다. `/portrait refund` 로 돌려준다.
11. **`ConnectionHolder`**: 5초 안에 다시 쓰는 연결은 검증을 건너뛴다. 드라이버가 끊긴 소켓을 닫힘으로 표시하는 것에 의존한다. MariaDB 를 부하 중에 재시작해 확인한다.
12. **편집기**: CDN 스크립트에 SRI 해시를 넣지 못했다 (네트워크 없음). HTML 주석에 정확한 URL 과 버전을 적어 두었다. 새 대화상자는 실제 브라우저에서 보지 못했다.

---

## 7. 이번에 하지 않은 것

- **사용자 지시로 제외**: 공개 저장소의 서버 접속 정보(D-1), NPC 선물(A-26), 토네이도(A-46, D-14), `glaze_coating` 등 주문 수치.
- **개발 중이라 제외**: 마법 발견 트리거(A-47), VFX 외부 이벤트(A-22), 교화(A-7, A-14, C-5, D-12), 채팅 레이아웃 모드(D-23).
- **GPT 담당 UI 라 목록만 전달**: MagicCodex 모드 렌더링·UI (A-12 ~ A-18, C-31 ~ C-34, C-37). 특히 A-12(클라 마법 목록이 최초 1회만 설치됨)는 콘텐츠 패치 전에 처리가 필요하다.
- **게임에서 보지 않고 고치기 위험해 제외**: PortableVFX 클라이언트 렌더링 (A-19, C-26 ~ C-30).
- **D-11 (스타캐치)**: 타이밍 정답이 시작 응답에 들어 있어 변조 클라이언트는 항상 만점이다. 판정과 지급은 서버가 하므로 복사·손실 문제는 없다. 고치려면 프로토콜과 UI 를 바꿔야 해서 그대로 두었다.
- **AI 예산 수치(D-17 ~ D-19)**: 운영 설정이라 숫자는 건드리지 않았다.
