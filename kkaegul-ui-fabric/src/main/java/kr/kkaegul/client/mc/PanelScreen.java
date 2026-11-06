package kr.kkaegul.client.mc;

import kr.kkaegul.client.ui.NicknamePanel;
import kr.kkaegul.client.ui.Panel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** 서버 창(Panel)을 마인크래프트 화면으로 감싼다 */
public final class PanelScreen extends Screen {
    final Panel panel;
    private float s = 1;

    PanelScreen(Panel panel) {
        super(Text.literal("깨굴 UI"));
        this.panel = panel;
    }

    private static long now() { return System.currentTimeMillis(); }

    @Override protected void init() {
        super.init();
        if (panel.textInput()) Ime.disable();
    }

    @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) { }

    @Override public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        KkaegulClient k = KkaegulClient.get();
        McCanvas c = k.canvas();
        s = c.begin(ctx);
        try {
            panel.render(c, now(), mouseX / s, mouseY / s);
        } finally {
            c.end();
        }
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        panel.modifiers((hasShiftDown() ? 1 : 0) | (hasControlDown() ? 2 : 0));
        if (button == 0 && panel.click((float) mx / s, (float) my / s, now())) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseScrolled(double mx, double my, double h, double v) {
        return panel.scroll((float) mx / s, (float) my / s, v) || super.mouseScrolled(mx, my, h, v);
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (panel.closable()) close();
            return true;
        }
        if (panel instanceof NicknamePanel np && key == GLFW.GLFW_KEY_V && (mods & GLFW.GLFW_MOD_CONTROL) != 0 && client != null) {
            np.paste(client.keyboard.getClipboard(), now());
            return true;
        }
        if (panel.key(key, mods, now())) return true;
        // 인벤토리 키로도 닫기 (입력창이 있는 닉네임 창 제외)
        if (client != null && !(panel instanceof NicknamePanel) && panel.closable() && client.options.inventoryKey.matchesKey(key, scan)) {
            close();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override public boolean charTyped(char ch, int mods) {
        return panel.typed(ch, now()) || super.charTyped(ch, mods);
    }

    @Override public boolean shouldCloseOnEsc() { return panel.closable(); }

    @Override public boolean shouldPause() { return false; }

    @Override public void removed() {
        if (panel.textInput()) Ime.restore();
        panel.closed();
        super.removed();
    }
}
