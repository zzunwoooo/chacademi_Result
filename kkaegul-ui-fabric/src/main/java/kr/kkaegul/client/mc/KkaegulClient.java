package kr.kkaegul.client.mc;

import com.google.gson.JsonObject;
import kr.kkaegul.client.ui.Fmt;
import kr.kkaegul.client.ui.Host;
import kr.kkaegul.client.ui.J;
import kr.kkaegul.client.ui.Panel;
import kr.kkaegul.client.ui.UiHub;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 운빨깨굴랜드 고화질 UI 렌더러 (Fabric 1.21.4).
 * 서버 KkaegulCore 의 kkaegul:ui 메시지를 받아 HUD·멘트·룰렛·연출·창을 그리고, 버튼은 kkaegul:action 으로 보낸다.
 * 이 모드가 kkaegul:ui 채널을 등록하면 서버는 자동으로 "렌더러 있음"으로 보고 채팅/타이틀 대체 출력을 멈춘다.
 */
public final class KkaegulClient implements ClientModInitializer, Host {
    private static final Logger LOG = LoggerFactory.getLogger("kkaegul-ui");
    private static KkaegulClient instance;
    private MinecraftClient mc;
    private Gfx gfx;
    private McCanvas canvas;
    private UiHub hub;
    private KeyBinding menuKey;

    public static KkaegulClient get() { return instance; }

    McCanvas canvas() {
        if (canvas == null) {
            gfx = new Gfx(mc);
            canvas = new McCanvas(mc, gfx);
        }
        return canvas;
    }

    public UiHub hub() { return hub; }

    @Override public void onInitializeClient() {
        instance = this;
        mc = MinecraftClient.getInstance();
        hub = new UiHub(this);

        PayloadTypeRegistry.playS2C().register(Payloads.Ui.ID, Payloads.Ui.CODEC);
        PayloadTypeRegistry.playC2S().register(Payloads.Action.ID, Payloads.Action.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Ui.ID, (payload, context) -> {
            String json = payload.json();
            context.client().execute(() -> {
                try {
                    hub.handle(J.parse(json), System.currentTimeMillis());
                } catch (RuntimeException e) {
                    LOG.warn("UI 메시지 처리 실패: {}", json, e);
                }
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(this::reset));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            reset();
            // 접속하자마자 HUD 값 요청 (서버가 채널 등록을 늦게 볼 수 있어 조금 뒤에도 한 번)
            send("hud.request");
        }));

        HudRenderCallback.EVENT.register((ctx, tick) -> {
            if (mc.options.hudHidden) return;
            McCanvas c = canvas();
            gfx.beginFrame();
            float s = c.begin(ctx);
            try {
                boolean cursor = mc.currentScreen instanceof ChatScreen;
                float mx = 0, my = 0;
                if (cursor) {
                    mx = (float) (mc.mouse.getX() * ctx.getScaledWindowWidth() / mc.getWindow().getWidth()) / s;
                    my = (float) (mc.mouse.getY() * ctx.getScaledWindowHeight() / mc.getWindow().getHeight()) / s;
                }
                hub.renderOverlay(c, System.currentTimeMillis(), cursor, mx, my, mc.currentScreen instanceof PanelScreen);
            } catch (RuntimeException e) {
                LOG.warn("UI 그리기 실패", e);
            } finally {
                c.end();
            }
        });

        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kkaegul.menu", GLFW.GLFW_KEY_M, "category.kkaegul"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.wasPressed()) {
                if (client.player != null && canSend()) send("menu.open", "tab", "me");
            }
            // 연출 중 화면 클릭 대신: 연출이 있으면 사용 키(우클릭)는 그대로 게임에 둔다
        });
        LOG.info("운빨깨굴랜드 UI 준비 완료");
    }

    private void reset() {
        if (mc.currentScreen instanceof PanelScreen) mc.setScreen(null);
        hub = new UiHub(this);
        if (gfx != null) gfx.close();
    }

    private boolean canSend() { return mc.getNetworkHandler() != null && ClientPlayNetworking.canSend(Payloads.Action.ID); }

    // ───────── Host ─────────

    @Override public void send(String action, String... kv) {
        if (!canSend()) return;
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        ClientPlayNetworking.send(Payloads.Action.of(o.toString()));
    }

    @Override public void sound(String id, float pitch, float volume) {
        try {
            mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of(id)), pitch, volume));
        } catch (RuntimeException ignored) { }
    }

    @Override public void openPanel(Panel panel) {
        if (mc.player == null) return;
        mc.setScreen(new PanelScreen(panel));
    }

    @Override public void closePanel(Panel panel) {
        if (mc.currentScreen instanceof PanelScreen ps && ps.panel == panel) mc.setScreen(null);
    }

    @Override public Panel openPanel() {
        return mc.currentScreen instanceof PanelScreen ps ? ps.panel : null;
    }

    // ───────── 머리 위 이름표 ─────────

    /** 서버가 보낸 닉네임(+칭호)으로 바꾼 이름. 없으면 null */
    public static Text displayName(PlayerEntity p) {
        KkaegulClient k = instance;
        if (k == null || k.hub == null) return null;
        Map<String, String[]> names = k.hub.names();
        if (names.isEmpty()) return null;
        String[] v = names.get(p.getUuid().toString().toLowerCase());
        if (v == null || v[0].isBlank()) return null;
        String title = v[1] == null ? "" : v[1];
        return Text.literal(Fmt.amp((title.isBlank() ? "" : title + " ") + "§f" + v[0]));
    }
}
