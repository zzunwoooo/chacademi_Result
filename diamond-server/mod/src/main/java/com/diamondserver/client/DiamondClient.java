package com.diamondserver.client;

import com.diamondserver.ui.HudUi;
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

/**
 * 나니 다이아서버 클라이언트 모드.
 * 서버(DiamondCore)가 보내는 신호를 받아 상태(St)에 적고, 화면 코드(ui 패키지)가 그 상태를 그린다.
 * 화면: 왼쪽 위 HUD · 후원 알림 · 룰렛 · 발표 창 · 즉사 연출 · 상점 · 닉네임 창.  그 밖에 강제 댄스 모션, 조작 반전, 한글 이름표.
 */
public final class DiamondClient implements ClientModInitializer {
    public static KeyBinding shopKey;
    private static int helloTick;
    private static final McG HUD_G = new McG();

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
            St.expire();
            while (shopKey.wasPressed())
                if (St.has && client.currentScreen == null) client.setScreen(new ShopScreen());
        });

        // 평소 화면. 상점·닉네임 창이 열려 있을 때는 그 창이 HUD 까지 직접 그린다 (알림이 창 위에 오도록)
        HudRenderCallback.EVENT.register((ctx, tick) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!St.has || mc.player == null) return;
            if (mc.currentScreen instanceof ShopScreen || mc.currentScreen instanceof NickScreen) return;
            McG g = HUD_G.begin(ctx);
            if (mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) { HudUi.blackoutOnly(g); return; }   // 정전은 F1·F3 에서도 가린다
            HudUi.draw(g);
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
                case "wheel" -> St.applyWheel(o);
                case "announce" -> St.applyAnnounce(o);
                case "death" -> St.applyDeath(o);
                case "names" -> St.applyNames(o);
                case "toast" -> St.toast(St.str(o, "text", ""), St.bool(o, "ok", true));
                case "motion" -> playMotion(mc, St.str(o, "player", ""), St.str(o, "name", ""));
                case "open" -> {
                    String screen = St.str(o, "screen", "");
                    if (screen.equals("shop") && mc.currentScreen == null) mc.setScreen(new ShopScreen());
                    if (screen.equals("nick")) {
                        boolean first = St.bool(o, "first", false);
                        // 처음 정하는 창은 다른 화면이 떠 있어도 띄운다. 바꾸기 창은 아무 화면도 없을 때만
                        if (first || mc.currentScreen == null)
                            mc.setScreen(new NickScreen(first, St.str(o, "current", ""), St.str(o, "rule", ""), (int) St.num(o, "max", 8)));
                    }
                }
                case "close" -> {
                    if (St.str(o, "screen", "").equals("nick") && mc.currentScreen instanceof NickScreen) mc.setScreen(null);
                }
                case "nickerr" -> {
                    if (mc.currentScreen instanceof NickScreen ns) ns.showError(St.str(o, "text", ""));
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            // 알 수 없는 신호는 무시 (게임이 멈추지 않게)
        }
    }
}
