# 책 읽기 UI (홀로그램 책 창 + 닫을 때 명령) — Claude 2026-10-23, 미빌드

브랜치 `claude/book-ui-20261023` (기준: `review/integrated-release-20261023-task7` @ af1ed3e — hires-item-world·NPC 추천 버튼 포함).
**MagicCodexBridge(magic-codex-paper) + MagicCodex 클라이언트 모드(magic-codex-fabric)를 같이 배포**해야 함 (새 채널 `magiccodex:book_request` / `magiccodex:book_response`).
별도 모드·플러그인 없음. 기존 UI·레이아웃·설정은 건드리지 않았고, 기존 파일 변경은 등록 몇 줄뿐.

## 0. 오너 결정 (2026-10-23)

| 항목 | 결정 |
|---|---|
| 읽음 조건 | 열고 닫기만 하면 읽음 (마지막 페이지 도달·체류 시간 조건 없음) |
| 닫는 방법 | ESC·X 버튼 동일하게 실행 |
| 중복 | 책마다 **플레이어당 평생 1번**, 학교·야생 공유 (DB) |
| 페이지 단위 | 펼친 면(왼쪽+오른쪽) = 1페이지, 아래에 `1 / 3` |
| 마법 발견 | 새 시스템·새 알림 없음. 책 명령으로 기존 `마법조건 주기`를 실행 → 기존 '새로운 마법 발견' 흐름·알림 그대로 |
| 레퍼런스 | 시안은 예시일 뿐 — 삽화·우측 하단 알림 없음. 책 안에는 글씨만 |

## 1. 바뀐 파일

| 파일 | 내용 |
|---|---|
| `magic-codex-protocol/.../BookProtocol.java` (새) | S2C OPEN(토큰·제목·페이지들), C2S CLOSE(토큰만). 최대 40페이지, 한쪽 1000자, 제목 40자 |
| `magic-codex-paper/.../BookBridge.java` (새) | books.yml·book-blocks.yml 읽기, 블록 우클릭 → 창 열기, CLOSE 검증 → 명령 실행, 명령어 |
| `magic-codex-paper/.../BookReadStore.java` (새) | DB 테이블 `codex_book_read(uuid, book, at, PK(uuid,book))` — 명령 실행 기록 |
| `magic-codex-paper/src/main/resources/books.yml` (새) | 설명 주석 + 예시 책 `sample_book` (명령 없음) |
| `MagicCodexBridge.java` | `BookBridge` 생성(낚시 다음)·종료 3줄 |
| `CommandHub.java` | /컨텐츠관리 설명에 "책" 추가 (문구 1줄) |
| `magic-codex-fabric/.../BookClient.java` (새) | 네트워킹 |
| `magic-codex-fabric/.../BookScreen.java` (새) | 책 창 렌더·입력 |
| `magic-codex-fabric/.../BookLayout.java` (새) | 좌표·글자 맞춤 (순수 계산, 테스트 대상) |
| `MagicCodexClient.java` | `BookClient.initialize()` 1줄 |
| `assets/magiccodex/textures/gui/book/` (새) | `book_panel.png`(원본 1672x941 그대로), `page_previous.png`·`page_next.png`·`close.png`(투명 여백만 잘라 256x256으로 축소 — 그림 변경 없음) |
| `magic-codex-fabric/src/test/.../BookUiTest.java` (새) | 패킷·글자 맞춤·비율 테스트 |
| `docs/ADMIN_COMMANDS_KO.md` | 책 명령 줄 추가 |

## 2. 설정 예시 (`plugins/MagicCodexBridge/books.yml`)

첫 시작 때 자동 생성. 고친 뒤 `/서버관리 새로고침 책` (형식 오류면 새로고침이 거절되고 이전 내용 유지, 이유는 콘솔).

```yaml
books:
  body_strengthening:            # 책ID: 영문 소문자·숫자·_·- (DB 기록 키라 한 번 정하면 바꾸지 말 것)
    title: "체술 강화"            # 40자까지, 첫 페이지 왼쪽 위
    pages:                        # 1~40페이지, 한 페이지 = 펼친 두 쪽
      - left: |
          몸을 움직이는 힘도 결국은 마력이다.
          달리는 발끝에 불씨를 실어 보자.
        right: |
          …
      - left: |
          두 번째 페이지 왼쪽
        # right 생략 가능 (빈 쪽)
    commands:                     # 선택. 닫을 때 콘솔로 실행, 플레이어당 평생 1번
      - "유저관리 {player} 마법조건 주기 flame_leap"
  library_notice:                 # 명령 없는 책 = 글만 보여 줌
    title: "도서관 이용 안내"
    pages:
      - left: "조용히 읽어 주세요."
```

- 치환: `{player}` 마크 닉네임, `{uuid}` UUID, `{book}` 책ID. 맨 앞 `/` 는 있어도 됨. 명령 20줄·한 줄 256자까지.
- 마법 발견 책은 `유저관리 {player} 마법조건 주기 <마법>` 을 쓰면 됨. 쓸 수 있는 마법 = MagicDiscovery `manual-conditions.yml` 의 수동 조건 마법 (flame_leap·heat_recovery·firebird_hunt·blue_bonfire·water_mirror·cauldron_fill 등 "책 읽기" 조건 포함, 자동완성으로 확인). 이 명령이 기존 발견 처리·권한·'새로운 마법 발견' 알림·최초 발견 칭호까지 그대로 함. 이미 배운 사람은 기존 동작대로 "기존 발견 기록 유지, 알림 없음".
- 본문 줄바꿈은 그대로, 빈 줄 = 문단 띄우기. 긴 줄은 쪽 너비에서 자동 줄바꿈.
- books.yml 은 **서버마다** 있음 (공유 아님). 같은 책을 학교·야생 둘 다 두려면 같은 내용을 양쪽에 넣기. 기록은 책ID 기준 공유라 같은 ID면 한쪽에서 읽은 사람은 다른 쪽에서 명령이 다시 실행되지 않음.

## 3. 책과 블록 연결

기존 하우징 문(`/하우징관리 문 등록`)과 같은 방식 — 블록 좌표에 책을 묶고, 그 블록을 우클릭하면 열림. 연결 정보는 서버마다 `book-blocks.yml` (`월드,x,y,z,책ID`).

1. 책장·독서대 등 블록을 바라보고 (6칸 안) `/컨텐츠관리 책 연결 <책ID>`
2. 풀기: 바라보고 `/컨텐츠관리 책 해제`
3. 확인: `/컨텐츠관리 책 목록` (책별 페이지·명령·연결 블록 수)
4. 미리보기: `/컨텐츠관리 책 열기 <책ID> [닉네임]` — 블록 없이 창만 띄움. **이렇게 연 책도 닫으면 명령이 실행됨** (스토리·NPC 등에서 명령으로 책을 띄울 때도 이걸 쓰면 됨)
5. 기록: `/유저관리 <닉네임> 책기록 보기` · `/유저관리 <닉네임> 책기록 초기화 <책ID> 확인` (지우면 다시 읽을 때 한 번 더 실행 — 테스트용)

연결 블록 우클릭은 이벤트를 취소함 (독서대 바닐라 창·블록 설치가 안 뜸). 0.5초 연타 방지. 블록 파괴 보호는 넣지 않음 (부숴도 연결은 남고, 같은 자리에 다시 놓으면 그대로 동작).

## 4. 서버 검증·중복 방지

- 서버가 열 때 무작위 64비트 토큰을 만들어 `플레이어 → (토큰, 책ID)` 세션에 저장하고 OPEN 으로 보냄. 클라는 닫을 때 **토큰만** 보냄 (책ID·명령 없음).
- CLOSE: 세션 토큰과 다르면 무시. 같으면 세션을 지우고(토큰 1회용) 그 책의 **서버 설정** 명령만 실행 대상. 한 번에 한 책만 열림 (새로 열면 이전 토큰 무효), 퇴장 시 세션 삭제.
- 명령 있는 책: 처리 중 표시(같은 책 두 번째 닫기는 무시) → DB `INSERT IGNORE` → **이 INSERT 가 행을 새로 만든 경우에만** 명령 실행. 이미 있으면 아무것도 안 함. 학교·야생이 동시에 받아도 PK 로 한 쪽만 성공.
- 기록을 먼저 남기고 명령을 실행 (중복 지급 방지 우선). INSERT 성공 뒤 플레이어가 이미 나갔으면 기록을 다시 지움 (다음에 다시 읽으면 실행).
- DB 오류면 명령을 실행하지 않고 "책 내용을 기록하지 못했어요 / 잠시 후 다시 읽어 주세요" 알림 (기존 알림 기능).
- DB 설정은 낚시 도감과 같은 규칙: `player-state-database.properties` 있으면 그 공유 MariaDB, 없으면 `database.properties`, 둘 다 없으면 로컬 `books.db`(시작 시 경고 — 이 경우 서버 간 공유 안 됨). 테이블 자동 생성.
- 일반 접속 시 DB 부담: 명령 있는 책을 닫을 때만 INSERT 1번, 읽은 책은 접속 중 메모리 캐시로 다시 안 물음. 렌더·틱 작업 없음.

## 5. 클라이언트 화면

미리보기: [1페이지](img/book_ui_preview_p1.jpg) · [2페이지](img/book_ui_preview_p2.jpg) · [5:4 화면](img/book_ui_preview_5x4.jpg) (실제 폰트·실제 PNG·실제 BookLayout 값으로 오프라인 렌더, 배경은 시안을 흐리게 한 것).

- 캔버스 1672x1000 (책 PNG 1672x941 + 아래 버튼 줄) 을 통째로 화면의 80% 안에 맞춰 축소 → GUI 배율·해상도·화면비가 달라도 비율 유지, 책이 화면을 꽉 채우지 않음.
- 글씨: CodexTypography (실제 픽셀 크기로 래스터) — 제목 `title`(마루부리 세미볼드), 본문 `heading`(같은 서체), 페이지 번호 `title`. 이미지에 굽지 않음.
- 글 상자: 왼쪽 x 185~752, 오른쪽 x 945~1485 (2026-10-23 오너 피드백 "오른쪽 글씨가 왼쪽에 붙어 보임" → 접히는 쪽 가장자리에서 70px 띄우고 너비 540, 왼쪽은 그대로), y 160~780 (PNG 실측: 금색 테두리 x 127/1535, 접히는 부분 x 790~875, 모서리 장식 피함). 왼쪽 정렬·위 정렬, 줄 간격 1.75.
- **기울어진 종이 맞춤 (2026-10-23 오너 피드백 "책이 덜 펴져서 종이가 기울었는데 글씨만 반듯함")**: 쪽마다 사다리꼴(바깥쪽이 높고 접히는 쪽이 낮음)이라 PNG 실측 기울기 — 윗변 왼쪽 +0.042 / 오른쪽 -0.047, 아랫변 왼쪽 -0.009 / 오른쪽 +0.009 — 를 줄 높이에 따라 섞어서, 글 줄·제목·구분선을 각 쪽 글 상자 가운데 기준으로 기울여(shear) 그림. 위쪽 줄은 많이, 아래쪽 줄은 거의 안 기울어짐. 아래 버튼 줄·닫기 버튼은 그대로. 값은 `BookLayout.LEFT_/RIGHT_TOP/BOTTOM_SLOPE`.
- 넘칠 때: 본문 27px → 21px까지 1px씩 줄여 맞추고, 그래도 넘치면 마지막 줄 끝을 "…" 로 (이 경우 페이지를 나누라는 뜻). 제목은 46→30px 한 줄, 그래도 길면 두 줄(34→24px).
- 첫 페이지만 왼쪽 위에 제목 + 얇은 금색 선과 작은 마름모(시안의 구분선). 버튼 줄 사이 얇은 선도 시안 그대로. 그 밖의 장식·글로우 추가 없음.
- 반투명: `BookLayout.PANEL_ALPHA = 0.82` (README 권장 0.75~0.85), 버튼 0.92(올리면 1.0), 넘길 수 없는 쪽 버튼 0.32. 배경은 아주 옅게 어둡게(`0x4D040A14`). 글씨 색: 제목 #EBD3A0, 본문 #DCE3EE 94%, 번호 #E9D9B0 — 눈부시지 않게 흰색 100% 안 씀, 가산 블렌드 안 씀.
- 조작: 화살표 버튼·키보드 ←/→·PageUp/Down·마우스 휠로 넘김(바닐라 책 넘김 소리), X·ESC로 닫기. 닫기 패킷은 화면이 어떻게 닫히든 1번만.
- 서버가 보낸 책이 오면 열려 있던 다른 창 위로 바로 열림 (낚시 판매소와 같은 방식, `MagicCodexClient.dismiss()`).

## 6. 테스트 결과

- 이 작업 환경에서는 Gradle 배포판·Maven 저장소 다운로드가 막혀 **Gradle 빌드·전체 테스트는 못 돌림.**
- `javac` 로 프로토콜 패키지 전체 + BookLayout·CodexLayout·CodexTextLayout·CodexTextRasterizer + `BookUiTest` 를 컴파일하고, 최소 JUnit 대용으로 실행 → **5/5 통과**
  - 패킷 왕복·잘못된 패킷 거절(토큰 0, 빈 페이지, 41페이지, 1001자, 뒤에 붙은 바이트, 크기 초과), 최대 크기 책(40페이지×양쪽 1000자 한글)도 한 패킷에 들어감
  - 실제 마루부리 폰트로: 보통 문단은 27px 유지, 띄어쓰기 없는 긴 문장·빈 줄 포함 문단·1000자 꽉 찬 쪽 모두 상자 너비/높이 안, 넘치면 21px + "…"
  - 기울인 뒤에도 글 상자 네 모서리가 금색 위·아래 선 안 (위 y>130, 아래 y<800), 바깥쪽이 더 높게 기울어짐
  - 제목 1줄/2줄 맞춤, 427x240 ~ 3840x1600 창 크기에서 캔버스 비율·가운데 정렬 유지
- **Paper 쪽(BookBridge·BookReadStore)은 컴파일 못 함** — 기존 FishShopBridge·FishingStore·Doors 와 같은 API 만 써서 작성. GPT 빌드 때 컴파일 확인 필요.

## 7. 빌드·배포 대상

| 대상 | 무엇 |
|---|---|
| MagicCodexBridge jar | 학교·야생 (책을 둘 서버). 아레나는 책이 없으면 생략 가능하지만 같은 jar 를 쓰면 그대로 둬도 무해 |
| MagicCodex 클라이언트 모드 | 런처 배포 (새 텍스처 4장 포함) |
| 설정 | 첫 시작 때 `books.yml` 자동 생성 (예시 책 `sample_book` — 필요 없으면 지워도 됨). `book-blocks.yml` 은 첫 `책 연결` 때 생성 |
| DB | `codex_book_read` 테이블 자동 생성 (공유 MariaDB). 마이그레이션 없음 |

## 8. 주의사항 (GPT)

1. 프로토콜·서버·클라 **같이 배포**. 구버전 모드는 채널이 없어서 "차카데미 모드를 업데이트해 주세요" 알림만 뜨고 창이 안 열림 (명령도 실행 안 됨).
2. 명령은 **콘솔 권한**으로 실행됨 — books.yml 은 OP·운영자만 고치는 파일로 유지.
3. 명령 실행이 실패해도(오타 등) 기록은 남음 → 콘솔 `책 <id> 명령 실행 실패: …` 확인 후 명령 고치고 `/유저관리 <닉네임> 책기록 초기화 <책> 확인`.
4. `마법조건 주기` 는 대상이 접속 중이고 발견 데이터 로딩이 끝나야 처리됨 (기존 동작). 접속 직후 0.x초 안에 책을 닫는 경우는 사실상 없지만, 실패하면 콘솔에 MagicDiscovery 쪽 문구가 남음 → 3번처럼 복구.
5. 책ID 바꾸기 = 새 책 (기록이 새로 시작). 지운 책ID 의 기록은 DB 에 남아 있어도 무해.
6. 컴파일 위험 지점: `BookScreen.tilt` 의 `m.peek().getPositionMatrix().mul(new Matrix4f().m01(k))` (JOML, y += k·x 기울이기), `BookBridge.finish` 의 enum switch 화살표 블록 안 `return`(람다 안), `CmdKit.confirm(CmdKit.word(...), this::reset)` 제네릭, `BookScreen` 의 `Math.clamp(int,int,int)`·`Math.clamp(float,int,int)` (Java 21). 문제 있으면 그 줄만 풀어서 써도 됨.
7. 좌표·글자 크기·투명도 상수는 전부 `BookLayout.java` 한 곳.
