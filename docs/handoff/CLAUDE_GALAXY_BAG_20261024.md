# Claude 2026-10-24 (not built): 아공간 배낭 — 서바이벌 인벤토리 새 화면 + 옆 제작창 + 배낭 2·3쪽(가방 확장권)

Branch `claude/galaxy-bag-20261024`, based on `claude/shop-admin-inventory-20261024` @ b01c784 (상점 관리 브랜치 위에 이 작업 커밋만 추가).
**Bridge(학교·야생)와 클라이언트 모드를 같이 배포.** 새 채널 `magiccodex:bag_request` / `magiccodex:bag_response`.
모드만 먼저 나가도 동작한다(서버가 없으면 배낭은 1쪽만, 화살표 흐리게). 브리지만 먼저 나가도 문제없다(모드 없는 클라이언트는 무시).

미리보기(오프라인 렌더, 실제 게임 화면 아님 — 아이템은 오너 레퍼런스에서 잘라 붙임):
`docs/handoff/img/galaxy_bag_preview_closed.png`, `_open.png`, `_locked.png`

## 0. 먼저 읽을 것 (주의사항)

1. **Gradle 빌드·실게임 확인 안 함.** 이 세션은 Maven/Fabric 저장소에 접근할 수 없었다.
   - 대신: 1.21.4 yarn 매핑(FabricMC/yarn 1.21.4 브랜치)으로 쓴 MC 메서드·필드 이름과 디스크립터를 하나씩 대조했다.
     (`HandledScreen.drawSlots/isPointWithinBounds/isClickOutsideBounds/drawSlotHighlightBack·Front/heldButtonType`,
     `RecipeBookProvider.refreshRecipeBook/onCraftFailed`, `ClientRecipeBook.getOrderedResults`,
     `RecipeResultCollection.populateRecipes/getAllRecipes/isCraftable`, `RecipeFinder`, `PlayerInventory.populateRecipeFinder/getChangeCount`,
     `ClientPlayerInteractionManager.clickRecipe(int, NetworkRecipeId, boolean)`, `SlotDisplayContexts.createParameters`, `SlotDisplay.getFirst/getStacks`.)
   - 레코드 접근자(`RecipeDisplayEntry.id()/display()`, `RecipeDisplay.result()`, `ShapedCraftingRecipeDisplay.width()/height()/ingredients()`,
     `ShapelessCraftingRecipeDisplay.ingredients()`)는 매핑 파일에 없다(모장 이름 그대로). 컴파일 오류가 나면 이 부분부터 본다.
   - MC 의존이 없는 클래스(`BagProtocol`, `GalaxyBagLayout`, `BagPageCodec`)는 javac 컴파일 + 새 테스트 12개 통과(로컬 JUnit 대용 실행).
   - 화면 골격(바닐라 PlayerScreenHandler 그대로, 슬롯 좌표 → 그림 좌표 매핑, 들고 있는 아이템 훅)은 예전에 호스팅에서 테스트 통과한
     `claude/survival-inventory-20261012`의 `ChacaInventoryScreen` 방식을 그대로 가져왔다(그 화면은 10-13에 오너 요청으로 제거됐고, 이번에 오너가 새 레퍼런스로 다시 요청).
2. **바꾸는 화면은 서바이벌 인벤토리(`InventoryScreen` 정확히 그 클래스) 하나뿐.** 크리에이티브·상자·작업대·상점·다른 MagicCodex 화면은 그대로.
3. **가짜 인벤토리가 아니다.** 바닐라 `PlayerScreenHandler`·슬롯·클릭/드래그/Shift/더블클릭/숫자키/Q·서버 검증 조합을 그대로 쓴다.
   방어구 4칸·왼손 칸은 화면에 없고 데이터는 그대로(Shift 클릭 시 방어구가 숨은 방어구 칸으로 들어가는 바닐라 동작 그대로 — 장비창 버튼으로 확인).
4. **배낭 2·3쪽은 서버별로 따로 저장한다**(`bag-pages.yml`의 `server-id`). 학교·야생 인벤토리가 서로 동기화되지 않으니, 쪽을 서버끼리 공유하면
   한 서버의 쪽이 다른 서버의 쪽을 덮어써 아이템이 사라진다. **열린 쪽 수(확장권 사용 수)만 공유**한다.
   → 학교·야생의 `server-id`가 반드시 달라야 한다. 비워 두면 `quests.yml`의 `server-id`(기본 school)를 쓴다. 야생 서버 quests.yml이 school이면 반드시 bag-pages.yml에 `server-id: wild`.
5. **가방 확장권 아이템은 아직 없다.** ItemsAdder 아이템 id 기본값 `item:bag_expansion`(bag-pages.yml `unlock-item`). 아이템을 만들 때 이 id로 만들거나 설정을 바꾼다.
   테스트는 `/유저관리 <닉네임> 배낭 해금 <1~3>`.
6. `mod_version`은 올리지 않았다.

## 1. 오너 요청 (2026-10-24)

- 레퍼런스 이미지대로 마크 서바이벌 인벤토리를 바꾼다. 인벤토리는 **중앙 상단**.
- 제작창은 평소에 없고, 우상단 **아래쪽 버튼(작업대 아이콘)**을 누르면 **부드럽게 새로 열리는 창**.
- **옷 모양 버튼 → 인게임 장비창**(MagicCodex `EquipmentScreen`) 열기.
- 1/3 쪽: "배낭 잠금 해제권을 사용해서 페이지 해금. 실제로는 프리셋 느낌으로 다음 페이지를 열면 DB에 저장된 아이템이 나오게. 핫바는 그대로."
- 잠금 UI 레퍼런스: 제목 "아공간 배낭", 자물쇠, "잠긴 공간 / 가방 확장권으로 개방", 잠긴 쪽에선 배낭 안 핫바 줄이 없다.

## 2. 클라이언트 (`magic-codex-fabric`)

| 파일 | 내용 |
| --- | --- |
| `client/GalaxyBagScreen.java` (새) | 화면 본체. `HandledScreen<PlayerScreenHandler>` + `RecipeBookProvider`(서버의 조합법 고스트·조합법 갱신을 받음). |
| `client/GalaxyBagLayout.java` (새) | MC 의존 없는 배치·애니메이션·클릭 판정. |
| `client/GalaxyRecipes.java` (새) | 제작창의 조합법 목록(바닐라 조합법 데이터, 2×2에 맞는 것만, 검색·제작 가능 필터·6개씩 쪽). |
| `client/BagPagesClient.java` (새) | 배낭 쪽 상태 수신, 쪽 넘김 요청. |
| `mixin/GalaxyBagSwapMixin.java` (새) | `MinecraftClient.setScreen`에서 `InventoryScreen`만 교체. |
| `mixin/GalaxyBagHooksMixin.java` (새, require=0) | 이 화면에서만 바닐라 16px 하이라이트 끄기, 들고 있는 아이템을 큰 크기로. |
| `mixin/HandledScreenAccessor.java` (새) | 드래그 분배 미리보기용 `heldButtonType`. |
| `client/UiResources.java` | 새 PNG 미리 올리기(다른 패널과 같은 방식) 8줄. |
| `client/MagicCodexClient.java` | `BagPagesClient.initialize()` 1줄. |
| `resources/magiccodex.mixins.json` | 믹스인 3개 추가. |
| `resources/assets/magiccodex/textures/gui/bag/*` | 제공 PNG(아래 3장) + `asset_manifest.json`. |
| `src/test/.../GalaxyBagLayoutTest.java` (새) | 8개. |

### 배치 (3차, 오너: "그대로만 맞추라")

- 슬롯·핫바·번호·쪽 표시·둥근 버튼·제작창 내용의 위치와 크기는 오너 레퍼런스(1672×941)에서 잰 값 그대로.
- 제공 테두리 PNG는 레퍼런스 그림과 모양이 조금 달라서(위·아래 가운데가 들어가고 오른쪽 위가 좁음) 레퍼런스 크기로 깔면
  쪽 화살표가 아래 선에 걸리고 둥근 버튼이 테두리 밖으로 나간다. 그래서 테두리만 **균등 배율 0.71**(비율 그대로)로 그려
  레퍼런스처럼 모든 링은 어두운 유리 안, 버튼 2개·쪽 표시는 테두리 안에 들어가게 했다(PNG 알파로 계산).
  배낭이 그만큼 넓어져서 제작창은 레퍼런스 위치에서 오른쪽으로 100 옮겼고(배낭 옆 8px), 캔버스는 1735×941.
- 안쪽 면은 레퍼런스 남색 #1E2152, 불투명도 90%(오너: "아주 조금만 반투명"), 은하 띠 아래까지 깔림 + 레퍼런스처럼 희미한 별 점.
- 제작창이 닫혀 있으면 배낭이 가운데. 열면 0.24초 동안 배낭이 왼쪽으로, 제작창이 오른쪽에서 밀려 들어와 둘이 가운데 정렬.
  제작창 슬롯은 85% 이상 들어온 뒤부터 클릭된다. 화면에 균등 맞춤 + 위쪽 정렬. 바닐라 회색 어둡게/흐림 없음.
- 글꼴: "아공간 배낭"·"잠긴 공간" 마루부리(title), "제작"·"조합법"·수량 Pretendard Bold, 숫자·쪽 표시 Pretendard Medium.

### 동작

- **옷 버튼**: 인벤토리를 바닐라처럼 닫고(조합칸 아이템 반환) 다음 틱에 장비창을 연다(`EquipmentClient.open()`).
- **작업대 버튼**: 제작창 열기/닫기(켜져 있으면 버튼 테두리가 밝음). 닫을 때 2×2 칸에 아이템이 있으면 Shift 클릭처럼 배낭으로 돌려보낸다(숨은 칸에 남기지 않음).
  제작창 열림 여부·조합법 열림·제작 가능 필터는 게임을 끌 때까지 기억.
- **책 버튼**: 아래 조합법 영역 켜기/끄기(페이드). 제작창 PNG가 고정 크기라 창 자체가 줄어들지는 않는다.
- **조합법 카드**: 클릭 = 바닐라 조합법 클릭(서버가 칸을 채움, Shift면 최대). 재료가 없으면 서버가 보내는 고스트를 2×2 칸에 흐리게 보여 준다.
  커서에 아이템을 들고 있을 때는 카드 클릭 무시. 카드에 마우스를 올리면 결과 아이템 툴팁(차카데미 툴팁 렌더러).
  검색창: 클릭하면 입력(한글 IME 커밋 문자 포함), Enter/ESC로 해제, 입력 중에는 E/Q 등 어떤 키도 인벤토리를 닫거나 던지지 않는다. Ctrl+V 붙여넣기.
  "제작 가능" 켜짐(기본) = 지금 만들 수 있는 것만. 꺼짐 = 전부(만들 수 있는 것 먼저, 못 만드는 것은 어둡게). 제작창 아래쪽에서 휠로도 쪽 넘김.
- **배낭 쪽 화살표**: 열린 쪽이면 서버에 요청 → 서버가 27칸을 바꿈 → 바뀐 상태 수신. 잠긴 쪽이면 서버 요청 없이 잠금 화면만 보여 준다.
  잠금 화면에서는 슬롯이 숨겨지고 클릭되지 않는다(배낭 안 핫바 줄도 레퍼런스대로 숨김 — 아이템은 그대로 있음).

## 3. 서버 (`magic-codex-paper`, MagicCodexBridge)

| 파일 | 내용 |
| --- | --- |
| `BagPagesBridge.java` (새) | 채널, 쪽 바꾸기, 가방 확장권 우클릭, `/유저관리 <닉네임> 배낭 보기·해금`. |
| `BagPagesSql.java` (새) | `codex_bag_unlock(uuid, pages)` — 서버 공유 / `codex_bag_pages(uuid, server_id, active, seq, page1..3)` — 서버별. 자동 생성. |
| `BagPageCodec.java` (새) | 쪽 바이트(27칸, 칸마다 Paper `serializeAsBytes`) + 쓰기 전 기록(journal). |
| `resources/bag-pages.yml` (새) | `server-id`, `unlock-item`, `unlock-item-name`. 없으면 자동 생성. |
| `MagicCodexBridge.java` | 생성·종료 2줄 + 필드. 실패해도 나머지는 계속(인벤토리는 1쪽만). |
| `magic-codex-protocol/.../BagProtocol.java` (새) | 요청(HELLO / SWITCH 1..3), 상태(열린 쪽·지금 쪽·준비됨). |
| `src/test/.../BagPagesTest.java` (새) | 4개(쪽 왕복·손상 거부·journal 최신만 유지·프로토콜 범위). |

- DB: 기존 `player-state-database.properties`(없으면 `database.properties`, 없으면 SQLite `bag-pages.db`) — 낚시 수조와 같은 방식. SQLite면 시작 시 경고(해금 수 공유 안 됨).
- **쪽 바꾸기 (메인 스레드, 한 틱)**: 열린 쪽·로드 완료·0.7초 간격·자기 인벤토리 창(CRAFTING/CREATIVE)만 확인 →
  ① 대상 쪽 읽기(손상이면 아무것도 건드리지 않고 거절) ② 지금 27칸(슬롯 9..35) 저장 ③ **journal 파일에 새 상태 기록(fsync)**
  ④ 27칸 교체 + `player.saveData()` ⑤ DB 쓰기는 비동기, 저장되면 journal 삭제.
  journal이 남아 있으면(크래시·DB 장애) 시작 2초 뒤부터 5초마다 다시 DB에 넣고, 접속 시 journal이 DB보다 새로우면 journal을 읽는다.
  핫바(0..8)·방어구·왼손·커서·조합칸은 절대 건드리지 않는다.
- **가방 확장권**: 우클릭(공기/블록, 양손) → 이벤트 취소 → 이미 3쪽이면 소모 안 함 → 1개 먼저 차감 → DB `pages=pages+1 WHERE pages<3`(트랜잭션) →
  성공 알림(+소리) / 실패·이미 최대(다른 서버에서 먼저 연 경우)면 확장권 돌려줌(가득 차면 발밑). PDC `magiccodexbridge:bag_unlock`(byte)이 붙은 아이템도 확장권으로 인정(나중에 직접 만드는 경우용).
- 알림은 모두 기존 알림 카드(`CmdKit.notify`). 배낭 화면 안에 글씨를 추가하지 않았다.
- `/유저관리 <닉네임> 배낭 보기`(접속 중): 열린 쪽, 지금 쪽, 쪽별 사용 칸 수. `/유저관리 <닉네임> 배낭 해금 <1~3>`: 열린 쪽 수 설정(줄여도 저장된 아이템은 남음, 지금 보고 있는 쪽이 더 높으면 앞쪽으로만 넘길 수 있음).

## 4. 호스팅에서 할 일

```powershell
cd C:\Chacademi\build-workspace
git fetch origin
git merge --no-ff origin/claude/galaxy-bag-20261024
.\magic-codex-fabric\gradlew.bat -p magic-codex-fabric test remapJar
# 브리지: 평소 Build-Server.ps1 -Verify
```

- 충돌 가능 파일: `UiResources.java`(warm 목록 끝), `MagicCodexClient.java`(initialize 1줄), `magiccodex.mixins.json`(끝), `MagicCodexBridge.java`(3곳).
- 야생 서버 `plugins/MagicCodexBridge/bag-pages.yml`의 `server-id`를 `wild`로(또는 quests.yml server-id가 이미 wild인지 확인).

## 5. 실게임 확인 목록

- [ ] E 키 → 새 배낭이 화면 위쪽 가운데. GUI 배율 2/3/4/자동, 1280×720·1920×1080·2560×1440·작은 창에서 링·글씨·버튼 위치.
- [ ] 좌/우 클릭, Shift 이동, 드래그 분배(좌·우), 더블클릭 모으기, 숫자키 1~9, Q/Ctrl+Q, 배낭 밖 클릭으로 버리기.
- [ ] 작업대 버튼 → 제작창이 부드럽게 열림/X·버튼으로 닫힘. 2×2 조합 → 결과 꺼내기, Shift 연속. 칸에 둔 채 제작창 닫기 → 아이템이 배낭으로.
- [ ] 조합법: 제작 가능 필터·검색(한글)·쪽 넘김·카드 클릭으로 칸 채우기·재료 부족 고스트. 검색 중 E 눌러도 안 닫힘.
- [ ] 옷 버튼 → 장비창. 조합칸 아이템이 반환되는지.
- [ ] 배낭 쪽: `/유저관리 <나> 배낭 해금 3` → 2쪽으로 넘기면 27칸이 비고(처음) 핫바 그대로, 아이템 넣고 1쪽 ↔ 2쪽 왕복해서 그대로인지. 재접속·서버 재시작 후에도 그대로인지.
- [ ] 해금 1로 되돌린 뒤 2쪽 → 잠금 화면(제목·자물쇠·문구, 링 흐림, 핫바 줄 없음), 클릭해도 아이템 안 움직임.
- [ ] 학교 ↔ 야생 이동: 쪽 내용은 서버별, 해금 수는 공유.
- [ ] 크리에이티브 전환 시 크리에이티브 화면으로 넘어감.
- [ ] 로그: 믹스인 오류 없음, `BAG PAGES ... failed` 없음.

## 6. 4차 (2026-10-24): 아이템 아이콘 크게 + 정확한 픽셀 크기로 새로 그리기

오너: "아이콘 72px는 되어야 256px짜리 부드럽게 줄였을 때 이쁘게 나온다. 16px로 줄였다 키우는 게 아니고, 아예 저 위치에 새로 그려서 픽셀 깨짐 없이."

- 아이템 크기(설계 단위, 1920×1080에서 약 1.1px/단위): 배낭 66, 핫바 64, 조합칸 64, 결과 76, 조합법 카드 68, 커서 66 → 1080p에서 약 70~84px.
- 새 `client/ExactItemIcons.java` + `client/IconResampler.java`(+ 테스트 2개):
  고해상도 아이콘(32px 초과 아이템 그림, `ItemIconTextures`가 다루는 것)을 그 자리의 **실제 화면 픽셀 크기**(GUI 행렬 × 창 배율)로
  워커 스레드에서 원본 PNG를 Lanczos-3(premultiplied)로 한 번 줄여 올리고, **화면 픽셀 격자에 1:1, nearest**로 그린다. GPU가 다시 늘이거나 줄이지 않는다.
  크기별로 캐시(최대 384장, 오래된 것부터 버림), 리소스 다시 읽기·접속 해제 때 비움. 준비되기 전 첫 프레임들은 기존 경로(트릴리니어)로 그린다.
  ItemsAdder처럼 items JSON으로 못 따라가는 아이템은 GUI 모드 아이템 상태(ItemModelManager.update + 기존 태그)로 키를 찾는다. 반짝임(마법 부여)은 그 위에 바닐라 글린트.
  바닐라 16px 아이템·블록(3D) 아이템은 바닐라 렌더 그대로.
- `ItemIconTextures`: `present/aspect/glint` 도우미, `PROGRAM` 패키지 공개, `reset`에서 `ExactItemIcons.reset()`.
  `HiResItemState`/`ItemRenderStateMixin`: 키 getter 추가.
- 지금은 아공간 배낭 화면에서만 쓴다(다른 화면은 그대로).
