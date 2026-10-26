현재 운영 안내는 chaca-portrait-paper/README.md를 따르세요. 아래는 최초 패치 인계 기록이며, 비교 생성 경로는 제거되었습니다.

# ChacaPortrait + 대화창 "내 차례" 작업 기록 (2026-10-10)

- **기준**: 공개 저장소 `zzunwoooo/chacademi-magiccodex-source` 브랜치 `codex/hires-item-icons-20261006`, 커밋 `fab36c1`
- **브랜치**: `feature/player-portrait`
- **작성**: Claude
- **운영 반영 상태**: 서버 설치, 키 생성, DB 변경, 재시작, 유료 API 호출 모두 **하지 않음**.

## 1. 변경 범위

| 위치 | 변경 |
| --- | --- |
| `chaca-portrait-paper/` (새 모듈) | ChacaPortrait 0.1.0 Paper 플러그인. `settings.gradle`에 포함 |
| `chaca-portrait-protocol/` (새 소스) | `school.magiccodex.portrait.PortraitProtocol`. chaca-portrait-paper와 magic-codex-fabric에만 srcDir로 포함 |
| `magic-codex-fabric` 새 파일 | `PortraitClient` (채널·조각 수신·SHA 확인·디스크 캐시)<br>`PlayerPortraitTexture` (premultiplied + 밉맵 텍스처)<br>`PlayerTurn` ("내 차례" 화면)<br>`PortraitPromptScreen` (다시 그리기 입력창) |
| `magic-codex-fabric` 수정 | `DialogueScreen`, `NpcTalkScreen`: "내 차례"를 연결하는 몇 줄<br>`MagicCodexClient`: initialize 한 줄<br>`build.gradle`: srcDir 한 줄 |

다음은 바꾸지 않았습니다.

- 기존 `DialogueProtocol`, `NpcTalkProtocol`, ChacaNPC, Bridge 서버 코드
- 대화창 UI 자산 (패널, 이름표, 선택 버튼, 폰트를 그대로 다시 씁니다)

## 2. 확정된 동작 (사용자 결정)

- **일러스트 생성 시점:** 최초 접속 시 1회 자동입니다.
- **다시 그리기:** ItemsAdder `item:reroll`을 **우클릭**하면 입력창이 열리고, 플레이어가 원하는 느낌을 적어 다시 그립니다.
- **그림체:** 사용자가 준 레퍼런스(GPT가 만든 캐릭터 일러스트)와 같은 그림체입니다.
- **구도:** 대화창에는 상반신만 보입니다. 기존 NPC 초상화와 같은 1024×1536 비율에, 머리부터 허벅지 중간까지입니다.
- **모델:** `gpt-image-2`와 `gpt-image-1.5`를 플러그인에서 바꿀 수 있습니다 (`image.model`, `/portrait model`).
- **"내 차례" 화면:**
  - 고정 대화와 AI 대화 **둘 다**에 들어갑니다.
  - 선택지/입력 직후 같은 양식에서 선택지만 숨기고, 내 일러스트·내 이름·내 문장을 타자기로 보여줍니다.
  - 넘기기는 **클릭 방식**입니다. 첫 클릭은 문장을 바로 다 보여주고, 다음 클릭에 NPC로 넘어갑니다.
  - 일러스트가 없으면 **띄우지 않습니다**.
- **고정 대화의 단일 진행 버튼:** 선택지가 하나뿐인 "계속" 같은 버튼은 "내 차례"로 보이지 않게 했습니다 (판단 기준: 선택지 2개 이상).

## 3. 서버 흐름과 안전장치

- **스킨:**
  - Paper 프로필 textures에서 읽습니다. SkinsRestorer로 바꾼 스킨도 여기에 반영됩니다.
  - 받는 주소는 `textures.minecraft.net`만 허용하고, 256KB까지만 받습니다.
  - 디코드 전에 64×64 / 64×32인지 확인합니다.
- **스킨 그림:** 앞·뒤 전신을 20배로 확대합니다. 구형 64×32 스킨의 왼팔·왼다리는 좌우 반전으로 만듭니다. AI를 쓰지 않습니다.
- **이미지 요청:** `/images/edits` JSON으로 [레퍼런스..., 스킨 그림]과 고정 지시문을 보냅니다.
  - gpt-image-2에는 `input_fidelity`를 보내지 않습니다. 문서상 생략해야 합니다.
  - 모델이 `moderation`/`input_fidelity`를 거부(400)하면 그 항목을 빼고 한 번 더 보냅니다. 4xx 거부는 과금되지 않습니다.
  - 형식 자체를 거부하면 multipart로 다시 보냅니다.
- **플레이어 요청:**
  - 100자·UTF-8 400바이트로 제한하고, 제어문자와 §를 막습니다. 그다음 moderation 검사를 거칩니다.
  - 지시문의 "추가 요청" 칸에만 따옴표로 감싸 넣습니다. 그림체·실존 인물·전연령 규칙은 바꿀 수 없다고 명시했습니다.
- **결과 확인:** PNG 시그니처, 최대 2048px(디코드 전 확인)을 봅니다. `require-transparent`가 켜져 있으면 네 귀퉁이가 투명한지도 확인합니다.
- **예산:**
  - 모든 호출 전에 **예약**하고, 사용량으로 **정산**합니다.
  - 정산은 finally에서 반드시 한 번 합니다. 사용량을 모르는 경우, 시간 초과, 5xx, 중단은 예약액으로 정산합니다.
  - 서버가 종료되어 남은 예약은 다음 시작 때 예약액으로 정산합니다.
  - 예산 행은 조건부 UPDATE로 갱신하므로, DB를 공유하면 서버 간에도 한도를 지킵니다.
- **결과 확정:** 한 트랜잭션으로 처리합니다.
  - 다시 그리기면 `PENDING→DONE` 전이를 먼저 하고, 실패하면 결과를 버립니다 (이미 반환된 경우).
  - 성공하면 일러스트 저장, 자동 시도 초기화, 다시 그리기 횟수를 함께 커밋합니다.
  - 쓰기는 시간 제한 없이 끝까지 기다립니다. 시간이 초과된 뒤에 뒤늦게 커밋되면 성공과 반환이 겹칠 수 있어서입니다.
- **잠금:** 플레이어별 DB 잠금(`cport_state.running_server`)을 둡니다. 20분이 지나면 죽은 것으로 보고, 시작할 때 자기 서버(`server-id`)의 잠금을 해제합니다.
  - **MariaDB를 공유하면 school/wild의 `server-id`가 반드시 달라야 합니다.** 시작할 때 경고를 남깁니다.
- **다시 그리기 아이템:** 아래 순서로 처리합니다.
  1. 문장 검사를 통과하면, 같은 칸에 같은 아이템이 있을 때만 1개 차감하고 바로 `saveData()` 합니다.
  2. `PENDING`으로 기록한 뒤 작업을 넣습니다.
  3. 실패하면 `REFUND_DUE → REFUNDED`로 전이한 쪽만 지급합니다. 오프라인이면 다음 접속 때 지급합니다.
  4. 문장이 거부되면 아이템은 그대로 두고, 같은 입력창을 다시 엽니다.
  5. 남은 위험: 차감 직후 기록하기 전, 또는 지급 직전 플러그인 종료의 아주 짧은 구간은 복제를 막기 위해 지급하지 않습니다 (로그 남김).
- **자동 시도 횟수:** 실제 이미지 생성 실패만 셉니다. 예산 소진, 키·레퍼런스 없음, 다른 서버 작업 중, 스킨 문제는 세지 않습니다.
- **전송:**
  - HELLO는 접속당 10초 간격으로만 받습니다.
  - 클라이언트가 캐시 SHA를 보내면, 같을 때는 조각 0개 META만 보냅니다.
  - 다를 때는 192KB 조각을 틱당 1개씩 보냅니다.
- **키:** 환경변수 `CHACAPORTRAIT_OPENAI_KEY`를 쓰고, 없으면 `CHACANPC_OPENAI_KEY`를 씁니다. config 기본값은 빈 칸이고, 로그·예외에 넣지 않습니다.


단가는 config 기본값입니다. 공개 자료 중 높은 쪽(USD/100만 토큰)이며, 공식 가격표로 확인이 필요합니다.

- gpt-image-2: 텍스트 입력 5, 이미지 입력 8, 출력 30
- gpt-image-1.5: 텍스트 입력 5, 이미지 입력 8, 출력 32

| 1024×1536 한 장 | gpt-image-2 | gpt-image-1.5 (input_fidelity high) |
| --- | --- | --- |
| medium | 약 $0.06~0.08 | 약 $0.09~0.12 (입력 이미지 토큰이 더 많음) |
| high | 약 $0.20 | 약 $0.24 |

- OpenAI 가격 페이지에 gpt-image-2가 이미지 입력 4 / 출력 15로 표시된 표도 있습니다. 그 단가가 맞으면 gpt-image-2 비용은 위의 약 절반입니다.
- 외형 정리(gpt-6-luna)는 한 장에 $0.001 미만입니다.
- 예시: 200명 최초 1장 + 다시 그리기 50장 = 250장이면, medium 기준 gpt-image-2 약 $15~20, gpt-image-1.5 약 $23~30입니다.
- 일러스트 예산 기본값은 `budget.total-usd: 30`이며, ChacaNPC 대화 예산과 별개입니다.
- 외부 자료에 따르면 **gpt-image-1.5는 2026-12-01 종료 예정**입니다. 운영 기간과 겹치면 gpt-image-2를 권장합니다.

## 5. 검증 상태 (구분)

| 항목 | 결과 |
| --- | --- |
| 실행한 것 | Claude 클라우드 환경에서 실행했습니다 (Maven 저장소 접근 불가).<br>- 새 서버 모듈 전체를 Paper **수기 스텁**에 대고 컴파일<br>- 단위 테스트 11개 통과: 프로토콜 3, 스킨 그림·지시문·비용 4, 응답 해석 3, 결과 검사 1. JUnit 대체 러너 사용<br>- SQLite 문장(upsert, 잠금, 예산 조건부 갱신, 횟수)은 Python sqlite3로 확인<br>- OpenAI 클라이언트는 로컬 목 서버로 확인: JSON 본문 키, 모델별 input_fidelity 유무, `moderation` 거부 시 재시도, Responses·moderation 해석<br>- 클라이언트 새 클래스 4개를 MC/Fabric **수기 스텁**에 대고 컴파일. 대화창 수정은 문법만 확인<br>- 별도 검토 에이전트 리뷰 후 지적 11건 중 10건 수정. 남은 1건은 위 "남은 위험" |
| 실행하지 못한 것 | `:chaca-portrait-paper:test :chaca-portrait-paper:jar`, Fabric `test remapJar`. **호스팅 빌드가 필요**합니다. |
| 실제 게임 | **미실시**: 최초 접속 생성, 전송·캐시, "내 차례"(고정·AI 대화), 아이템 우클릭 입력창, 반환 |

### 호스팅 권장 확인

```powershell
.\gradlew.bat :chaca-portrait-paper:test :chaca-portrait-paper:jar
.\magic-codex-fabric\gradlew.bat -p magic-codex-fabric test remapJar
```

## 6. 남은 결정·확인

- `item:reroll` ItemsAdder 아이템 정의와 지급 경로
- 레퍼런스 일러스트 배치: `plugins/ChacaPortrait/reference/style-reference.png`
  - 공개 Git에는 넣지 않았습니다. 별도로 전달합니다.
  - 전신 원본에서 머리부터 허벅지 중간까지 잘라 1024×1536으로 맞춘 버전입니다.
- 공유 DB 여부와 `server-id`
- gpt-image-2 투명 배경(preview)의 결과 품질. 배경이 남으면 `require-transparent: true`로 실패 처리할지
