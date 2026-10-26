package school.chacademia.kyudichi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

/** Ball state + one shared mover. Positions are ball centres; velocities are blocks/tick. */
class Ball {
    final BallKind kind; final int index;
    Vector pos, prev, vel = new Vector();
    BallVisual visual;
    int wanderTimer;
    Vector steerTarget;

    Ball(BallKind kind, int index, Vector pos) { this.kind = kind; this.index = index; this.pos = pos.clone(); this.prev = pos.clone(); }

    /**
     * Moves by vel inside `box` (soft walls: reflect and damp) without entering solid blocks (axis-separated, reflect).
     * Returns true if anything was hit.
     */
    boolean step(World world, Box box, double restitution) {
        prev = pos.clone();
        boolean hit = false;
        double[] p = {pos.getX(), pos.getY(), pos.getZ()}, v = {vel.getX(), vel.getY(), vel.getZ()};
        double[] lo = {box.minX() + 0.3, box.minY() + 0.3, box.minZ() + 0.3}, hi = {box.maxX() - 0.3, box.maxY() - 0.3, box.maxZ() - 0.3};
        // fast balls (1-2 blocks/tick) move in sub-steps of at most 0.45 blocks so they cannot pass through a wall or floor
        int n = (int) Math.min(8, Math.max(1, Math.ceil(Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / 0.45)));
        for (int k = 0; k < n; k++) {
            for (int a = 0; a < 3; a++) {
                double next = p[a] + v[a] / n;
                if (next < lo[a] || next > hi[a]) { v[a] = -v[a] * restitution; next = Math.clamp(next, lo[a], Math.max(lo[a], hi[a])); hit = true; }
                double old = p[a]; p[a] = next;
                if (solid(world, p[0], p[1], p[2])) { p[a] = old; v[a] = -v[a] * restitution; hit = true; }
            }
        }
        pos = new Vector(p[0], p[1], p[2]);
        vel = new Vector(v[0], v[1], v[2]);
        return hit;
    }

    /**
     * Keeps a self-moving ball away from the arena walls (owner 2026-10-20: players chasing it hit the invisible boundary).
     * Inside `margin` of a face the outward part of `desired` fades out and an inward push of up to `speed` is added.
     * Not used for thrown / deflected balls (they may fly to the wall).
     */
    Vector avoidEdges(Vector desired, Box box, double margin, double marginY, double speed) {
        if (margin <= 0) return desired;
        double[] p = {pos.getX(), pos.getY(), pos.getZ()}, v = {desired.getX(), desired.getY(), desired.getZ()};
        double[] lo = {box.minX(), box.minY(), box.minZ()}, hi = {box.maxX(), box.maxY(), box.maxZ()};
        for (int a = 0; a < 3; a++) {
            double m = Math.min(a == 1 ? marginY : margin, (hi[a] - lo[a]) / 4);
            if (m <= 0.01) continue;
            double near = 1 - (p[a] - lo[a]) / m, far = 1 - (hi[a] - p[a]) / m;
            if (near > 0) { near = Math.min(1, near); if (v[a] < 0) v[a] *= 1 - near; v[a] += speed * near; }
            if (far > 0) { far = Math.min(1, far); if (v[a] > 0) v[a] *= 1 - far; v[a] -= speed * far; }
        }
        return new Vector(v[0], v[1], v[2]);
    }

    /** Squared distance from point c to the segment this tick moved along (prev → pos): hits cannot be skipped at speed. */
    double sweptDistanceSquared(Vector c) {
        Vector ab = pos.clone().subtract(prev), ac = c.clone().subtract(prev);
        double len2 = ab.lengthSquared();
        double t = len2 < 1e-9 ? 1 : Math.clamp(ac.dot(ab) / len2, 0, 1);
        return prev.clone().add(ab.multiply(t)).distanceSquared(c);
    }

    /** Resting on something solid (or the box floor) right under the ball. */
    boolean grounded(World world, Box box) {
        return pos.getY() - 0.45 <= box.minY() + 0.3 || solid(world, pos.getX(), pos.getY() - 0.45, pos.getZ());
    }

    /** An unloaded chunk counts as solid: the ball bounces off instead of forcing a synchronous chunk load on the main thread. */
    static boolean solid(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return true;
        Block b = w.getBlockAt(bx, (int) Math.floor(y), bz);
        return !b.isPassable();
    }

    Location location(World w) { return new Location(w, pos.getX(), pos.getY(), pos.getZ()); }

    /** Steers velocity toward `desired` (blocks/tick) by `rate` (0..1 per tick). */
    void steer(Vector desired, double rate) { vel.add(desired.clone().subtract(vel).multiply(Math.clamp(rate, 0, 1))); }

    static Vector randomDirection(java.util.Random r, double verticalScale) {
        Vector d = new Vector(r.nextGaussian(), r.nextGaussian() * verticalScale, r.nextGaussian());
        return d.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : d.normalize();
    }

    // ------------------------------------------------------------------
    static final class Quaffle extends Ball {
        enum State { FREE, HELD, FLYING }
        State state = State.FREE;
        UUID holder; long heldSince;
        UUID thrower; Team throwerTeam; long thrownAt;
        Quaffle(Vector p) { super(BallKind.QUAFFLE, 0, p); }
    }

    static final class Bludger extends Ball {
        /** owner 2026-10-20: CHASE = getting into range, WINDUP = short telegraph, DASH = straight charge that flies on through. */
        enum State { FREE, CHASE, WINDUP, DASH, DEFLECT }
        State state = State.FREE;
        UUID target; long startAt;                       // startAt: second bludger delay
        UUID deflectBy; Team deflectTeam; long deflectUntil; UUID deflectedFrom;
        long windupUntil, dashUntil;
        final Map<UUID, Long> ignore = new HashMap<>();
        Bludger(int i, Vector p) { super(BallKind.BLUDGER, i, p); }
    }

    static final class Snitch extends Ball {
        long nextPauseAt, pausedUntil, nextDashAt, dashUntil;
        Vector dashDir;
        Snitch(Vector p) { super(BallKind.SNITCH, 0, p); }
    }
}
