package school.magiccodex.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * The exact view/projection used for this frame's world, captured during world rendering so the HUD
 * (drawn right after) can project world points to the screen and turn the aim cursor into a world ray.
 */
public final class AimView {
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f();
    private static Vec3d camera = Vec3d.ZERO;
    private static float fovY = 70;
    private static boolean valid;

    private AimView() {}

    static void capture(WorldRenderContext context) {
        Matrix4f projection = context.projectionMatrix(), view = context.positionMatrix();
        if (projection == null || view == null || context.camera() == null) { valid = false; return; }
        VIEW_PROJECTION.set(projection).mul(view);
        INVERSE.set(VIEW_PROJECTION).invert();
        camera = context.camera().getPos();
        float m11 = projection.m11();
        fovY = m11 > 1e-4f ? (float) Math.toDegrees(2 * Math.atan(1 / m11)) : 70;
        valid = Float.isFinite(INVERSE.m00());
    }

    public static boolean valid() { return valid && MinecraftClient.getInstance().world != null; }
    public static Vec3d camera() { return camera; }
    public static float fovY() { return fovY; }

    /** World point -> GUI-scaled screen point, or null when behind the camera / no frame yet. */
    public static float[] toScreen(Vec3d world) {
        if (!valid()) return null;
        var w = MinecraftClient.getInstance().getWindow();
        return AimMath.project(VIEW_PROJECTION, world.x - camera.x, world.y - camera.y, world.z - camera.z, w.getScaledWidth(), w.getScaledHeight());
    }

    /** GUI-scaled screen point -> world ray direction from {@link #camera()}. */
    public static Vec3d ray(float screenX, float screenY) {
        var w = MinecraftClient.getInstance().getWindow();
        double[] d = AimMath.unproject(INVERSE, screenX, screenY, w.getScaledWidth(), w.getScaledHeight());
        return new Vec3d(d[0], d[1], d[2]);
    }
}
