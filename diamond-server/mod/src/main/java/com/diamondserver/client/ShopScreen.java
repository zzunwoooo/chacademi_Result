package com.diamondserver.client;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 상점 화면 (구매 / 판매). 지금은 색 상자로 그린 임시 디자인이다.
 * 배치: 위 제목·보유 다이아, 그 아래 탭, 왼쪽 분류, 오른쪽 상품 2열 × 4줄, 맨 아래 수량·구매 줄.
 */
public final class ShopScreen extends Screen {
    private static final int W = 340, H = 214;
    private static final int COLS = 2, ROWS = 4, CARD_W = 124, CARD_H = 26;
    private static final int WIN = 0xF00D1424, CARD = 0xFF182238, CARD_HOVER = 0xFF22304E, LINE = 0xFF2B3D63, DIM = 0xFF6B7890;

    private final Map<String, ItemStack> icons = new HashMap<>();
    private int x0, y0;
    private boolean sellTab;
    private String cat;        // 고른 분류
    private String sel;        // 고른 상품 id
    private int qty = 1;
    private int scroll;        // 위로 넘긴 줄 수

    public ShopScreen() { super(Text.literal("상점")); }

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        if (cat == null && !St.cats.isEmpty()) cat = St.cats.get(0).id();
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) { }

    // ── 자리 계산 (그리기와 클릭이 같은 값을 쓴다) ─────────────────
    private int[] tabRect(int i) { return new int[]{x0 + 10 + i * 48, y0 + 26, 44, 16}; }
    private int[] catRect(int i) { return new int[]{x0 + 6, y0 + 50 + i * 20, 66, 18}; }
    private int[] cardRect(int i) { return new int[]{x0 + 80 + (i % COLS) * 128, y0 + 50 + (i / COLS) * 30, CARD_W, CARD_H}; }
    private int[] minusRect() { return new int[]{x0 + 150, y0 + 186, 16, 16}; }
    private int[] plusRect() { return new int[]{x0 + 190, y0 + 186, 16, 16}; }
    private int[] actionRect() { return new int[]{x0 + W - 8 - 58, y0 + 185, 58, 18}; }
    private int[] closeRect() { return new int[]{x0 + W - 20, y0 + 6, 12, 12}; }

    private static boolean in(int[] r, double mx, double my) { return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]; }

    private ItemStack icon(String id) {
        return icons.computeIfAbsent(id, k -> {
            Identifier rid = Identifier.tryParse(k);
            ItemStack s = rid == null ? ItemStack.EMPTY : new ItemStack(Registries.ITEM.get(rid));
            return s.isEmpty() ? new ItemStack(Items.PAPER) : s;
        });
    }

    private List<St.Item> shown() {
        List<St.Item> out = new ArrayList<>();
        for (St.Item i : St.items) if (i.cat().equals(cat)) out.add(i);
        return out;
    }

    private St.Item selected() {
        for (St.Item i : St.items) if (i.id().equals(sel)) return i;
        return null;
    }

    /** 판매 탭 한 줄: 내 인벤토리에 있는 광물 */
    private record Holding(St.Sell sell, int count, double value) { }

    private List<Holding> holdings() {
        List<Holding> out = new ArrayList<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return out;
        Map<String, Integer> counts = new HashMap<>();
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            counts.merge(Registries.ITEM.getId(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
        for (St.Sell s : St.sells) {
            int n = counts.getOrDefault(s.mat(), 0) + 9 * (s.block().isEmpty() ? 0 : counts.getOrDefault(s.block(), 0));
            if (n > 0) out.add(new Holding(s, n, n * s.price()));
        }
        return out;
    }

    private int maxScroll(int count) { return Math.max(0, (count + COLS - 1) / COLS - ROWS); }

    // ── 그리기 ─────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer f = textRenderer;
        ctx.fill(0, 0, width, height, 0x90000000);
        Hud.box(ctx, x0, y0, W, H, WIN, LINE);

        // 제목 · 보유 다이아 · 닫기
        ctx.drawText(f, "상점", x0 + 10, y0 + 8, Hud.WHITE, false);
        int[] cr = closeRect();
        ctx.drawText(f, "X", cr[0] + 3, cr[1] + 2, in(cr, mx, my) ? Hud.WHITE : DIM, false);
        String held = Hud.n(St.held);
        int chipW = 22 + f.getWidth(held) + 8;
        int chipX = x0 + W - 26 - chipW;
        Hud.box(ctx, chipX, y0 + 3, chipW, 18, CARD, LINE);
        ctx.drawItem(icon("minecraft:diamond"), chipX + 2, y0 + 4);
        ctx.drawText(f, held, chipX + 22, y0 + 8, Hud.AQUA, false);

        // 탭
        String[] tabs = {"구매", "판매"};
        for (int i = 0; i < 2; i++) {
            int[] r = tabRect(i);
            boolean on = (i == 1) == sellTab;
            ctx.drawText(f, tabs[i], r[0] + (r[2] - f.getWidth(tabs[i])) / 2, r[1] + 4, on ? Hud.WHITE : (in(r, mx, my) ? Hud.GRAY : DIM), false);
            if (on) ctx.fill(r[0], r[1] + r[3], r[0] + r[2], r[1] + r[3] + 1, Hud.AQUA);
        }
        ctx.fill(x0 + 1, y0 + 45, x0 + W - 1, y0 + 46, LINE);
        ctx.fill(x0 + 1, y0 + 178, x0 + W - 1, y0 + 179, LINE);

        if (sellTab) renderSell(ctx, f, mx, my); else renderBuy(ctx, f, mx, my);

        Hud.drawToasts(ctx, mc, Hud.drawBanner(ctx, mc));
    }

    private void card(DrawContext ctx, TextRenderer f, int[] r, boolean hover, boolean on, ItemStack icon, String name, String sub, int subColor) {
        Hud.box(ctx, r[0], r[1], r[2], r[3], hover ? CARD_HOVER : CARD, on ? Hud.AQUA : LINE);
        ctx.drawItem(icon, r[0] + 5, r[1] + 5);
        ctx.drawText(f, name, r[0] + 26, r[1] + 4, Hud.WHITE, false);
        ctx.drawText(f, sub, r[0] + 26, r[1] + 15, subColor, false);
    }

    private void button(DrawContext ctx, TextRenderer f, int[] r, String label, boolean enabled, boolean hover) {
        Hud.box(ctx, r[0], r[1], r[2], r[3], !enabled ? CARD : hover ? 0xFF2E86B8 : 0xFF236A94, enabled ? Hud.AQUA : LINE);
        ctx.drawText(f, label, r[0] + (r[2] - f.getWidth(label)) / 2, r[1] + (r[3] - 8) / 2, enabled ? Hud.WHITE : DIM, false);
    }

    private void renderBuy(DrawContext ctx, TextRenderer f, int mx, int my) {
        // 왼쪽 분류
        for (int i = 0; i < St.cats.size(); i++) {
            St.Cat c = St.cats.get(i);
            int[] r = catRect(i);
            boolean on = c.id().equals(cat);
            if (on || in(r, mx, my)) ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], on ? CARD_HOVER : CARD);
            if (on) ctx.fill(r[0], r[1], r[0] + 2, r[1] + r[3], Hud.AQUA);
            ctx.drawText(f, c.label(), r[0] + 8, r[1] + 5, on ? Hud.WHITE : Hud.GRAY, false);
        }
        ctx.fill(x0 + 75, y0 + 46, x0 + 76, y0 + 178, LINE);

        // 상품
        List<St.Item> list = shown();
        scroll = Math.min(scroll, maxScroll(list.size()));
        if (list.isEmpty()) ctx.drawText(f, "상품이 없습니다", x0 + 80 + (W - 88 - f.getWidth("상품이 없습니다")) / 2, y0 + 106, DIM, false);
        for (int v = 0; v < COLS * ROWS; v++) {
            int idx = scroll * COLS + v;
            if (idx >= list.size()) break;
            St.Item it = list.get(idx);
            int[] r = cardRect(v);
            String name = it.amount() > 1 ? it.name() + " ×" + it.amount() : it.name();
            card(ctx, f, r, in(r, mx, my), it.id().equals(sel), icon(it.icon()), name, "다이아 " + Hud.n(it.price()) + "개",
                    St.held >= it.price() ? Hud.AQUA : Hud.RED);
        }
        if (maxScroll(list.size()) > 0) ctx.drawText(f, scroll < maxScroll(list.size()) ? "▼" : "▲", x0 + W - 8 - 8, y0 + 168, DIM, false);

        // 아래 줄: 고른 상품 · 수량 · 합계 · 구매
        St.Item s = selected();
        if (s == null) {
            ctx.drawText(f, "살 물건을 고르세요", x0 + 10, y0 + 190, DIM, false);
        } else {
            ctx.drawText(f, s.name(), x0 + 10, y0 + 190, Hud.WHITE, false);
            int[] m = minusRect(), p = plusRect();
            button(ctx, f, m, "-", qty > 1, in(m, mx, my));
            button(ctx, f, p, "+", qty < 64, in(p, mx, my));
            String q = String.valueOf(qty);
            ctx.drawText(f, q, x0 + 178 - f.getWidth(q) / 2, y0 + 190, Hud.WHITE, false);
            long total = s.price() * qty;
            ctx.drawText(f, "합계 " + Hud.n(total), x0 + 212, y0 + 190, St.held >= total ? Hud.AQUA : Hud.RED, false);
        }
        int[] a = actionRect();
        button(ctx, f, a, "구매", s != null, in(a, mx, my));
    }

    private void renderSell(DrawContext ctx, TextRenderer f, int mx, int my) {
        List<Holding> list = holdings();
        scroll = Math.min(scroll, maxScroll(list.size()));
        int[] a = actionRect();
        a = new int[]{a[0] - 14, a[1], a[2] + 14, a[3]};
        if (list.isEmpty()) {
            String msg = "인벤토리에 팔 수 있는 광물이 없습니다";
            ctx.drawText(f, msg, x0 + (W - f.getWidth(msg)) / 2, y0 + 100, DIM, false);
            String sub = "석탄 · 철 · 구리 · 금 · 레드스톤 · 청금석 · 에메랄드";
            ctx.drawText(f, sub, x0 + (W - f.getWidth(sub)) / 2, y0 + 114, DIM, false);
            button(ctx, f, a, "전부 판매", false, false);
            return;
        }
        double sum = 0;
        for (Holding h : list) sum += h.value();
        for (int v = 0; v < COLS * ROWS; v++) {
            int idx = scroll * COLS + v;
            if (idx >= list.size()) break;
            Holding h = list.get(idx);
            int[] r = sellCardRect(v);
            card(ctx, f, r, in(r, mx, my), false, icon(h.sell().mat()), h.sell().name() + " ×" + h.count(),
                    "다이아 +" + String.format("%.2f", h.value()), Hud.GREEN);
        }
        if (maxScroll(list.size()) > 0) ctx.drawText(f, scroll < maxScroll(list.size()) ? "▼" : "▲", x0 + W - 8 - 8, y0 + 168, DIM, false);
        ctx.drawText(f, "눌러서 한 종류만 판매 · 예상 +" + String.format("%.2f", sum), x0 + 10, y0 + 190, Hud.GRAY, false);
        button(ctx, f, a, "전부 판매", true, in(a, mx, my));
    }

    /** 판매 탭은 왼쪽 분류가 없어서 상품 칸이 창 가운데에 온다 */
    private int[] sellCardRect(int i) { return new int[]{x0 + 44 + (i % COLS) * 128, y0 + 50 + (i / COLS) * 30, CARD_W, CARD_H}; }

    // ── 입력 ───────────────────────────────────────────────
    private void click() {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        if (in(closeRect(), mx, my)) { close(); return true; }
        for (int i = 0; i < 2; i++) if (in(tabRect(i), mx, my)) {
            sellTab = i == 1; scroll = 0; click();
            return true;
        }
        if (sellTab) {
            List<Holding> list = holdings();
            int[] a = actionRect();
            a = new int[]{a[0] - 14, a[1], a[2] + 14, a[3]};
            if (!list.isEmpty() && in(a, mx, my)) { sendSell("all"); click(); return true; }
            for (int v = 0; v < COLS * ROWS; v++) {
                int idx = scroll * COLS + v;
                if (idx >= list.size()) break;
                if (in(sellCardRect(v), mx, my)) { sendSell(list.get(idx).sell().mat()); click(); return true; }
            }
            return true;
        }
        for (int i = 0; i < St.cats.size(); i++) if (in(catRect(i), mx, my)) {
            cat = St.cats.get(i).id(); scroll = 0; click();
            return true;
        }
        List<St.Item> list = shown();
        for (int v = 0; v < COLS * ROWS; v++) {
            int idx = scroll * COLS + v;
            if (idx >= list.size()) break;
            if (in(cardRect(v), mx, my)) {
                String id = list.get(idx).id();
                if (!id.equals(sel)) { sel = id; qty = 1; }
                click();
                return true;
            }
        }
        St.Item s = selected();
        if (s == null) return true;
        int step = hasShiftDown() ? 10 : 1;
        if (in(minusRect(), mx, my)) { qty = Math.max(1, qty - step); click(); }
        else if (in(plusRect(), mx, my)) { qty = Math.min(64, qty + step); click(); }
        else if (in(actionRect(), mx, my)) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "buy");
            o.addProperty("id", s.id());
            o.addProperty("qty", qty);
            DiamondClient.send(o);
            click();
        }
        return true;
    }

    private void sendSell(String mat) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "sell");
        o.addProperty("mat", mat);
        DiamondClient.send(o);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        int count = sellTab ? holdings().size() : shown().size();
        scroll = Math.max(0, Math.min(maxScroll(count), scroll - (int) Math.signum(vertical)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (DiamondClient.shopKey != null && DiamondClient.shopKey.matchesKey(key, scan)) { close(); return true; }
        return super.keyPressed(key, scan, mods);
    }
}
