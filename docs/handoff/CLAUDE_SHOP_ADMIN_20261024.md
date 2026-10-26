# Claude 2026-10-24 (not built): 상점 관리 — 인벤토리에서 등록 · 분류/순서 · 40개 제한 해제 · 파일 일괄 등록 · 피피 잡화점 195종

Branch `claude/shop-admin-inventory-20261024`, based on `review/integrated-release-20261023-task7` @ af1ed3e.
**Bridge (school/wild) and the client mod must ship together.** Both shop channels changed wire format.

## 1. What the owner asked for

- In the shop admin screen, products are registered by **picking a slot from my inventory**, not from the main hand.
- The owner said design does not matter for operator UI, so Claude edited the admin screen directly with the existing shared box/button style.
- The general-goods shop of NPC 피피 (`pipi`) gets 195 vanilla trade/collectible items, sorted by category.
- Prices follow the fishing-shop grade scale (fish base 80 / 400 / 2000 / 10000).
  - Farmable items (iron golem, piglin, crop, guardian, etc.) are **buy-only**: the shop sells them, players cannot sell them.
  - Exploration collectibles are **sell-only**: players can sell them, the shop does not.

## 2. Changes

### Protocol (`magic-codex-protocol`)

- `ShopAdminProtocol`
  - Magic bumped `0x53414431` → `0x53414432`. An old client gets no admin reply, so it fails cleanly instead of misparsing.
  - `Request` gained `category` (≤40), `sort` (0–99999), `slot` (-1 = main hand, 0–35 = inventory), and `slotItem` (item id of the picked slot).
  - `Product` gained `category` and `sort`.
- `ShopProtocol` (player shop): the response product count limit went from 40 to **256**.
  - The old client rejects responses with more than 40 products, so it must be updated together.

### Store (`ShopStore`)

- Two columns added with `ALTER ADD COLUMN` on the same pattern as `origin`:
  - `category VARCHAR(40) NOT NULL DEFAULT ''`
  - `sort_order INTEGER NOT NULL DEFAULT 0`
- Existing products become blank category and sort 0, so the current shop order is unchanged.
- Catalogue order is `ORDER BY sort_order, name, id` (was `name, id`).
- `adminAdd` and `adminEdit` gained 8-argument overloads. The old signatures stay and are still used by the tests and the `상품추가` command.
- New `importProducts(shop, title, portrait, entries)`, all in one transaction:
  - Creates the shop if missing; otherwise updates the title, and the portrait only when it is given.
  - A product with the same name only gets its price, category and sort updated. Its **item bytes are kept**.
  - New names are inserted.
  - Products missing from the file are **not deleted**.
  - Duplicate names in the file are rejected before any change.

### Bridge

- `ShopAdminBridge.picked`: on ADD with `slot ≥ 0`, it reads **the server's** `getInventory().getItem(slot)`.
  - Empty slot → refused.
  - Item type key different from `slotItem` → refused ("고른 칸의 아이템이 바뀌었어요").
  - The copy keeps all components/PDC, has amount 1, and nothing is consumed (same as before).
- `ShopAdminBridge` admin search also matches the category.
- `ShopBridge` (player shop list):
  - The 40-item cut is removed; only the byte budget remains.
  - The cost estimate changed from `preview+1000` to the real UTF-8 size of name, id and prices plus 64.
  - With 195 vanilla items this is about 40KB, under the 55.9KB budget.
- `/상점관리 목록 <상점>` now prints `[분류 순서]` in front of each product.
- New command **`/상점관리 가져오기 <파일>`** (`ShopImportFile`):
  - Reads `plugins/MagicCodexBridge/shops/<파일>.yml`.
  - The bundled `shops/pipi.yml` is extracted on first use and never overwritten.
  - Only vanilla item ids are accepted. Custom items are registered through the admin screen.
  - Sort is file order ×10, unless a `순서` value is given.
  - `없음` / `off` / blank means that direction is blocked.
  - Every bad line is reported (first 5 shown), and nothing is saved if any line is bad.

### Client (`ShopAdminScreen`, `ShopAdminClient`)

- The 「손 아이템 등록」 button is now **「인벤토리에서 등록」**.
  - It opens a picker with the same 36-slot layout as vanilla: 3 rows of storage, then the hotbar row.
  - Hovering shows the tooltip; ESC or 취소 closes the picker.
- After a pick:
  - The name is filled with the localized item name.
  - The item icon and the slot number are shown next to the title.
  - Category and sort keep their previous values, so one category can be entered in a row.
- The right panel gained **분류** and **순서** fields.
- List rows show `분류 · 순서 · 구매 · 판매`.
- The confirm dialog shows category and sort.
- The pick is kept through search and paging; it is cleared when the shop revision changes, e.g. after a save.

## 3. 피피 잡화점 data

`magic-codex-paper/src/main/resources/shops/pipi.yml` holds 195 products in 8 categories, in this order:

| Category | Products |
| --- | --- |
| 광물 | 18 |
| 나무 | 27 |
| 꽃 | 21 |
| 농산물 | 22 |
| 자연 블록 | 12 |
| 바다 | 13 |
| 몹 전리품 | 18 |
| 탐험 수집품 | 64 |

- The shop title is `피피의 잡화점` and the portrait is left blank.
  - **The 피피 portrait file name is not known yet.** After import, run `/상점관리 초상 pipi <name>`. The file is in resource pack chacademia_npc at `textures/gui/dialogue/<name>.png`.
- Material ids use 1.21.4 names:
  - `pale_*`, `open_eyeblossom`, `closed_eyeblossom`
  - `turtle_scute`, `armadillo_scute`, `breeze_rod`, `heavy_core`, `trial_key`
  - the `flow`, `guster` and `scrape` sherds, the `bolt` and `flow` trims
  - `music_disc_creator`, `music_disc_creator_music_box`, `music_disc_precipice`
- If Paper rejects any of them, the import names the line.

## 4. Deploy order

1. Build and deploy the MagicCodexBridge JAR (school and wild) and the MagicCodex client JAR together, plus the launcher.
2. When the Bridge starts, the two columns are added automatically to both the shared MariaDB and SQLite. The DB user needs ALTER permission.
   - This is the same requirement as the earlier `origin` column. If it is missing, Bridge fails to start the shop module.
3. Run these in game as OP:
   - `/상점관리 가져오기 pipi`
   - `/상점관리 초상 pipi <피피 초상>`
   - `/상점관리 NPC연결 pipi <피피 NPC>`
4. Check `/상점관리 목록 pipi` and the player shop window (all 195 items scroll, sorted by category).

## 5. Not done / caution

- **The player shop window has no category tabs yet.** Items are only grouped by order. Tabs would change the approved shop UI, so they wait for a reference image from GPT. Category data already exists on the server.
  - `ShopProtocol.Product` does not carry category yet; that needs a protocol field when tabs are added.
- Not built or tested with Gradle. The Claude workspace cannot reach Maven or Paper repos.
  - `ShopAdminProtocol`, `ShopProtocol`, `ShopStore` and the database module compiled with plain `javac`.
  - Protocol round-trip and slot/sort bounds were checked by hand.
  - Tests were added for the new behaviour:
    - `ShopAdminProtocolTest.inventorySlotCategoryAndSortRoundTripAndBounds`
    - `ShopAdminStoreTest.categoryAndSortOrderTheCatalogueAndEditKeepsItem`
    - `ShopAdminStoreTest.importCreatesShopUpdatesSameNameAndIsAllOrNothing`
  - The Paper and Fabric files (`ShopImportFile`, `ShopAdminBridge`, `ShopBridge`, `ShopAdminScreen`) need a real build.
- The import matches existing products **by name**. Renaming a product in the admin screen and then re-importing adds a second copy.
- Iron and gold stay sellable at 40 / 80 as the owner approved. If farms make them a problem, switch `판매` to `없음` in the yml and import again.

## 6. 2026-10-24 follow-up: 피피 잡화점 nameplate (portrait · owner · role)

- `shops/pipi.yml` sets three shop fields:
  - `초상: pipi-neutral` (the `chacademia_npc` pack's `textures/gui/dialogue/pipi-neutral.png`)
  - `주인: 피피`
  - `역할: 잡화상인`
- Products and prices are unchanged: 195 items, fish-grade balance.
- **New shop fields `owner` and `role`**:
  - `ShopStore` adds the `codex_shops` columns `owner VARCHAR(40)` and `role VARCHAR(40)` (default `''`), with the same `ALTER ADD COLUMN` pattern as before.
  - `importProducts(..., owner, role, ...)` saves them.
  - `ShopBridge` sends:
    - the name: the NPC's name when the shop is opened from an NPC, otherwise the shop's `owner`
    - `role`
  - `ShopProtocol.Response` gained a trailing `role` field.
    - Decoding is lenient: an older server's packet without it reads as `""`.
    - The client shows `role`, or 「상점 주인」 when it is blank.
- **Deploy**: Bridge (school/wild) and the client mod, together with the earlier shop changes on this branch.
- **Apply in game (OP)**:
  1. `/상점관리 가져오기 pipi` creates the shop with the portrait, owner, role and 195 products.
  2. `/상점관리 NPC연결 pipi <피피 NPC>`
  3. Open it from 피피 and check that the left illustration is 피피 and the label reads 「잡화상인 / 피피」.
  - If `plugins/MagicCodexBridge/shops/pipi.yml` already exists on a server from an earlier build, replace it with the jar's version first. The bundled file is only extracted when it is missing.
- Preview with the real illustration: `docs/handoff/img/shop/p_v3_pipi.jpg`. The product icons there are placeholders.
