# 고해상도 아이템: 손·3인칭·바닥·액자도 매끈하게 (2026-10-23, 미빌드)

- 브랜치 `claude/hires-item-world-20261023` (← `claude/npc-buttons-20261023` @ b97de93). **MagicCodex 클라이언트 모드만** 바뀜. 서버·프로토콜 변경 없음.
- 증상(오너 스크린샷): 128px 아이콘을 손에 들면 바닐라 nearest 확대로 픽셀 계단이 보이고, 3인칭·바닥에서는 `item/generated` 의 픽셀별 돌출 옆면이 테두리에 털처럼 붙음.

## 바뀐 것

- `ItemModelManagerMixin`: `ItemModelManager.update(...)` 두 오버로드 RETURN 에서 렌더 상태에 고해상도 모델 id 를 붙임 (`HiResItemState` 덕 인터페이스). GUI 모드·반짝임(마법 부여)·조건 밖 아이템은 null = 바닐라.
- `ItemRenderStateMixin`: `ItemRenderState.render` HEAD 에서 id 가 있으면 `ItemIconTextures.renderWorld` 로 그리고 바닐라 취소. `clear` 에서 id 초기화.
- `ItemIconTextures.renderWorld`: 모델 자체 display 변환(`getTransformation().apply(leftHand, …)`) → 아이템 평면(z=8/16)에 앞·뒤 사각형 2장(간격 1/1000 블록). 레이어 `RenderLayer.getItemEntityTranslucentCull(월드용 텍스처)` = 바닐라 아이템과 같은 조명·오버레이·외곽선.
- 월드용 텍스처를 아이콘마다 하나 더 올림: 알파 미리곱 없이(바닐라 엔티티 셰이더용), 투명 텍셀 RGB 는 가장 가까운 보이는 색으로 채움(`bleed`) → 확대·밉맵 때 검은 테두리 없음.
- `CodexTextureFilterMixin`: `magiccodex:runtime_item_icon/*` 는 바닐라 레이어에서도 밉맵 사용(멀리 있는 바닥 아이템 반짝임 방지). 블러는 기존 규칙(magiccodex 네임스페이스 = 부드럽게) 그대로.
- 정사각형이 아닌 텍스처(예: 티켓 256x172)는 GUI·월드 모두 비율 유지, 16x16 칸 가운데 정렬 (이전에는 GUI 에서 늘어남).
- 바닐라 16px 아이템, 32px 이하 텍스처, 조건 밖 모델(엘리먼트·틴트·다층·애니메이션)은 그대로.

## 2차 — 인벤토리에서도 깨지던 아이템 (같은 날)

- 오너 스크린샷: 핫바 칸의 아티팩트 열쇠·조각이 바닐라 nearest 축소처럼 깨짐 → GUI 매끈 렌더가 **적용 안 된** 상태로 보임. 기존 GUI 경로는 `items/<id>.json` → `item/generated` 체인만 따라가서, ItemsAdder 식 정의(`range_dispatch`·`custom_model_data`, 다른 parent 등)는 조건 밖이라 바닐라로 남는다.
- 바뀐 것: 조건을 JSON 대신 **바닐라가 실제로 고른 모델**로도 판정. `ItemModelManager.update` 직후 렌더 상태가 레이어 1개·특수 렌더러 없음·모든 쿼드가 같은 스프라이트·틴트 없음·모든 꼭짓점 z 가 7.4/16~8.6/16(= item/generated 평면) 이고 스프라이트가 33px 이상이면, 그 스프라이트의 원본 PNG(`textures/<경로>.png`)로 매끈 카드를 그림. **GUI 포함 모든 모드.** 판정은 베이크 모델별로 캐시(리로드 때 비움).
- 키 형식 `magiccodex-sprite:<ns>/<경로>` → `ItemIconTextures.decode` 가 JSON 없이 PNG 를 바로 읽음. `.png.mcmeta`(애니메이션)는 그대로 제외.
- 3D 엘리먼트 모델(가구 등)은 z 검사에서 빠져 바닐라 그대로. 32px 이하 텍스처도 그대로.
- 새 `ItemLayerAccessor`(LayerRenderState `model`·`specialModelType` 접근자, yarn 이름 대조) — 접근자는 require=0 이 안 되므로 이름이 틀리면 모드 로드 실패. 빌드 때 꼭 확인.
- 실게임 로그 `High-res item icons active (magiccodex-sprite:...)` 가 뜨면 새 경로로 그려지는 것.

## 확인 못 함 / 볼 것

- **Gradle 컴파일 전** (작성 환경에서 Maven 차단). API 이름은 yarn 1.21.4 매핑과 대조: `ItemModelManager.update` 6·7인자, `ItemRenderState.render/clear/getTransformation/leftHand/layerCount`, `Transformation.apply(ZLMatrixStack)`, `RenderLayer.getItemEntityTranslucentCull`, `VertexConsumer.vertex(Matrix4f,FFF)/color(I)/texture/overlay(I)/light(I)/normal(Entry,FFF)`.
- `@Shadow public abstract Transformation getTransformation()` 접근자가 바닐라와 다르면 믹스인 오류 → 고칠 것. 주입은 전부 `require = 0`.
- 호스팅: `.\magic-codex-fabric\gradlew.bat -p magic-codex-fabric test remapJar` (새 테스트 `ItemIconBleedTest`, `ItemIconSpriteKeyTest`, `CodexTextureFilterTest.runtimeItemIconsAlwaysUseTheirMipChain`).
- 실게임: 1인칭 손(오른손·왼손), F5 3인칭, 바닥에 버린 아이템(가까이·멀리), 아이템 액자, 머리에 쓴 아이템, 마법 부여된 아이템(바닐라로 남아야 함), 인벤토리 티켓 비율.
- 리소스팩 아이콘을 256px 로 바꿔도 코드 변경 없음 (`MAX_SIZE` 1024).
