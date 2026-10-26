# 차카데미 서바이벌 인벤토리 (Claude, 2026-10-12)

브랜치 `claude/survival-inventory-20261012`
기준: `codex/chat-discovery-magic-protection-20261011` @ `c373610`(작업 시작 시점의 최신 통합본)
대상 모듈: `magic-codex-fabric`만. 서버·Paper·Velocity·DB는 바꾸지 않았다.

미리보기(오프라인 렌더, 실제 게임 화면 아님): `docs/handoff/img/chaca_inventory_preview.png`, `chaca_inventory_preview_book.png`
아이템 그림은 리소스팩에 있는 실제 128px 아이콘 대신 저장소에 있는 기숙사 아이콘으로 대신 그렸다.

## 0. 먼저 읽을 것 (주의사항)

1. **Gradle 빌드와 실게임 확인은 아직 하지 않았다.** 호스팅에서 빌드·테스트한다(AGENTS.md).
   - 대신 사용자 PC의 Loom 캐시에 있는 실제 1.21.4 yarn 매핑 jar와 Fabric API jar(yarn으로 리맵)로 모듈 전체를 `javac` 타입 검사했다. 오류는 기존 `CodexTextureFilterMixin`의 access widener 1건뿐인데, 이건 Loom이 처리하는 환경 차이라 이번 변경과 무관하다.
   - 같은 클래스패스로 모듈의 JUnit 전체를 실행했다. **257개 모두 통과**: 새 테스트 13개와 기존 244개다. AW에 의존하는 `CodexTextureFilterTest`는 실행 대상에서 뺐다.
   - 믹스인 대상 메서드와 필드의 디스크립터는 `javap`로 대조했다.
2. **바꾸는 것은 서바이벌 인벤토리 화면 하나뿐이다.** 정확히 `InventoryScreen` 클래스가 열릴 때만 교체한다(E 키, 튜토리얼, 다른 모드 경로 모두 포함).
   - 크리에이티브, 창고·상자, 작업대, 상점, MagicCodex 다른 화면, `InventoryScreen`을 상속한 다른 모드 화면은 그대로다.
3. **가짜 인벤토리가 아니다.** 바닐라 `PlayerScreenHandler`, 슬롯, `HandledScreen`의 클릭·드래그·Shift·더블클릭·숫자키·Q 처리, 서버 검증 조합, 바닐라 조합법 위젯을 그대로 쓴다. 바뀐 것은 "그리는 위치"와 "마우스 좌표 → 슬롯" 판정뿐이다.
4. **방어구 4칸과 왼손 칸은 화면에서 빠졌지만 데이터는 그대로 있다.** 다만 바닐라 서버 동작 때문에 아래 두 경우 숨은 칸으로 아이템이 들어간다(사라진 것이 아니라 장착된 것).
   - Shift 클릭: 방어구·겉날개는 방어구 칸으로, 방패 등은 비어 있는 왼손 칸으로 들어간다.
   - F 키: 왼손과 교체한다.

   필요하면 별도 표시를 추가할지 사용자와 정한다.
5. **이전 대비 동작이 바뀐 점 두 가지**
   - 32px 넘는 고해상도 아이콘이 마법 부여(반짝임)일 때도 이제 고해상도로 그리고 그 위에 바닐라 반짝임을 덮는다. 전에는 바닐라 16px로 대체했다. 인벤토리뿐 아니라 핫바 HUD, 툴팁 등 GUI 전체에 적용된다.
   - 상태 효과 목록(바닐라 인벤토리 오른쪽 목록)은 새 화면에 없다. HUD의 효과 아이콘은 반투명 배경 뒤로 보인다.

## 1. 변경 범위

| 파일 | 내용 |
| --- | --- |
| `client/ChacaInventoryScreen.java` (새 파일) | 화면 본체. `RecipeBookScreen<PlayerScreenHandler>`를 상속하고 `AbstractCraftingRecipeBookWidget`를 사용한다(바닐라 `InventoryScreen`과 같은 구성). |
| `client/ChacaInventoryLayout.java` (새 파일) | MC 의존이 없는 배치·부유·클릭 판정 코드. 1672×941 레퍼런스 좌표를 그대로 쓴다. |
| `client/GhostItemView.java` (새 파일) | 조합법 고스트 아이템 읽기용 인터페이스. |
| `mixin/ChacaInventorySwapMixin.java` (새 파일) | `MinecraftClient.setScreen` 인자를 교체한다. |
| `mixin/HandledScreenHooksMixin.java` (새 파일) | 새 화면에서만 바닐라 16px 슬롯 하이라이트를 끄고, 들고 있는 아이템을 큰 크기로 그린다. `require=0`. |
| `mixin/HandledScreenAccessor.java` (새 파일) | 드래그 분배 미리보기에 필요한 `heldButtonType`. |
| `mixin/RecipeBookWidgetAccessor.java`, `GhostRecipeAccessor.java`, `GhostCyclingItemMixin.java` (새 파일) | 조합법 고스트(재료 부족 표시)를 수정 구체 위치에 다시 그리기 위한 읽기 전용 접근. |
| `client/ItemIconTextures.java` | 반짝임 아이템도 고해상도로 그린다(`drawGlint`). 실패하면 반짝임만 끄고 아이콘은 유지한다. |
| `client/UiResources.java` | 새 PNG 4장을 한가할 때 미리 올린다(다른 패널과 같은 방식). |
| `resources/magiccodex.mixins.json` | 믹스인 6개 등록. |
| `resources/assets/magiccodex/textures/gui/inventory/*.png`, `asset_manifest.json` | 제공 PNG 4종. 아래 2장 참고. |
| `src/test/.../ChacaInventoryLayoutTest.java` (새 파일) | 13개 테스트. |
| `docs/ITEM_ICONS_HIRES.md` | 반짝임 처리 추가 기록. |

## 2. PNG 처리

- 제공된 ZIP의 SHA256을 manifest와 대조해 일치를 확인했다.
- 각 PNG를 manifest의 `visible_bounds`(실제 그림 영역)에 8px 여유를 두고 잘랐다. 그다음 4K에서 화면에 그려질 최대 크기의 약 2배까지만 Lanczos로 줄였다.

  | 파일 | 처리 후 크기 |
  | --- | --- |
  | orb | 582×640 |
  | crystal | 284×640 |
  | pedestal | 640×115 |
  | book | 320×321 |

  원본은 합계 4.8MB에 GPU 약 33MB였고, 처리 후 0.8MB가 됐다.
- 결과 `asset_manifest.json`에 원본 SHA256과 자른 영역을 남겼다. 테스트로 크기와 투명 모서리를 확인한다.
- 글씨, 아이템, 수량, 연결선, 하이라이트는 모두 런타임에 그린다. PNG에 합친 것은 없다.
- 그리기는 기존 `HudTextureCache` 방식을 그대로 쓴다: 프리멀티플라이드, 밉맵, 트릴리니어.

## 3. 화면 구성 (레퍼런스 좌표 1672×941)

- 큰 패널 없이 요소가 떠 있다. 배경은 남색 반투명 그라데이션(`0x84050A18` → `0xB4030611`)이다. 바닐라의 회색 어둡게 처리는 쓰지 않는다.
- **조합 입력 4칸 (구체):**
  - 좌상 = 슬롯1, 우상 = 2, 좌하 = 3, 우하 = 4. 바닐라 2×2 순서와 같다.
  - 3.7~4.7초 주기로 각각 다르게 ±4.5px 천천히 뜬다.
  - 아이템을 들고 있거나 드래그 중이면 0.25초 안에 멈춘다.
  - 클릭 판정은 그 프레임에 그린 위치 그대로다(같은 시간값 사용).
- **결과 (중앙 큰 구체):** 수정은 아이템 **뒤**에 그린다. 결과가 있으면 수정을 32%로 흐리게 하고 아이템 뒤에 어두운 원을 깔아 결과가 잘 보이게 한다.
- **조합법 책:** 클릭하면 바닐라 조합법 위젯이 열리고 닫힌다. 바닐라 초록 버튼은 제거했다.
- **보유 27칸 + 단축바 9칸:** 타원 받침 위에 아이템을 둔다.
  - 선택된 단축바 칸은 하늘색 타원으로 표시한다.
  - 마우스를 올리면 얇은 금색 타원이 나타나고 아이템이 3px 올라간다.
- **글씨:** 기존 `CodexTypography`의 `title`(마루부리) 글꼴로 "소지품", "조합", "조합법", 단축바 번호 1~9, 수량을 그린다. 수량은 바닐라처럼 1이면 표시하지 않는다.
- **조합법을 열었을 때:**
  - 보통 GUI 배율: 바닐라 책 오른쪽에 **조합 영역만** 크게 보여 준다. 보유칸은 책을 닫으면 다시 나온다.
    - 1080p 자동 배율(480×270)에서 조합 구체 아이템은 약 21 GUI px다.
    - 그래서 조합법을 누른 결과를 바로 보고 꺼낼 수 있다.
  - 바닐라 narrow 모드(GUI 폭 379 미만): 책만 보인다. 바닐라와 같고, 이때는 아이템을 던지지 않는다.
  - 책 토글 직후의 마우스 떼기는 바닐라처럼 무시한다. 들고 있던 아이템이 의도치 않게 던져지거나 놓이지 않게 하기 위해서다.
- **클릭으로 던지기:** 화면의 빈 구석(조합 영역과 보유칸 영역 밖)을 들고 있는 아이템으로 클릭하면 바닐라 패널 밖 클릭처럼 버린다. 슬롯 사이 틈은 "밖"이 아니다.

## 4. 아이템 선명도 (128px 아이콘)

- 저해상도로 줄였다가 다시 키우지 않는다. 아이템마다 GUI 행렬만 `크기/16`으로 키우고 `drawItem`을 호출한다.
- 그러면 기존 `ItemIconMixin` → `ItemIconTextures` 경로가 원본 128px 텍스처를 최종 화면 크기에서 한 번만 트릴리니어로 샘플링한다. 엘시 서버에서 UI PNG를 모드가 직접 그린 것과 같은 방식이다.
- 3D(블록) 아이템과 바닐라 16px 아이템은 바닐라 렌더러가 큰 크기로 그린다.
- 아이템 크기(레퍼런스 px)와 1080p GUI2(960×540) 기준 크기:

  | 위치 | 레퍼런스 크기 | GUI2 크기 |
  | --- | --- | --- |
  | 보유칸 | 70 | 약 39 GUI px(약 78 화면 px) |
  | 단축바 | 66 | |
  | 조합 | 72 | |
  | 결과 | 96 | |

- 수량·내구도 막대·쿨다운·드래그 분배 미리보기(노란 숫자 포함)는 바닐라 계산을 그대로 쓰고, 그리기만 새 위치에 한다.
- 이름·설명 색과 툴팁은 바닐라 `drawMouseoverTooltip` 그대로다(기존 `HandledItemTooltipMixin` 포함). z 400이라 모든 UI 위에 오고, 바닐라 위치 보정으로 화면 밖으로 나가지 않는다.

## 5. 마법 시전 키

- `CastingClient.gameplay()`가 `currentScreen == null`일 때만 시전하므로 인벤토리에서는 숫자키·마우스 엄지 버튼이 PASS된다.
- 숫자키 교환과 Q는 바닐라 `matchesKey` 경로라 영향이 없다.
- 이번에 바꾼 코드는 없다. 실게임에서 한 번 확인해 달라(6장).

## 6. 호스팅에서 할 일 / 확인할 것

```powershell
cd C:\Chacademi\build-workspace
git fetch origin
git merge --no-ff origin/claude/survival-inventory-20261012   # 진행 중인 퀘스트·키세팅·채팅 수정분과 충돌 시 아래 표 기준
.\magic-codex-fabric\gradlew.bat -p magic-codex-fabric test remapJar
```

- 충돌 가능성이 있는 파일:
  - `UiResources.java`: warm 목록에 4줄 추가.
  - `ItemIconTextures.java`: `draw()` 첫 줄과 `drawGlint` 추가.
  - `magiccodex.mixins.json`: 끝에 6개 추가.

  나머지는 새 파일이다.
- `mod_version`은 올리지 않았다. 배포 담당이 정한다(예: `-inventory.1`).
- 로그 확인
  - 믹스인 오류가 없어야 한다. 새 믹스인 중 `HandledScreenHooksMixin`만 `require=0`이고 나머지는 기본(1)이다.
  - `High-res item glint disabled`가 찍히면 반짝임 레이어 경로에 문제가 있는 것이다. 아이콘은 그대로 나온다.

실게임 확인 목록 (사용자 지침 7번 기준):

- [ ] GUI 배율 2/3/4/자동, 창 크기 1280×720·1920×1080·2560×1440·작은 창에서 확인할 것:
  - 슬롯 간격과 글씨
  - 툴팁(화면 가장자리 아이템)
  - 조합법 창
  - 클릭 좌표: 구체가 떠 있는 상태에서 가장자리를 클릭
- [ ] 좌/우 클릭, Shift 이동, 드래그 분배(좌·우), 더블클릭 모으기, 숫자키 1~9 교환, Q / Ctrl+Q 버리기, 빈 구석 클릭 버리기
- [ ] 조합: 2×2 배치 → 결과 꺼내기, Shift로 연속 꺼내기, 빠른 반복 클릭
  - 재료 소모와 결과 수량이 맞는지, 중복이나 유실이 없는지
- [ ] 조합 실패와 조합법 위젯
  - 재료가 없는 레시피를 누르면 구체에 흐린 고스트가 보여야 한다.
  - 재료가 있는 레시피를 누르면 구체가 채워지고 결과를 꺼낼 수 있어야 한다.
  - 책을 열고 닫을 때 들고 있던 아이템이 그대로 있어야 한다.
- [ ] 가득 찬 인벤토리에서 Shift로 결과 꺼내기 (서버가 남는 것을 바닥에 떨어뜨리는 바닐라 동작)
- [ ] 조합칸에 아이템을 둔 채 닫기 / 재접속: 아이템이 인벤토리로 돌아오는지 (바닐라 서버 동작)
- [ ] 크리에이티브 전환: 열린 상태에서 `/gamemode creative` → 크리에이티브 화면으로 넘어가고, 서바이벌로 돌아오면 새 화면
- [ ] 128px 아이템
  - 외곽선과 디테일이 매끈한지
  - 마법 부여 아이템의 반짝임이 아이콘 모양을 따라가는지 (사각형으로 번지면 보고)
  - 3D 블록 아이템, 바닐라 16px 아이템, 내구도 막대, 쿨다운(엔더진주 등)
- [ ] 인벤토리에서 마법 모드(Z) 켠 상태로 숫자키·엄지 버튼: 시전되지 않고 숫자키 교환만 되는지
- [ ] 첫 열기 지연: 처음 접속 직후 "화면을 준비하고 있어요"가 한 번 보일 수 있다(다른 MagicCodex 화면과 같은 동작)

## 7. 알려진 한계

- 상태 효과 목록이 없다(0장 5).
- Shift·F로 숨은 방어구·왼손 칸에 장착된다(0장 4).
- 반짝임은 부드러운 가장자리(알파 0.02 이상)까지 살짝 번질 수 있다.
- 조합법이 열린 동안에는 보유칸이 숨겨진다. 직접 재료를 끌어다 놓으려면 책을 닫는다.
- 레퍼런스와 달리 `소지품` 제목 양옆 장식은 HudMesh 선과 별로 그렸다. 별도 PNG는 없다.

## 8. 검증 방법 기록 (재현용)

호스팅이 아닌 Claude 작업 환경에서 했다. 사용자 PC의 `~/.gradle` Loom 캐시에서 읽기 전용으로 가져온 파일은 다음과 같다.

- `minecraft-merged-1.21.4-...yarn...build.8-v2.jar`
- fabric-api 모듈 8개 (tiny-remapper로 intermediary → named 리맵)
- 그 밖의 라이브러리와 junit 5.11.4

이 클래스패스로 `javac --release 21` 타입 검사와 JUnit Platform 실행을 했다. 산출 JAR은 만들지 않았다.
