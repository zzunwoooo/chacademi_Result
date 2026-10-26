# 하우징 1차 (기숙사 내 방 꾸미기) — Claude 인계 2026-10-17

브랜치 `claude/housing-20261017` (기반: `claude/kyudichi-20261017` @ 268e92c → 파티 UI·큐디치 브랜치를 먼저 또는 함께 통합).
**빌드·실게임 안 함.** Claude 환경에서는 Gradle·Maven·Minecraft jar 를 받을 수 없다. 확인한 것은 아래 「확인한 것」.

오너 결정 (2026-10-17):
- 방은 마인크래프트 월드에 만들지 않는다. **UI 전용 미니룸**(싸이월드 미니홈피 느낌).
- 기숙사마다 있는 **기숙사실에서 방(문)으로 들어가면 UI 가 열리고 내 방이 나온다.**
- 처음 **7×7** (2026-10-17 오너 최종 결정), 돈을 써서 7→9→11→13→15 로 넓힌다(최대 15×15, 관리자 `/하우징관리 크기` 는 32 까지). 벽 높이 **4칸**. 가구는 UI 에서 놓는다.
- 방 바닥·벽 기본 그림(오너 납품 HOUSING_DEFAULT_TEXTURES): `textures/gui/housing/floor_default.png`(128², 칸마다 한 장) · `wall_default.png`(128×512 = 한 칸 × 높이 4칸). 벽지·바닥재 아이템을 적용하면 그 아이템 색으로 그린다(아이템별 그림은 아직 없음). 그림은 `RenderLayer.getGuiTextured` 사각형에 직접 UV 로 입힌다(`HousingScreen.texFace`) — 실게임에서 벽·바닥이 안 보이거나 뒤집혀 보이면 이 부분.
- 미니미(내 스킨 캐릭터)는 2차. 미니미는 각자 클라이언트에서 자유 행동, 서버는 **방에 있는 사람 명단·닉네임만** 보낸다 (이번에 구현).
- 플러그인은 새로 만든다 (Bridge 에 넣지 않음). 오너가 직접 승인했으므로 MagicCodex 수정도 바로 진행.

## 0. 먼저 볼 것 (배포)

1. **같이 배포해야 하는 것 2개.** 새 채널이라 한쪽만 바꾸면 창이 열리지 않는다(오류는 없음, 채팅으로 안내).
   - 새 플러그인 `housing-paper` → jar `chaca-housing-0.1.0.jar` (**ChacaHousing**). 학교·야생 서버 둘 다 (기숙사실이 있는 서버는 학교).
   - 클라이언트 모드 `magic-codex-fabric` (+ `magic-codex-protocol` 새 파일 2개). `mod_version` 은 GPT 버전 체계와 충돌하지 않게 건드리지 않았다 — 배포 때 올려 줄 것.
   - Bridge(magic-codex-paper)·Velocity 는 **바뀌지 않았다.**
2. `settings.gradle` 에 `include "housing-paper"` 추가. build.gradle 은 kyudichi-paper 와 같은 형식 (`../magic-codex-database/lib/mariadb-java-client-3.5.10.jar` 를 jar 에 포함).
3. **DB**: `plugins/ChacaHousing/database.properties` 를 Bridge 와 같은 형식으로 **공용 MariaDB** 로. 테이블 자동 생성:
   `codex_housing_room / _layout / _like / _visit / _guestbook / _presence / _online`. (보유 가구 테이블 없음 — 가구는 권한)
   Bridge 테이블 `codex_nicknames`(닉네임), `codex_houses`(기숙사), `codex_friends`(친구 목록)는 **읽기만** 한다.
   기숙사는 Bridge `SchoolBridge.house` 와 같은 순서로 찾는다: `codex_houses` → Bridge `StatsService` 의 dormitory(리플렉션) → `magiccodex.house.<arkeon|lumina|bestiaz|noxer>` 권한. 방 정보에는 기숙사 이름 + 문장 아이콘만 보인다.
   SQLite 모드로 켜지면 경고를 남기고 닉네임·기숙사·친구는 비어 보인다.
4. `config.yml` 의 `server-name` 을 서버마다 다르게 (`school`, `wild`). 구경 중 명단·접속 표시의 서버 구분에만 쓴다.
5. **Vault** 가 있어야 방 확장이 된다 (Bridge 상점과 같은 리플렉션 방식). 확장 비용은 `config.yml expansion` — 단계 9·11·13·15 는 오너 결정, **비용(10000·25000·50000·100000)은 임시 값 — 오너가 정할 것.** 처음 크기 `start-size: 7`.
6. 새 리소스: `assets/magiccodex/textures/gui/housing/*.png` 49장 (3.6MB, 배경 1.4MB). 오너가 준 GPT PNG(CHACADEMI_HOUSING_COMPONENTS/ICONS/LAYOUTS)를 manifest 의 visibleRect 로 잘라 줄인 것. 팝업 완성 시안(popup_*)·nameplate 는 넣지 않았다(README 대로 부품 조립).

## 1. 동작

### 들어가기
- 관리자가 기숙사실의 블록(문 등)을 바라보고 `/하우징관리 문 등록 [기숙사|모두]` → 그 블록을 **우클릭하면 내 방 꾸미기 창**이 열린다 (실제 문은 위·아래 칸 모두 인식, 등록된 블록은 부서지지 않음).
  기숙사를 정한 문은 그 기숙사 학생만 열 수 있다 (관리자 예외). `plugins/ChacaHousing/doors.yml` (서버별).
- `/방구경 <닉네임>` 어디서나 남의 방 구경 (config `visit-command: false` 면 관리자만).
- `/내방` 은 관리자 테스트용 (권한 `chacademi.housing.anywhere`, 기본 op).
- MagicCodex 모드가 없으면 채팅으로 안내만.

### 편집 모드 (내 방, 문으로 열었을 때)
- 레퍼런스 layout_edit 배치: 제목·방 정보(얼굴·닉네임·기숙사·오늘/누적 방문·좋아요), 상단 버튼(방 확장·BGM·방명록·친구 방·닫기), 도구(회전·회수·되돌리기·저장), 레이아웃 1·2·3, 보관함(가구·소품·벽걸이·벽지·바닥 탭 + 검색 + 가로 스크롤).
- 보관함에서 고르면 손에 들고, 방 위에서 초록/빨강 칸으로 놓을 자리를 보여 준다. 칸 구분선은 평소엔 숨기고 가구를 들고 있을 때만 점선으로 보인다(벽걸이면 벽에도). 좌클릭 놓기, **R** 회전, 우클릭·ESC 취소, Shift+클릭 같은 가구 이어서 놓기.
- 놓인 가구 좌클릭 = 집어서 옮기기, 우클릭·Delete = 보관함으로. **Ctrl+Z** 되돌리기, **Ctrl+S** 저장. 휠 = 확대/축소, 빈 곳 드래그(또는 우·가운데 버튼) = 이동.
- 벽걸이는 마우스가 있는 쪽 벽(왼쪽/오른쪽)에 걸린다. 벽지·바닥재는 누르면 적용/해제.
- 레이아웃 3칸은 각각 따로 꾸밀 수 있고, **저장한 칸이 방문자에게 보이는 레이아웃**이 된다(초록 점). 칸마다 보유 개수까지 쓸 수 있다.
- 저장하지 않고 닫으면 확인 창. 저장은 서버가 `HousingRules.validate` 로 다시 검사(범위·겹침·보유 개수·카테고리).
- 방 확장: 확인 창(지금 크기 → 다음 크기, 비용) → Vault 출금 → DB `size` 가 그대로일 때만 갱신, 아니면 환불.
- BGM 버튼은 「다음 업데이트에서 열려요」 안내만 (2차).

### 구경 모드 (남의 방)
- layout_visit 배치: 「OO의 방」, 방 정보 + **좋아요 버튼**, 오른쪽 방명록, 상단 BGM·친구 방·닫기. 문으로 연 창에서 남의 방에 갔으면 「내 방」 버튼으로 돌아온다.
- 방문 수: 하루에 한 사람 한 번 (`codex_housing_visit`, 이틀 지난 줄은 지움). 누적은 방 줄의 `visits`.
- 방명록: 60자, 10초 간격(config), 방마다 최근 100개 보관(config), 창에는 최근 30개. 방 주인·쓴 사람·관리자가 지울 수 있다(⋯ → 지우기).
- 친구 방: Bridge 친구 목록(`codex_friends`) + 접속 표시(`codex_housing_online`, 30초 하트비트) + 방 크기. 방이 없는(한 번도 안 들어온) 친구는 「방 없음」.

### 구경 중 명단 (실시간)
- 창이 열려 있는 동안 `codex_housing_presence` 에 (보는 사람, 방 주인, 닉네임, 서버, 시각)을 2초마다 올리고, 같은 방을 보는 사람(모든 서버)을 읽어 바뀌었을 때만 보낸다. 8초 갱신 없으면 빠진다.
- 방이 바뀌면(저장·확장·좋아요·방명록·새 방문·관리자 크기 변경) `codex_housing_room.rev` 가 오르고, 그 방을 보고 있는 모든 서버가 다음 동기화 때 다시 보낸다. 편집 중인 주인 창은 **저장하지 않은 칸을 덮어쓰지 않는다.**
- 화면에는 「구경 중 N명 · 이름 3개」 표시판. (미니미 2차에서 이 명단으로 캐릭터를 그린다.)

### 가구
- `plugins/ChacaHousing/furniture.yml` (오너가 언제든 수정, `/하우징관리 리로드`). 이름·카테고리(가구/소품/벽걸이/벽지/바닥재)·크기·높이·색·테두리색·아이콘(선택).
- **가구 모델이 아직 없어서 색 상자로 그린다.** 지금 목록 14개는 기능 확인용 임시 가구. 아이콘 PNG 를 모드에 넣고 `icon:` 을 적으면 보관함에 아이콘이 나온다.
- **가구는 권한으로 준다 (2026-10-17 오너 결정, 있다/없다만 판단).** 권한 `chacademi.housing.furniture.<가구ID>`, 전부는 `chacademi.housing.furniture.*` (LuckPerms). 학교·야생이 같은 권한을 봐야 한다(LuckPerms 공유 DB).
- 권한이 있으면 그 가구를 `furniture.yml` 의 `max` 개까지(레이아웃마다) 놓을 수 있다. 벽지·바닥재는 1.
- 권한이 빠지면 **다음 접속 때** 저장된 레이아웃에서 빠진다(`HousingStore.touch` → `HousingRules.prune`). 저장할 때도 서버가 그때의 권한으로 다시 검사한다. 관리자 크기 축소 때는 권한과 상관없이 크기·max 로만 정리.
- `/하우징관리 가구 목록` = 가구 ID·최대 개수·권한 안내. (예전 지급·회수 명령은 없앰)

## 2. 프로토콜 `chacademi:housing` (클라이언트 ↔ 게임 서버)

`HousingProtocol` (magic-codex-protocol, 플러그인·모드가 같은 소스). GOLDEN: `magic-codex-fabric HousingProtocolTest` = `housing-paper HousingWireTest`.

```
클라 → 서버: u8 1 · u8 action
  1 VISIT UUID · 2 CLOSE · 3 SAVE u8 slot·Layout · 4 EXPAND u8 지금크기 · 5 LIKE UUID · 6 GB_POST UUID·UTF · 7 GB_DELETE UUID·i64 · 8 FRIENDS
서버 → 클라: u8 1 · u8 kind
  1 ROOM (flags 1 열기·2 편집·4 좋아요·8 내 방으로 돌아갈 수 있음) · 2 VIEWERS · 3 GUESTBOOK · 4 FRIENDS · 5 RESULT · 6 CATALOG
```
- 창은 서버가 연다(ROOM + 열기 플래그). 서버는 창이 열린 사람(세션)의 요청만 받는다. 모든 요청 0.3초 간격 제한.
- 한 패킷 32,000바이트 이하 (레이아웃 최대 200개 배치, 방명록 30개, 친구 100명, 가구 400종).

## 3. 파일

| 위치 | 파일 | 내용 |
|---|---|---|
| 프로토콜 | `HousingProtocol.java` (새) | 형식, 방명록 글 정리(`cleanText`) |
| 프로토콜 | `HousingRules.java` (새) | 가구 정의·배치·레이아웃, 놓기 검사(`fits`)·저장 검사(`validate`)·정리(`prune`), DB 텍스트 형식 `v1|벽지|바닥|id,x,y,z,r;…` |
| 플러그인 | `housing-paper/**` (새) | `HousingPlugin`, `Rooms`(세션·요청·동기화), `HousingStore`(DB), `Catalog`(furniture.yml, 권한 → 놓을 수 있는 개수), `Doors`, `Commands`, `Economy`(Vault), `Skins`, `Settings`, `Net` |
| 클라 | `HousingClient.java` (새) | 채널, 상태(카탈로그·방·명단·방명록·친구), 얼굴 텍스처 |
| 클라 | `HousingScreen.java` (새) | 창 전체 (`SocialScreen` 상속) |
| 클라 | `HousingIso.java` (새) | 쿼터뷰 좌표·마우스→칸/벽·윤곽 (순수 자바) |
| 클라 | `HousingTex.java` (새) | 텍스처 목록·9조각 그리기 |
| 클라 | `MagicCodexClient.java` | `HousingClient.initialize()` 한 줄 |
| 리소스 | `textures/gui/housing/*.png` (새) | 위 0-6 |
| 테스트 | `HousingProtocolTest`, `HousingRulesTest`, `HousingIsoTest` (fabric), `HousingWireTest`, `SkinsTest`, `SettingsTest` (housing-paper) | |

## 4. 확인한 것

- 순수 테스트 18개 통과 (프로토콜 GOLDEN·잘못된 패킷·글 정리, 배치 규칙·정리·텍스트 형식, 쿼터뷰 왕복·윤곽, 스킨 해시, 확장 단계) — JUnit 대신 같은 단언을 쓰는 작은 실행기로.
- 플러그인: 손으로 만든 Bukkit/Paper API 스텁으로 `javac -Xlint:all` 경고·오류 0. **실제 Paper API·MariaDB 로는 아직 안 돌려 봄** (SQL 은 MariaDB·SQLite 두 방언, `INSERT IGNORE`/`ON DUPLICATE KEY UPDATE`).
- 클라: 마인크래프트 jar 가 없어 **실제 컴파일 못 함.** 쓴 API 는 `PartyUiClient`/`PartyScreen` 과 같은 것만(같은 줄 형태). 스텁으로 `javac` 오류 0.
- 화면: 같은 Java 코드를 Java2D 로 그리는 스텁에 돌려 편집/구경/들고 있기/친구 방/확장/방명록/탭 화면을 그려 확인 (얼굴은 스킨 대신 단색).

## 5. 실게임에서 볼 것 (우선순위)

1. 테스트 계정에 권한 `chacademi.housing.furniture.*` → 기숙사실 문 등록 → 우클릭 → 창이 열리는지 (안 열리면 서버 로그 「하우징 준비 완료」, 클라 `canSend(chacademi:housing)`).
2. 놓기·회전·옮기기·우클릭 회수·Ctrl+Z·저장 → 다시 열었을 때 그대로인지. 겹치게/밖으로 놓으면 빨간 칸.
3. 두 계정: `/방구경` → 좋아요·방명록·구경 중 표시판, 주인 창에 실시간 반영(2초 안), 주인이 저장하면 구경하는 쪽 방이 바뀌는지.
4. 학교↔야생: 다른 서버에서 같은 방·명단이 보이는지 (공용 DB).
5. 방 확장 (Vault 잔액 부족·성공·두 번 연타).
6. GUI 배율 2·3·4, 16:9·4:3 에서 잘림 없음. 큰 방(8×8 이상)에서 휠 확대·드래그.
7. **GUI 레이어 컬링**: 방 사각형들은 화면에서 시계 반대 방향으로 맞춰 그린다(`HousingScreen.quad`). 방 바닥·벽이 안 보이면 이 순서를 볼 것.

## 6. 한계·남은 일 (2차)

- 미니미(전용 비율 모델 + 유저 스킨, 자유 행동, 구경 중인 사람 미니미) — 명단은 이미 온다(`VIEWERS`).
- 가구 3D 모델 (지금은 색 상자). `furniture.yml` 에 모델 경로 칸을 추가하고 `HousingScreen.drawPlaced` 를 바꾸면 된다.
- BGM 선택(팝업 PNG 는 받아 둠), 가구 획득 경로(상점·보상), 확장 비용 확정.
- 가구 위에 소품 올리기, 방 랭킹·인기 방 목록은 아직 없음.
- 정렬은 바닥 칸 합 기준이라 아주 긴 가구끼리 붙으면 앞뒤가 어긋날 수 있다(상자일 때는 거의 안 보임).

## 2026-10-20 가구 100종 그림 (브랜치 `claude/housing-furniture-20261020`)

기반: `codex/operating-final-20261019` @ ef55623 + 하우징 수정 8521b9e(cherry-pick, `claude/kyudichi-combat-20261019` 에만 있던 것).

- 오너 납품 `chacademi_housing_100.zip`(GPT 제작) → `textures/gui/housing/furniture/<id>.png`(바닥 가구·소품·특수·벽걸이 80장, 원본 크기), `wallpaper/<id>.png`(256×1024로 축소), `floor/<id>.png`(512², 불투명). 약 12MB.
- `furniture.yml` 을 100종으로 교체 (기획 `claude/housing-furniture-plan.md`). 크기·최대 개수는 기획대로, 높이는 그림에서 잰 값, color/accent 는 그림 평균색(불러오는 동안의 상자 색). `icon:` = 그림 경로(방·보관함 공용).
  **운영 서버 `plugins/ChacaHousing/furniture.yml` 은 jar 로 바뀌지 않는다 → 학교·야생 모두 새 파일로 교체 후 `/하우징관리 리로드`.** 예전 임시 가구 ID(moon_rug, moon_banner, starry_wallpaper, cream_wallpaper, oak_floor, marble_floor)는 없어지므로 그 배치는 다음 접속 때 빠진다.
- 그리기 (`HousingScreen.drawPlaced`, `art`, `artFace`, HudTextureCache.drawGrid 사용 → 밉맵 있는 UI 텍스처):
  - 바닥 가구: 그림의 (32 + 가로칸×128, 높이-32) 점을 차지 칸의 맨 앞 꼭짓점에, 한 칸 = 가로 256px 비율로. 홀수 회전은 좌우 뒤집기(2·3 회전은 0·1 과 같은 그림).
  - 벽걸이: 정면 그림을 벽 칸(가로칸×높이칸)에 붙임. 왼쪽 벽은 화면 왼쪽이 z 큰 쪽.
  - 벽지: 칸마다 한 장(높이 4칸). 바닥재: 한 장이 2×2 칸.
  - 그림이 아직 안 올라온 프레임은 예전처럼 색 상자/색으로 그린다.
  - 마우스 판정은 여전히 `height` 상자 윤곽.
- 특수 가구 11종은 **그림만** 들어갔다. 누르면 창 열기·미끼 받기·전시 동작은 아직 없음(다음 작업).
- 러그도 바닥 칸을 차지하므로 러그 위에 다른 가구는 못 놓는다(겹치기 규칙 그대로).
