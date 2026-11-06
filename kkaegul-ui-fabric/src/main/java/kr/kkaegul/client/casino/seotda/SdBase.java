package kr.kkaegul.client.casino.seotda;

import kr.kkaegul.client.casino.render.ElseaFont;
import kr.kkaegul.client.casino.render.ElseaFont.Align;
import kr.kkaegul.client.casino.render.HqTexture;
import kr.kkaegul.client.casino.render.SdAssets;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * 섯다 화면 공통 (1600×1000 캔버스 좌표, 그리기 도우미, 방 만들기/설정 입력 폼).
 */
public abstract class SdBase extends Screen {

    protected static final int BROWN = 0xFF52351F;
    protected static final int MUTED = 0xFF8D775C;
    protected static final int CORAL = 0xFFBC5F47;
    protected static final int RED = 0xFFC9483A;
    protected static final int CREAM = 0xFFFFF5DB;
    protected static final int WHITE = 0xFFFFFFFF;
    protected static final int GREEN = 0xFF2E7D4A;
    protected static final float CW = 1600, CH = 1000;

    /** 코드로 화면을 바꾸는 중 (removed에서 서버에 나가기 알림을 보내지 않도록) */
    public static boolean switching;

    /** 서버가 닫으라고 할 때 (나가기 알림을 다시 보내지 않음) */
    public void closeQuietly() {
        switching = true;
        MinecraftClient.getInstance().setScreen(null);
    }
    protected static boolean sound = true;

    protected float ox, oy, s;
    protected long modalAt;

    // ----- 방 만들기 / 설정 폼 -----
    protected final SdField formTitle = new SdField(20, false);
    protected final SdField formAnte = new SdField(9, true);
    protected SdField focus;
    protected boolean formTried;
    protected static final long[] PRESETS = {1_000, 5_000, 10_000, 100_000};

    // ----- 게임마다 다른 폼 문구 (홀덤은 블라인드) -----
    protected long[] formPresets() {
        return PRESETS;
    }

    protected String anteLabel() {
        return "기본 판돈";
    }

    protected String anteRangeText() {
        return "판돈은 " + num(minAnte()) + " ~ " + num(maxAnte()) + " 다이아예요.";
    }

    protected String anteHint(long value) {
        return "직접 입력하거나 - + 버튼으로 조절해요";
    }

    protected String formInfo() {
        return "이긴 사람이 받는 판돈에서 수수료 " + rakeText() + "%를 떼요. 족보는 AI 대전과 같아요.";
    }

    protected long anteStep(long v) {
        return v >= 100_000 ? 10_000 : 1000;
    }

    protected String presetLabel(long v) {
        return num(v);
    }

    protected SdBase(String title) {
        super(Text.literal(title));
    }

    // ------------------------------------------------------------------ 좌표

    protected void layout() {
        s = Math.min((width - 6) / CW, (height - 6) / CH);
        ox = (width - CW * s) / 2f;
        oy = (height - CH * s) / 2f;
    }

    protected float X(float v) {
        return ox + v * s;
    }

    protected float Y(float v) {
        return oy + v * s;
    }

    protected float L(float v) {
        return v * s;
    }

    protected boolean in(double mx, double my, float x, float y, float w, float h) {
        return kr.kkaegul.client.casino.UiSound.mark(mx >= X(x) && mx <= X(x + w) && my >= Y(y) && my <= Y(y + h));
    }

    protected boolean in(double mx, double my, float[] r) {
        return in(mx, my, r[0], r[1], r[2], r[3]);
    }

    // ------------------------------------------------------------------ 그리기

    protected void drawRoom(DrawContext ctx) {
        HqTexture bg = SdAssets.SD_ROOM_BACKGROUND;
        float k = Math.max((float) width / bg.width(), (float) height / bg.height());
        float w = bg.width() * k, h = bg.height() * k;
        bg.draw(ctx, (width - w) / 2f, (height - h) / 2f, w, h);
        ctx.fill(0, 0, width, height, 0x40263126);
        SdAssets.SD_WINDOW_FRAME.draw(ctx, X(0), Y(0), L(CW), L(CH));
    }

    protected void titlePlaque(DrawContext ctx, String title) {
        HqTexture plaque = SdAssets.SD_TITLE_PLAQUE;
        float pw = 420, ph = pw * plaque.height() / plaque.width();
        plaque.draw(ctx, X(590), Y(80 - ph / 2), L(pw), L(ph));
        float size = title.length() > 4 ? 44 : 54;
        text(ctx, title, 813, 83, size, 0xFF68452E, Align.CENTER);
        text(ctx, title, 812, 80, size, CREAM, Align.CENTER);
    }

    /** 팝업 열기 전 어둡게 + 튀어나오는 효과 */
    protected void beginModal(DrawContext ctx, float[] r) {
        ctx.draw();
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(0, 0, 300);
        ctx.fill(0, 0, width, height, 0x88263126);
        float t = Math.min(1f, (Util.getMeasuringTimeMs() - modalAt) / 170f);
        float k = 0.93f + 0.07f * easeOutBack(t);
        float cx = X(r[0] + r[2] / 2), cy = Y(r[1] + r[3] / 2);
        m.translate(cx, cy, 0);
        m.scale(k, k, 1f);
        m.translate(-cx, -cy, 0);
    }

    protected void endModal(DrawContext ctx) {
        ctx.getMatrices().pop();
    }

    protected void frame(DrawContext ctx, float[] r) {
        // 테두리 그림 아래쪽 여백만큼 조금 더 길게
        SdAssets.SD_MODAL_FRAME.drawNine(ctx, X(r[0]), Y(r[1]), L(r[2]), L(r[3] + 26), 130, L(42));
    }

    protected float text(DrawContext ctx, String str, float rx, float cy, float size, int color, Align align) {
        return ElseaFont.draw(ctx, str, X(rx), Y(cy), L(size), color, align);
    }

    /** 흰 윤곽선 글자 (테이블 위 이름 등) */
    protected float textOutlined(DrawContext ctx, String str, float rx, float cy, float size, int color, Align align) {
        return ElseaFont.draw(ctx, str, X(rx), Y(cy), L(size), color, align, 0xF0FFFFFF) / s;
    }

    protected float textW(String str, float size) {
        return ElseaFont.width(str, L(size)) / s;
    }

    protected void icon(DrawContext ctx, HqTexture tex, float cx, float cy, float h) {
        float w = tex.widthFor(h);
        tex.draw(ctx, X(cx - w / 2f), Y(cy - h / 2f), L(w), L(h));
    }

    protected void button(DrawContext ctx, HqTexture tex, float[] r, int mx, int my, boolean hoverable) {
        boolean hover = hoverable && in(mx, my, r);
        float k = hover ? 1.03f : 1f;
        float w = r[2] * k, h = r[3] * k;
        int inset = Math.round(tex.height() * 0.45f);
        tex.drawNine(ctx, X(r[0] - (w - r[2]) / 2f), Y(r[1] - (h - r[3]) / 2f), L(w), L(h), inset, L(Math.min(h * 0.45f, 32)));
    }

    /** 글자 버튼 (enabled=false면 흐리게) */
    protected void labelButton(DrawContext ctx, HqTexture tex, float[] r, String label, float size, int color,
                               boolean enabled, int mx, int my) {
        if (enabled) button(ctx, tex, r, mx, my, true);
        else disabledButton(ctx, tex, r);
        text(ctx, label, r[0] + r[2] / 2, r[1] + r[3] / 2, size, enabled ? color : fade(color), Align.CENTER);
    }

    /** 비활성 버튼: 같은 모양 그대로 색만 옅게 (네모 덮개 없이) */
    protected void disabledButton(DrawContext ctx, HqTexture tex, float[] r) {
        int inset = Math.round(tex.height() * 0.45f);
        tex.drawNine(ctx, X(r[0]), Y(r[1]), L(r[2]), L(r[3]), inset, L(Math.min(r[3] * 0.45f, 32)), 0x99F3EBDD);
    }

    protected static int fade(int color) {
        return (color & 0x00FFFFFF) | 0xA0000000;
    }

    /** 아이콘 + 글자 버튼 */
    protected void iconButton(DrawContext ctx, HqTexture tex, float[] r, HqTexture ic, String label, float size, int color,
                              boolean enabled, int mx, int my) {
        if (enabled) button(ctx, tex, r, mx, my, true);
        else disabledButton(ctx, tex, r);
        float tw = textW(label, size), iw = size * 1.05f;
        float x0 = r[0] + r[2] / 2 - (tw + iw + 12) / 2;
        icon(ctx, ic, x0 + iw / 2, r[1] + r[3] / 2, iw);
        text(ctx, label, x0 + iw + 12, r[1] + r[3] / 2, size, enabled ? color : fade(color), Align.LEFT);
    }

    /** 알약 모양 표시 (배경 텍스처 + 가운데 글자) */
    protected void pill(DrawContext ctx, HqTexture tex, float x, float y, float w, float h, String label, float size, int color) {
        int inset = tex == SdAssets.SD_BUTTON_CORAL || tex == SdAssets.SD_BUTTON_SAGE || tex == SdAssets.SD_BUTTON_CREAM
                ? Math.round(tex.height() * 0.45f) : 20;
        tex.drawNine(ctx, X(x), Y(y), L(w), L(h), inset, L(h / 2));
        text(ctx, label, x + w / 2, y + h / 2, size, color, Align.CENTER);
    }

    protected void fill(DrawContext ctx, float rx, float ry, float rw, float rh, int argb) {
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(X(rx), Y(ry), 0);
        m.scale(L(rw), L(rh), 1f);
        ctx.fill(0, 0, 1, 1, argb);
        m.pop();
    }

    protected void dot(DrawContext ctx, float cx, float cy, float r, int argb) {
        int steps = 8;
        for (int i = 0; i < steps; i++) {
            float y0 = -r + (2 * r) * i / steps;
            float yc = y0 + r / steps;
            float half = (float) Math.sqrt(Math.max(0, r * r - yc * yc));
            fill(ctx, cx - half, cy + y0, half * 2, 2 * r / steps + 0.4f, argb);
        }
    }

    protected void caret(DrawContext ctx, float x, float cy, float size) {
        if ((Util.getMeasuringTimeMs() / 500) % 2 == 1) return;
        fill(ctx, x, cy - size * 0.5f, 2.5f, size, BROWN);
    }

    /** 초상화: 동그란 테두리 + 캐릭터 */
    protected void portrait(DrawContext ctx, String avatar, float x, float y, float size) {
        SdAssets.SD_AVATAR_FRAME.draw(ctx, X(x), Y(y), L(size), L(size));
        HqTexture a = SdAssets.avatar(avatar);
        float box = size * 0.70f;
        float w = a.width() >= a.height() ? box : box * a.width() / a.height();
        float h = a.width() >= a.height() ? box * a.height() / a.width() : box;
        a.draw(ctx, X(x + size / 2 - w / 2), Y(y + size / 2 - h / 2 + size * 0.03f), L(w), L(h));
    }

    protected void coinLine(DrawContext ctx, long amount, float cx, float cy, float size) {
        String t = num(amount);
        float tw = textW(t, size);
        float x0 = cx - (tw + size) / 2;
        dot(ctx, x0 + size * 0.33f, cy, size * 0.33f + 2.2f, 0xF0FFFFFF);
        dot(ctx, x0 + size * 0.33f, cy, size * 0.33f, BROWN);
        textOutlined(ctx, t, x0 + size, cy, size, BROWN, Align.LEFT);
    }

    /** 입력칸 */
    protected void input(DrawContext ctx, float[] r, SdField f, String placeholder, float size, boolean error) {
        SdAssets.SD_INPUT_BOX.drawNine(ctx, X(r[0]), Y(r[1]), L(r[2]), L(r[3]), 30, L(Math.min(24, r[3] / 2)));
        if (error) {
            fill(ctx, r[0] + 3, r[1] + 2, r[2] - 6, 3, RED);
            fill(ctx, r[0] + 3, r[1] + r[3] - 5, r[2] - 6, 3, RED);
        }
        float cy = r[1] + r[3] / 2;
        String v = f.digits && !f.get().isEmpty() ? num(f.number()) : f.get();
        if (v.isEmpty() && focus != f) text(ctx, placeholder, r[0] + 22, cy, size, 0xFFB3A48C, Align.LEFT);
        float w = text(ctx, v, r[0] + 22, cy, size, BROWN, Align.LEFT) / s;
        if (focus == f) caret(ctx, r[0] + 22 + w + 3, cy, size);
    }

    protected static void play(SoundEvent ev, float pitch) {
        if (!sound) return;
        kr.kkaegul.client.casino.UiSound.play(ev, pitch, 0.8f);
    }

    protected static String num(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    protected static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    // ------------------------------------------------------------------ 방 만들기 / 설정 폼

    protected static final float[] FORM = {440, 150, 720, 680};
    protected static final float[] FORM_CLOSE = {1090, 182, 40, 40};
    protected static final float[] F_TITLE = {650, 255, 470, 62};
    protected static final float[] F_ANTE = {650, 370, 470, 62};
    protected static final float[] F_MINUS = {660, 377, 48, 48};
    protected static final float[] F_PLUS = {1062, 377, 48, 48};
    protected static final float[] F_CANCEL = {705, 720, 195, 70};
    protected static final float[] F_OK = {915, 720, 205, 70};

    protected float[] presetRect(int i) {
        return new float[]{650 + i * 120, 448, 110, 50};
    }

    protected void openForm(String title, long ante) {
        formTitle.set(title);
        formAnte.set(Long.toString(ante));
        formTried = false;
        focus = formTitle;
    }

    protected long minAnte() {
        return SeotdaLobbyState.minAnte;
    }

    protected long maxAnte() {
        return SeotdaLobbyState.maxAnte;
    }

    protected String formError() {
        String t = formTitle.get().strip();
        if (t.isEmpty()) return "방 제목을 입력해 주세요.";
        long a = formAnte.number();
        if (a < minAnte() || a > maxAnte()) return anteRangeText();
        return null;
    }

    /**
     * @param settings true면 방 설정 (변경 저장), false면 방 만들기
     */
    protected void drawForm(DrawContext ctx, int mx, int my, boolean settings, long balance) {
        frame(ctx, FORM);
        text(ctx, settings ? "방 설정" : "방 생성하기", 485, 205, 38, BROWN, Align.LEFT);
        icon(ctx, SdAssets.SD_CLOSE, FORM_CLOSE[0] + 20, FORM_CLOSE[1] + 20, 22);

        text(ctx, "방 제목", 485, F_TITLE[1] + 31, 25, BROWN, Align.LEFT);
        boolean titleErr = formTried && formTitle.get().strip().isEmpty();
        input(ctx, F_TITLE, formTitle, "방 제목 입력", 24, titleErr);
        if (titleErr) text(ctx, "방 제목을 입력해 주세요.", F_TITLE[0] + 6, 337, 16, RED, Align.LEFT);
        text(ctx, formTitle.length() + " / 20", F_TITLE[0] + F_TITLE[2], 337, 16, MUTED, Align.RIGHT);

        text(ctx, anteLabel(), 485, F_ANTE[1] + 31, 25, BROWN, Align.LEFT);
        SdAssets.SD_INPUT_BOX.drawNine(ctx, X(F_ANTE[0]), Y(F_ANTE[1]), L(F_ANTE[2]), L(F_ANTE[3]), 30, L(24));
        button(ctx, SdAssets.SD_BUTTON_CREAM, F_MINUS, mx, my, true);
        fill(ctx, F_MINUS[0] + 15, F_MINUS[1] + 22, 18, 4, BROWN);
        button(ctx, SdAssets.SD_BUTTON_CREAM, F_PLUS, mx, my, true);
        icon(ctx, SdAssets.SD_PLUS, F_PLUS[0] + 24, F_PLUS[1] + 24, 18);
        String av = formAnte.get().isEmpty() ? "" : num(formAnte.number());
        float cx = F_ANTE[0] + F_ANTE[2] / 2, cy = F_ANTE[1] + F_ANTE[3] / 2;
        float w = text(ctx, av, cx, cy, 30, BROWN, Align.CENTER) / s;
        if (focus == formAnte) caret(ctx, cx + w / 2 + 3, cy, 28);
        long a = formAnte.number();
        long[] presets = formPresets();
        for (int i = 0; i < presets.length; i++) {
            float[] r = presetRect(i);
            boolean sel = a == presets[i];
            button(ctx, sel ? SdAssets.SD_BUTTON_SAGE : SdAssets.SD_BUTTON_CREAM, r, mx, my, !sel);
            text(ctx, presetLabel(presets[i]), r[0] + r[2] / 2, r[1] + r[3] / 2, 19, sel ? WHITE : BROWN, Align.CENTER);
        }
        boolean anteErr = formTried && (a < minAnte() || a > maxAnte());
        text(ctx, anteErr ? anteRangeText() : anteHint(a),
                F_ANTE[0] + 6, 522, 16, anteErr ? RED : MUTED, Align.LEFT);

        text(ctx, "참여 인원", 485, 576, 25, BROWN, Align.LEFT);
        SdAssets.SD_RANKING_ROW.drawNine(ctx, X(650), Y(548), L(470), L(56), 20, L(16));
        text(ctx, "최대 3명 · 2명부터 시작", 672, 576, 21, MUTED, Align.LEFT);

        if (settings) {
            SdAssets.SD_TURN_BANNER.drawNine(ctx, X(485), Y(622), L(635), L(52), 30, L(18));
            text(ctx, "설정을 변경하면 참가자의 준비가 해제돼요.", 802, 648, 18, BROWN, Align.CENTER);
        } else {
            text(ctx, formInfo(),
                    802, 648, 16, MUTED, Align.CENTER);
        }
        fill(ctx, 470, 692, 660, 2, 0x30684A2E);
        text(ctx, "내 보유금", 485, 735, 17, MUTED, Align.LEFT);
        icon(ctx, SdAssets.SD_COIN_STACK, 502, 768, 30);
        text(ctx, num(balance), 524, 768, 25, BROWN, Align.LEFT);
        labelButton(ctx, SdAssets.SD_BUTTON_CREAM, F_CANCEL, "취소", 25, BROWN, true, mx, my);
        labelButton(ctx, SdAssets.SD_BUTTON_SAGE, F_OK, settings ? "변경 저장" : "방 만들기", 25, WHITE, formError() == null, mx, my);
    }

    protected String rakeText() {
        double r = SeotdaState.rake;
        return r == Math.floor(r) ? Long.toString((long) r) : Double.toString(r);
    }

    /** @return 0 아무것도 아님, 1 취소, 2 확인(검사 통과) */
    protected int clickForm(double mx, double my) {
        if (in(mx, my, FORM_CLOSE) || in(mx, my, F_CANCEL)) {
            focus = null;
            return 1;
        }
        if (in(mx, my, F_TITLE)) {
            focus = formTitle;
            return 0;
        }
        long step = anteStep(formAnte.number());
        if (in(mx, my, F_MINUS)) {
            formAnte.set(Long.toString(Math.max(minAnte(), Math.min(maxAnte(), formAnte.number() - step))));
            return 0;
        }
        if (in(mx, my, F_PLUS)) {
            formAnte.set(Long.toString(Math.max(minAnte(), Math.min(maxAnte(), formAnte.number() + step))));
            return 0;
        }
        if (in(mx, my, F_ANTE)) {
            focus = formAnte;
            return 0;
        }
        long[] presets = formPresets();
        for (int i = 0; i < presets.length; i++) {
            if (in(mx, my, presetRect(i))) {
                formAnte.set(Long.toString(presets[i]));
                return 0;
            }
        }
        if (in(mx, my, F_OK)) {
            formTried = true;
            if (formError() == null) {
                focus = null;
                return 2;
            }
        }
        return 0;
    }

    protected String formJson(String action) {
        return "{\"action\":\"" + action + "\",\"title\":\"" + escape(formTitle.get().strip()) + "\",\"ante\":" + formAnte.number() + "}";
    }

    protected static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ 입력칸 타이핑

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (focus == null) return super.charTyped(chr, modifiers);
        focus.type(String.valueOf(chr));
        return true;
    }

    /** 입력칸이 잡혀 있으면 글자 키 처리. 처리했으면 true */
    protected boolean typingKey(int keyCode) {
        if (focus == null) return false;
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            focus.backspace();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            focus = focus == formTitle ? formAnte : formTitle;
            return true;
        }
        if (Screen.isPaste(keyCode)) {
            focus.type(MinecraftClient.getInstance().keyboard.getClipboard());
            return true;
        }
        return keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_ENTER && keyCode != GLFW.GLFW_KEY_KP_ENTER;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
