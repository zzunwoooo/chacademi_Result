package com.diamondserver.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** 나니 다이아서버 클라이언트 모드: 화면 숫자(남은 목표·보유 다이아) · 알림 · 상점 화면 */
public final class DiamondClient implements ClientModInitializer {
    public static KeyBinding shopKey;
    private static int helloTick;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(JsonPayload.ID, JsonPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(JsonPayload.ID, JsonPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(JsonPayload.ID, (payload, context) -> {
            final String json = payload.json();
            context.client().execute(() -> handle(context.client(), json));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> { St.clear(); helloTick = 0; });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> St.clear());

        Motion.init();
        shopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.diamondserver.shop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.diamondserver"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 서버에서 상태를 받을 때까지 1초마다 "모드 있음"을 알린다 (다이아 서버가 아니면 보낼 수 없어서 아무 일도 없다)
            if (client.player != null && !St.has && helloTick++ % 20 == 0 && ClientPlayNetworking.canSend(JsonPayload.ID)) {
                JsonObject o = new JsonObject();
                o.addProperty("t", "hello");
                send(o);
            }
            St.expireToasts();
            while (shopKey.wasPressed())
                if (St.has && client.currentScreen == null) client.setScreen(new ShopScreen());
        });

        HudRenderCallback.EVENT.register((ctx, tick) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!St.has || mc.player == null) return;
            Hud.drawBlackout(ctx, mc);                               // 정전은 화면 숨김(F1)·F3 상태에서도 가린다
            if (mc.options.hudHidden) return;
            if (mc.getDebugHud().shouldShowDebugHud()) return;
            if (mc.currentScreen instanceof ShopScreen) return;      // 상점 화면이 직접 그린다
            Hud.drawPanel(ctx, mc);
            Hud.drawQueue(ctx, mc);
            Hud.drawEffects(ctx, mc);
            Hud.drawPick(ctx, mc);
            Hud.drawToasts(ctx, mc, Hud.drawBanner(ctx, mc));
        });
    }

    /** 서버가 알려 준 사람에게 모션을 재생 (내 캐릭터 포함, 화면에 보이는 사람만) */
    private static void playMotion(MinecraftClient client, String uuid, String name) {
        if (client.world == null) return;
        try {
            if (client.world.getPlayerByUuid(java.util.UUID.fromString(uuid)) instanceof net.minecraft.client.network.AbstractClientPlayerEntity p)
                Motion.play(p, name);
        } catch (IllegalArgumentException ignored) { }
    }

    static void send(JsonObject o) {
        if (ClientPlayNetworking.canSend(JsonPayload.ID)) ClientPlayNetworking.send(new JsonPayload(o.toString()));
    }

    private static void handle(MinecraftClient mc, String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            switch (St.str(o, "t", "")) {
                case "state" -> St.applyState(o);
                case "shop" -> St.applyShop(o);
                case "event" -> St.applyEvent(o);
                case "queue" -> St.applyQueue(o);
                case "effects" -> St.applyEffects(o);
                case "fx" -> St.applyFx(o);
                case "pick" -> St.applyPick(o);
                case "motion" -> playMotion(mc, St.str(o, "player", ""), St.str(o, "name", ""));
                case "toast" -> {
                    com.google.gson.JsonElement ok = o.get("ok");
                    St.toast(St.str(o, "text", ""), ok == null || ok.isJsonNull() || ok.getAsBoolean());
                }
                case "open" -> {
                    if (St.str(o, "screen", "").equals("shop") && mc.currentScreen == null) mc.setScreen(new ShopScreen());
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            // 알 수 없는 신호는 무시 (게임이 멈추지 않게)
        }
    }
}
