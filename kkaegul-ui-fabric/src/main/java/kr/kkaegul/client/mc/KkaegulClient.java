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
    private boolean altCursor, mouseWasDown;
    private float lastMx, lastMy;
    private double rawX, rawY;
    private long restoreUntil;
    /** HUD 를 그릴 때의 배율 (클릭 좌표 계산용) */
    private volatile float hudScale = 1;

    public static KkaegulClient get() { return instance; }

    /** 채팅창에서 HUD 클릭 (화면 좌표 → HUD 좌표). 처리했으면 true */
    public boolean clickHud(double mx, double my) {
        if (mc.player == null || !canSend()) return false;
        return hub.clickOverlay((float) mx / hudScale, (float) my / hudScale);
    }

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
        Fmt.translator = k -> net.minecraft.client.resource.language.I18n.translate(k);
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            try { LandWorld.render(ctx, hub.land); } catch (RuntimeException e) { LOG.warn("땅 경계 그리기 실패", e); }
        });

        PayloadTypeRegistry.playS2C().register(Payloads.Ui.ID, Payloads.Ui.CODEC);
        PayloadTypeRegistry.playC2S().register(Payloads.Action.ID, Payloads.Action.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Ui.ID, (payload, context) -> {
            String json = payload.json();
            context.client().execute(() -> {
                try {
                    JsonObject m = J.parse(json);
                    if ("casino".equals(J.str(m, "type"))) { kr.kkaegul.client.casino.CasinoNet.receive(J.str(m, "game"), J.str(m, "state")); return; }
                    hub.handle(m, System.currentTimeMillis());
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
            hub.tabList = mc.options.playerListKey.isPressed();
            McCanvas c = canvas();
            gfx.beginFrame();
            float s = c.begin(ctx);
            hudScale = s;
            try {
                boolean cursor = mc.currentScreen instanceof ChatScreen || altCursor;
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
            // Alt 를 누르고 있으면 마우스 커서를 꺼내서 HUD 에 올려 볼 수 있게 (떼면 다시 잠금)
            boolean alt = client.currentScreen == null && client.player != null
                    && net.minecraft.client.util.InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_ALT);
            if (alt && !altCursor) { altCursor = true; client.mouse.unlockCursor(); }
            else if (!alt && altCursor) { altCursor = false; if (client.currentScreen == null) client.mouse.lockCursor(); }
            // Alt 커서로 HUD 누르기 (▼ → 메뉴 등)
            boolean down = altCursor && GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (altCursor && !down) {   // 누르는 순간 게임이 커서를 가운데로 잠그므로 직전 위치를 기억
                lastMx = (float) (client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth()) / hudScale;
                lastMy = (float) (client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight()) / hudScale;
                rawX = client.mouse.getX();
                rawY = client.mouse.getY();
            }
            if (down && !mouseWasDown && canSend()) {
                restoreUntil = System.currentTimeMillis() + 1500;   // 이 클릭으로 창이 열리면 커서를 이 자리에 둠
                hub.clickOverlay(lastMx, lastMy);
                altCursor = false;      // 다음 틱에 Alt 가 아직 눌려 있으면 커서를 다시 꺼냄
            }
            mouseWasDown = down;
            while (menuKey.wasPressed()) {
                if (client.player != null && canSend()) hub.toggleDropdown(System.currentTimeMillis());
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

    @Override public void copy(String text) { mc.keyboard.setClipboard(text); }

    @Override public void sound(String id, float pitch, float volume) {
        try {
            mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvent.of(Identifier.of(id)), pitch, volume));
        } catch (RuntimeException ignored) { }
    }

    @Override public void openPanel(Panel panel) {
        if (mc.player == null) return;
        boolean restore = mc.currentScreen == null && System.currentTimeMillis() < restoreUntil;
        mc.setScreen(new PanelScreen(panel));
        if (restore) {
            // 창이 열리면 마인크래프트가 커서를 화면 가운데로 옮김 → Alt 커서로 누른 자리로 되돌림
            restoreUntil = 0;
            GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), rawX, rawY);
            ((kr.kkaegul.client.mixin.MouseAccessor) mc.mouse).kkaegul$setX(rawX);
            ((kr.kkaegul.client.mixin.MouseAccessor) mc.mouse).kkaegul$setY(rawY);
        }
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
        if (k == null || k.hub == null || !k.hub.nameplate()) return null;
        Map<String, String[]> names = k.hub.names();
        if (names.isEmpty()) return null;
        String[] v = names.get(p.getUuid().toString().toLowerCase());
        if (v == null || v[0].isBlank()) return null;
        String title = v[1] == null ? "" : v[1];
        return Text.literal(Fmt.amp((title.isBlank() ? "" : title + " ") + "§f" + v[0]));
    }
}
