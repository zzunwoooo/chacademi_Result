package dev.portablevfx.paper.api;

import java.util.UUID;
import dev.portablevfx.protocol.EffectBasis;

/**
 * Generic visual-only API registered with Bukkit's ServicesManager.
 * Every method must be called on the main server thread while PortableVFX is enabled.
 * Invalid requests throw IllegalArgumentException; exhausted play/handle budgets throw
 * java.util.concurrent.RejectedExecutionException. No method performs gameplay actions.
 */
public interface PortableVfxService {
    PlayResult play(EffectRequest request);

    /** Explicit deterministic seed/timing/anchor request. Only extended-capable clients receive it. */
    default PlayResult play(EffectRequest request, PlaybackOptions options) {
        throw new UnsupportedOperationException("Extended playback requires the dual runtime");
    }

    /** Exposes the existing visual entity-follow operation without parsing a command response.
     * Uses the loaded living target's position and requires the request's world to match. */
    default PlayResult follow(EffectRequest request, UUID target) {
        throw new UnsupportedOperationException("Follow API requires alpha.6");
    }

    /** Stop an existing handle for its actual recipients; control delivery may span ticks. */
    boolean stop(UUID handle);

    /** Remove all server handles and enqueue one clear per affected compatible recipient. */
    int clear();

    /** Degrees, bounded +/-360. Does not restart the effect or extend its original lifetime.
     * Best effort, at most one request per active handle per server tick. Returns deliveries.
     * Legacy clients continue static/follow playback and do not receive this extension. */
    default int orient(UUID handle, float yaw, float pitch, float roll) { return 0; }

    /** Starts a generic Claude-system virtual cast. Server owns every position/basis update.
     * Requires authority and extended-play capabilities, a claude: ID, scale=1, white RGB and opacity=1.
     * Effect ID presence is not acknowledged. Author visual overrides in config.
     * No entity, target, collision, damage, or gameplay action is created by this API. */
    default PlayResult startCast(EffectRequest request, EffectBasis initialBasis, long seed) {
        throw new UnsupportedOperationException("Authoritative casts require 3.1.0-claude.alpha.1");
    }

    /** Starts with a bounded width in metres, independent of uniform scale. Zero uses authored reference.
     * A positive width requires the additional width capability, and is applied before first emission. */
    default PlayResult startCast(EffectRequest request, EffectBasis initialBasis, long seed, double effectWidth) {
        if (effectWidth == 0) return startCast(request, initialBasis, seed);
        throw new UnsupportedOperationException("Explicit effectWidth requires width playback");
    }

    /** Once per cast/tick; positions must remain within 256 blocks of start and original world.
     * Best effort current sample only; does not extend TTL. */
    default int updateCast(UUID handle, double x, double y, double z, EffectBasis basis) { return 0; }

    /** Atomically finishes the projectile and starts the explicit impact effect at a server hit pose.
     * EffectBasis.impact(normalX, normalY, normalZ, directionX, directionY, directionZ)
     * constructs the surface-aligned basis. Only original capable recipients are eligible. */
    default PlayResult impactCast(UUID handle, EffectRequest impact, EffectBasis basis, long seed) {
        throw new UnsupportedOperationException("Authoritative impacts require 3.1.0-claude.alpha.1");
    }

    /** Width-bearing terminal phase. Pass the same width as the wave for an aligned collapse. */
    default PlayResult impactCast(UUID handle, EffectRequest impact, EffectBasis basis, long seed, double effectWidth) {
        if (effectWidth == 0) return impactCast(handle, impact, basis, seed);
        throw new UnsupportedOperationException("Explicit effectWidth requires width playback");
    }

    /** Graceful emission finish without a hit; stop(handle) remains immediate hard cancellation. */
    default boolean finishCast(UUID handle) { return false; }
    default boolean finishCast(UUID handle,boolean clearLocal) { if(!clearLocal)return finishCast(handle);throw new UnsupportedOperationException("Clear-local finish unavailable"); }

    /** Full catalog capability (new explicit stop, link and scale parameters). */
    default boolean supportsCatalogCast(UUID player) { return false; }
    default PlayResult startCast(EffectRequest request,EffectBasis basis,long seed,double width,double scaleInput,double linkLength) {
        if(scaleInput==0&&linkLength<0)return startCast(request,basis,seed,width);
        throw new UnsupportedOperationException("Catalog parameters unavailable");
    }
    default PlayResult startFollowCast(EffectRequest request,UUID target,long seed,double width,double scaleInput) {
        throw new UnsupportedOperationException("Controlled follow unavailable");
    }
    default int updateCast(UUID handle,double x,double y,double z,EffectBasis basis,double linkLength) {
        if(linkLength<0)return updateCast(handle,x,y,z,basis);
        throw new UnsupportedOperationException("Link pose unavailable");
    }

    /** Associate a visual with its actual caster; never changes simulation or gameplay. */
    default void owner(UUID handle,UUID caster) {}

    VfxStatus status();
}
