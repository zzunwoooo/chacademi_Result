package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.protocol.TarotProtocol;

import static school.magiccodex.client.TarotLayout.*;

/**
 * 마법 힌트권 「운명의 타로」 창 (오너 디자인 납품 2026-10-26, §41). 결과(별·힌트·그림)는 서버가 이미 정했다 — 여기서는 보여 주기만.
 * <ol>
 *   <li>선택: 같은 뒷면 3장 부채꼴. 마우스를 올리거나(또는 ←/→/Tab) 고른 카드만 들리고 금색 테두리. 클릭·Enter·Space 로 한 장.</li>
 *   <li>공개: 한 번 고르면 입력 잠금. 고른 카드는 가운데로, 나머지는 사라짐. 세로축 뒤집기(가로 배율 |cos|, 거울상 없음),
 *       90도에서 뒷면 → 앞면(그림 위에 등급 빈 프레임). 앞면 그림이 아직 안 올라왔으면 90도에서 기다린다.</li>
 *   <li>결과: 카드 위 등급 별 1~3, 아래 힌트 바탕(위·아래 띠 고정, 가운데만 늘림)에 2~3줄(길면 4줄), 「확인」(빈 버튼 PNG 위 글자).</li>
 * </ol>
 * 패널·힌트 바탕은 PNG 알파 그대로 (게임 화면이 비침). 화면 전체를 어둡게 덮는 사각형은 그리지 않는다.
 * 닫힐 때(확인·Esc·다른 창) 채팅에 힌트 한 줄 → 고르기 전에 Esc 로 닫아도 힌트는 남는다.
 * 그림은 이 창 전용 캐시에 올리고 창이 닫히면 GPU 에서 내린다 (카드 PNG 1024×1536 이 커서 공용 캐시에 남기지 않음).
 */
public final class TarotScreen extends Screen {
    private static final String DIR = "magiccodex:textures/gui/tarot/";
    static final String PANEL = DIR + "ui-parts/main-panel-v2.png", HINT = DIR + "ui-parts/hint-panel-v3.png",
            ORNAMENT = DIR + "ui-parts/title-ornament.png", CONFIRM = DIR + "ui-parts/confirm-normal.png",
            CONFIRM_HOVER = DIR + "ui-parts/confirm-hover-v2.png", BACK = DIR + "cards/card_back.png";
    static final String[] FRAMES = {DIR + "cards/card_frame_1_silverblue.png", DIR + "cards/card_frame_2_violetblue.png", DIR + "cards/card_frame_3_gold.png"};
    static final String[] STARS = {DIR + "ui-parts/star-silver-blue.png", DIR + "ui-parts/star-violet-blue.png", DIR + "ui-parts/star-gold.png"};
    /** 별 PNG 안의 보이는 상자 (알파 64 초과). */
    private static final int[][] STAR_BOX = {{67, 65, 1221, 1146}, {70, 59, 1234, 1142}, {120, 117, 1177, 1091}};
    private static final int[] ORNAMENT_BOX = {66, 223, 2106, 497};
    private static final float ORNAMENT_LINE_SRC = 360;
    static final String[] ILLUSTRATIONS = {"01-moonlit-lake", "02-mystical-eye", "03-sun-mountains", "04-shooting-star", "05-magic-book",
            "06-golden-key", "07-hourglass", "08-lantern", "09-feather", "10-purple-crystals", "11-red-rose", "12-ancient-tree",
            "13-black-cat-crescent", "14-owl", "15-two-silver-fish", "16-pearl-shell", "17-silver-chalice", "18-floating-castle-island",
            "19-moonlit-doorway", "20-hand-golden-flame"};
    private static final Identifier TITLE_FACE = Identifier.of("magiccodex", "title"), TEXT_FACE = Identifier.of("magiccodex", "label");
    private static final int GOLD = 0xF3C45A, TITLE = 0xF3DDA0, GUIDE = 0xE8E2D0, HINT_TEXT = 0xF4F0E4, BUTTON_TEXT = 0xF6E7BF;

    private final TarotProtocol.Card card;
    private final String illustration, frame, star;
    private HudTextureCache images;
    private CodexTypography typography;
    private TarotLayout.Wrapped wrapped;
    private int focus = -1, hovered = -1, chosen = -1, lastHot = -1;
    private long clock, lastNow = -1;
    private float lastMx = Float.NaN, lastMy = Float.NaN;
    private boolean revealSound, confirmHover, chatted;
    private final float[] lift = new float[3];

    TarotScreen(TarotProtocol.Card card) {
        super(Text.literal("운명의 타로"));
        this.card = card;
        this.illustration = DIR + "illustrations/" + ILLUSTRATIONS[card.illustration()] + ".png";
        this.frame = FRAMES[card.stars() - 1];
        this.star = STARS[card.stars() - 1];
    }

    @Override protected void init() {
        if (images == null) images = new HudTextureCache(client, true);
        typography = UiResources.text();
    }

    // ------------------------------------------------------------------ 그리기

    private boolean loaded(String path) { return images.size(path) != null || images.failed(path); }
    private static int white(float a) { return argb(0xFFFFFF, a); }
    private static int argb(int rgb, float a) { return Math.round(clamp01(a) * 255) << 24 | (rgb & 0xFFFFFF); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    @Override public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {}   // 게임 화면을 어둡게·흐리게 하지 않음

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        if (images == null) init();
        typography = UiResources.text();
        images.beginFrame(); typography.beginFrame();
        try {
            long now = Util.getMeasuringTimeMs();
            // 앞면은 고르는 동안 미리 불러 둔다 (뒤집을 때 기다리지 않게)
            boolean selectReady = loaded(PANEL) & loaded(ORNAMENT) & loaded(BACK);
            boolean frontReady = loaded(frame) & loaded(illustration) & loaded(star);
            loaded(HINT); loaded(CONFIRM); loaded(CONFIRM_HOVER);
            if (chosen >= 0) {
                long dt = lastNow < 0 ? 0 : Math.min(100, now - lastNow);
                clock = frontReady || clock + dt < FLIP_HALF ? clock + dt : FLIP_HALF;   // 앞면 그림이 아직이면 90도(옆모습)에서 기다림
            }
            lastNow = now;
            if (!selectReady) { loading(c, now); return; }
            float s = scale(width, height), left = (width - PANEL_W * s) / 2, top = (height - PANEL_H * s) / 2;
            float mx = (mouseX - left) / s, my = (mouseY - top) / s;
            if (wrapped == null) wrapped = fitHint(card.line(), (text, size) -> typography.width(text, size, TEXT_FACE));
            var m = c.getMatrices();
            m.push();
            try {
                m.translate(left, top, 0); m.scale(s, s, 1);
                images.drawStretched(c, PANEL, 0, 0, PANEL_W, PANEL_H, 0xFFFFFFFF); c.draw();
                box(c, ORNAMENT, ORNAMENT_BOX, CX, ORNAMENT_LINE_Y, ORNAMENT_W / (ORNAMENT_BOX[2] - ORNAMENT_BOX[0]), ORNAMENT_LINE_SRC, 1);
                text(c, "운명의 타로", CX, TITLE_Y, TITLE_SIZE, TITLE, 1, TITLE_FACE);
                if (chosen < 0) select(c, mx, my, now);
                else reveal(c, mx, my);
                if (images.failed(BACK) || chosen >= 0 && clock >= FLIP_HALF && (images.failed(frame) || images.failed(illustration)))
                    text(c, "카드 그림을 불러오지 못했어요 · 힌트는 그대로예요", CX, 940, 20, GUIDE, .8f, TEXT_FACE);
            } finally { m.pop(); }
        } finally { images.endFrame(); }
    }

    /** 첫 열기에 그림이 아직 없을 때: 가운데 점 세 개만 (화면을 덮지 않음). */
    private void loading(DrawContext c, long now) {
        for (int i = 0; i < 3; i++) {
            float pulse = (float) (.35 + .65 * (.5 + .5 * Math.sin(now / 180.0 - i * 1.2)));
            HudMesh.disk(c, width / 2f - 12 + i * 12, height / 2f, 2.5f, argb(0xE8CF96, pulse));
        }
    }

    private void select(DrawContext c, float mx, float my, long now) {
        // 마우스가 움직였을 때만 마우스 쪽을 따름 (키보드로 고른 포커스를 가만히 있는 마우스가 덮지 않게)
        if (mx != lastMx || my != lastMy) { lastMx = mx; lastMy = my; hovered = cardAt(mx, my); if (hovered >= 0) focus = -1; }
        int hot = hovered >= 0 ? hovered : focus;
        if (hot != lastHot && hot >= 0) sound("light.chime", .07f, 1.5f);
        lastHot = hot;
        for (int i = 0; i < 3; i++) lift[i] = lerp(lift[i], i == hot ? 1 : 0, .25f);
        // 들린 카드를 맨 위에
        int[] order = hot == 0 ? new int[]{2, 1, 0} : hot == 2 ? new int[]{0, 1, 2} : new int[]{0, 2, 1};
        for (int i : order) backCard(c, selectX(i), selectY(i) - LIFT * lift[i], SELECT_H, selectTilt(i), 1, 1, lift[i]);
        text(c, "카드 한 장을 고르세요.", CX, GUIDE_Y, GUIDE_SIZE, GUIDE, 1, TITLE_FACE);
    }

    private void reveal(DrawContext c, float mx, float my) {
        long t = clock;
        float fade = 1 - progress(t, 0, FADE_MS);
        if (fade > 0) {
            for (int i = 0; i < 3; i++) if (i != chosen) backCard(c, selectX(i), selectY(i), SELECT_H, selectTilt(i), 1, fade, 0);
            text(c, "카드 한 장을 고르세요.", CX, GUIDE_Y, GUIDE_SIZE, GUIDE, fade, TITLE_FACE);
        }
        float p = smooth(progress(t, 0, MOVE_MS));
        float x = lerp(selectX(chosen), CX, p), y = lerp(selectY(chosen) - LIFT, RESULT_Y, p), h = lerp(SELECT_H, RESULT_H, p), tilt = lerp(selectTilt(chosen), 0, p);
        float degrees = flipDegrees(t), w = flipWidth(degrees);
        if (frontVisible(degrees)) {
            if (!revealSound) { revealSound = true; if (card.stars() == 3) sound("spell_discovered", .5f, 1f); else sound("light.chime", .3f, card.stars() == 2 ? 1.25f : 1.1f); }
            frontCard(c, x, y, h, w);
        } else backCard(c, x, y, h, tilt, w, 1, Math.max(0, 1 - p * 1.6f));
        if (t >= FLIP_HALF && !frontVisible(degrees)) {   // 앞면 그림 기다리는 중
            long now = Util.getMeasuringTimeMs();
            for (int i = 0; i < 3; i++) HudMesh.disk(c, CX - 24 + i * 24, RESULT_Y, 5, argb(0xE8CF96, (float) (.35 + .65 * (.5 + .5 * Math.sin(now / 180.0 - i * 1.2)))));
            c.draw();
        }
        if (t < RESULT_AT) return;
        // 별
        int n = card.stars();
        for (int i = 0; i < n; i++) {
            float a = star(t, i), size = STAR_SIZE * (.6f + .4f * a), sx = CX + (i - (n - 1) / 2f) * STAR_GAP;
            int[] b = STAR_BOX[n - 1];
            box(c, star, b, sx, STAR_Y, size / (b[3] - b[1]), (b[1] + b[3]) / 2f, a);
        }
        // 힌트
        float ta = smooth(progress(t, TEXT_AT, TEXT_MS)), ph = wrapped.panelHeight();
        hintPanel(c, ph, ta);
        float lh = wrapped.lineHeight(), cy = HINT_TOP + ph / 2 - (wrapped.lines().size() - 1) * lh / 2;
        for (int i = 0; i < wrapped.lines().size(); i++) text(c, wrapped.lines().get(i), CX, cy + i * lh, wrapped.size(), HINT_TEXT, ta, TEXT_FACE);
        // 확인
        float ba = smooth(progress(t, BUTTON_AT, BUTTON_MS)), by = buttonY(wrapped), bh = buttonH();
        boolean over = t >= CONFIRM_AT && Math.abs(mx - CX) <= BUTTON_W / 2 && Math.abs(my - by) <= bh / 2;
        if (over && !confirmHover) sound("light.chime", .07f, 1.5f);
        confirmHover = over;
        var m = c.getMatrices();
        m.push();
        try {
            float k = BUTTON_W / (BUTTON_SRC_X1 - BUTTON_SRC_X0);
            m.translate(CX - BUTTON_W / 2, by - bh / 2, 0); m.scale(k, k, 1);
            images.drawPart(c, over ? CONFIRM_HOVER : CONFIRM, 0, 0, BUTTON_SRC_X1 - BUTTON_SRC_X0, BUTTON_SRC_Y1 - BUTTON_SRC_Y0,
                    BUTTON_SRC_X0, BUTTON_SRC_Y0, BUTTON_SRC_X1 - BUTTON_SRC_X0, BUTTON_SRC_Y1 - BUTTON_SRC_Y0, white(ba));
            c.draw();
        } finally { m.pop(); }
        text(c, "확인", CX, by, BUTTON_TEXT, buttonColor(over), ba, TITLE_FACE);
    }
    private static int buttonColor(boolean over) { return over ? 0xFFF4D0 : BUTTON_TEXT; }

    /** 힌트 바탕: 보이는 부분만, 위·아래 띠는 원래 비율, 가운데 띠만 늘림 (모서리 장식이 찌그러지지 않게). */
    private void hintPanel(DrawContext c, float height, float alpha) {
        float k = HINT_W / (HINT_SRC_X1 - HINT_SRC_X0);
        int w = HINT_SRC_X1 - HINT_SRC_X0, topH = HINT_SLICE_TOP - HINT_SRC_Y0, bottomH = HINT_SRC_Y1 - HINT_SLICE_BOTTOM;
        int total = Math.round(height / k), middle = Math.max(1, total - topH - bottomH);
        var m = c.getMatrices();
        m.push();
        try {
            m.translate(CX - HINT_W / 2, HINT_TOP, 0); m.scale(k, k, 1);
            int tint = white(alpha);
            images.drawPart(c, HINT, 0, 0, w, topH, HINT_SRC_X0, HINT_SRC_Y0, w, topH, tint);
            images.drawPart(c, HINT, 0, topH, w, middle, HINT_SRC_X0, HINT_SLICE_TOP, w, HINT_SLICE_BOTTOM - HINT_SLICE_TOP, tint);
            images.drawPart(c, HINT, 0, topH + middle, w, bottomH, HINT_SRC_X0, HINT_SLICE_BOTTOM, w, bottomH, tint);
            c.draw();
        } finally { m.pop(); }
    }

    /** 뒷면 카드: (cx, cy) 가운데, 보이는 높이 h, 기울기(도), 가로 배율(뒤집기), 투명도, 금색 강조 0~1. */
    private void backCard(DrawContext c, float cx, float cy, float h, float tilt, float widthScale, float alpha, float glow) {
        if (alpha <= 0 || widthScale <= .001f) return;
        var m = c.getMatrices();
        m.push();
        try {
            m.translate(cx, cy, 0); m.multiply(new Quaternionf().rotateZ((float) Math.toRadians(tilt))); m.scale(widthScale, 1, 1);
            float w = h * (BACK_BOX[2] - BACK_BOX[0]) / (BACK_BOX[3] - BACK_BOX[1]), r = 28 * h / (BACK_BOX[3] - BACK_BOX[1]);
            if (glow > .01f) { halo(c, w, h, r, glow * alpha); c.draw(); }
            if (images.failed(BACK)) {   // 그림이 없을 때 대신: 남색 카드 + 금색 테두리
                HudMesh.quad(c, -w / 2, -h / 2, w, h, argb(0x101A3C, alpha), argb(0x101A3C, alpha));
                outline(c, w, h, r, 3, argb(GOLD, alpha));
                c.draw();
            } else {
                var pl = place(BACK_BOX, 0, 0, h);
                m.push();
                try { m.translate(pl.x(), pl.y(), 0); m.scale(pl.scale(), pl.scale(), 1); images.drawStretched(c, BACK, 0, 0, CARD_TEX_W, CARD_TEX_H, white(alpha)); c.draw(); }
                finally { m.pop(); }
            }
            if (glow > .01f) { outline(c, w - 2, h - 2, r, 3.5f, argb(0xF8DA86, glow * alpha)); c.draw(); }
        } finally { m.pop(); }
    }

    /** 앞면: 그림(cover) → 등급 빈 프레임. 완성형 앞면 PNG 는 쓰지 않는다. */
    private void frontCard(DrawContext c, float cx, float cy, float h, float widthScale) {
        if (widthScale <= .001f) return;
        int g = card.stars() - 1;
        var m = c.getMatrices();
        m.push();
        try {
            m.translate(cx, cy, 0); m.scale(widthScale, 1, 1);
            var pl = place(FRAME_BOX[g], 0, 0, h);
            m.translate(pl.x(), pl.y(), 0); m.scale(pl.scale(), pl.scale(), 1);
            int[] r = ART_RECT[g];
            float[] uv = cover(r);
            if (images.failed(illustration)) HudMesh.quad(c, r[0], r[1], r[2] - r[0], r[3] - r[1], 0xFF101A3C, 0xFF101A3C);
            else images.drawPart(c, illustration, r[0], r[1], r[2] - r[0], r[3] - r[1], uv[0], uv[1], Math.round(uv[2]), Math.round(uv[3]), 0xFFFFFFFF);
            c.draw();
            images.drawStretched(c, frame, 0, 0, CARD_TEX_W, CARD_TEX_H, 0xFFFFFFFF);
            c.draw();
        } finally { m.pop(); }
    }

    /** 금색 은은한 빛: 카드 가장자리에서 바깥으로 옅어지는 띠 4개. */
    private static void halo(DrawContext c, float w, float h, float r, float a) {
        float g = 16;
        int in = argb(GOLD, .55f * a), out = argb(GOLD, 0);
        HudMesh.vertical(c, -w / 2 + r, -h / 2 - g, w - 2 * r, g, out, in);
        HudMesh.vertical(c, -w / 2 + r, h / 2, w - 2 * r, g, in, out);
        HudMesh.quad(c, -w / 2 - g, -h / 2 + r, g, h - 2 * r, out, in);
        HudMesh.quad(c, w / 2, -h / 2 + r, g, h - 2 * r, in, out);
        for (int i = 0; i < 4; i++) {   // 모서리: 둥근 띠 몇 겹
            float sx = i % 2 == 0 ? -1 : 1, sy = i < 2 ? -1 : 1, start = (float) (sx < 0 ? (sy < 0 ? Math.PI : Math.PI / 2) : (sy < 0 ? Math.PI * 1.5 : 0));
            for (int k = 0; k < 4; k++) HudMesh.arc(c, sx * (w / 2 - r), sy * (h / 2 - r), r + g * (k + .5f) / 4, g / 4, start, (float) (Math.PI / 2), argb(GOLD, .55f * a * (1 - (k + .5f) / 4)));
        }
    }

    /** 둥근 사각 테두리 (가운데 0,0). */
    private static void outline(DrawContext c, float w, float h, float r, float t, int color) {
        HudMesh.line(c, -w / 2 + r, -h / 2, w / 2 - r, -h / 2, t, color);
        HudMesh.line(c, w / 2 - r, h / 2, -w / 2 + r, h / 2, t, color);
        HudMesh.line(c, -w / 2, h / 2 - r, -w / 2, -h / 2 + r, t, color);
        HudMesh.line(c, w / 2, -h / 2 + r, w / 2, h / 2 - r, t, color);
        HudMesh.arc(c, -w / 2 + r, -h / 2 + r, r, t, (float) Math.PI, (float) (Math.PI / 2), color);
        HudMesh.arc(c, w / 2 - r, -h / 2 + r, r, t, (float) (Math.PI * 1.5), (float) (Math.PI / 2), color);
        HudMesh.arc(c, w / 2 - r, h / 2 - r, r, t, 0, (float) (Math.PI / 2), color);
        HudMesh.arc(c, -w / 2 + r, h / 2 - r, r, t, (float) (Math.PI / 2), (float) (Math.PI / 2), color);
    }

    /** 그림 하나를 보이는 상자 기준으로: 가운데 x = cx, 원본 y = anchorSrc 가 cy 에 오도록 배율 k 로. */
    private void box(DrawContext c, String path, int[] box, float cx, float cy, float k, float anchorSrc, float alpha) {
        if (alpha <= 0) return;
        var m = c.getMatrices();
        m.push();
        try {
            m.translate(cx - (box[0] + box[2]) / 2f * k, cy - anchorSrc * k, 0); m.scale(k, k, 1);
            var size = images.size(path);
            if (size != null) images.drawStretched(c, path, 0, 0, size[0], size[1], white(alpha));
            c.draw();
        } finally { m.pop(); }
    }

    private void text(DrawContext c, String value, float x, float y, float size, int rgb, float alpha, Identifier face) {
        if (alpha <= 0) return;
        float fit = Math.min(size, size * (PANEL_W - 160) / Math.max(1, typography.width(value, size, face)));
        typography.draw(c, value, x, y, fit, argb(rgb, alpha), face, true);
        c.draw();
    }

    private void sound(String name, float volume, float pitch) {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of("magiccodex", name)), pitch, volume));
    }

    // ------------------------------------------------------------------ 입력

    private void pick(int i) {
        if (chosen >= 0 || i < 0 || i > 2) return;
        chosen = i; clock = 0; lastNow = Util.getMeasuringTimeMs();
        sound("light.ritual", .35f, 1.1f);
    }
    private boolean confirmable() { return chosen >= 0 && clock >= CONFIRM_AT; }

    private float[] local(double x, double y) {
        float s = scale(width, height), left = (width - PANEL_W * s) / 2, top = (height - PANEL_H * s) / 2;
        return new float[]{(float) ((x - left) / s), (float) ((y - top) / s)};
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return true;
        var p = local(x, y);
        if (chosen < 0) { pick(cardAt(p[0], p[1])); return true; }
        if (confirmable() && wrapped != null && Math.abs(p[0] - CX) <= BUTTON_W / 2 && Math.abs(p[1] - buttonY(wrapped)) <= buttonH() / 2) close();
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE;
        if (chosen < 0) {
            int current = hovered >= 0 ? hovered : focus;
            if (key == GLFW.GLFW_KEY_LEFT) { focus = current < 0 ? 1 : Math.max(0, current - 1); hovered = -1; return true; }
            if (key == GLFW.GLFW_KEY_RIGHT) { focus = current < 0 ? 1 : Math.min(2, current + 1); hovered = -1; return true; }
            if (key == GLFW.GLFW_KEY_TAB) { focus = current < 0 ? ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? 2 : 0) : (current + ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? 2 : 1)) % 3; hovered = -1; return true; }
            if (enter) { pick(current < 0 ? 1 : current); return true; }
            return true;
        }
        if (enter && confirmable()) close();
        return true;
    }

    @Override public void tick() { if (client != null && (client.player == null || client.world == null || !client.player.isAlive())) close(); }
    @Override public boolean shouldPause() { return false; }

    /** 어떻게 닫히든(확인·Esc·다른 창·연결 끊김) 힌트를 채팅에 한 번 남기고, 이 창의 그림을 GPU 에서 내린다. */
    @Override public void removed() {
        if (!chatted) { chatted = true; TarotClient.chat(card); }
        if (images != null) { images.close(); images = null; }
        super.removed();
    }
}
