package com.diamondserver.client;

import com.diamondserver.ui.HudUi;
import com.diamondserver.ui.NickUi;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * 닉네임 창을 게임 화면으로 띄운다. 그리기는 NickUi(화면 코드)가 맡는다.
 * 글자 입력: 게임의 입력 칸을 화면 밖에 두고 입력만 받는다 (한글 조합은 게임·한글 패치 모드가 처리, 그리기는 우리 화면 코드).
 * first = 처음 정하는 것: 닉네임을 정할 때까지 닫을 수 없다.
 */
public final class NickScreen extends Screen {
    private final McG g = new McG();
    private final NickUi ui;
    private final int max;
    private TextFieldWidget field;

    public NickScreen(boolean first, String current, String rule, int max) {
        super(Text.literal("닉네임 설정"));
        this.max = Math.max(1, max);
        MinecraftClient mc = MinecraftClient.getInstance();
        String self = mc.player == null ? "" : mc.player.getUuidAsString();
        this.ui = new NickUi(new NickUi.Host() {
            @Override public void submit(String name) {
                JsonObject o = new JsonObject();
                o.addProperty("t", "nick");
                o.addProperty("name", name);
                DiamondClient.send(o);
            }
            @Override public void close() { NickScreen.this.close(); }
            @Override public void clickSound() {
                MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1f));
            }
        }, first, current, rule, self);
    }

    @Override
    protected void init() {
        String keep = field != null ? field.getText() : ui.input;
        field = new TextFieldWidget(textRenderer, -400, -100, 300, 20, Text.empty());
        field.setMaxLength(max);
        field.setText(keep);
        addSelectableChild(field);
        setInitialFocus(field);
    }

    /** 서버가 거절한 이유를 보여 준다 */
    void showError(String text) { ui.showError(text); }

    @Override public boolean shouldPause() { return false; }

    @Override public boolean shouldCloseOnEsc() { return !ui.first; }

    @Override public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) { }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        g.begin(ctx);
        if (field != null) {
            ui.input = field.getText();
            if (getFocused() != field) setFocused(field);
        }
        HudUi.under(g, false);
        ui.draw(g);
        HudUi.over(g, true);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        ui.mouseDown(g.toV(x), g.toV(y));
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        ui.mouseUp();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 257 || key == 335) {                 // Enter = 확인
            if (field != null) ui.input = field.getText();
            ui.submit();
            return true;
        }
        if (key == 256 && ui.first) return true;        // 처음 정할 때는 ESC 로 닫을 수 없다
        return super.keyPressed(key, scan, mods);
    }
}
