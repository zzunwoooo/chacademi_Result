package com.diamondserver.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * 닉네임 정하는 창.
 *
 * ★ 디자인 없음: 게임 기본 입력 칸·기본 버튼·기본 배경만 쓴다. 화면 모양은 오너가 승인한 그림 배치로 바꾼다.
 *   한글 입력(조합)은 게임 기본 입력 칸과 한글 패치 모드가 처리한다.
 * first = 처음 정하는 것: 닉네임을 정할 때까지 닫을 수 없다.
 */
public final class NickScreen extends Screen {
    private final boolean first;
    private final String rule, current;
    private final int max;
    private TextFieldWidget field;
    private String error = "";
    private long sentAt;

    public NickScreen(boolean first, String current, String rule, int max) {
        super(Text.literal("닉네임 설정"));
        this.first = first;
        this.current = current == null ? "" : current;
        this.rule = rule == null ? "" : rule;
        this.max = Math.max(1, max);
    }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 2 - 10;
        String keep = field != null ? field.getText() : current;
        field = new TextFieldWidget(textRenderer, cx - 100, y, 200, 20, Text.literal("닉네임"));
        field.setMaxLength(max);
        field.setText(keep);
        addDrawableChild(field);
        setInitialFocus(field);
        addDrawableChild(ButtonWidget.builder(Text.literal("확인"), b -> submit()).dimensions(cx - 100, y + 28, first ? 200 : 98, 20).build());
        if (!first) addDrawableChild(ButtonWidget.builder(Text.literal("닫기"), b -> close()).dimensions(cx + 2, y + 28, 98, 20).build());
    }

    private void submit() {
        String name = field.getText().strip();
        if (name.isEmpty()) { error = "닉네임을 적어 주세요."; return; }
        JsonObject o = new JsonObject();
        o.addProperty("t", "nick");
        o.addProperty("name", name);
        DiamondClient.send(o);
        error = "";
        sentAt = System.currentTimeMillis();
    }

    /** 서버가 거절한 이유를 보여 준다 */
    void showError(String text) { error = text == null ? "" : text; sentAt = 0; }

    @Override public boolean shouldPause() { return false; }

    @Override public boolean shouldCloseOnEsc() { return !first; }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 257 || key == 335) { submit(); return true; }       // Enter
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);                              // 게임 기본 배경 + 입력 칸 + 버튼
        int cx = width / 2, y = height / 2 - 10;
        ctx.drawCenteredTextWithShadow(textRenderer, first ? "닉네임을 정해 주세요" : "닉네임 바꾸기", cx, y - 34, Hud.WHITE);
        ctx.drawCenteredTextWithShadow(textRenderer, rule, cx, y - 20, Hud.GRAY);
        String msg = !error.isEmpty() ? error : sentAt > 0 && System.currentTimeMillis() - sentAt < 3000 ? "확인하는 중..." : "";
        if (!msg.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, msg, cx, y + 54, Hud.WHITE);
    }
}
