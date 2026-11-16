package com.diamondserver.client;

import com.diamondserver.ui.HudUi;
import com.diamondserver.ui.MailUi;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/** 우편함 화면을 게임 화면으로 띄운다. 그리기·입력은 전부 MailUi(화면 코드)가 맡는다. */
public final class MailScreen extends Screen {
    private final McG g = new McG();
    private final MailUi ui = new MailUi(new MailUi.Host() {
        @Override public void claim(String id) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "mail.claim");
            o.addProperty("id", id);
            DiamondClient.send(o);
        }
        @Override public void claimAll() {
            JsonObject o = new JsonObject();
            o.addProperty("t", "mail.claimall");
            DiamondClient.send(o);
        }
        @Override public void close() { MailScreen.this.close(); }
        @Override public void clickSound() {
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1f));
        }
    });

    public MailScreen() { super(Text.literal("우편함")); }

    @Override public boolean shouldPause() { return false; }

    @Override public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) { }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        g.begin(ctx);
        MinecraftClient mc = MinecraftClient.getInstance();
        double gx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / Math.max(1, mc.getWindow().getWidth());
        double gy = mc.mouse.getY() * mc.getWindow().getScaledHeight() / Math.max(1, mc.getWindow().getHeight());
        HudUi.under(g, "mail");
        ui.draw(g, g.toV(gx), g.toV(gy));
        HudUi.over(g, true);          // 알림·룰렛·즉사는 창보다 위
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        // 왼쪽 위 HUD 의 상점 자리를 누르면 상점으로, 우편함 자리를 다시 누르면 닫는다
        String nav = HudUi.navAt(g.toV(x), g.toV(y));
        if (nav.equals("shop")) { MinecraftClient.getInstance().setScreen(new ShopScreen()); return true; }
        if (nav.equals("mail")) { close(); return true; }
        ui.mouseDown(g.toV(x), g.toV(y));
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        ui.mouseUp();
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        ui.scroll(vertical);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (DiamondClient.mailKey != null && DiamondClient.mailKey.matchesKey(key, scan)) { close(); return true; }
        if (DiamondClient.shopKey != null && DiamondClient.shopKey.matchesKey(key, scan)) { MinecraftClient.getInstance().setScreen(new ShopScreen()); return true; }
        return super.keyPressed(key, scan, mods);
    }
}
