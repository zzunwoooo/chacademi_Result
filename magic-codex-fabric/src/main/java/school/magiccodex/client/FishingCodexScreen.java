package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.FishingArt.Art;
import school.magiccodex.protocol.FishingProtocol;
import school.magiccodex.protocol.FishingProtocol.*;

import static school.magiccodex.client.FishingArt.*;

/**
 * 낚시 도감 (/낚시도감, 2026-10-20 reference): fish grid with search and four filters on the left, the selected fish
 * on the right. Uncaught fish are locked black silhouettes ("???", the server hides their place and time). The second
 * tab lists opened treasure chests. Top right: a short progress line with the 25/50/75/100% reward chests sitting on it;
 * they glow when one can be taken, a click claims it, hovering shows what the tier gives. Open dropdowns and the
 * reward tooltip are drawn in a last, separate pass (flushed, raised z) so they sit above every other batch.
 */
final class FishingCodexScreen extends Screen {
    static final String[] SEASONS = {"전체", "봄", "여름", "가을", "겨울"};
    static final String[] PLACES = {"전체", "민물", "강", "늪", "바다", "정글", "설원", "산", "동굴", "하늘섬"};
    static final String[] CAUGHT = {"전체", "잡음", "못 잡음"};
    static final String[] GRADES = {"전체", "일반", "고급", "유일", "전설"};
    static final float GRID_X = 181, GRID_Y = 293, CELL_W = 160, CELL_H = 128, STEP_X = 174, STEP_Y = 141;
    static final int COLS = 5, ROWS = 4;
    static final float[] FILTER_X = {675, 856, 1031, 1207}, FILTER_W = {163, 157, 158, 176};
    static final float FILTER_Y = 225, FILTER_H = 45;
    // progress line (top right, between the title and the count): 0% at PROG_X0, the 100% chest at PROG_X1
    static final float PROG_X0 = 990, PROG_X1 = 1290, PROG_Y = 122, CHEST = 54;

    private final UiResources.Entrance entrance = new UiResources.Entrance();
    private Codex codex;
    private int tab, scroll, selected, open = -1;
    private final int[] filter = new int[4];
    private TextFieldWidget search;
    private String query = "", notice = "";
    private long openedAt, noticeAt, lastFrame;
    private final float[] hover = new float[4];
    private final boolean[] wasOver = new boolean[4];
    private int hovered = -1;
    private List<List<ItemStack>> tierIcons;

    FishingCodexScreen(Codex codex) { super(Text.literal("낚시 도감")); this.codex = codex; }

    void onCodex(Codex c) { codex = c; tierIcons = null; if (!c.notice().isBlank()) { notice = c.notice(); noticeAt = Util.getMeasuringTimeMs(); } }

    @Override protected void init() {
        if (openedAt == 0) openedAt = Util.getMeasuringTimeMs();
        search = new TextFieldWidget(textRenderer, 0, 0, 300, 20, Text.literal("검색"));
        search.setMaxLength(16); search.setText(query); search.setDrawsBackground(false);
        search.setChangedListener(v -> { query = v; scroll = 0; });
        // not a drawable child: the field is drawn by render() in the reference style, the widget only takes typing
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {}

    private CodexLayout layout() { return CodexLayout.fit(width, height); }

    private List<Entry> rows() {
        var out = new ArrayList<Entry>();
        if (codex == null) return out;
        String q = query.strip();
        for (Entry e : codex.entries()) {
            if (!q.isEmpty() && !(e.caught() && e.name().contains(q))) continue;
            if (filter[0] > 0 && e.grade() != filter[0] - 1) continue;
            if (filter[1] > 0 && !(e.season().equals("사계절") || e.season().contains(SEASONS[filter[1]]))) continue;
            if (filter[2] > 0 && !e.place().contains(PLACES[filter[2]])) continue;
            if (filter[3] == 1 && !e.caught()) continue;
            if (filter[3] == 2 && e.caught()) continue;
            out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ input

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        var l = layout();
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        if (open >= 0) {
            String[] opts = options(open);
            for (int i = 0; i < opts.length; i++) if (inside(mx, my, FILTER_X[open], FILTER_Y + FILTER_H + 4 + i * 34, FILTER_W[open], 34)) {
                filter[open] = i; scroll = 0; open = -1; click(); return true;
            }
            open = -1; return true;
        }
        if (inside(mx, my, 1462, 77, 40, 40)) { close(); return true; }
        for (int t = 0; t < 2; t++) if (inside(mx, my, 197 + t * 170, 165, 160, 40)) { tab = t; scroll = 0; selected = 0; click(); return true; }
        // completion rewards
        for (int t = 0; t < 4; t++) if (codex != null && overChest(mx, my, t)) {
            if ((codex.available() >> t & 1) != 0) FishingClient.claim(t);
            else { notice = (codex.claimed() >> t & 1) != 0 ? "이미 받은 보상이에요." : "도감 " + new int[]{25, 50, 75, 100}[t] + "%를 채우면 받을 수 있어요."; noticeAt = Util.getMeasuringTimeMs(); }
            click(); return true;
        }
        if (tab == 0) {
            boolean searchHit = inside(mx, my, 196, 225, 461, 45);
            search.setFocused(searchHit); setFocused(searchHit ? search : null);
            if (searchHit) return true;
            for (int f = 0; f < 4; f++) if (inside(mx, my, FILTER_X[f], FILTER_Y, FILTER_W[f], FILTER_H)) { open = f; click(); return true; }
            var rows = rows();
            for (int i = 0; i < COLS * ROWS; i++) {
                int index = scroll * COLS + i;
                if (index >= rows.size()) break;
                float x = GRID_X + (i % COLS) * STEP_X, y = GRID_Y + (i / COLS) * STEP_Y;
                if (inside(mx, my, x, y, CELL_W, CELL_H)) { selected = index; click(); return true; }
            }
        } else for (int g = 0; g < 4; g++) {
            float x = GRID_X + g * STEP_X * 1.2f, y = GRID_Y + 40;
            if (inside(mx, my, x, y, CELL_W * 1.1f, CELL_H * 1.6f)) { selected = g; click(); return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        if (open >= 0) return true;   // nothing under an open dropdown reacts
        int max = Math.max(0, (rows().size() + COLS - 1) / COLS - ROWS);
        scroll = Math.clamp(scroll - (int) Math.signum(v), 0, max);
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (search.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) return search.keyPressed(key, scan, mods);
        return super.keyPressed(key, scan, mods);
    }
    @Override public boolean charTyped(char chr, int mods) { return search.isFocused() ? search.charTyped(chr, mods) : super.charTyped(chr, mods); }

    private String[] options(int f) { return switch (f) { case 0 -> GRADES; case 1 -> SEASONS; case 2 -> PLACES; default -> CAUGHT; }; }
    private void click() { if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.5f, .25f)); }
    private float rewardX(int t) { return PROG_X0 + (PROG_X1 - PROG_X0) * (t + 1) / 4f; }
    private boolean overChest(double mx, double my, int t) { return inside(mx, my, rewardX(t) - CHEST / 2, PROG_Y - CHEST / 2, CHEST, CHEST); }
    /** Species needed for tier t (ceil(total * (t+1) / 4)). */
    private int need(int t) { int total = codex == null ? 0 : codex.entries().size(); return (total * (t + 1) + 3) / 4; }
    private List<List<ItemStack>> icons() {
        if (tierIcons == null && codex != null) {
            var out = new ArrayList<List<ItemStack>>();
            for (var l : codex.tierRewards()) { var row = new ArrayList<ItemStack>(); for (Reward w : l) row.add(FishingClient.decode(w.preview())); out.add(row); }
            tierIcons = out;
        }
        return tierIcons == null ? List.of() : tierIcons;
    }
    private void hoverSound(boolean claimable) {
        if (client == null) return;
        client.getSoundManager().play(claimable ? PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.7f, .12f)
                : PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 2.0f, .1f));
    }

    // ------------------------------------------------------------------ drawing

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var l = layout();
        double mx = l.localX(mouseX), my = l.localY(mouseY);
        var images = UiResources.images(); var text = UiResources.text();
        images.beginFrame(); text.beginFrame();
        c.fill(0, 0, width, height, 0x88050A12);
        float t = (Util.getMeasuringTimeMs() - openedAt) / 1000f;
        var m = c.getMatrices(); m.push();
        try {
            m.translate(l.x(), l.y(), 0); m.scale(l.scale(), l.scale(), 1);
            rect(c, 146, 56, 1380, 823, 0xF50B1730);
            draw(c, FRAME_CODEX, 133, 43, 1406, 849, 0xFFFFFFFF);   // frames_manifest.json: source crop 133,43
            // while a dropdown is open nothing under it reacts to the mouse
            double hx = open >= 0 ? -1 : mx, hy = open >= 0 ? -1 : my;
            header(c, t, hx, hy);
            if (tab == 0) { fishGrid(c, t, hx, hy); filters(c, hx, hy); }
            else treasures(c, t, hx, hy);
            if (!notice.isBlank() && (Util.getMeasuringTimeMs() - noticeAt) < 4000) text(c, notice, 836, 868, 16, 0xFFF2D9A0, BODY, true);
            // top layer: flush every batch drawn so far (frame, images, text), then draw above it at a raised z
            if ((tab == 0 && open >= 0) || hovered >= 0) {
                c.draw(); m.push();
                try {
                    m.translate(0, 0, 400);
                    if (tab == 0 && open >= 0) dropdown(c, mx, my);
                    else rewardTooltip(c, hovered);
                    c.draw();
                } finally { m.pop(); }
            }
        } finally { m.pop(); images.endFrame(); }
        // the search text field is drawn by us; keep the widget only for typing
        entrance.draw(c, width, height);
    }

    private void header(DrawContext c, float t, double mx, double my) {
        text(c, "낚시 도감", 197, 118, 50, IVORY, TITLE, false);
        int caught = codex == null ? 0 : codex.caught(), total = codex == null ? 0 : codex.entries().size();
        String count = caught + " / " + total;
        FishingArt.right(c, count, 1433, 127, 23, 0xFFF2C25E, BOLD);
        boolean overX = inside(mx, my, 1462, 77, 40, 40);
        HudMesh.line(c, 1472, 87, 1492, 107, 2.4f, overX ? 0xFFFFE9B0 : 0xFFE7C77E); HudMesh.line(c, 1492, 87, 1472, 107, 2.4f, overX ? 0xFFFFE9B0 : 0xFFE7C77E);
        progress(c, t, mx, my, caught, total);
        // tabs
        String[] tabs = {"물고기", "보물"};
        for (int i = 0; i < 2; i++) {
            float cx = 282 + i * 170;
            text(c, tabs[i], cx, 183, 22, tab == i ? 0xFFF2C25E : MUTED, BOLD, true);
            if (tab == i) { rect(c, cx - 85, 204, 170, 2.8f, 0xFFE7C77E); bloom(c, cx, 205, 60, 0xE7C77E, .18f); }
        }
        hline(c, 176, 1510, 207, 0x40E7C77E);
    }

    private void filters(DrawContext c, double mx, double my) {
        // search
        boolean focused = search.isFocused();
        card(c, 196, 225, 461, 45, 0xD00A1530, focused ? 0xFFE7C77E : 0x80DCE6F0, 1.4f);
        draw(c, SEARCH, 210, 235, 24, 24, 0xFFDCE6F0);
        String shown = query.isEmpty() && !focused ? "검색" : query + (focused && (Util.getMeasuringTimeMs() / 500) % 2 == 0 ? "|" : "");
        text(c, shown, 250, 248, 18, query.isEmpty() && !focused ? DIM : TEXT, BODY, false);
        String[] labels = {"등급", "계절", "장소", "잡은 여부"};
        for (int f = 0; f < 4; f++) {
            float x = FILTER_X[f], w = FILTER_W[f];
            boolean over = inside(mx, my, x, FILTER_Y, w, FILTER_H) || open == f;
            card(c, x, FILTER_Y, w, FILTER_H, 0xD00A1530, over ? 0xFFE7C77E : 0x80DCE6F0, 1.4f);
            String v = filter[f] == 0 ? labels[f] : options(f)[filter[f]];
            text(c, v, x + 18, FILTER_Y + 23, 17, filter[f] == 0 ? TEXT : 0xFFF2C25E, BODY, false);
            float ax = x + w - 26, ay = FILTER_Y + 20;
            HudMesh.line(c, ax - 6, ay, ax, ay + 6, 2, TEXT); HudMesh.line(c, ax, ay + 6, ax + 6, ay, 2, TEXT);
        }
    }

    /** The open filter's option list; drawn by render() in the top layer. */
    private void dropdown(DrawContext c, double mx, double my) {
        String[] opts = options(open);
        float x = FILTER_X[open], w = FILTER_W[open], y0 = FILTER_Y + FILTER_H + 4;
        card(c, x, y0, w, opts.length * 34 + 4, 0xF60B1428, 0xFFE7C77E, 1.4f);
        for (int i = 0; i < opts.length; i++) {
            float y = y0 + 2 + i * 34;
            if (inside(mx, my, x, y, w, 34)) rect(c, x + 3, y, w - 6, 34, 0x40E7C77E);
            text(c, opts[i], x + 18, y + 17, 16, i == filter[open] ? 0xFFF2C25E : TEXT, BODY, false);
        }
    }

    // ------------------------------------------------------------------ completion rewards

    private void progress(DrawContext c, float t, double mx, double my, int caught, int total) {
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0 : Math.min(.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        // track + gold fill up to the caught ratio
        float ratio = total == 0 ? 0 : Math.clamp((float) caught / total, 0f, 1f), fx = PROG_X0 + (PROG_X1 - PROG_X0) * ratio;
        HudMesh.line(c, PROG_X0, PROG_Y, PROG_X1, PROG_Y, 3f, 0x50DCE6F0);
        if (ratio > 0) {
            HudMesh.line(c, PROG_X0, PROG_Y, fx, PROG_Y, 3f, 0xFFE7C77E);
            bloom(c, fx, PROG_Y, 12, 0xFFE6A0, .35f);
        }
        diamond(c, PROG_X0, PROG_Y, 4, ratio > 0 ? GOLD : 0x80DCE6F0);
        hovered = -1;
        if (codex == null) return;
        for (int i = 0; i < 4; i++) {
            float x = rewardX(i);
            boolean claimed = (codex.claimed() >> i & 1) != 0, available = (codex.available() >> i & 1) != 0, reached = caught >= need(i);
            boolean over = overChest(mx, my, i);
            if (over) hovered = i;
            if (over && !wasOver[i]) hoverSound(available);   // once, when the mouse enters
            wasOver[i] = over;
            hover[i] += ((over ? 1 : 0) - hover[i]) * Math.min(1, dt * 14);   // smooth ease toward 1.15x / back
            if (!over && hover[i] < .003f) hover[i] = 0;
            float k = hover[i], size = CHEST * (1 + .15f * k);
            if (available) bloom(c, x, PROG_Y, 38, 0xFFD582, .35f + .2f * (float) Math.sin(t * 4 + i));
            if (k > 0) bloom(c, x, PROG_Y, 46 * (.7f + .3f * k), 0xFFFFFF, .55f * k);
            Art art = claimed ? chest(i, true) : reward(i);
            int tint = claimed ? 0xFFFFFFFF : available ? 0xFFFFFFFF : reached ? 0xFFE8E8E8 : 0xFF5A6070;
            // both arts keep the chest's visible box at the same place on their canvas: centre it on the line
            draw(c, art, x - size / 2, PROG_Y - size * .54f, size, size, tint);
        }
    }

    /** "25% 달성 보상" card next to the hovered chest, listing what that tier gives. Top layer. */
    private void rewardTooltip(DrawContext c, int tier) {
        if (codex == null || tier < 0) return;
        int caught = codex.caught();
        boolean claimed = (codex.claimed() >> tier & 1) != 0, available = (codex.available() >> tier & 1) != 0;
        int left = need(tier) - caught;
        String title = new int[]{25, 50, 75, 100}[tier] + "% 달성 보상";
        String status = claimed ? "받은 보상" : available ? "클릭해서 받기" : left > 0 ? left + "종 더 잡기" : "";
        var rewards = codex.tierRewards().get(tier);
        var stacks = icons().size() > tier ? icons().get(tier) : List.<ItemStack>of();
        String[] names = new String[rewards.size()];
        float w = Math.max(width(title, 18, BOLD), width(status, 15, BODY)) + 32;
        for (int i = 0; i < rewards.size(); i++) {
            Reward r = rewards.get(i);
            ItemStack st = i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY;
            names[i] = st.isEmpty() ? r.name() : ItemTooltipRenderer.localName(st, Text.literal(r.name())).getString();
            float rw = (st.isEmpty() ? 0 : 34) + width(names[i], 16, BODY) + (r.amount() > 1 || !st.isEmpty() ? width("×" + r.amount(), 16, BOLD) + 14 : 0) + 32;
            w = Math.max(w, rw);
        }
        if (rewards.isEmpty()) w = Math.max(w, width("보상 정보가 없어요.", 15, BODY) + 32);
        w = Math.max(220, w);
        float h = 44 + (status.isEmpty() ? 0 : 22) + Math.max(1, rewards.size()) * 32 + 10;
        float cx = rewardX(tier), x = Math.clamp(cx - w / 2, 160, 1510 - w), y = PROG_Y + CHEST * .6f + 6;
        card(c, x, y, w, h, 0xF50B1428, 0xFFE7C77E, 1.4f);
        float ty = y + 22;
        text(c, title, x + 16, ty, 18, 0xFFF2C25E, BOLD, false);
        if (!status.isEmpty()) { ty += 22; text(c, status, x + 16, ty, 15, available ? 0xFFFFE9B0 : claimed ? MUTED : TEXT, BODY, false); }
        ty += 12;
        hline(c, x + 12, x + w - 12, ty, 0x40E7C77E);
        ty += 20;
        if (rewards.isEmpty()) text(c, "보상 정보가 없어요.", x + 16, ty, 15, MUTED, BODY, false);
        for (int i = 0; i < rewards.size(); i++, ty += 32) {
            Reward r = rewards.get(i);
            ItemStack st = i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY;
            float nx = x + 16;
            if (!st.isEmpty()) { AlchemyScreen.item(c, st, x + 16 + 12, ty, 24); nx += 34; }
            else diamond(c, x + 20, ty, 3.5f, GOLD);
            if (st.isEmpty()) nx += 10;
            text(c, names[i], nx, ty, 16, TEXT, BODY, false);
            if (r.amount() > 1 || !st.isEmpty()) FishingArt.right(c, "×" + r.amount(), x + w - 16, ty, 16, 0xFFF2C25E, BOLD);
        }
    }

    private Art fishArt(Entry e) { return FishingArt.fish(e.id()); }

    private void fishGrid(DrawContext c, float t, double mx, double my) {
        var rows = rows();
        int max = Math.max(0, (rows.size() + COLS - 1) / COLS - ROWS);
        scroll = Math.clamp(scroll, 0, max);
        if (selected >= rows.size()) selected = Math.max(0, rows.size() - 1);
        for (int i = 0; i < COLS * ROWS; i++) {
            int index = scroll * COLS + i;
            if (index >= rows.size()) break;
            Entry e = rows.get(index);
            float x = GRID_X + (i % COLS) * STEP_X, y = GRID_Y + (i / COLS) * STEP_Y;
            boolean sel = index == selected, over = inside(mx, my, x, y, CELL_W, CELL_H);
            int gc = GRADE[e.grade()];
            card(c, x, y, CELL_W, CELL_H, sel ? 0xF01A2A55 : over ? 0xE8132544 : 0xE00F1D39, sel ? gc : e.caught() ? alpha(gc, .45f) : 0x50DCE6F0, sel ? 2.4f : 1.3f);
            if (sel) bloom(c, x + CELL_W / 2, y + CELL_H / 2, CELL_W * .6f, gc & 0xFFFFFF, .15f);
            Art art = e.caught() ? fishArt(e) : null;
            if (art != null) draw(c, art, x + CELL_W / 2 - 52, y + 6, 104, 104, 0xFFFFFFFF);
            else drawCentered(c, SHAPES[e.shape()], x + CELL_W / 2, y + 56, 116, 116, e.caught() ? 0xFF1C3460 : 0xFF050B18, false, 0, false);
            text(c, e.caught() ? e.name() : "???", x + CELL_W / 2, y + 110, fit(e.name(), 16, BOLD, CELL_W - 12), e.caught() ? (sel ? gc : TEXT) : DIM, BOLD, true);
            if (!e.caught()) draw(c, LOCK, x + CELL_W - 30, y + 8, 22, 22, 0xFFFFFFFF);
        }
        // scrollbar
        rect(c, 1053, 293, 3, 552, 0x30DCE6F0);
        float frac = max == 0 ? 0 : (float) scroll / max;
        rect(c, 1051.5f, 293 + frac * (552 - 90), 6, 90, 0xB0E7C77E);
        vline(c, 1073, 290, 850, 0x40E7C77E);
        diamond(c, 1073, 290, 3.5f, GOLD);
        if (rows.isEmpty()) { text(c, "조건에 맞는 물고기가 없어요.", 610, 520, 19, MUTED, BODY, true); detail(c, t, null); return; }
        detail(c, t, rows.get(selected));
    }

    private void detail(DrawContext c, float t, Entry e) {
        if (e == null) return;
        int gc = GRADE[e.grade()];
        text(c, e.caught() ? e.name() : "???", 1111, 333, fit(e.name(), 40, TITLE, 380), IVORY, TITLE, false);
        text(c, grade(e.grade()), 1113, 377, 23, gc, BOLD, false);
        card(c, 1103, 403, 395, 172, 0xE00B1530, gc, 1.8f);
        bloom(c, 1300, 489, 150, gc & 0xFFFFFF, .14f + .04f * (float) Math.sin(t * 2));
        Art art = e.caught() ? fishArt(e) : null;
        c.enableScissor(1105, 405, 1496, 573);
        try {
            if (art != null) draw(c, art, 1300 - 84, 489 - 84, 168, 168, 0xFFFFFFFF);
            else drawCentered(c, SHAPES[e.shape()], 1300, 489, 300, 300, e.caught() ? 0xFF1C3460 : 0xFF050B18, false, 0, false);
        } finally { c.disableScissor(); }
        if (!e.caught()) draw(c, LOCK, 1300 - 22, 489 - 22, 44, 44, 0xDDFFFFFF);
        var lines = UiResources.text().wrapText(e.description(), 380, 18);
        float y = 606;
        for (int i = 0; i < Math.min(2, lines.size()); i++) { text(c, lines.get(i), 1111, y, 18, TEXT, BODY, false); y += 22; }
        String[][] rows = {{"최대 크기", e.caught() ? cm(e.best()) : "???"}, {"잡은 횟수", e.count() + "회"}, {"계절", e.season()},
                {"장소", e.place()}, {"수심", e.depth()}, {"시간", e.time()}};
        float ry = 652;
        for (String[] r : rows) {
            hline(c, 1103, 1497, ry - 17, 0x30DCE6F0);
            text(c, r[0], 1111, ry, 17, MUTED, BODY, false);
            text(c, r[1], 1333, ry, fit(r[1], 17, BODY, 160), TEXT, BODY, false);
            ry += 35;
        }
    }

    private void treasures(DrawContext c, float t, double mx, double my) {
        int[] counts = codex == null ? new int[4] : codex.treasures();
        for (int g = 0; g < 4; g++) {
            float x = GRID_X + g * STEP_X * 1.2f, y = GRID_Y + 40, w = CELL_W * 1.1f, h = CELL_H * 1.6f;
            boolean sel = g == selected, over = inside(mx, my, x, y, w, h);
            int gc = GRADE[g];
            card(c, x, y, w, h, sel ? 0xF01A2A55 : over ? 0xE8132544 : 0xE00F1D39, sel ? gc : alpha(gc, .45f), sel ? 2.4f : 1.3f);
            if (counts[g] > 0) bloom(c, x + w / 2, y + 86, 70, gc & 0xFFFFFF, .16f);
            draw(c, chest(g, counts[g] > 0), x + w / 2 - 70, y + 14, 140, 140, counts[g] > 0 ? 0xFFFFFFFF : 0xFF3A4050);
            text(c, grade(g) + " 보물상자", x + w / 2, y + 170, 16, counts[g] > 0 ? gc : DIM, BOLD, true);
            text(c, counts[g] + "개 열어 봄", x + w / 2, y + 192, 14, MUTED, BODY, true);
        }
        vline(c, 1073, 290, 850, 0x40E7C77E);
        int g = Math.clamp(selected, 0, 3);
        int gc = GRADE[g];
        text(c, grade(g) + " 보물상자", 1111, 333, 36, IVORY, TITLE, false);
        text(c, grade(g), 1113, 377, 23, gc, BOLD, false);
        card(c, 1103, 403, 395, 172, 0xE00B1530, gc, 1.8f);
        bloom(c, 1300, 489, 150, gc & 0xFFFFFF, .14f);
        draw(c, chest(g, true), 1300 - 80, 489 - 80, 160, 160, counts[g] > 0 ? 0xFFFFFFFF : 0xFF3A4050);
        text(c, "낚시 중에 가끔 물고기 대신 낚여요.", 1111, 606, 18, TEXT, BODY, false);
        text(c, "깊이 낚을수록 좋은 상자가 나와요.", 1111, 628, 18, TEXT, BODY, false);
        hline(c, 1103, 1497, 670, 0x30DCE6F0);
        text(c, "열어 본 횟수", 1111, 687, 17, MUTED, BODY, false);
        text(c, counts[g] + "회", 1333, 687, 17, TEXT, BODY, false);
    }
}
