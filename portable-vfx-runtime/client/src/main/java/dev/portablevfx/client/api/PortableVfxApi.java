package dev.portablevfx.client.api;

import dev.portablevfx.client.EffectRuntime;
import dev.portablevfx.client.definition.EffectDefinition;
import dev.portablevfx.client.definition.EffectLibrary;
import dev.portablevfx.protocol.PlayEffect;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * Public local-client API, obtainable from PortableVfxClient.api(). All calls require the client
 * thread; other threads must use MinecraftClient.getInstance().execute(...). Never sends packets.
 */
public final class PortableVfxApi {
    private final MinecraftClient client;
    private final EffectLibrary library;
    private final EffectRuntime runtime;

    public PortableVfxApi(MinecraftClient client, EffectLibrary library, EffectRuntime runtime) {
        this.client = client; this.library = library; this.runtime = runtime;
    }

    /** Validated wire-compatible request. False means unknown ID, wrong world, distance or capacity. */
    public boolean play(PlayEffect request) { return runtime.play(request); }

    /** durationTicks == 0 resolves the JSON default before constructing a protocol request. */
    public Optional<UUID> play(Identifier effectId, Vec3d position, float scale, int durationTicks) {
        runtime.requireMainThread();
        EffectDefinition effect = library.get(effectId);
        if (effect == null || client.world == null) return Optional.empty();
        UUID id = UUID.randomUUID();
        PlayEffect request = new PlayEffect(id, effectId.toString(), client.world.getRegistryKey().getValue().toString(),
                position.x, position.y, position.z, 0, 0, 0, scale, 0xffffff, 1,
                durationTicks == 0 ? effect.durationTicks() : durationTicks);
        return runtime.play(request) ? Optional.of(id) : Optional.empty();
    }

    /** Explicit local named attachment with deterministic seed; only follows an already tracked entity. */
    public Optional<UUID> playAttached(Identifier effectId, net.minecraft.entity.Entity target,
            dev.portablevfx.protocol.EffectAnchor anchor, Vec3d localOffset,
            float scale, int durationTicks, long seed) {
        runtime.requireMainThread();
        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(localOffset, "localOffset");
        EffectDefinition effect = library.get(effectId);
        if (effect == null || client.world == null || target.getWorld() != client.world) return Optional.empty();
        UUID id = UUID.randomUUID();
        var request = new PlayEffect(id, effectId.toString(), client.world.getRegistryKey().getValue().toString(),
                target.getX(), target.getY(), target.getZ(), 0, 0, 0, scale, 0xffffff, 1,
                durationTicks == 0 ? effect.durationTicks() : durationTicks, target.getUuid(), seed, -1,
                anchor, (float) localOffset.x, (float) localOffset.y, (float) localOffset.z);
        return runtime.play(request) ? Optional.of(id) : Optional.empty();
    }

    public boolean orient(dev.portablevfx.protocol.OrientEffect update) { return runtime.orient(update); }

    /** Generic local Claude phase controls. No gameplay, damage, collision or server messages. */
    public boolean move(UUID id, Vec3d position) { return runtime.move(id, position); }
    public boolean basis(UUID id, Vec3d right, Vec3d up, Vec3d forward) {
        return runtime.basis(id,new float[]{(float)right.x,(float)right.y,(float)right.z,(float)up.x,(float)up.y,(float)up.z,(float)forward.x,(float)forward.y,(float)forward.z});
    }
    public boolean finish(UUID id) { return runtime.finish(id); }
    public boolean width(UUID id,double metres) { return runtime.width(id,metres); }
    /** Caller chooses the impact system and hit inputs; projectile particles drain generically. */
    public Optional<UUID> impact(UUID flight, Identifier impactId, Vec3d hitPoint, Vec3d surfaceNormal, Vec3d incomingDirection) {
        runtime.requireMainThread();
        var definition=library.get(impactId);
        if(definition==null || !definition.backend().equals("claude") || !runtime.isClaude(flight) || runtime.isFinishing(flight))return Optional.empty();
        if(surfaceNormal==null)surfaceNormal=incomingDirection.negate();
        float[] basis=dev.portablevfx.protocol.EffectBasis.impact(
            surfaceNormal.x,surfaceNormal.y,surfaceNormal.z,incomingDirection.x,incomingDirection.y,incomingDirection.z).toArray();
        var id=play(impactId,hitPoint,1,0);
        if(id.isPresent()) { runtime.basis(id.get(),basis);runtime.width(id.get(),runtime.width(flight));runtime.finish(flight,true); }
        return id;
    }

    public String status() { return runtime.status(); }

    public boolean stop(UUID instanceId) { return runtime.stop(instanceId); }
    public void clear() { runtime.clear(); }
    public Set<Identifier> list() { runtime.requireMainThread(); return Set.copyOf(library.ids()); }
    public Set<UUID> activeInstances() { return runtime.activeIds(); }
}
