package com.diamondserver.client;

import com.diamondserver.ui.HudUi;
import com.diamondserver.ui.ShopUi;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/** 상점 화면을 게임 화면으로 띄운다. 그리기·입력은 전부 ShopUi(화면 코드)가 맡는다. */
public final class ShopScreen extends Screen {
    private final McG g = new McG();
    private final ShopUi ui = new ShopUi(new ShopUi.Host() {
        @Override public int count(String mcId) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) return 0;
            int n = 0;
            var inv = mc.player.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (!s.isEmpty() && Registries.ITEM.getId(s.getItem()).toString().equals(mcId)) n += s.getCount();
            }
            return n;
        }
        @Override public void buy(String id, int qty) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "buy");
            o.addProperty("id", id);
            o.addProperty("qty", qty);
            DiamondClient.send(o);
        }
        @Override public void sell(String mat) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "sell");
            o.addProperty("mat", mat);
            DiamondClient.send(o);
        }
        @Override public void submit(long qty) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "submit");
            o.addProperty("qty", qty);
            DiamondClient.send(o);
        }
        @Override public void close() { ShopScreen.this.close(); }
        @Override public void clickSound() {
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1f));
        }
    });

    public ShopScreen() { super(Text.literal("상점")); }

    @Override public boolean shouldPause() { return false; }

    @Override public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) { }

    /** 마우스 위치를 화면 코드 좌표로 (소수점까지 정확하게) */
    private double[] mouse() {
        MinecraftClient mc = MinecraftClient.getInstance();
        double gx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / Math.max(1, mc.getWindow().getWidth());
        double gy = mc.mouse.getY() * mc.getWindow().getScaledHeight() / Math.max(1, mc.getWindow().getHeight());
        return new double[]{g.toV(gx), g.toV(gy)};
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        g.begin(ctx);
        double[] m = mouse();
        HudUi.under(g, "shop");
        ui.draw(g, m[0], m[1]);
        HudUi.over(g, true);          // 알림·룰렛·즉사는 창보다 위
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        // 왼쪽 위 HUD 의 우편함 자리를 누르면 우편함으로, 상점 자리를 다시 누르면 닫는다
        String nav = HudUi.navAt(g.toV(x), g.toV(y));
        if (nav.equals("mail")) { MinecraftClient.getInstance().setScreen(new MailScreen()); return true; }
        if (nav.equals("shop")) { close(); return true; }
        ui.mouseDown(g.toV(x), g.toV(y), hasShiftDown());
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
        if (DiamondClient.shopKey != null && DiamondClient.shopKey.matchesKey(key, scan)) { close(); return true; }
        if (DiamondClient.mailKey != null && DiamondClient.mailKey.matchesKey(key, scan)) { MinecraftClient.getInstance().setScreen(new MailScreen()); return true; }
        return super.keyPressed(key, scan, mods);
    }
}
