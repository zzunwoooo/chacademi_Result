package school.magiccodex.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Over-the-shoulder third person that replaces Shoulder Surfing Reloaded.
 * Only F5 "third person back" changes; first person and the front view stay vanilla.
 */
public final class ShoulderCamera {
    private ShoulderCamera() {}

    public static boolean active() {
        var c = MinecraftClient.getInstance();
        return AimSettings.get().shoulder && c.options.getPerspective() == Perspective.THIRD_PERSON_BACK;
    }

    /**
     * Final camera position for the shoulder view. {@code eye} is where vanilla is about to move back from,
     * the rotation is the camera rotation already set this frame. Clipped against blocks like vanilla.
     */
    public static Vec3d position(BlockView area, Entity focused, Vec3d eye, Quaternionf rotation) {
        var s = AimSettings.get();
        float scale = focused instanceof LivingEntity living ? living.getScale() : 1f;
        // Camera-local axes as in Camera.moveBy: +x right, +y up, +z backwards.
        var local = new Vector3f(s.side * s.sideOffset * scale, s.height * scale, s.distance * scale).rotate(rotation);
        Vec3d wanted = eye.add(local.x, local.y, local.z);
        double fraction = clip(area, focused, eye, wanted);
        return eye.add(wanted.subtract(eye).multiply(fraction));
    }

    /** Same eight-corner probe as vanilla Camera.clipToSpace, returning the free fraction (0..1). */
    static double clip(BlockView area, Entity focused, Vec3d from, Vec3d to) {
        double best = 1;
        Vec3d delta = to.subtract(from);
        double length = delta.length();
        if (length < 1e-6) return 1;
        for (int i = 0; i < 8; i++) {
            double ox = ((i & 1) * 2 - 1) * .1, oy = ((i >> 1 & 1) * 2 - 1) * .1, oz = ((i >> 2 & 1) * 2 - 1) * .1;
            Vec3d a = from.add(ox, oy, oz), b = to.add(ox, oy, oz);
            HitResult hit = area.raycast(new RaycastContext(a, b, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, focused));
            if (hit.getType() != HitResult.Type.MISS) {
                double d = hit.getPos().distanceTo(a) - .05;
                best = Math.min(best, Math.max(0, d / length));
            }
        }
        return best;
    }

    static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            ClientCommandManager.literal("시점")
                .executes(ctx -> { ctx.getSource().sendFeedback(Text.literal(status())); return 1; })
                .then(ClientCommandManager.literal("어깨").executes(ctx -> set(ctx.getSource(), s -> s.shoulder = true)))
                .then(ClientCommandManager.literal("기본").executes(ctx -> set(ctx.getSource(), s -> s.shoulder = false)))
                .then(ClientCommandManager.literal("좌우").executes(ctx -> set(ctx.getSource(), s -> s.side = -s.side)))
                .then(ClientCommandManager.literal("거리").then(ClientCommandManager.argument("블록", FloatArgumentType.floatArg(AimSettings.MIN_DISTANCE, AimSettings.MAX_DISTANCE))
                    .executes(ctx -> { float v = FloatArgumentType.getFloat(ctx, "블록"); return set(ctx.getSource(), s -> s.distance = v); })))
                .then(ClientCommandManager.literal("옆").then(ClientCommandManager.argument("블록", FloatArgumentType.floatArg(0, AimSettings.MAX_SIDE))
                    .executes(ctx -> { float v = FloatArgumentType.getFloat(ctx, "블록"); return set(ctx.getSource(), s -> s.sideOffset = v); })))
                .then(ClientCommandManager.literal("높이").then(ClientCommandManager.argument("블록", FloatArgumentType.floatArg(-.5f, 1.5f))
                    .executes(ctx -> { float v = FloatArgumentType.getFloat(ctx, "블록"); return set(ctx.getSource(), s -> s.height = v); })))));
    }

    private static int set(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, java.util.function.Consumer<AimSettings> change) {
        var s = AimSettings.get();
        change.accept(s);
        s.save();
        source.sendFeedback(Text.literal(status()));
        return 1;
    }

    static String status() {
        var s = AimSettings.get();
        return "시점: " + (s.shoulder ? "어깨 (" + (s.side > 0 ? "오른쪽" : "왼쪽") + ")" : "기본 3인칭")
            + String.format(" · 거리 %.1f · 옆 %.1f · 높이 %.1f", s.distance, s.sideOffset, s.height)
            + "  /시점 어깨|기본|좌우|거리 n|옆 n|높이 n";
    }
}
