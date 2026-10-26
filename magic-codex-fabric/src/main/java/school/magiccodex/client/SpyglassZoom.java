package school.magiccodex.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * 접이식 망원경 아티팩트 (folding_spyglass, 2026-10-26): 장착한 채 아티팩트 사용 키(기본 X)를 누르고 있는 동안 화면 확대.
 *
 * 장착 여부는 서버가 보내 준 장착 목록({@link ArtifactClient#equipped})만 믿는다 (도감에서 장착 = 서버가 확인한 상태).
 * 확대는 내 화면에서만 일어나고 서버에 아무것도 보내지 않는다 (ArtifactClient.use 가 이 아티팩트는 USE 를 보내지 않음).
 * 바닐라 망원경과 같게: 1인칭에서 시야 배율 0.1 (SpyglassFovMixin), 마우스 감도 낮춤 (SpyglassMouseMixin),
 * 바닐라 망원경 테두리 그림(textures/misc/spyglass_scope.png) + 바깥 검은 칸, 들어 올리기/내리기 소리.
 */
public final class SpyglassZoom {
    public static final String ID = "folding_spyglass";
    public static final float FOV = 0.1f;
    private static final Identifier SCOPE = Identifier.ofVanilla("textures/misc/spyglass_scope.png");
    private static boolean active;
    private static float scale = 0.5f;
    private static long lastFrame;

    private SpyglassZoom() {}

    public static boolean active() { return active; }

    static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(SpyglassZoom::tick);
        HudLayerRegistrationCallback.EVENT.register(d -> d.attachLayerBefore(IdentifiedLayer.CROSSHAIR, Identifier.of("magiccodex", "folding_spyglass"), SpyglassZoom::render));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> active = false);
    }

    private static void tick(MinecraftClient c) {
        var key = ArtifactClient.binding();
        boolean want = c.player != null && c.player.isAlive() && c.currentScreen == null && key != null && key.isPressed()
                && c.options.getPerspective().isFirstPerson() && ArtifactClient.equipped().contains(ID);
        if (want == active) return;
        active = want;
        c.getSoundManager().play(PositionedSoundInstance.master(want ? SoundEvents.ITEM_SPYGLASS_USE : SoundEvents.ITEM_SPYGLASS_STOP_USING, 1f));
    }

    /** 바닐라 InGameHud 망원경 테두리와 같은 계산 (커지며 펼쳐지는 연출 포함). */
    private static void render(DrawContext ctx, RenderTickCounter ticks) {
        long now = System.nanoTime();
        float frameTicks = lastFrame == 0 ? 1 : Math.min(5f, (now - lastFrame) / 50_000_000f);
        lastFrame = now;
        if (!active) { scale = 0.5f; return; }
        scale = MathHelper.lerp(Math.min(1f, 0.5f * frameTicks), scale, 1.125f);
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        float f = Math.min(w, h);
        float g = Math.min(w / f, h / f) * scale;
        int size = MathHelper.floor(f * g);
        int x0 = (w - size) / 2, y0 = (h - size) / 2, x1 = x0 + size, y1 = y0 + size;
        ctx.drawTexture(RenderLayer::getGuiTextured, SCOPE, x0, y0, 0f, 0f, size, size, size, size, size, size);
        int black = 0xFF000000;
        ctx.fill(0, y1, w, h, black);
        ctx.fill(0, 0, w, y0, black);
        ctx.fill(0, y0, x0, y1, black);
        ctx.fill(x1, y0, w, y1, black);
    }
}
