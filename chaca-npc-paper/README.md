# ChacaNPC (chaca-npc-paper)

school 서버 전용 AI NPC 플러그인. Paper 1.21.4 / Java 21 / **Citizens 필수**. 대화 모델은 OpenAI `gpt-6-luna`(설정으로 변경 가능).
메인 스토리는 MagicCodex 고정 대화가 맡고, ChacaNPC는 서브 NPC와 메인 NPC의 일상 대화를 맡습니다.

빌드: `.\gradlew.bat :chaca-npc-paper:test :chaca-npc-paper:jar` → `chaca-npc-paper/build/libs/chaca-npc-0.2.0.jar`
(Citizens API는 `https://maven.citizensnpcs.co/repo` 의 `citizens-main`, 기본 컴파일 기준값 `2.0.37-SNAPSHOT`.
school 서버에 실제 설치된 Citizens 버전은 아직 확인되지 않았습니다. 다르면 `-PcitizensVersion=...` 로 맞추세요.)

## 구성

| 위치 | 내용 |
| --- | --- |
| `chaca-npc-paper/` | 서버 플러그인 (패키지 `kr.chacademy.npc`) |
| `chaca-npc-protocol/` | AI 대화 패킷 계약 `school.magiccodex.npctalk.NpcTalkProtocol` — chaca-npc-paper 와 magic-codex-fabric 에만 포함 (Bridge JAR에는 없음) |
| `magic-codex-paper` 의 `NpcSocialFacade` | 호감도·스토리 우선 판단·퀘스트 연결 공개 facade. Bridge JAR에만 있고 ChacaNPC는 리플렉션으로 호출 (클래스 중복 없음) |
| `magic-codex-fabric` 의 `NpcTalkClient` / `NpcTalkScreen` | HUD 대화창 (기존 대화창 질감·배치 재사용 + 입력칸·추천 버튼·하트) |

## 설치 (운영 반영은 통합 확인 후)

1. `network/school/plugins` 에 Citizens(1.21.4 지원 버전)와 `chaca-npc-0.2.0.jar` 설치. 같은 플러그인 JAR 중복 설치 금지.
2. **API 키**: school 서버 프로세스에 환경변수 `CHACANPC_OPENAI_KEY` 를 설정 (권장). `config.yml` 의 `openai.api-key` 는 비워 둠.
   키·DB 비밀번호·실제 접속 설정은 공개 Git에 넣지 않습니다.
3. **DB**: `plugins/ChacaNPC/database.properties` (형식은 MagicCodex 플러그인들과 같음, 예시: `database.properties.example`).
   파일이 없으면 SQLite `plugins/ChacaNPC/chaca-npc.db`. 운영은 기존 MariaDB 인스턴스의 **전용 스키마**(예: `chacademi_npc`) + 그 스키마 권한만 가진 계정을 권장.
   테이블은 모두 `cnpc_` 접두어이며 켜질 때 자동 생성됩니다. (스키마·계정 생성은 아직 하지 않았음)
4. `config.yml`: `budget.start-date`(운영 시작일), `world-lore`(세계관 요약).
5. 장소 `/NPC관리 장소 <이름> [반경] [표시이름]`, NPC `/NPC관리 소환 <캐릭터>` 또는 기존 Citizens NPC 옆에서 `/NPC관리 연결 <캐릭터>`.

## 클릭 진입점 (연결된 NPC)

ChacaNPC 하나가 처리하고, MagicCodexBridge의 Citizens 클릭 처리에서는 그 NPC들이 제외됩니다(`claimCitizensNpcs`).

1. **Shift + 손에 아이템** → 선물
2. **열 수 있는 고정(스토리) 대화**가 있으면 MagicCodex 대화를 엶 (기존 권한·조건·진행 상태 검사 그대로)
3. 결과가 `NONE` 일 때만 **AI 대화**. `BUSY`(처리 중)·`BLOCKED`(모드 미설치·검토 대기)·오류일 때는 AI로 덮지 않음

다른 유저 스텟 보기(실제 Player만, NPC 메타데이터 제외)는 건드리지 않습니다.

## 대화 화면

- MagicCodex 모드가 `NpcTalkProtocol` 버전 handshake(HELLO/HELLO_ACK)를 마친 클라이언트 → HUD 대화창
- 그 외 → 채팅 대체 화면 (`/말 <할 말>`, `/말 그만`, 채팅 클릭 버튼 = `/대화선택`)
- 서버는 **검사(입력 필터·moderation·대사 검사)를 통과한 완성 대사만** 보냅니다. HUD는 타자기 효과로 표시.
- 모든 요청·응답에 세션 토큰과 요청 순번. 창 닫기·재접속·다른 NPC 전환·시간 초과 후 도착한 응답은 화면에 보내지 않음(비용은 정산).
- 입력은 서버에서도 100자(코드포인트)·UTF-8 400바이트·제어문자 검사.

## 퀘스트·선물

- 퀘스트: 캐릭터 파일 `quests:` 에 **MagicCodex 의뢰 id**. 제안 가능 여부는 `questOfferable`(공개·권한·진행 중 여부), 수락은 기존 의뢰 수락 경로(`acceptFromDialogue`).
  AI는 허락된 id 중 하나를 고르고 대사로 꺼낼 뿐입니다. 수락 토큰은 플레이어·NPC·퀘스트·만료(5분)에 묶이고 한 번만 처리됩니다. AI 출력으로 명령어를 실행하는 경로는 없습니다.
- 선물: 하루 횟수 예약 + pending 기록 → 같은 칸의 같은 아이템 1개 차감 → 확정. 차감 전 아이템이 바뀌면 취소,
  확정 DB 실패 시 아이템 반환 + 취소, 중복 클릭은 플레이어당 1건만, 토큰당 점수 1회. 서버가 차감 후 확정 전에 꺼지면 Bridge가 다음 시작 때 pending을 확정합니다(아이템 복제 없음).

## 예산

- 3주 총예산(`budget.total-usd`)을 플러그인이 관리: 모든 AI 호출(대화·버튼 미리 생성·NPC 잡담·분위기 요약)이 **요청 전 최대 비용을 예약**하고 끝나면 실제 사용량으로 정산.
  사용량을 모르면(스트림 끊김) 예약 금액으로 정산. 하루 한도 = 남은 예산 ÷ 남은 날짜.
- `timeout-seconds`(8초)가 지나면 화면에는 고정 대사를 보이고, 요청은 `hard-timeout-seconds`(45초)까지 받아 비용을 정산합니다.
- OpenAI 프로젝트 지출 한도·알림은 별개의 안전장치입니다. 알림과 강제 한도는 다르며, 강제 한도도 반영 지연으로 소폭 초과할 수 있습니다
  ([OpenAI 안내](https://developers.openai.com/api/docs/guides/spend-limits)).
- `gpt-6-luna` 는 공식 API 모델이지만, 사용할 OpenAI 프로젝트의 접근 권한은 따로 확인해야 합니다.

## AI 호출 보호 장치

- 플레이어 대화와 백그라운드 작업(버튼 미리 생성·NPC 잡담·분위기 요약)은 스레드·대기열이 따로이고, 플레이어 쪽이 붐비면 백그라운드부터 버립니다.
- 플레이어 대기열(`openai.max-queue`)이 가득 차면 바로 고정 대사 + 하루 횟수 환불. 대기 중 기한이 지났거나 창을 닫은 요청은 OpenAI에 보내지 않습니다(과금 없음).
- 429·5xx·연결 실패만 한 번 다시 보냅니다. 연속 실패가 `openai.circuit.failures` 번이면 `cooloff-seconds` 동안 AI를 부르지 않습니다(차단기).
- 실패는 `debug` 와 무관하게 종류별로 집계해 1분에 한 번 WARNING 으로 남기고, `/서버관리 AI 예산` 에 최근 1시간 집계·차단기 상태가 나옵니다.
- 과금 없이 실패한 호출은 플레이어 하루 횟수를 돌려줍니다. "생각 중"은 입력 시점부터 `timeout-seconds`+3초 안에 반드시 끝납니다.

## 저장·공개되는 글 검사

- AI 대사: 제어문자·`§` 색 코드 제거 후 전송. 메모·약속·분위기 노트: 추가로 `&` 색 코드·URL 제거 + 금지어 검사(`filter.yml`), 걸리면 그 항목만 버림.
- NPC끼리 잡담(근처 모두에게 보임)에 들어가는 소문은 서버가 만든 문장뿐입니다(플레이어 이름·플레이어/AI가 쓴 글 없음). `social.public-rumors: false` 로 끌 수 있습니다.
  잡담 출력도 `filter.yml` 의 `chatter-banned` 로 한 번 더 검사하고, 접속 중인 플레이어 이름이 들어가면 내보내지 않습니다.
- 마법 힌트는 서버가 대화에 넣어 준 시점 기준으로 횟수를 셉니다. 호감도가 낮으면 재료 대신 `hints.yml` 의 `vague` 문장만 AI에게 줍니다.

## 관리자 명령어

`/테스트 NPC 대화 <캐릭터> <문장…>` · `/서버관리 AI 예산|켜기|끄기|캐시` · `/NPC관리 목록` · `/NPC관리 분위기노트 보기|승인 <번호|전체>|삭제 <번호>|비우기|지금생성` · `/서버관리 새로고침 NPC` · `/NPC관리 장소` · `/NPC관리 경로 추가|비우기` · `/NPC관리 소환|연결|연결해제` · `/유저관리 <닉네임> AI대화 …` · 유저: `/말`, `/호칭 <이름|초기화>`
