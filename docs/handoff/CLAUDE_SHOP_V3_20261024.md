# Claude 2026-10-24 (not built): 일반 NPC 상점 v3 — 4×3 카드 · 페이지 · 정렬 · 하단 상세바

Branch `claude/shop-admin-inventory-20261024`, the commit after b01c784. The shop-admin work is in [CLAUDE_SHOP_ADMIN_20261024.md](CLAUDE_SHOP_ADMIN_20261024.md).
**Client mod only.** The player-shop protocol limit raised in the admin commit (40 → 256 products) still needs the Bridge on the same deploy.

## 1. What the owner asked for

Rebuild the general shop window to the reference in the owner's ZIP "일반 NPC 상점 12칸 UI v2". The ZIP holds `reference.png` (the approved mockup), `shop_frame.png`, `button_primary.png` and a README.

The README rules this follows:

- Drawing order: game screen → light blur → **navy backing in code** (alpha 0.8, `0xCC0B1828`) → `shop_frame.png` at alpha 1 → products, text and buttons at alpha 1.
  - No overall opacity is applied to the whole UI.
- Layout:
  - Catalogue of **4 columns × 3 rows**.
  - Search, the 전체 / 구매 / 판매 filter and **sort** across the top.
  - Description, quantity, total and buy/sell across the bottom.
- Card borders, the selection line, arrows, the search field and secondary buttons are **runtime geometry**.
  - Only the frame and the gold trade button are textures.
- The NPC illustration is separate from the item area.
  - It is drawn before the backing, and its width is capped so its right edge stops before the first card column.
- The old opaque panel is **not loaded** with the new pack.
  - `ShopView` no longer touches `v2/shop_frame`, rows, tabs, the footer panel, balance, scrollbar or navy/gold button PNGs.
  - From v2 only `coin.png` and `search.png` (icons) are still used.
  - The v2 files are left in place; delete them once the owner confirms.

## 2. Files

- `textures/gui/shop/v3/shop_frame.png` and `button_primary.png` are copied byte-for-byte from the ZIP.
  - SHA-256 `5828d718…f24b6` and `8977c4b3…7f8dec`.
- `ShopView` is rewritten for the same 1672×941 reference space.
  - The frame is drawn at one uniform scale `K = 0.9`; its outer line sits at x421..1650, y41..900.
  - Lines measured in the PNG: outer x37..1402 / y62..1017, ornament y140, header line y176, footer line y887.
  - Header: compass emblem, title, coin, 보유 골드 and amount.
  - Search row:
    - search field
    - three tabs, gold when selected
    - divider
    - sort dropdown: 기본순 / 가격 낮은순 / 가격 높은순 / 이름순
  - Cards: 4×3, each with icon, name and price.
    - Sell-only items show a cyan 「판매」 tag and 「보유 N」.
    - The selected card gets a gold 2.5px line and an inner glow.
  - Pager: ‹ n / m ›. The mouse wheel turns pages.
  - Footer:
    - item icon (hover shows the tooltip), name and first lore line
    - 구매 and 판매 prices
    - quantity stepper and 합계
    - the **판매 / 구매 switch** (navy; in the 전체 tab it switches direction when the product trades both ways)
    - `button_primary.png` as 구매하기 / 판매하기, with tint for hover, pressed and disabled
  - Bottom-left: role and NPC name over a soft dark band, with a small compass and a thin line, as in the reference.
    - The old `nameplate.png` is not used.
  - The close button is removed because the reference has none. ESC closes.
- `ShopScreen`:
  - `scroll` and drag are replaced by `page`.
  - Adds sort and the open state of the sort list. The open list takes the next click; ESC closes it.
  - The footer switch is added.
  - The card-row 구매/판매 buttons are gone. Trading only goes through the footer `trade()`, the same single path as before.
  - Protocol, price checks, quantity limits and sounds are unchanged.
- `HudMesh.diamond(rx, ry)`: a diamond with separate half-width and half-height, used for star points.
- `ShopViewTest` is rewritten for the new geometry:
  - footer boxes don't overlap and stay inside the footer band
  - header and grid stay inside the frame
  - the NPC never reaches the cards
  - card, icon, page and arrow hits
  - the open sort list takes all clicks
  - card price direction
  - The test ran green with a minimal JUnit stub.

## 3. Preview

`tools/shop-preview/Preview.java` draws `ShopView` with Java2D, using the real fonts and PNGs. Args: `<assets dir> <font dir> <icon dir> <portrait png> <out png> [open]`.

- Icons in the preview are crops of `reference.png`.
- `docs/handoff/img/shop/p_v3.jpg` is the preview.
- `compare_v3.jpg` puts the reference on top and the preview below.

## 4. Not done / check in game

- Not built with Gradle. Only `ShopView` and the test compiled with plain `javac`. `ShopScreen` and `HudMesh` need the real build.
- In game, check:
  - the backing alpha against the real blurred world (README: try 0.75–0.85)
  - frame sharpness at GUI scale 2, 3 and 4
  - item icons at 84px
  - the hover tooltip
  - a page with fewer than 12 items
  - 200+ products (피피 잡화점)
- Categories are still not shown to players. The sort list's 기본순 keeps the admin order, which is grouped by category.
  - Adding categories to the dropdown later needs `category` in `ShopProtocol.Product`.

## 5. 2nd pass (same day) — owner: "레퍼런스 그대로만, 판단하지 말 것"

The first pass added its own choices (smaller NPC, dark band behind the name, glow on the selected card, inner tab line, wrong text sizes, bright card borders). They are all removed. `ShopView` now copies `reference.png` measurement by measurement:

- **Positions**: every box, line and text centre was measured from reference.png pixels.
  - Panel lines: x488.5..1650, top y21.5, footer line y799, bottom y914.5.
  - Footer bar starts at x416.5.
  - Cards: x517 + 279n, y199 + 191.5n, 268×181.
  - Tabs: x1062/1186/1307. Sort box: x1481. Stepper: x958..1118. Switch: x1337. Buy button: x1452..1623 × 826..890.
- **Text sizes and faces**: each size was solved from the text's pixel width in the mockup.
  - Maruburi: title 41, gold amount 28.2, NPC name 37.8.
  - Pretendard bold: card name 21.7, card price 17.3, footer name 28.7, prices 17.5, total 25.5, buy label 23.4, tabs 17.9.
  - Pretendard medium / regular: the rest.
- **Colours**: all sampled from the mockup.
  - Panel backing is `0xE00E1D31`, solved from inside vs. outside pixels (alpha ≈ 0.88).
  - Footer bar is near opaque (`0xF20C1C30`), so the illustration is hidden under it as in the mockup.
  - Card borders are 1px `#242F40`; the selected card is 2.5px `#F2D184`.
  - Fields and tabs use `#6A5F57` / `#615C5C` lines and small cut corners.
- **Illustration**: full screen height (942) at x = -3, as SIFT matching of `elena-neutral.png` against the mockup gives (scale 0.6135).
  - Drawn after the cards, so it overlaps the panel's left edge, and before the footer bar, as in the mockup.
- **Mockup decorations, drawn as geometry**:
  - role and name with the star and the underline
  - the thin gold line at x22 with stars at its two ends and the short bottom line
  - the four-point star before the title
- **Frame**: `shop_frame.png` is stretched onto the mockup's panel lines.
  - x scale 0.851; y scale 0.942 above the footer line and 0.892 below it.
  - Frame rows below v880 (without the left 120 source px) are drawn again over the footer fill, and the footer bar's left end (x416.5) is drawn as 3.5px gold lines.
  - The lines inside `shop_frame.png` are thicker and brighter than the mockup's. That is the asset itself and is left unchanged.
- **Sort labels**: the closed box shows the chosen sort. The labels are 기본순 / 가격순 / 가격 높은순 / 이름순 (the mockup shows 「가격순」).

The overlay check against reference.png is in `docs/handoff/img/shop/compare_v3.jpg`. The text, boxes and lines line up within a few px.

## 6. 3rd pass — owner: no code-drawn lines or ornaments beyond the supplied PNGs

- **Removed** every code-drawn ornament:
  - the star before the title
  - the star and underline by the NPC name
  - the thin left-edge line and its stars
  - the gold lines that extended the footer bar to x416.5
  - `HudMesh.diamond` and `Canvas.diamond`
- **Footer** stays inside `shop_frame.png`'s own footer lines (x488.5..1650).
  - The fill sits under the frame's footer rows (the frame is drawn again over it).
  - The item icon and name moved inside the frame: icon centre x538, text x590, max width 140.
  - Everything else in the footer keeps the measured positions.
- **What remains as geometry** is only what the bundle README lists:
  - card borders and the selection line
  - pager and sort arrows
  - search and sort fields
  - tabs, stepper, the 판매/구매 switch
  - thin dividers

## 7. 4th pass — owner: remove the thick yellow lines; keep the dark band behind the name

- **`shop_frame.png` is not drawn at all.**
  - Its thick yellow border, the ornament line under the title and the header line do not match the approved mockup's thin lines. The owner asked to remove them.
  - The panel and the footer are now only the navy fills: panel `0xE00E1D31`, footer `0xF20C1C30`.
  - The PNG stays in the jar (v3/) but nothing loads it. If thin lines are wanted later, they need a new frame PNG that matches the mockup.
- **The soft dark band behind the role / name is back**, as in the first pass and the mockup's darker lower-left:
  - six 6px fade steps from y760, then `0xA808111E` from y796 down
  - it spans x0..488.5

## 8. 5th pass — owner: the mockup's thin gold lines must be there, exactly as in the mockup

`shop_frame.png` draws thick, bright lines that the mockup does not have. Code-drawn lines did not look like the mockup either. So the mockup's own lines and stars are lifted out of `reference.png` into two overlays:

- **`textures/gui/shop/v3/shop_deco_panel.png`** (1672×941, drawn right after the panel fill, before the cards and the illustration):
  - thin panel border (top, right, upper left)
  - the two top corner ornaments
  - the four-point star before the title
  - the small dotted ornament line and the faint header line
- **`textures/gui/shop/v3/shop_deco_front.png`** (1672×941, drawn after the illustration and the footer fill):
  - footer bar border (x416.5..1650 × 799..914.5) with its right star and corners
  - the thin left-edge line with its two stars and the short bottom line
  - the star, the underline and the small star by the NPC name
- **How the overlays are made** (`tools/shop-preview/extract_deco.py`):
  - Each piece is masked to its own rectangle.
  - The gold pixels are inpainted away to get the background, and the line is unmixed as colour + alpha over that background.
  - Straight runs that cross the illustration are copied from one clean sample, so no gold trim from the character leaks in.
  - Both overlays are about 50 KB.
- **The footer bar is back at x416.5** (the mockup's box). The footer icon and name are back at the measured spots: icon centre x468, text x543, width 186.
- **The dark band** behind the role / name is kept. Its fade is now 24 steps of 3px, so it has no visible bands.
- Everything else is unchanged, including the remaining runtime geometry from the README (cards, fields, tabs, stepper, switch, dividers, arrows).

## 9. 6th pass — the shade behind the name covers only the name, as in the mockup

- The full-width dark band is replaced by **`textures/gui/shop/v3/shop_name_shade.png`**.
  - It is a 400×150 soft oval: colour `#080E18`, alpha up to 0.62, fading out on every side (smoothstep from 35% of the radius).
  - It is centred on the role / name: x NAME_CX ± 200, y around the midpoint of ROLE_CY and NAME_CY.
- Nothing else changed.
