package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.DrawBoardProtocol;

import static school.magiccodex.client.DrawBoardLayout.*;

/**
 * 운영자 전용 「행운의 뽑기판」 창 (오너 디자인 납품 2026-10-26, §44). /뽑기판 으로 서버가 연다.
 * <ul>
 *   <li>10 × 10 칸. 칸을 누르면 서버(공유 DB)에 "이 칸 공개/가림"을 보내고, 서버 상태가 오면 짧게 뒤집힌다 (가로 |cos|, 반에서 면 바뀜)
 *       + 공개될 때 약한 반짝임. 같은 회차 안에서 그 칸의 아이템은 서버가 정한 그대로라 다시 가렸다 열어도 같다.</li>
 *   <li>다른 운영자(다른 서버 포함)가 바꾼 것도 서버가 보내 주면 같은 방식으로 뒤집힌다.</li>
 *   <li>100칸이 모두 공개되면 서버가 잠깐 그대로 둔 뒤 새로 섞은 다음 회차를 보낸다 → 모든 칸이 물결처럼 다시 가려진다.</li>
 *   <li>모든 칸은 같은 정사각형 (DrawBoardLayout: 화면 배율은 하나, 칸 PNG 의 테두리 상자를 같은 칸에 맞춤).</li>
 * </ul>
 * 제목·안내·닫기 X·아이템은 기존 글꼴·선·아이템 렌더러로 그린다 (그림에 굽지 않음). 화면 전체를 어둡게 덮지 않는다.
 */
public final class DrawBoardScreen extends Screen implements DrawBoardClient.Viewer {
    private static final String DIR = "magiccodex:textures/gui/draw100/";
    static final String PANEL_PNG = DIR + "panel.png", HIDDEN = DIR + "tile_hidden.png", HOVER = DIR + "tile_hover.png",
            REVEALED = DIR + "tile_revealed.png", FX = DIR + "reveal_fx.png";
    private static final Identifier TITLE_FACE = Identifier.of("magiccodex", "title"), TEXT_FACE = Identifier.of("magiccodex", "label");
    private static final int TITLE_RGB = 0xF3DDA0, GUIDE_RGB = 0xE8E2D0, NOTE_RGB = 0x92E7F2, CLOSE_RGB = 0xE8CF96, CLOSE_HOT = 0xFFF4D0;

    private final long session;
    private DrawBoardProtocol.State state;
    private HudTextureCache images;
    private CodexTypography typography;
    /** 칸마다: 지금 목표 면(공개?), 보여 줄 아이템 번호, 뒤집기 시작 시각(-1 없음), 뒤집기 전 면, 누르고 답 기다리는 중(시각), 호버 정도. */
    private final boolean[] target = new boolean[CELLS], from = new boolean[CELLS];
    private final int[] item = new int[CELLS];
    private final long[] flipAt = new long[CELLS], pressAt = new long[CELLS], fxAt = new long[CELLS];
    private final float[] hover = new float[CELLS];
    private int round = -1, lastHot = -1;
    private long itemsRevision = Long.MIN_VALUE, lastFrame = -1, noteAt;
    private String note = "";

    private static final int CELLS = DrawBoardProtocol.CELLS;

    DrawBoardScreen(long session) {
        super(Text.literal("행운의 뽑기판"));
        this.session = session;
        java.util.Arrays.fill(flipAt, -1); java.util.Arrays.fill(pressAt, -1); java.util.Arrays.fill(fxAt, -1); java.util.Arrays.fill(item, -1);
    }

    @Override public long session() { return session; }

    @Override protected void init() {
        if (images == null) images = new HudTextureCache(client, true);
        typography = UiResources.text();
    }

    // ------------------------------------------------------------------ 상태 받기

    @Override public void receive(DrawBoardProtocol.State s) {
        long now = Util.getMeasuringTimeMs();
        boolean first = state == null, newRound = !first && s.round() != round;
        var next = new boolean[CELLS];
        var nextItem = new int[CELLS];
        java.util.Arrays.fill(nextItem, -1);
        for (var c : s.revealed()) { next[c.cell()] = true; nextItem[c.cell()] = c.item(); }
        for (int i = 0; i < CELLS; i++) {
            pressAt[i] = -1;
            if (first) { target[i] = next[i]; item[i] = nextItem[i]; continue; }
            if (next[i] == target[i]) { if (next[i]) item[i] = nextItem[i]; continue; }
            from[i] = shownFace(i, now);
            target[i] = next[i];
            if (next[i]) item[i] = nextItem[i];   // 가릴 때는 앞 반 동안 원래 아이템을 그대로 보여 줌
            // 새 회차: 칸마다 조금씩 늦춰 물결처럼
            flipAt[i] = now + (newRound ? (i / 10 + i % 10) * WAVE_STEP_MS : 0);
            fxAt[i] = next[i] ? flipAt[i] + FLIP_MS / 2 : -1;   // 공개: 면이 바뀌는 때부터 반짝임
        }
        if (!first && !newRound && s.revealed().size() > countTargets(state)) sound("light.chime", .12f, 1.35f);
        if (newRound) sound("light.ritual", .25f, 1.2f);
        state = s; round = s.round(); itemsRevision = s.itemsRevision();
        if (!s.message().isEmpty()) { note = s.message(); noteAt = now; }
    }

    private static int countTargets(DrawBoardProtocol.State s) { return s == null ? 0 : s.revealed().size(); }

    /** 지금 화면에 보이는 면 (뒤집는 중이면 반을 지났는지로). */
    private boolean shownFace(int i, long now) {
        if (flipAt[i] < 0 || now < flipAt[i]) return flipAt[i] < 0 ? target[i] : from[i];
        float t = (now - flipAt[i]) / (float) FLIP_MS;
        return t >= 1 || secondHalf(t) ? target[i] : from[i];
    }

    // ------------------------------------------------------------------ 그리기

    private boolean loaded(String path) { return images.size(path) != null || images.failed(path); }
    private static int argb(int rgb, float a) { return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24 | (rgb & 0xFFFFFF); }

    @Override public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {}   // 게임 화면을 어둡게 덮지 않음

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        if (images == null) init();
        typography = UiResources.text();
        images.beginFrame(); typography.beginFrame();
        ItemStack tooltip = ItemStack.EMPTY;
        try {
            long now = Util.getMeasuringTimeMs();
            float dt = lastFrame < 0 ? 0 : Math.min(100, now - lastFrame);
            lastFrame = now;
            boolean ready = loaded(PANEL_PNG) & loaded(HIDDEN) & loaded(REVEALED);
            loaded(HOVER); loaded(FX);
            if (!ready || state == null) { loading(c, now); return; }
            float s = scale(width, height), left = (width - PANEL * s) / 2, top = (height - PANEL * s) / 2;
            float mx = (mouseX - left) / s, my = (mouseY - top) / s;
            boolean playable = state.ready() && !state.complete();
            int hot = playable ? cellAt(mx, my) : -1;
            if (hot != lastHot && hot >= 0 && !target[hot]) sound("light.chime", .05f, 1.6f);
            lastHot = hot;
            var m = c.getMatrices();
            m.push();
            try {
                m.translate(left, top, 0); m.scale(s, s, 1);
                images.drawStretched(c, PANEL_PNG, 0, 0, PANEL, PANEL, 0xFFFFFFFF); c.draw();
                text(c, "행운의 뽑기판", CX, TITLE_Y, TITLE_SIZE, TITLE_RGB, 1, TITLE_FACE);
                boolean noting = !note.isEmpty() && now - noteAt < 2600;
                if (state.complete()) text(c, "모두 공개! 곧 새로 섞어요", CX, GUIDE_Y, GUIDE_SIZE, TITLE_RGB, .55f + .45f * (float) Math.abs(Math.sin(now / 260.0)), TEXT_FACE);
                else text(c, noting ? note : "원하는 칸을 눌러 아이템을 확인하세요.", CX, GUIDE_Y, GUIDE_SIZE, noting ? NOTE_RGB : GUIDE_RGB, 1, TEXT_FACE);
                closeButton(c, closeHit(mx, my));
                float dim = state.ready() ? 1 : .35f;
                // 1) 칸 바탕 (숨김·공개 면, 호버 겹침)
                for (int i = 0; i < CELLS; i++) {
                    hover[i] += ((i == hot && !target[i] && flipAt[i] < 0 ? 1 : 0) - hover[i]) * Math.min(1, dt / HOVER_MS);
                    float x = cellX(i), y = cellY(i), w = 1;
                    boolean face = shownFace(i, now);
                    if (flipAt[i] >= 0 && now >= flipAt[i]) {
                        float t = (now - flipAt[i]) / (float) FLIP_MS;
                        if (t >= 1) flipAt[i] = -1; else w = flipWidth(t);
                    }
                    float press = pressAt[i] >= 0 ? .94f : 1;   // 누르고 답 기다리는 동안 살짝 눌림
                    if (face) tile(c, REVEALED, REVEALED_FRAME, x, y, w * press, argb(0xFFFFFF, dim));
                    else {
                        tile(c, HIDDEN, HIDDEN_FRAME, x, y, w * press, argb(0xFFFFFF, dim));
                        if (hover[i] > .02f && !images.failed(HOVER)) tile(c, HOVER, HOVER_FRAME, x, y, w * press, argb(0xFFFFFF, hover[i] * dim));
                    }
                }
                c.draw();
                // 2) 공개된 칸의 실제 아이템
                for (int i = 0; i < CELLS; i++) {
                    if (!shownFace(i, now) || item[i] < 0) continue;
                    var stack = DrawBoardClient.item(itemsRevision, item[i]);
                    float w = flipAt[i] >= 0 && now >= flipAt[i] ? flipWidth((now - flipAt[i]) / (float) FLIP_MS) : 1;
                    if (stack.isEmpty()) { waiting(c, cellX(i) + TILE / 2, cellY(i) + TILE / 2, now); continue; }
                    drawItem(c, stack, cellX(i), cellY(i), w);
                    if (i == cellAt(mx, my) && flipAt[i] < 0) tooltip = stack;
                }
                // 3) 공개 반짝임: 면이 바뀐 때부터 FX_MS 동안 올라갔다 내려감 (칸 밖으로 넘치는 빛은 그대로)
                for (int i = 0; i < CELLS; i++) {
                    if (fxAt[i] < 0 || now < fxAt[i]) continue;
                    float t = (now - fxAt[i]) / (float) FX_MS;
                    if (t >= 1 || !target[i]) { fxAt[i] = -1; continue; }
                    if (!images.failed(FX)) tile(c, FX, FX_FRAME, cellX(i), cellY(i), 1, argb(0xFFFFFF, (float) Math.sin(Math.PI * t) * .9f));
                }
                c.draw();
                if (!state.ready()) notReady(c);
            } finally { m.pop(); }
        } finally { images.endFrame(); }
        if (!tooltip.isEmpty() && !ItemTooltipRenderer.render(c, textRenderer, tooltip, mouseX, mouseY)) c.drawItemTooltip(textRenderer, tooltip, mouseX, mouseY);
    }

    /** 칸 PNG 하나: 테두리 상자를 (x, y, TILE, TILE) 정사각형에 맞추고, 칸 가운데 기준 가로 배율 w (뒤집기). */
    private void tile(DrawContext c, String path, int[] frame, float x, float y, float w, int tint) {
        if (w <= .001f) return;
        float[] f = fit(frame, x, y, TILE);
        var m = c.getMatrices();
        m.push();
        try {
            float cx = x + TILE / 2;
            m.translate(cx, 0, 0); m.scale(w, 1, 1); m.translate(-cx, 0, 0);
            m.translate(f[0], f[1], 0); m.scale(f[2], f[3], 1);
            images.drawStretched(c, path, 0, 0, TEX, TEX, tint);
        } finally { m.pop(); }
    }

    /** 등록한 실제 ItemStack (기존 아이템 렌더러 + 개수 표시). 칸 한 변의 ITEM_FRACTION 크기, 가운데. */
    private void drawItem(DrawContext c, ItemStack stack, float x, float y, float w) {
        if (w <= .02f) return;
        float k = TILE * ITEM_FRACTION / 16f, cx = x + TILE / 2, cy = y + TILE / 2;
        var m = c.getMatrices();
        m.push();
        try {
            m.translate(cx, cy, 0); m.scale(w * k, k, 1); m.translate(-8, -8, 0);
            c.drawItem(stack, 0, 0);
            c.drawStackOverlay(textRenderer, stack, 0, 0);
        } finally { m.pop(); }
    }

    private void closeButton(DrawContext c, boolean hot) {
        float h = CLOSE_SIZE / 2;
        int color = argb(hot ? CLOSE_HOT : CLOSE_RGB, 1);
        HudMesh.line(c, CLOSE_X - h, CLOSE_Y - h, CLOSE_X + h, CLOSE_Y + h, hot ? 4.2f : 3.2f, color);
        HudMesh.line(c, CLOSE_X - h, CLOSE_Y + h, CLOSE_X + h, CLOSE_Y - h, hot ? 4.2f : 3.2f, color);
        c.draw();
    }

    /** 등록이 100개가 아닐 때: 흐린 칸 위에 안내 상자. */
    private void notReady(DrawContext c) {
        float w = 760, h = 150, x = CX - w / 2, y = 700 - h / 2;
        HudMesh.quad(c, x, y, w, h, 0xEB08122C, 0xEB08122C);
        int gold = argb(CLOSE_RGB, 1);
        HudMesh.line(c, x, y, x + w, y, 2, gold); HudMesh.line(c, x + w, y, x + w, y + h, 2, gold);
        HudMesh.line(c, x + w, y + h, x, y + h, 2, gold); HudMesh.line(c, x, y + h, x, y, 2, gold);
        c.draw();
        text(c, "등록된 아이템 " + state.registered() + "/100개", CX, 676, 28, TITLE_RGB, 1, TEXT_FACE);
        text(c, "/뽑기판 관리 에서 100개를 채우면 판이 시작돼요", CX, 724, 22, GUIDE_RGB, 1, TEXT_FACE);
    }

    private void waiting(DrawContext c, float cx, float cy, long now) {
        for (int i = 0; i < 3; i++) HudMesh.disk(c, cx - 10 + i * 10, cy, 2.6f, argb(0xE8CF96, (float) (.35 + .65 * (.5 + .5 * Math.sin(now / 180.0 - i * 1.2)))));
        c.draw();
    }

    private void loading(DrawContext c, long now) {
        for (int i = 0; i < 3; i++) HudMesh.disk(c, width / 2f - 12 + i * 12, height / 2f, 2.5f, argb(0xE8CF96, (float) (.35 + .65 * (.5 + .5 * Math.sin(now / 180.0 - i * 1.2)))));
    }

    private void text(DrawContext c, String value, float x, float y, float size, int rgb, float alpha, Identifier face) {
        if (alpha <= 0) return;
        float fit = Math.min(size, size * (PANEL - 260) / Math.max(1, typography.width(value, size, face)));
        typography.draw(c, value, x, y, fit, argb(rgb, alpha), face, true);
        c.draw();
    }

    private void sound(String name, float volume, float pitch) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of("magiccodex", name)), pitch, volume));
    }

    // ------------------------------------------------------------------ 입력

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0 || state == null) return true;
        float s = scale(width, height), left = (width - PANEL * s) / 2, top = (height - PANEL * s) / 2;
        float mx = (float) ((x - left) / s), my = (float) ((y - top) / s);
        if (closeHit(mx, my)) { close(); return true; }
        int cell = cellAt(mx, my);
        if (cell < 0 || !state.ready() || state.complete()) return true;
        long now = Util.getMeasuringTimeMs();
        if (flipAt[cell] >= 0 || pressAt[cell] >= 0 && now - pressAt[cell] < 2000) return true;   // 뒤집는 중·답 기다리는 중
        boolean reveal = !target[cell];
        if (DrawBoardClient.send(session, DrawBoardProtocol.FLIP, round, cell, reveal, -1, "", "") == 0) { note = "서버 연결을 확인하세요"; noteAt = now; return true; }
        pressAt[cell] = now;
        sound("light.chime", .1f, reveal ? 1.15f : .9f);
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override public void tick() { if (client != null && (client.player == null || client.world == null || !client.player.isAlive())) close(); }
    @Override public boolean shouldPause() { return false; }

    @Override public void removed() {
        DrawBoardClient.close(session);
        if (images != null) { images.close(); images = null; }
        super.removed();
    }
}
