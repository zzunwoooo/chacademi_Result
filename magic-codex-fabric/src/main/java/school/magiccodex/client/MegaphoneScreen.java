package school.magiccodex.client;

import static school.magiccodex.client.MegaphoneLayout.*;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * 확성기 입력 창 (메모 #102, UI v2 납품 패키지). 창에는 제목 "확성기"·닫기·입력칸 하나·보내기 버튼만 있다
 * (글자 수·안내문·아이템 미리보기·두 번째 입력칸 없음). 화면 전체를 어둡게 하거나 흐리게 하지 않는다 — 패널 PNG 의 반투명만.
 * 아이템은 고르지 않는다 (보낼 때 서버가 반대 손 아이템을 읽음). 배치·텍스처는 MegaphoneLayout.
 *
 * <p>상태: 열기 → 편집 → 보내기(Enter·버튼) 또는 닫기(Esc·X = 취소, 확성기 그대로). 한 줄, 최대 maxChars code point
 * (붙여넣기 줄바꿈은 지우고 넘치는 뒤는 자름). 빈 값·공백만이면 보내지 않는다. 보내기는 한 번만 (done).
 * 길이·권한·쿨다운·금칙어 최종 검사는 서버.
 */
final class MegaphoneScreen extends Screen {
    private final long token;
    private final int maxChars;
    private TextFieldWidget input;
    private boolean done;

    MegaphoneScreen(long token, int maxChars) {
        super(Text.literal(TITLE_TEXT));
        this.token = token;
        this.maxChars = Math.max(1, maxChars);
    }

    long token() { return token; }

    @Override protected void init() {
        String draft = input == null ? "" : input.getText();
        input = new TextFieldWidget(textRenderer, 0, 0, 800, 30, Text.literal(TITLE_TEXT));
        // 글자 수는 code point 로 센다: 위젯 한도는 넉넉히 두고, 바뀔 때마다 줄바꿈 제거·code point 자르기.
        input.setMaxLength(maxChars * 2 + 8);
        input.setText(sanitizeInput(draft, maxChars));
        input.setChangedListener(value -> {
            String clean = sanitizeInput(value, maxChars);
            if (!clean.equals(value)) input.setText(clean);
        });
        input.setFocused(true);
    }

    private CodexLayout fit() { return canvas(width, height); }

    @Override public boolean shouldPause() { return false; }
    /** 전체 암막·블러 없음 (납품 명세). */
    @Override public void renderBackground(DrawContext c, int x, int y, float delta) {}

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        long now = Util.getMeasuringTimeMs();
        var f = fit();
        double mx = f.localX(mouseX), my = f.localY(mouseY);
        var images = UiResources.images();
        var text = UiResources.text();
        images.beginFrame(); text.beginFrame();
        c.getMatrices().push();
        try {
            c.getMatrices().translate(f.x(), f.y(), 0);
            c.getMatrices().scale(f.scale(), f.scale(), 1);
            nine(images, c, INPUT_PANEL, 1010, 210, INPUT_CORNER, INPUT);
            text.draw(c, TITLE_TEXT, TITLE.x(), textY(TITLE, TITLE_SIZE), TITLE_SIZE, TEXT_COLOR, TITLE_FACE, false);
            images.drawTexture(c, CLOSE, Math.round(CLOSE_RECT.x()), Math.round(CLOSE_RECT.y()), 0, 0, 24, 24, 24, 24);
            nine(images, c, INPUT_FIELD, 786, 72, FIELD_CORNER, FIELD);
            drawInput(c, text, now);
            nine(images, c, SEND.contains(mx, my) ? SEND_HOVER : SEND_NORMAL, 144, 72, SEND_CORNER, SEND);
            text.draw(c, SEND_TEXT, SEND.centerX(), SEND.centerY(), SEND_TEXT_SIZE, TEXT_COLOR, SEND_FACE, true);
        } finally {
            c.getMatrices().pop();
            images.endFrame();
        }
    }

    private static void nine(HudTextureCache images, DrawContext c, Identifier id, int sw, int sh, int corner, Rect r) {
        NotificationSkin.nine(images, c, id, sw, sh, corner, r.x(), r.y(), r.w(), r.h(), 1f, 0xFFFFFFFF);
    }

    /** 입력 글자: 글자 영역(FIELD_TEXT) 안에서 커서가 보이도록 가로로 밀어 보여 주고, 영역 밖은 그리지 않는다. */
    private void drawInput(DrawContext c, CodexTypography text, long now) {
        String value = input.getText();
        float y = textY(FIELD_TEXT, FIELD_TEXT_SIZE), max = FIELD_TEXT.w();
        int cursor = Math.clamp(input.getCursor(), 0, value.length()), from = 0;
        while (from < cursor && text.width(value.substring(from, cursor), FIELD_TEXT_SIZE, FIELD_FACE) > max)
            from = value.offsetByCodePoints(from, 1);
        int to = value.length();
        while (to > from && text.width(value.substring(from, to), FIELD_TEXT_SIZE, FIELD_FACE) > max)
            to = value.offsetByCodePoints(to, -1);
        if (to > from) text.draw(c, value.substring(from, to), FIELD_TEXT.x(), y, FIELD_TEXT_SIZE, TEXT_COLOR, FIELD_FACE, false);
        if (now / 500 % 2 == 0) {
            int x = Math.round(FIELD_TEXT.x() + (cursor > from ? text.width(value.substring(from, cursor), FIELD_TEXT_SIZE, FIELD_FACE) : 0));
            c.fill(x, Math.round(y - 14), x + 2, Math.round(y + 14), TEXT_COLOR);
        }
    }

    private void click() {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, .12f));
    }

    private void submit() {
        if (done) return;
        String value = sanitizeInput(input.getText(), maxChars).strip();
        if (value.isEmpty()) return;   // 빈 값·공백만은 보내지 않는다 (창 유지)
        done = true;
        click();
        // 결과(소모·거절 사유)는 서버가 공지·알림으로 알린다. 거절이면 확성기는 그대로다.
        MegaphoneClient.answer(token, value, true);
        if (client != null) client.setScreen(null);
    }

    void serverClosed() { done = true; if (client != null) client.setScreen(null); }

    @Override public void close() {
        if (!done) { done = true; MegaphoneClient.answer(token, "", false); }
        if (client != null) client.setScreen(null);
    }

    @Override public void removed() {
        if (!done) { done = true; MegaphoneClient.answer(token, "", false); }
        super.removed();
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { submit(); return true; }
        return input.keyPressed(key, scan, mods) || super.keyPressed(key, scan, mods);
    }

    @Override public boolean charTyped(char ch, int mods) { return input.charTyped(ch, mods); }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        var f = fit();
        double mx = f.localX(x), my = f.localY(y);
        if (SEND.contains(mx, my)) { submit(); return true; }
        // 닫기 아이콘은 24×24 그대로 그리고, 누르는 영역만 조금 넓게
        if (new Rect(CLOSE_RECT.x() - 8, CLOSE_RECT.y() - 8, CLOSE_RECT.w() + 16, CLOSE_RECT.h() + 16).contains(mx, my)) { click(); close(); return true; }
        if (FIELD.contains(mx, my)) { input.setFocused(true); return true; }
        return true;
    }
}
