package school.magiccodex.paper;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * C04 aim (2026-10-21): pure checks for a client-chosen aim point (unit tested). The server keeps the authority:
 * a point is used only when it is within range, in front of the server's look ray (the client turns the server head
 * towards the point just before casting) and not behind a wall. Slack covers the server/client eye difference while
 * moving (broom flight).
 */
final class AimCheck {
    private AimCheck() {}
    /** Allowed eye mismatch: standing/walking, and airborne (flying, gliding, riding). */
    static final double SLACK_GROUND = 2.0, SLACK_AIR = 6.0;
    /** Largest angle between the server look ray and the eye->point direction that is always accepted. */
    static final double MAX_ANGLE_DEG = 12;
    /** Ground spells: how far below the eyes a ground point may be (client scans 32 blocks down). */
    static final double GROUND_DEPTH = 34;
    /** Ground AoE projection: the ground under the point is searched this far down; none = the zone stays in the air. */
    static final double GROUND_SNAP = 20;

    static double slack(boolean airborne) { return airborne ? SLACK_AIR : SLACK_GROUND; }

    /** Range: 3D for normal spells; horizontal (with a vertical limit) for ground spells cast from above. */
    static boolean inRange(Vector eye, Vector point, double range, double slack, boolean ground) {
        double dx = point.getX() - eye.getX(), dy = point.getY() - eye.getY(), dz = point.getZ() - eye.getZ(), r = range + slack;
        if (ground) return dx * dx + dz * dz <= r * r && dy <= r && -dy <= GROUND_DEPTH + slack;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /** Direction: within MAX_ANGLE of the look ray, or the point is at most `slack` blocks beside the ray. */
    static boolean inFront(Vector eye, Vector look, Vector point, double slack) {
        Vector to = point.clone().subtract(eye);
        double dist = to.length();
        if (dist < 1.5) return true;
        if (look.lengthSquared() < 1e-9) return false;
        Vector dir = look.clone().normalize();
        double along = to.dot(dir);
        if (along <= 0) return false;
        if (along / dist >= Math.cos(Math.toRadians(MAX_ANGLE_DEG))) return true;
        double side2 = Math.max(0, dist * dist - along * along);
        return side2 <= slack * slack;
    }

    /** Sight: nothing solid between the eyes and the point, except within `tolerance` blocks of the point. */
    static boolean clear(double pointDistance, Double blockHitDistance, double tolerance) {
        return blockHitDistance == null || blockHitDistance >= pointDistance - tolerance;
    }

    /** An entity target: the chosen point must lie on (near) the entity's box. */
    static boolean onEntity(BoundingBox box, Vector point, double slack) {
        return box.clone().expand(0.6 + slack).contains(point);
    }
}
