package com.diamondserver.client;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 상점 화면 (구매 / 판매).
 *
 * ★ 디자인 없음: 꾸밈(상자·색·그림) 없이 글자만 쓴다. 화면 모양은 오너가 GPT 로 받아 오는 그림으로 정한다.
 *   배경은 게임 기본 화면 배경을 그대로 쓴다. 고른 것은 앞에 ">" 로, 누를 수 있는 것은 "[ ]" 로 표시한다.
 *   글자 자리: 위 제목·보유 다이아, 그 아래 탭, 왼쪽 분류, 오른쪽 상품 2열 × 4줄, 맨 아래 수량·구매 줄.
 */
public final class ShopScreen extends Screen {
    private static final int W = 340, H = 214;
    private static final int COLS = 2, ROWS = 4, CARD_W = 124, CARD_H = 26;
    private static final int WHITE = Hud.WHITE, GRAY = Hud.GRAY;

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

    // ── 누르는 자리 계산 (그리기와 클릭이 같은 값을 쓴다) ─────────────
    private int[] tabRect(int i) { return new int[]{x0 + 10 + i * 48, y0 + 26, 44, 16}; }
    private int[] catRect(int i) { return new int[]{x0 + 6, y0 + 50 + i * 20, 66, 18}; }
    private int[] cardRect(int i) { return new int[]{x0 + 80 + (i % COLS) * 128, y0 + 50 + (i / COLS) * 30, CARD_W, CARD_H}; }
    /** 판매 탭은 왼쪽 분류가 없어서 품목 칸이 창 가운데에 온다 */
    private int[] sellCardRect(int i) { return new int[]{x0 + 44 + (i % COLS) * 128, y0 + 50 + (i / COLS) * 30, CARD_W, CARD_H}; }
    private int[] minusRect() { return new int[]{x0 + 146, y0 + 186, 22, 16}; }
    private int[] plusRect() { return new int[]{x0 + 188, y0 + 186, 22, 16}; }
    private int[] actionRect() { return new int[]{x0 + W - 8 - 58, y0 + 185, 58, 18}; }
    private int[] sellAllRect() { return new int[]{x0 + W - 8 - 72, y0 + 185, 72, 18}; }

    private static boolean in(int[] r, double mx, double my) { return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]; }

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

    // ── 그리기 (글자만) ───────────────────────────────────────
    private void t(DrawContext ctx, String s, int x, int y, int color) { ctx.drawText(textRenderer, s, x, y, color, true); }

    private void centered(DrawContext ctx, String s, int[] r, int color) {
        t(ctx, s, r[0] + (r[2] - textRenderer.getWidth(s)) / 2, r[1] + (r[3] - 8) / 2, color);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer f = textRenderer;
        renderBackground(ctx, mx, my, delta);          // 게임 기본 화면 배경

        t(ctx, "상점", x0 + 10, y0 + 8, WHITE);
        String held = "보유 다이아 " + Hud.n(St.held);
        t(ctx, held, x0 + W - 10 - f.getWidth(held), y0 + 8, WHITE);

        String[] tabs = {"구매", "판매"};
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 1) == sellTab;
            centered(ctx, on ? "[" + tabs[i] + "]" : tabs[i], tabRect(i), on || in(tabRect(i), mx, my) ? WHITE : GRAY);
        }

        if (sellTab) renderSell(ctx, f, mx, my); else renderBuy(ctx, f, mx, my);

        Hud.drawToasts(ctx, mc, Hud.drawBanner(ctx, mc));
    }

    /** 상품·품목 한 칸: 윗줄 이름, 아랫줄 값. 고른 것은 앞에 ">" */
    private void card(DrawContext ctx, int[] r, boolean hover, boolean on, String name, String sub) {
        t(ctx, (on ? "> " : "") + name, r[0] + 4, r[1] + 4, on || hover ? WHITE : GRAY);
        t(ctx, sub, r[0] + 4, r[1] + 15, GRAY);
    }

    private void renderBuy(DrawContext ctx, TextRenderer f, int mx, int my) {
        for (int i = 0; i < St.cats.size(); i++) {
            St.Cat c = St.cats.get(i);
            int[] r = catRect(i);
            boolean on = c.id().equals(cat);
            t(ctx, (on ? "> " : "  ") + c.label(), r[0] + 2, r[1] + 5, on || in(r, mx, my) ? WHITE : GRAY);
        }

        List<St.Item> list = shown();
        scroll = Math.min(scroll, maxScroll(list.size()));
        if (list.isEmpty()) t(ctx, "상품이 없습니다", x0 + 80 + (W - 88 - f.getWidth("상품이 없습니다")) / 2, y0 + 106, GRAY);
        for (int v = 0; v < COLS * ROWS; v++) {
            int idx = scroll * COLS + v;
            if (idx >= list.size()) break;
            St.Item it = list.get(idx);
            int[] r = cardRect(v);
            String name = it.amount() > 1 ? it.name() + " ×" + it.amount() : it.name();
            card(ctx, r, in(r, mx, my), it.id().equals(sel), name, "다이아 " + Hud.n(it.price()) + "개");
        }
        if (maxScroll(list.size()) > 0) t(ctx, scroll < maxScroll(list.size()) ? "아래로 더 있음" : "위로 더 있음", x0 + 80, y0 + 168, GRAY);

        St.Item s = selected();
        if (s == null) {
            t(ctx, "살 물건을 고르세요", x0 + 10, y0 + 190, GRAY);
        } else {
            t(ctx, s.name(), x0 + 10, y0 + 190, WHITE);
            centered(ctx, "[-]", minusRect(), qty > 1 ? WHITE : GRAY);
            centered(ctx, "[+]", plusRect(), qty < 64 ? WHITE : GRAY);
            String q = String.valueOf(qty);
            t(ctx, q, x0 + 178 - f.getWidth(q) / 2, y0 + 190, WHITE);
            t(ctx, "합계 " + Hud.n(s.price() * qty), x0 + 214, y0 + 190, WHITE);
        }
        centered(ctx, "[구매]", actionRect(), s != null ? WHITE : GRAY);
    }

    private void renderSell(DrawContext ctx, TextRenderer f, int mx, int my) {
        List<Holding> list = holdings();
        scroll = Math.min(scroll, maxScroll(list.size()));
        if (list.isEmpty()) {
            String msg = "인벤토리에 팔 수 있는 광물이 없습니다";
            t(ctx, msg, x0 + (W - f.getWidth(msg)) / 2, y0 + 100, GRAY);
            centered(ctx, "[전부 판매]", sellAllRect(), GRAY);
            return;
        }
        double sum = 0;
        for (Holding h : list) sum += h.value();
        for (int v = 0; v < COLS * ROWS; v++) {
            int idx = scroll * COLS + v;
            if (idx >= list.size()) break;
            Holding h = list.get(idx);
            int[] r = sellCardRect(v);
            card(ctx, r, in(r, mx, my), false, h.sell().name() + " ×" + h.count(), "다이아 +" + String.format("%.2f", h.value()));
        }
        if (maxScroll(list.size()) > 0) t(ctx, scroll < maxScroll(list.size()) ? "아래로 더 있음" : "위로 더 있음", x0 + 44, y0 + 168, GRAY);
        t(ctx, "눌러서 한 종류만 판매 · 예상 +" + String.format("%.2f", sum), x0 + 10, y0 + 190, GRAY);
        centered(ctx, "[전부 판매]", sellAllRect(), WHITE);
    }

    // ── 입력 ───────────────────────────────────────────────
    private void click() {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        for (int i = 0; i < 2; i++) if (in(tabRect(i), mx, my)) {
            sellTab = i == 1; scroll = 0; click();
            return true;
        }
        if (sellTab) {
            List<Holding> list = holdings();
            if (!list.isEmpty() && in(sellAllRect(), mx, my)) { sendSell("all"); click(); return true; }
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
