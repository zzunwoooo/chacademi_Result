package school.magiccodex.client;

import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.HousingRankProtocol;

/**
 * 하우징 랭킹 창 (#51). 승인 레퍼런스 "하우징_랭킹" (2026-10-25)의 layout.json 좌표를 그대로 가상 좌표(1672×941)로 쓴다.
 * 그림은 textures/gui/housing_rank/*.png (분리 PNG 11장), 글씨·숫자는 전부 코드로 그린다. panel 은 표 선이 들어 있어 통째로만 맞춘다(9조각 금지).
 * 탭 = 일일 방문 / 누적 방문 / 하트 (따로 정렬 버튼 없음). 1~3위는 월계관 가운데에 숫자, 4위부터는 숫자만. 방 그림·썸네일 없음.
 * 방 이동은 서버가 기존 친구 방문 규칙으로 다시 판단한다 (버튼이 켜져 보여도 서버가 거절할 수 있음).
 */
public final class HousingRankScreen extends Screen {
    private static final float VW = 1672, VH = 941;
    private static final Identifier BODY = Identifier.of("magiccodex", "label"), BOLD = Identifier.of("magiccodex", "hud_bold"), SERIF = Identifier.of("magiccodex", "heading");
    private static final int GOLD = 0xFFF0D9A8, HEAD = 0xFFE8D19B, TEXT = 0xFFF1EEE3, MUTED = 0xFF9FB2C4, DARK = 0xFF2A1E0A, LOCKED = 0xFFB9C3CC;
    private record Tex(String name, int w, int h, int[] ltrb) { Identifier id() { return Identifier.of("magiccodex", "textures/gui/housing_rank/" + name + ".png"); } }
    private static final int[] SLICE = {28, 24, 28, 24};
    private static final Tex PANEL = new Tex("panel", 1672, 941, null), TAB_ON = new Tex("tab_selected", 463, 140, SLICE), TAB_OFF = new Tex("tab_normal", 426, 140, SLICE),
            VISIT_ON = new Tex("visit_enabled", 463, 140, SLICE), VISIT_OFF = new Tex("visit_disabled", 458, 137, SLICE), CLOSE = new Tex("close", 126, 126, null),
            LEFT = new Tex("page_left", 92, 151, null), RIGHT = new Tex("page_right", 92, 146, null),
            GOLD_MEDAL = new Tex("rank_gold", 257, 273, null), SILVER_MEDAL = new Tex("rank_silver", 265, 274, null), BRONZE_MEDAL = new Tex("rank_bronze", 1053, 1120, null);
    // layout.json housing_layout
    private static final float[][] TABS = {{616, 134, 205, 62}, {831, 134, 201, 62}, {1039, 134, 180, 62}};
    private static final String[] TAB_NAMES = {"일일 방문", "누적 방문", "하트"};
    private static final float[] COLUMNS = {190, 442, 728, 958, 1184, 1432};
    private static final String[] HEADERS = {"순위", "주인", "오늘 방문", "누적 방문", "하트", "방문"};
    private static final float[] ROW_Y = {291, 378, 465, 552, 639, 726};
    private static final float ROW_H = 87, VISIT_X = 1338, VISIT_DY = 18, VISIT_W = 195, VISIT_H = 58;
    private static final float[] PAGE_LEFT = {740, 838, 17, 26}, PAGE_RIGHT = {915, 838, 17, 26}, CLOSE_BOX = {1539, 122, 32, 32};

    private int metric, page;
    private float x0, y0, k = 1;

    HousingRankScreen(int metric) { super(Text.literal("하우징 랭킹")); this.metric = Math.clamp(metric, 0, 2); }

    @Override protected void init() { fit(); if (HousingRankClient.page == null || HousingRankClient.page.metric() != metric) HousingRankClient.query(metric, page); }
    @Override public void resize(net.minecraft.client.MinecraftClient client, int w, int h) { super.resize(client, w, h); fit(); }
    @Override public boolean shouldPause() { return false; }
    @Override public void tick() { HousingRankClient.tick(); }

    private void fit() { k = Math.min(width / VW, height / VH) * 0.94f; x0 = (width - VW * k) / 2; y0 = (height - VH * k) / 2; }
    private double lx(double mx) { return (mx - x0) / k; }
    private double ly(double my) { return (my - y0) / k; }
    private static boolean in(double x, double y, float[] r, float pad) { return x >= r[0] - pad && x < r[0] + r[2] + pad && y >= r[1] - pad && y < r[1] + r[3] + pad; }

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        fit();
        double mx = lx(mouseX), my = ly(mouseY);
        var images = UiResources.images(); images.beginFrame(); UiResources.text().beginFrame();
        c.fill(0, 0, width, height, 0x88050A14);
        c.getMatrices().push();
        try {
            c.getMatrices().translate(x0, y0, 0); c.getMatrices().scale(k, k, 1);
            whole(c, PANEL, 0, 0, VW, VH, 0xFFFFFFFF);
            // 제목 (오너 2026-10-25): 기존 UI 굵은 글꼴, 별 장식 오른쪽 · 밑줄 높이(158)에 세로 가운데 — 밑줄은 이 구간에서 흐려져 글씨와 겹쳐 보이지 않음
            text(c, "하우징 랭킹", 311, 158, 44, GOLD, BOLD, true);
            for (int i = 0; i < 3; i++) {
                var r = TABS[i]; boolean on = i == metric, over = !on && in(mx, my, r, 0);
                nine(c, on ? TAB_ON : TAB_OFF, r[0], r[1], r[2], r[3], over ? 0xFFFFF4DC : 0xFFFFFFFF);
                text(c, TAB_NAMES[i], r[0] + r[2] / 2, r[1] + r[3] / 2, 24, on ? DARK : over ? TEXT : 0xFFE6E0CF, on ? BOLD : BODY, true);
            }
            boolean closeOver = in(mx, my, CLOSE_BOX, 10);
            whole(c, CLOSE, CLOSE_BOX[0], CLOSE_BOX[1], CLOSE_BOX[2], CLOSE_BOX[3], closeOver ? 0xFFFFFFFF : 0xE6FFFFFF);
            for (int i = 0; i < 6; i++) text(c, HEADERS[i], COLUMNS[i], 255, 22, HEAD, BODY, true);

            var data = HousingRankClient.page;
            boolean ready = data != null && data.metric() == metric && !HousingRankClient.loading;
            if (!ready) text(c, "불러오는 중…", VW / 2, 500, 24, MUTED, BODY, true);
            else if (!data.error().isEmpty()) text(c, data.error(), VW / 2, 500, 22, MUTED, BODY, true);
            else if (data.rows().isEmpty()) text(c, metric == 0 ? "오늘은 아직 방문 기록이 없어요" : metric == 1 ? "아직 방문 기록이 없어요" : "아직 하트를 받은 방이 없어요", VW / 2, 500, 24, MUTED, BODY, true);
            else rows(c, data.rows(), mx, my);

            text(c, "오늘 방문은 한국 시간 기준", 128, 851, 17, MUTED, BODY, false);
            int pages = ready ? data.pages() : 1, shown = ready ? data.page() : page;
            boolean prev = ready && shown > 0, next = ready && shown + 1 < pages;
            whole(c, LEFT, PAGE_LEFT[0], PAGE_LEFT[1], PAGE_LEFT[2], PAGE_LEFT[3], prev ? (in(mx, my, PAGE_LEFT, 14) ? 0xFFFFFFFF : 0xE6FFFFFF) : 0x55FFFFFF);
            whole(c, RIGHT, PAGE_RIGHT[0], PAGE_RIGHT[1], PAGE_RIGHT[2], PAGE_RIGHT[3], next ? (in(mx, my, PAGE_RIGHT, 14) ? 0xFFFFFFFF : 0xE6FFFFFF) : 0x55FFFFFF);
            text(c, (shown + 1) + " / " + pages, 836, 852, 22, TEXT, SERIF, true);
            if (Util.getMeasuringTimeMs() < HousingRankClient.noticeUntil) text(c, HousingRankClient.notice, 1536, 851, 17, GOLD, BODY, false, true);
        } finally { c.getMatrices().pop(); images.endFrame(); }
    }

    private void rows(DrawContext c, List<HousingRankProtocol.Row> rows, double mx, double my) {
        for (int i = 0; i < rows.size() && i < ROW_Y.length; i++) {
            var r = rows.get(i); float top = ROW_Y[i], cy = top + ROW_H / 2;
            boolean over = mx >= 104 && mx < 1570 && my >= top && my < top + ROW_H;
            if (over) { // 승인 기준: 옅은 청록 바탕 + 금색 테두리
                c.fill(105, Math.round(top + 1), 1569, Math.round(top + ROW_H - 1), 0x2E6FC7D6);
                c.fill(105, Math.round(top + 1), 1569, Math.round(top + 2), 0x99E8C878);
                c.fill(105, Math.round(top + ROW_H - 2), 1569, Math.round(top + ROW_H - 1), 0x99E8C878);
            }
            Tex medal = r.rank() == 1 ? GOLD_MEDAL : r.rank() == 2 ? SILVER_MEDAL : r.rank() == 3 ? BRONZE_MEDAL : null;
            if (medal != null) {
                float h = 76, w = h * medal.w() / medal.h();
                whole(c, medal, COLUMNS[0] - w / 2, cy - h / 2, w, h, 0xFFFFFFFF);
                text(c, Integer.toString(r.rank()), COLUMNS[0], cy + 3, 30, 0xFFFFF4D6, SERIF, true);
            } else text(c, Integer.toString(r.rank()), COLUMNS[0], cy, 28, HEAD, SERIF, true);
            fitted(c, r.name(), COLUMNS[1], cy, 26, 300, TEXT, BODY);
            text(c, HousingRankProtocol.number(r.today()), COLUMNS[2], cy, 26, TEXT, SERIF, true);
            text(c, HousingRankProtocol.number(r.total()), COLUMNS[3], cy, 26, TEXT, SERIF, true);
            text(c, HousingRankProtocol.number(r.hearts()), COLUMNS[4], cy, 26, TEXT, SERIF, true);
            float[] b = {VISIT_X, top + VISIT_DY, VISIT_W, VISIT_H};
            boolean open = r.visit() != HousingRankProtocol.VISIT_LOCKED, hover = open && in(mx, my, b, 0);
            nine(c, open ? VISIT_ON : VISIT_OFF, b[0], b[1], b[2], b[3], hover ? 0xFFFFF4DC : 0xFFFFFFFF);
            String label = r.visit() == HousingRankProtocol.VISIT_SELF ? "내 방" : open ? "방 이동" : "친구 전용";
            text(c, label, b[0] + b[2] / 2, b[1] + b[3] / 2, 22, open ? (hover ? 0xFFFFFFFF : 0xFFFCEFD0) : LOCKED, open ? BOLD : BODY, true);
        }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return super.mouseClicked(x, y, button);
        double mx = lx(x), my = ly(y);
        if (in(mx, my, CLOSE_BOX, 10)) { click(); close(); return true; }
        for (int i = 0; i < 3; i++) if (in(mx, my, TABS[i], 0)) { if (i != metric) { metric = i; page = 0; HousingRankClient.query(metric, page); click(); } return true; }
        var data = HousingRankClient.page;
        boolean ready = data != null && data.metric() == metric && !HousingRankClient.loading;
        if (ready && in(mx, my, PAGE_LEFT, 14) && data.page() > 0) { page = data.page() - 1; HousingRankClient.query(metric, page); click(); return true; }
        if (ready && in(mx, my, PAGE_RIGHT, 14) && data.page() + 1 < data.pages()) { page = data.page() + 1; HousingRankClient.query(metric, page); click(); return true; }
        if (ready) for (int i = 0; i < data.rows().size() && i < ROW_Y.length; i++) {
            var r = data.rows().get(i); float[] b = {VISIT_X, ROW_Y[i] + VISIT_DY, VISIT_W, VISIT_H};
            if (in(mx, my, b, 0)) { if (r.visit() != HousingRankProtocol.VISIT_LOCKED) { click(); HousingRankClient.visit(r.owner()); } return true; }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double h, double v) {
        var data = HousingRankClient.page;
        if (data == null || data.metric() != metric || HousingRankClient.loading) return false;
        int next = Math.clamp(data.page() - (int) Math.signum(v), 0, data.pages() - 1);
        if (next != data.page()) { page = next; HousingRankClient.query(metric, page); }
        return true;
    }

    // ------------------------------------------------------------------ 그리기 도우미
    private static void whole(DrawContext c, Tex t, float x, float y, float w, float h, int tint) {
        int ix = Math.round(x), iy = Math.round(y), iw = Math.round(x + w) - ix, ih = Math.round(y + h) - iy;
        UiResources.images().drawTexture(c, t.id(), ix, iy, 0, 0, iw, ih, t.w(), t.h(), t.w(), t.h(), tint);
    }
    /** 9조각: 원본 px 기준 ltrb 를 칸 높이 비율로 줄여 모서리로 쓴다 (layout.json nine_slice_ltrb). */
    private static void nine(DrawContext c, Tex t, float x, float y, float w, float h, int tint) {
        int[] s = t.ltrb(); float scale = h / t.h();
        float l = s[0] * scale, tp = s[1] * scale, r = s[2] * scale, b = s[3] * scale;
        int[] sx = {0, s[0], t.w() - s[2], t.w()}, sy = {0, s[1], t.h() - s[3], t.h()};
        int[] dx = {Math.round(x), Math.round(x + l), Math.round(x + w - r), Math.round(x + w)}, dy = {Math.round(y), Math.round(y + tp), Math.round(y + h - b), Math.round(y + h)};
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            int dw = dx[col + 1] - dx[col], dh = dy[row + 1] - dy[row], sw = sx[col + 1] - sx[col], sh = sy[row + 1] - sy[row];
            if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) continue;
            UiResources.images().drawTexture(c, t.id(), dx[col], dy[row], sx[col], sy[row], dw, dh, sw, sh, t.w(), t.h(), tint);
        }
    }
    private static void text(DrawContext c, String s, float x, float y, float size, int color, Identifier face, boolean center) { text(c, s, x, y, size, color, face, center, false); }
    /** right = 오른쪽 끝을 x 에 맞춤. */
    private static void text(DrawContext c, String s, float x, float y, float size, int color, Identifier face, boolean center, boolean right) {
        if (right) x -= UiResources.text().width(s, size, face);
        UiResources.text().draw(c, s, x, y, size, color, face, center);
    }
    private static void fitted(DrawContext c, String s, float cx, float y, float size, float w, int color, Identifier face) {
        float fit = Math.min(size, size * w / Math.max(w, UiResources.text().width(s, size, face)));
        UiResources.text().draw(c, s, cx, y, fit, color, face, true);
    }
    private void click() { if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1f, .25f)); }
}
