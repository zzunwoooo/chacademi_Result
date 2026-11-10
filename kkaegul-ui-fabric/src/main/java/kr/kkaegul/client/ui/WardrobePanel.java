package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 옷장 (서버 open/update "wardrobe": owned · equipped · counts, 정의는 Cosmetics).
 * 디자이너 그림 wardrobe/* (1600x1000 판) 좌표 그대로 — 아래 숫자는 전부 PNG 픽셀에서 잰 값:
 *  판 왼쪽 분홍 칸 x 130~560 y 244~850 (받침대 윗면 가운데 (346, 792)) = 내 캐릭터 3D (끌어서 돌리기)
 *  오른쪽 위 긴 칸 안쪽 x 590~1472 y 248~316 = 부위 탭 5개 / 오른쪽 큰 칸 안쪽 x 590~1472 y 336~856 = 치장품 격자(4×3) + 고른 치장품 설명·버튼
 *  리본 글씨 칸 가운데 (800, 171)
 * 칸 누르기: 가진 것 = 장착/해제 (wardrobe.equip id / wardrobe.unequip slot), 없는 것 = 설명만.
 * 그림 id 는 W_* 상수에 모아 둠 (그림이 없으면 F_* 상점 그림으로 — 그땐 배치가 어긋날 수 있음).
 */
public final class WardrobePanel implements Panel {
    // ───── 디자이너 그림 (textures/ui/ 아래) ─────
    static final String W_FRAME = "wardrobe/wardrobe_frame", F_FRAME = "shop/window_frame";
    static final String W_TAB = "wardrobe/tab_normal", F_TAB = "shop/filter_default";
    static final String W_TAB_SEL = "wardrobe/tab_selected", F_TAB_SEL = "shop/filter_selected";
    static final String W_SLOT_ICON = "wardrobe/slot_";               // + head/face/back/offhand/balloon
    static final String W_ITEM = "wardrobe/item_slot_normal", F_ITEM = "shop/item_default";
    static final String W_ITEM_HOVER = "wardrobe/item_slot_hover", F_ITEM_HOVER = "shop/item_hover";
    static final String W_ITEM_EQUIPPED = "wardrobe/item_slot_equipped", F_ITEM_EQUIPPED = "shop/item_selected";
    static final String W_ITEM_LOCKED = "wardrobe/item_slot_locked";
    static final String W_RARITY = "wardrobe/rarity_";                // + common/uncommon/rare/legendary — 칸 위에 겹치는 테두리 (같은 220x260 캔버스)
    static final String W_EQUIP = "wardrobe/btn_equip_", W_UNEQUIP = "wardrobe/btn_unequip_";   // + normal/hover (360x110)

    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final float DW = 1600, DH = 1000;
    // 탭: 그림 240x96, 몸통 x 6~233 y 24~70 (높이 46) → 몸통 164x48 로, 양끝 48px(알약 둥근 끝)은 늘리지 않음
    private static final float TAB_W = 164, TAB_H = 48, TAB_X = 605, TAB_Y = 258, TAB_STEP = 172;
    // 칸: 그림 220x260, 몸통 x 22~197 y 25~237, 위 칸 가운데 (110, 100) · 이름표 y 202~229 가운데 (110, 215.5) 폭 150, 체크 x 168~196 y 22~50
    private static final float CS = .74f;                                   // 그림 → 화면 배율
    private static final int COLS = 4, ROWS = 3;
    private static final float GRID_X = 606, GRID_Y = 347, STEP_X = 142, STEP_Y = 170;
    // 고른 치장품 (큰 칸 오른쪽 x 1185~1462)
    private static final float DX = 1192, DCX = 1322, DW_ = 260;
    // 캐릭터 (받침대 윗면 가운데에 발)
    private static final float PV_X = 346, PV_FEET = 792, PV_H = 440;

    private final Host host;
    private final long opened;
    private Set<String> owned = new HashSet<>();
    private Map<String, String> equipped = new HashMap<>();
    private long ownedCount, totalCount;
    private int tab;
    private int scrollRow;
    private String selected;
    private float yaw = -25, dragYaw;
    private float dragX = Float.NaN;
    private record R(String key, float x, float y, float w, float h) {}
    private final List<R> hits = new ArrayList<>();
    private float k = 1, ox, oy;

    public WardrobePanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.opened = now;
        apply(data);
        for (int i = 0; i < Cosmetics.SLOTS.length; i++) if (equipped.containsKey(Cosmetics.SLOTS[i])) { tab = i; break; }
    }

    @Override public String screen() { return "wardrobe"; }

    @Override public void update(JsonObject d, long now) { apply(d); }

    private void apply(JsonObject d) {
        if (d == null) return;
        if (d.has("owned")) owned = new HashSet<>(J.strs(d, "owned"));
        if (d.has("equipped")) {
            Map<String, String> m = new HashMap<>();
            for (var e : J.objOrEmpty(d, "equipped").entrySet())
                if (e.getValue().isJsonPrimitive() && !e.getValue().getAsString().isEmpty()) m.put(e.getKey(), e.getValue().getAsString());
            equipped = m;
        }
        JsonObject c = J.objOrEmpty(d, "counts");
        if (c.has("owned")) ownedCount = J.num(c, "owned");
        if (c.has("total")) totalCount = J.num(c, "total");
    }

    private String slot() { return Cosmetics.SLOTS[tab]; }

    private boolean isEquipped(Cosmetics.Def d) { return d != null && d.id().equals(equipped.get(d.slot())); }

    // ───────── 그리기 ─────────
    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x77000000);
        float pop = .94f + .06f * Anim.outBack(Anim.t(now, opened, 280));
        k = Math.min(W * .94f / DW, H * .98f / DH) * pop;
        ox = W / 2 - 800 * k;
        oy = H / 2 - 500 * k;
        float rawX = mx;
        mx = (mx - ox) / k;
        my = (my - oy) / k;
        if (!Float.isNaN(dragX)) yaw = dragYaw + (rawX - dragX) / k * .6f;

        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        c.image(c.pick(W_FRAME, F_FRAME), 0, 0, 1600, 1000, 0xFFFFFFFF);
        c.textFit("옷장", 800, 171, 40, 300, Theme.INK, Canvas.CENTER, TXT);
        // 닫기: 판 위 테두리 띠(y 164~236)의 빈 곳 — 오른쪽 위 작은 클로버(x 1345~) 왼쪽
        boolean hClose = Anim.in(mx, my, 1276, 172, 56, 56);
        c.image("shop/close", 1284, 180, 40, 40, hClose ? 0xFFFFB9A8 : 0xFFFFFFFF);
        hits.add(new R("close", 1276, 172, 56, 56));

        left(c);
        List<Cosmetics.Def> list = Cosmetics.bySlot(slot());
        tabs(c, mx, my);
        grid(c, list, mx, my);
        detail(c, now, list, mx, my);
        c.pop();
    }

    /** 왼쪽 분홍 칸: 모은 개수 · 안내 · 받침대 위 캐릭터 */
    private void left(Canvas c) {
        long own = ownedCount > 0 ? ownedCount : owned.size(), total = totalCount > 0 ? totalCount : Cosmetics.total();
        c.textFit("모은 치장품 §2" + Fmt.num(own) + " §r/ " + Fmt.num(total), 150, 274, 22, 250, Theme.INK, Canvas.LEFT, TXT);
        c.textFit("끌어서 돌려 보기", 542, 274, 18, 140, Theme.MUTED, Canvas.RIGHT, TXT);
        if (!c.entity(PV_X, PV_FEET - PV_H / 2, PV_H, yaw))
            c.imageFit("shop/frog_pouch", PV_X, PV_FEET - 150, 300, 0xFFFFFFFF);   // 미리보기 도구 / 캐릭터를 못 그릴 때
        hits.add(new R("rotate", 130, 300, 430, 550));
    }

    /** 오른쪽 위 긴 칸: 부위 탭 5개 */
    private void tabs(Canvas c, float mx, float my) {
        String tabImg = c.pick(W_TAB, F_TAB), tabSel = c.pick(W_TAB_SEL, F_TAB_SEL);
        for (int i = 0; i < Cosmetics.SLOTS.length; i++) {
            float tx = TAB_X + i * TAB_STEP;
            boolean on = i == tab, hov = !on && Anim.in(mx, my, tx, TAB_Y, TAB_W, TAB_H);
            tabArt(c, on ? tabSel : tabImg, tx, TAB_Y, hov ? 0xFFF2FFE6 : 0xFFFFFFFF);
            String ic = W_SLOT_ICON + Cosmetics.SLOTS[i];
            float cy = TAB_Y + TAB_H / 2, textX = tx + TAB_W / 2;
            if (c.size(ic) != null) { c.imageFit(ic, tx + 30, cy, 40, 0xFFFFFFFF); textX = tx + 100; }
            c.textFit(Cosmetics.SLOT_NAMES[i], textX, cy, 22, 100, on ? 0xFF2F5A1C : 0xFF6B5A4C, Canvas.CENTER, TXT);
            hits.add(new R("tab:" + i, tx, TAB_Y, TAB_W, TAB_H));
        }
    }

    /** 탭 그림: 몸통(6~233, 24~70)을 (x,y,TAB_W,TAB_H) 에, 양끝 48px 은 비율 그대로·가운데만 늘림, 위 장식(클로버)은 여백으로 위에 */
    private static void tabArt(Canvas c, String id, float x, float y, int argb) {
        int[] s = c.size(id);
        if (s == null || s[0] != 240) { c.image(id, x, y, TAB_W, TAB_H, argb); return; }
        float f = TAB_H / 46f, top = y - 24 * f, hh = 96 * f;
        float x0 = x - 6 * f, x1 = x + TAB_W + 7 * f, end = 48 * f;
        c.imagePart(id, x0, top, end, hh, 0, 0, .2f, 1, argb);
        c.imagePart(id, x0 + end, top, x1 - x0 - 2 * end, hh, .2f, 0, .8f, 1, argb);
        c.imagePart(id, x1 - end, top, end, hh, .8f, 0, 1, 1, argb);
    }

    /** 큰 칸 왼쪽: 치장품 칸 격자 */
    private void grid(Canvas c, List<Cosmetics.Def> list, float mx, float my) {
        int rows = (list.size() + COLS - 1) / COLS;
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, rows - ROWS)));
        if (list.isEmpty()) c.text("아직 이 부위 치장품이 없어요", 884, 596, 24, Theme.MUTED, Canvas.CENTER, TXT);
        for (int r = 0; r < ROWS; r++)
            for (int col = 0; col < COLS; col++) {
                int i = (scrollRow + r) * COLS + col;
                if (i >= list.size()) continue;
                card(c, list.get(i), GRID_X + col * STEP_X, GRID_Y + r * STEP_Y, mx, my);
            }
        if (rows > ROWS) { // 스크롤 막대 (격자 오른쪽 빈 곳)
            float track = (ROWS - 1) * STEP_Y + 213 * CS;
            float th = track * ROWS / rows, ty = GRID_Y + (track - th) * scrollRow / (rows - ROWS);
            c.fill(1170, GRID_Y, 6, track, 3, 0x22000000);
            c.fill(1170, ty, 6, th, 3, 0xFF9CCB6C);
        }
    }

    /** 칸 하나: (bx,by) = 몸통 왼쪽 위. 그림은 캔버스 통째로 (여백은 바깥으로) */
    private void card(Canvas c, Cosmetics.Def d, float bx, float by, float mx, float my) {
        float x = bx - 22 * CS, y = by - 25 * CS, w = 220 * CS, h = 260 * CS;
        boolean own = owned.contains(d.id()), eq = isEquipped(d), sel = d.id().equals(selected);
        boolean hov = Anim.in(mx, my, bx, by, 176 * CS, 213 * CS);
        String img;
        int tint = 0xFFFFFFFF;
        if (!own) {
            img = c.pick(W_ITEM_LOCKED, null);
            if (img == null) { img = c.pick(W_ITEM, F_ITEM); tint = 0xFFC9C4BC; }
        } else if (eq) img = c.pick(W_ITEM_EQUIPPED, F_ITEM_EQUIPPED);
        else img = hov || sel ? c.pick(W_ITEM_HOVER, F_ITEM_HOVER) : c.pick(W_ITEM, F_ITEM);
        c.image(img, x, y, w, h, tint);
        // 등급 테두리 (칸과 같은 캔버스) → 장착 칸은 오른쪽 위 체크가 테두리에 덮이지 않게 그 부분만 다시
        String rb = W_RARITY + d.rarity();
        if (c.size(rb) != null) {
            c.image(rb, x, y, w, h, 0xFFFFFFFF);
            if (eq && img.equals(W_ITEM_EQUIPPED))
                c.imagePart(img, x + 168 * CS, y + 22 * CS, 28 * CS, 28 * CS, 168 / 220f, 22 / 260f, 196 / 220f, 50 / 260f, 0xFFFFFFFF);
        }
        // 아이템: 위 칸 가운데 (110, 100), 잠김 칸의 자물쇠(y 171~191)·이름표와 안 겹치게 96px
        float lift = hov && own ? -2 : 0, isz = 96 * CS;
        if (!c.item(d.icon(), x + 110 * CS - isz / 2, y + 100 * CS - isz / 2 + lift, isz))
            c.imageFit("shop/frog_sheepish", x + 110 * CS, y + 100 * CS, isz, own ? 0xFFFFFFFF : 0x99FFFFFF);
        c.textFit(d.name(), x + 110 * CS, y + 215.5f * CS, 17, 140 * CS, own ? Theme.INK : 0xFFE8E2D8, Canvas.CENTER, own ? TXT : Canvas.BOLD);
        hits.add(new R("item:" + d.id(), bx, by, 176 * CS, 213 * CS));
    }

    private Cosmetics.Def shown(List<Cosmetics.Def> list) {
        Cosmetics.Def d = Cosmetics.def(selected);
        if (d != null && d.slot().equals(slot())) return d;
        Cosmetics.Def eq = Cosmetics.def(equipped.get(slot()));
        if (eq != null) return eq;
        return list.isEmpty() ? null : list.get(0);
    }

    /** 큰 칸 오른쪽: 고른 치장품 */
    private void detail(Canvas c, long now, List<Cosmetics.Def> list, float mx, float my) {
        Cosmetics.Def d = shown(list);
        if (d == null) {
            c.textFit("치장 상자를 열면 치장품이 나와요", DCX, 596, 20, DW_, Theme.MUTED, Canvas.CENTER, TXT);
            return;
        }
        boolean own = owned.contains(d.id()), eq = isEquipped(d);
        float bob = (float) Math.sin(now / 420.0) * 3;
        if (!c.item(d.icon(), DCX - 55, 375 + bob, 110)) c.imageFit("shop/frog_sheepish", DCX, 430, 100, 0xFFFFFFFF);
        c.textFit(d.name(), DCX, 512, 28, DW_, Theme.INK, Canvas.CENTER, TXT);
        c.text("★ " + Cosmetics.rarityName(d.rarity()), DCX, 546, 22, Cosmetics.rarityColor(d.rarity()), Canvas.CENTER, TXT);
        c.image("shop/divider_horizontal", DX, 568, DW_, 2, 0xFFFFFFFF);
        c.text("부위", DX, 594, 20, Theme.MUTED, Canvas.LEFT, TXT);
        c.textFit(Cosmetics.slotName(d.slot()), DX + DW_, 594, 20, 160, Theme.INK, Canvas.RIGHT, TXT);
        c.text("상태", DX, 626, 20, Theme.MUTED, Canvas.LEFT, TXT);
        c.textFit(eq ? "장착 중" : own ? "보유" : "미보유", DX + DW_, 626, 20, 160, eq ? Theme.GREEN_DARK : own ? Theme.INK : Theme.MUTED, Canvas.RIGHT, TXT);
        c.image("shop/divider_horizontal", DX, 648, DW_, 2, 0xFFFFFFFF);
        List<String> lines = TextRaster.wrap(d.description().isEmpty() ? "설명이 없어요" : d.description(), DW_, 19, TXT);
        for (int i = 0; i < Math.min(3, lines.size()); i++)
            c.text(i == 2 && lines.size() > 3 ? lines.get(i) + "…" : lines.get(i), DX, 676 + i * 30, 19, Theme.MUTED, Canvas.LEFT, TXT);
        if (own) button(c, eq, mx, my);
        else c.textFit("치장 상자에서 나올 수 있어요", DCX, 808, 19, DW_, Theme.MUTED, Canvas.CENTER, TXT);
    }

    /** 입기/벗기 버튼: 그림 360x110 몸통 x 5~354 y 29~79 → 몸통 폭 DW_ 로, 왼쪽 그림 아이콘(옷걸이·X, x ~40 까지) 오른쪽에 글자 */
    private void button(Canvas c, boolean eq, float mx, float my) {
        float f = DW_ / 350f, bx = DX, by = 790, bh = 50 * f;
        boolean hov = Anim.in(mx, my, bx, by, DW_, bh);
        String id = (eq ? W_UNEQUIP : W_EQUIP) + (hov ? "hover" : "normal");
        if (c.size(id) != null) c.image(id, bx - 5 * f, by - 29 * f, 360 * f, 110 * f, 0xFFFFFFFF);
        else c.imageNine(eq ? "shop/secondary_default" : "shop/purchase_default", bx, by, DW_, bh, .16f, .45f, .16f * 424 * bh / 68f, .45f * bh, 0xFFFFFFFF);
        c.textFit(eq ? "벗기" : "입기", bx + (197 - 5) * f, by + bh / 2, 24, 200 * f, eq ? 0xFF8A3B4A : 0xFF2F4A1C, Canvas.CENTER, TXT);
        hits.add(new R(eq ? "unequip" : "equip", bx, by, DW_, bh));
    }

    // ───────── 입력 ─────────
    @Override public boolean click(float mx, float my, long now) {
        float x = (mx - ox) / k, y = (my - oy) / k;
        for (R r : hits) {
            if (!Anim.in(x, y, r.x(), r.y(), r.w(), r.h())) continue;
            String key = r.key();
            if (key.equals("close")) { host.closePanel(this); return true; }
            if (key.startsWith("tab:")) {
                int t = Integer.parseInt(key.substring(4));
                if (t != tab) { tab = t; scrollRow = 0; selected = null; host.sound("minecraft:ui.button.click", 1.3f, .35f); }
                return true;
            }
            if (key.startsWith("item:")) {
                String id = key.substring(5);
                selected = id;
                if (owned.contains(id)) toggle(Cosmetics.def(id));
                else host.sound("minecraft:ui.button.click", 1.5f, .3f);
                return true;
            }
            if (key.equals("equip") || key.equals("unequip")) { toggle(shown(Cosmetics.bySlot(slot()))); return true; }
            if (key.equals("rotate")) { dragX = mx; dragYaw = yaw; return true; }
        }
        return true;
    }

    /** 입기 ↔ 벗기. 서버 응답 전에 화면부터 바꿈 (서버 update 가 오면 그걸로 덮어씀) */
    private void toggle(Cosmetics.Def d) {
        if (d == null || !owned.contains(d.id())) return;
        if (isEquipped(d)) {
            host.send("wardrobe.unequip", "slot", d.slot());
            equipped.remove(d.slot());
            host.sound("minecraft:item.armor.equip_leather", .9f, .6f);
        } else {
            host.send("wardrobe.equip", "id", d.id());
            equipped.put(d.slot(), d.id());
            host.sound("minecraft:item.armor.equip_leather", 1.2f, .7f);
        }
    }

    @Override public void release(float mx, float my) { dragX = Float.NaN; }

    @Override public boolean scroll(float mx, float my, double amount) {
        float x = (mx - ox) / k, y = (my - oy) / k;
        if (Anim.in(x, y, 590, 336, 590, 520)) { scrollRow += amount < 0 ? 1 : -1; return true; }
        if (Anim.in(x, y, 130, 244, 430, 606)) { yaw += amount > 0 ? 15 : -15; return true; }
        return false;
    }
}
