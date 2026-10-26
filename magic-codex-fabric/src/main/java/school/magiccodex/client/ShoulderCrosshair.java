package school.magiccodex.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Shoulder view: what the crosshair is on decides the block to break/place and the entity to hit or use.
 * Vanilla traces from the eyes, but the shoulder camera sits beside the head, so the eye ray and the crosshair ray
 * are parallel and offset: the block in front of the character was chosen instead of the one under the crosshair.
 * The choice now follows the camera ray, and is then checked from the eyes exactly as the server checks it
 * (same reach, nothing solid in between), so reach never grows and the server accepts every action.
 */
public final class ShoulderCrosshair {
    private ShoulderCrosshair() {}

    /** Distance along the view ray from the camera to the plane through the eyes (where the camera ray passes the head). */
    static double passDistance(Vec3d camera, Vec3d eye, Vec3d direction) {
        return Math.max(0, eye.subtract(camera).dotProduct(direction));
    }

    /** Crosshair target for the shoulder view; null keeps the vanilla result (first person, front view, spectator...). */
    public static HitResult find(Entity cameraEntity, double blockRange, double entityRange, float tickDelta) {
        var c = MinecraftClient.getInstance();
        if (!(cameraEntity instanceof PlayerEntity player) || player != c.player || player.isSpectator() || c.world == null) return null;
        if (!ShoulderCamera.active() || !c.gameRenderer.getCamera().isThirdPerson()) return null;
        Vec3d eye = player.getCameraPosVec(tickDelta), direction = player.getRotationVec(tickDelta);
        Vec3d camera = c.gameRenderer.getCamera().getPos();
        double reach = Math.max(blockRange, entityRange);
        // Start beside the head, not at the camera: blocks behind the character must never be picked.
        Vec3d start = camera.add(direction.multiply(passDistance(camera, eye, direction)));
        Vec3d end = start.add(direction.multiply(reach + 1.5));

        var world = c.world;
        BlockHitResult block = world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        Vec3d limit = block.getType() == HitResult.Type.MISS ? end : block.getPos();
        EntityHitResult entity = ProjectileUtil.raycast(player, start, limit, player.getBoundingBox().stretch(limit.subtract(eye)).expand(2.5),
                EntityPredicates.CAN_HIT, start.squaredDistanceTo(limit));

        if (entity != null) {
            // Reachable from the eyes and not behind a wall as seen from the eyes: hit it.
            BlockHitResult wall = world.raycast(new RaycastContext(eye, entity.getPos(), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
            boolean blocked = wall.getType() != HitResult.Type.MISS && wall.getPos().squaredDistanceTo(eye) < entity.getPos().squaredDistanceTo(eye) - 1e-4;
            if (!blocked && player.canInteractWithEntity(entity.getEntity(), 0)) return entity;
            if (blocked) return inRange(wall, eye, blockRange);
            return BlockHitResult.createMissed(entity.getPos(), Direction.getFacing(direction), BlockPos.ofFloored(entity.getPos()));
        }
        if (block.getType() == HitResult.Type.MISS)
            return BlockHitResult.createMissed(end, Direction.getFacing(direction), BlockPos.ofFloored(end));
        // The crosshair's block, unless something nearer blocks it from the eyes: then that block (as the server sees it).
        Vec3d inside = block.getPos().add(direction.multiply(.02));
        BlockHitResult fromEye = world.raycast(new RaycastContext(eye, inside, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        if (fromEye.getType() != HitResult.Type.MISS && !fromEye.getBlockPos().equals(block.getBlockPos())) return inRange(fromEye, eye, blockRange);
        return inRange(block, eye, blockRange);
    }

    /** Same rule as vanilla's ensureTargetInRange: further than the reach from the eyes = nothing targeted. */
    private static HitResult inRange(BlockHitResult hit, Vec3d eye, double range) {
        if (hit.getPos().squaredDistanceTo(eye) <= range * range) return hit;
        return BlockHitResult.createMissed(hit.getPos(), hit.getSide(), hit.getBlockPos());
    }
}
