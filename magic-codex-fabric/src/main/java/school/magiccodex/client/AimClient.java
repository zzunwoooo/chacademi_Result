package school.magiccodex.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** Wiring for the shoulder camera, magic aim cursor and aimed casting. */
public final class AimClient {
    private static final boolean[] swallowRelease = new boolean[8];

    private AimClient() {}

    public static void initialize() {
        AimSettings.get();
        SpellChargeClient.initialize();
        ShoulderCamera.registerCommand();
        registerCommand();
        WorldRenderEvents.AFTER_ENTITIES.register(AimRenderer::render);
        HudLayerRegistrationCallback.EVENT.register(drawer -> drawer.attachLayerAfter(IdentifiedLayer.CROSSHAIR,
                Identifier.of("magiccodex", "aim"), (context, ticks) -> AimHud.render(context)));
        ClientTickEvents.END_CLIENT_TICK.register(AimTargeting::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { AimTargeting.cancel(false); AimHud.close(); });
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public Identifier getFabricId() { return Identifier.of("magiccodex", "aim_hud"); }
            @Override public void reload(ResourceManager manager) { AimHud.close(); }
        });
    }

    /**
     * Raw mouse buttons, before vanilla (CodexMouseMixin). While a spell is aimed: left = cast, right = cancel.
     * In cursor mode a left click on the top menu / friend card goes to that HUD element instead of the world.
     * @return true when vanilla must not see the event.
     */
    public static boolean mouse(MinecraftClient c, int button, int action) {
        if (button < 0 || button >= swallowRelease.length) return false;
        if (action == GLFW.GLFW_RELEASE) {
            // 338 화염 투창: releasing the held left button throws with the charge so far
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && SpellChargeClient.charging()) { swallowRelease[button] = false; AimTargeting.release(); return true; }
            if (!swallowRelease[button]) return false;
            swallowRelease[button] = false;
            return true;
        }
        if (action != GLFW.GLFW_PRESS || c.currentScreen != null || c.player == null) return false;
        boolean used = false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && AimTargeting.targeting()) used = AimTargeting.press();
        else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && AimTargeting.targeting()) { AimTargeting.cancel(true); used = true; }
        if (used) swallowRelease[button] = true;
        return used;
    }

    private static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            ClientCommandManager.literal("조준")
                .executes(ctx -> { ctx.getSource().sendFeedback(Text.literal(status())); return 1; })
                .then(ClientCommandManager.literal("스마트")
                    .then(ClientCommandManager.literal("켜기").executes(ctx -> set(ctx.getSource(), s -> s.smartCast = true)))
                    .then(ClientCommandManager.literal("끄기").executes(ctx -> set(ctx.getSource(), s -> s.smartCast = false))))
));

    }

    private static int set(FabricClientCommandSource source, java.util.function.Consumer<AimSettings> change) {
        var s = AimSettings.get();
        change.accept(s);
        s.save();
        source.sendFeedback(Text.literal(status()));
        return 1;
    }

    static String status() {
        var s = AimSettings.get();
        return "조준: 일반 마우스 시점 · 스마트 시전 " + (s.smartCast ? "켜짐" : "꺼짐")
            + "  /조준 스마트 켜기|끄기";
    }
}
