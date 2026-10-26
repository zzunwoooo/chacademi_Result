package school.magiccodex.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Shoulder view fix for server-side entity rays (taming target card, taming cast).
 * The server traces from the player's eyes along the head rotation, but the shoulder camera sits beside the head,
 * so its crosshair ray runs parallel and offset: a creature under the crosshair can be missed by the eye ray.
 * When that happens, one look packet turns the server-side head toward the creature for the following request,
 * and {@link #restore} puts the real rotation back right after it. Nothing changes on the client; no protocol change.
 */
public final class CrosshairAim {
    private CrosshairAim() {}

    /** Creature under the crosshair that the eye ray would miss, as {yaw, pitch} from the eyes; null when no fix is needed. */
    static float[] correction(MinecraftClient c, double reach) {
        var player = c.player;
        var world = c.world;
        if (player == null || world == null || !ShoulderCamera.active() || !AimView.valid() || player.isSpectator()) return null;
        Vec3d camera = AimView.camera(), eye = player.getEyePos(), direction = player.getRotationVec(1f);
        Vec3d far = camera.add(direction.multiply(camera.distanceTo(eye) + reach));
        var block = world.raycast(new RaycastContext(camera, far, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d limit = block.getType() == HitResult.Type.MISS ? far : block.getPos();
        Entity best = null;
        Vec3d bestPoint = null;
        double closest = camera.squaredDistanceTo(limit);
        for (var e : world.getOtherEntities(player, player.getBoundingBox().expand(reach + 3), AimTargeting::targetable)) {
            var hit = e.getBoundingBox().expand(.1).raycast(camera, limit);
            if (hit.isPresent() && camera.squaredDistanceTo(hit.get()) < closest) {
                closest = camera.squaredDistanceTo(hit.get());
                best = e;
                bestPoint = hit.get();
            }
        }
        if (best == null || eye.squaredDistanceTo(bestPoint) > reach * reach) return null;
        // Already on the eye ray (first person, or far enough away): leave the rotation alone.
        if (best.getBoundingBox().expand(.2).raycast(eye, eye.add(direction.multiply(reach))).isPresent()) return null;
        // Aim a little inside the box from the crosshair's hit point so the server ray cannot graze past an edge.
        Vec3d point = bestPoint.add(best.getBoundingBox().getCenter().subtract(bestPoint).multiply(.35));
        return AimMath.yawPitch(point.x - eye.x, point.y - eye.y, point.z - eye.z);
    }

    /** Sends the corrected head rotation to the server. @return true when a packet was sent (call {@link #restore} after). */
    static boolean aim(MinecraftClient c, double reach) {
        float[] look = correction(c, reach);
        if (look == null || c.getNetworkHandler() == null) return false;
        c.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(look[0], look[1], c.player.isOnGround(), c.player.horizontalCollision));
        return true;
    }

    /** Puts the server-side head rotation back to the player's real one. */
    static void restore(MinecraftClient c) {
        if (c.player == null || c.getNetworkHandler() == null) return;
        c.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(c.player.getYaw(), c.player.getPitch(), c.player.isOnGround(), c.player.horizontalCollision));
    }
}
