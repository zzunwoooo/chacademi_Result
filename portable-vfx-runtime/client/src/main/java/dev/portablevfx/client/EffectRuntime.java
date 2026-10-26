package dev.portablevfx.client;

import dev.portablevfx.client.definition.EffectDefinition;
import dev.portablevfx.client.definition.EffectLibrary;
import dev.portablevfx.protocol.PlayEffect;
import dev.portablevfx.protocol.EffectAnchor;
import dev.portablevfx.protocol.AnchorTransform;
import dev.portablevfx.protocol.OrientEffect;
import dev.portablevfx.protocol.PoseEffect;
import dev.portablevfx.protocol.FinishEffect;
import dev.portablevfx.protocol.ImpactEffect;
import dev.portablevfx.protocol.EffectControlState;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;

/** Main-client-thread owned state. Server data cannot allocate beyond MAX_ACTIVE. */
public final class EffectRuntime {
    public static final int MAX_ACTIVE = 128;
    public static final double MAX_DISTANCE = 256;
    private final MinecraftClient client;
    private final EffectLibrary library;
    private java.util.function.Supplier<Set<Identifier>> readyEffects=Set::of;
    public void readiness(java.util.function.Supplier<Set<Identifier>> supplier){readyEffects=java.util.Objects.requireNonNull(supplier);}
    public Set<Identifier> readyEffectIds(){return readyEffects.get();}
    private final Map<UUID, ActiveEffect> active = new LinkedHashMap<>();
    private ClientWorld lastWorld;
    private long revision, simulationTicks;
    private String status = "No backend initialized";
    private String lastPlayRejection = "none";

    public EffectRuntime(MinecraftClient client, EffectLibrary library) {
        this.client = client;
        this.library = library;
    }

    public void owner(UUID instance,UUID owner){requireMainThread();var effect=active.get(instance);if(effect!=null&&effect.serverOrigin&&effect.owner==null)effect.owner=owner;}
    /**
     * 투명도 설정에서 빼는 효과 (오너 결정 2026-10-25, 메모 #29): 원소 반응, 던전 기믹, 큐디치 성소는 늘 100%.
     * 게임 정보(반응 종류·기믹 상태·성소 위치)를 알려 주는 효과라 내 것/남의 것 투명도로 흐려지면 안 된다.
     */
    static final java.util.Set<String> OPACITY_EXEMPT_PACKS=java.util.Set.of("elementreactions",
        "conduitturn","dimensionmark","lightbeam","mirrortune","prismtint","rewind","statuewake","steamignition","stoneweight","thawingheat","vineawaken","waterlevel");
    static boolean opacityExempt(String effectId){
        if(effectId==null||!effectId.startsWith("claude:"))return false;
        int slash=effectId.indexOf('/',7);String pack=slash<0?effectId.substring(7):effectId.substring(7,slash);
        return OPACITY_EXEMPT_PACKS.contains(pack)||pack.startsWith("shrine");
    }
    public float opacity(ActiveEffect effect){
        if(opacityExempt(effect.request.effectId()))return 1f;
        boolean own=!effect.serverOrigin || effect.owner!=null&&client.player!=null&&effect.owner.equals(client.player.getUuid());
        return dev.portablevfx.client.api.VfxPreferences.get().opacity(own);
    }
    public boolean play(PlayEffect request) { return play(request, false); }

    /** Called only by the registered S2C receiver; distinguishes network instances from local previews. */
    public boolean playFromServer(PlayEffect request) { return play(request, true); }

    private boolean play(PlayEffect request, boolean serverOrigin) {
        requireMainThread();
        synchronizeWorld();
        if (client.world == null || client.player == null) return false;
        if (!client.world.getRegistryKey().getValue().toString().equals(request.dimensionId())) return false;
        if (client.player.squaredDistanceTo(request.x(), request.y(), request.z()) > MAX_DISTANCE * MAX_DISTANCE) return false;
        EffectDefinition definition = library.get(Identifier.of(request.effectId()));
        if (definition == null) {
            lastPlayRejection="effect not loaded: "+request.effectId()+" (config errors="+library.configErrors().size()+")";
            return false;
        }
        if (definition.backend().equals("claude") && !readyEffectIds().contains(Identifier.of(request.effectId()))) {
            lastPlayRejection="GPU resources not ready: "+request.effectId();status=lastPlayRejection;return false;
        }
        if (definition.backend().equals("claude") && request.scale()!=1f) return false;
        boolean attached = "attached".equals(library.claudeRole(Identifier.of(request.effectId())));
        if(attached && (request.anchor()!=EffectAnchor.ENTITY || request.followEntity()==null
                || request.offsetX()!=0 || request.offsetY()!=0 || request.offsetZ()!=0)) {
            lastPlayRejection="Attached Claude system requires entity feet with zero offset";return false;
        }
        // Both renderers currently use authored layer colors; runtime tint is not implemented.
        if (request.rgb() != 0xffffff || request.opacity() != 1f) return false;
        long elapsed = request.startTick() < 0 ? 0 : Math.max(0, client.world.getTime() - request.startTick());
        if (elapsed >= request.durationTicks()) return false;
        // Reject when full; never evict another caller's effect silently. Same UUID replaces itself.
        if (active.size() >= MAX_ACTIVE && !active.containsKey(request.instanceId())) return false;
        net.minecraft.entity.Entity followTarget = null;
        if (request.followEntity() != null) {
            for (var entity : client.world.getEntities()) {
                if (request.followEntity().equals(entity.getUuid())) { followTarget = entity; break; }
            }
            if (followTarget == null || followTarget.isRemoved() || !followTarget.isAlive()) return false;
        }
        ActiveEffect effect = new ActiveEffect(request, definition, followTarget);
        effect.serverOrigin = serverOrigin;
        effect.attached = attached;
        effect.effectWidth = request.effectWidth();
        effect.scaleInput=request.scaleInput();effect.linkLength=request.linkLength();
        effect.age = (int) elapsed;
        if (position(effect, 1) == null) return false;
        active.put(request.instanceId(), effect);
        lastPlayRejection="none";
        return true;
    }

    public boolean orient(OrientEffect update) {
        requireMainThread(); synchronizeWorld();
        ActiveEffect effect = active.get(update.instanceId());
        if(effect == null || !effect.orientation.accept(update))return false;
        effect.claudeBasis=null; // Explicit Euler updates supersede the previous matrix pose.
        return true;
    }

    /** Network-owned Claude poses have one lifecycle sequence shared with finish and impact. */
    public boolean poseFromServer(PoseEffect update) {
        requireMainThread(); synchronizeWorld();
        ActiveEffect effect = authoritative(update.instanceId());
        if (effect == null || effect.finishing || effect.request.anchor() != EffectAnchor.WORLD
                || client.player == null || client.player.squaredDistanceTo(update.x(),update.y(),update.z()) > MAX_DISTANCE*MAX_DISTANCE
                || !effect.control.accept(update.instanceId(),update.dimensionId(),update.sequence(),false)) return false;
        move(update.instanceId(),new net.minecraft.util.math.Vec3d(update.x(),update.y(),update.z()));
        effect.claudeBasis = update.basis().toArray();
        if(update.linkLength()>=0)effect.linkLength=update.linkLength();
        return true;
    }

    public boolean finishFromServer(FinishEffect update) {
        requireMainThread(); synchronizeWorld();
        ActiveEffect effect = authoritative(update.instanceId());
        if (effect == null || !effect.control.accept(update.instanceId(),update.dimensionId(),update.sequence(),true)) return false;
        return finish(update.instanceId(), update.clearLocal());
    }

    /** Finish is authoritative even when the child asset is absent or the client is at capacity. */
    public boolean impactFromServer(ImpactEffect update) {
        requireMainThread(); synchronizeWorld();
        ActiveEffect effect = authoritative(update.instanceId());
        if (effect == null || !effect.control.accept(update.instanceId(),update.impact().dimensionId(),update.sequence(),true)) return false;
        finish(update.instanceId(), true);
        PlayEffect next = update.impact();
        EffectDefinition definition = library.get(Identifier.of(next.effectId()));
        // Never replace a pre-existing unrelated phase, including a local preview with that UUID.
        if (!active.containsKey(next.instanceId()) && definition != null && definition.backend().equals("claude")
                && playFromServer(next)) active.get(next.instanceId()).claudeBasis = update.basis().toArray();
        return true;
    }

    private ActiveEffect authoritative(UUID id) {
        ActiveEffect effect = active.get(id);
        return effect != null && effect.serverOrigin && effect.definition.backend().equals("claude") ? effect : null;
    }

    /** Move an existing client-local world effect without restarting its animation. Interpolated for rendering. */
    public boolean move(UUID id, net.minecraft.util.math.Vec3d position) {
        requireMainThread(); synchronizeWorld();
        ActiveEffect effect=active.get(id);
        if (effect==null || effect.request.anchor()!=EffectAnchor.WORLD || client.player==null
                || position==null || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || Math.abs(position.x)>dev.portablevfx.protocol.VfxProtocol.MAX_POSITION
                || Math.abs(position.y)>dev.portablevfx.protocol.VfxProtocol.MAX_POSITION
                || Math.abs(position.z)>dev.portablevfx.protocol.VfxProtocol.MAX_POSITION
                || client.player.squaredDistanceTo(position)>MAX_DISTANCE*MAX_DISTANCE) return false;
        if (effect.movingPosition==null) {
            effect.previousMovingPosition=new net.minecraft.util.math.Vec3d(effect.request.x(),effect.request.y(),effect.request.z());
        }
        effect.movingPosition=position;
        return true;
    }
    public boolean finish(UUID id) { return finish(id,false); }
    public boolean finish(UUID id, boolean clearLocal) {
        requireMainThread(); synchronizeWorld();
        ActiveEffect effect=active.get(id);
        if(effect==null || !effect.definition.backend().equals("claude"))return false;
        if(!effect.finishing) { position(effect,1); effect.finishing=true;effect.finishClearLocal=clearLocal;effect.finishAge=effect.age; } return true;
    }
    public boolean isClaude(UUID id) { requireMainThread();var effect=active.get(id);return effect!=null&&effect.definition.backend().equals("claude"); }
    public boolean isFinishing(UUID id) { requireMainThread();var effect=active.get(id);return effect==null||effect.finishing; }
    public net.minecraft.util.math.Vec3d currentPosition(UUID id) { requireMainThread();var effect=active.get(id);return effect==null?null:position(effect,1); }
    public boolean basis(UUID id, float[] basis) {
        requireMainThread();var effect=active.get(id);
        if(effect==null || !effect.definition.backend().equals("claude") || basis==null || basis.length!=9)return false;
        for(float v:basis)if(!Float.isFinite(v))return false;
        for(int a=0;a<3;a++)for(int b=a;b<3;b++) {
            double dot=0;for(int k=0;k<3;k++)dot+=basis[a*3+k]*basis[b*3+k];
            if(Math.abs(dot-(a==b?1:0))>0.00001)return false;
        }
        double det=basis[0]*(basis[4]*basis[8]-basis[5]*basis[7])-basis[3]*(basis[1]*basis[8]-basis[2]*basis[7])+basis[6]*(basis[1]*basis[5]-basis[2]*basis[4]);
        if(det<.99999)return false;
        effect.claudeBasis=basis.clone();return true;
    }
    /** Width is authored once before the render instance is created. Zero selects the authored reference. */
    public boolean width(UUID id,double metres) {
        requireMainThread();var effect=active.get(id);
        if(effect==null || !effect.definition.backend().equals("claude") || effect.renderStarted
                || !Double.isFinite(metres) || metres<0 || metres>64)return false;
        effect.effectWidth=metres;return true;
    }
    public double width(UUID id) { requireMainThread();var effect=active.get(id);return effect==null?0:effect.effectWidth; }
    public float[] renderBasis(ActiveEffect effect,float delta) {
        if(effect.attached && !effect.finishing && effect.followTarget!=null) {
            // LivingEntity.getYaw(delta) is interpolated HEAD yaw. Attached schema systems
            // follow body facing, sampled at the same render fraction as their feet position.
            float heading=effect.followTarget instanceof net.minecraft.entity.LivingEntity living
                    ? net.minecraft.util.math.MathHelper.lerpAngleDegrees(delta,living.prevBodyYaw,living.bodyYaw)
                    : effect.followTarget.getYaw(delta);
            double yaw=Math.toRadians(heading);
            effect.claudeBasis=dev.portablevfx.client.claude.ClaudeFrames.projectile(new double[]{-Math.sin(yaw),0,Math.cos(yaw)});
        }
        return effect.claudeBasis;
    }
    public boolean contains(UUID id) { requireMainThread(); return active.containsKey(id); }

    public boolean stop(UUID id) { requireMainThread(); return active.remove(id) != null; }
    public void clear() { requireMainThread(); active.clear(); revision++; }
    public Set<UUID> activeIds() { requireMainThread(); return Set.copyOf(active.keySet()); }
    public Collection<ActiveEffect> snapshot() { requireMainThread(); return List.copyOf(active.values()); }

    public void tick() {
        requireMainThread();
        synchronizeWorld();
        if (client.isPaused()) return;
        simulationTicks++;
        for (var effect:active.values()) if(effect.movingPosition!=null) effect.previousMovingPosition=effect.movingPosition;
        active.values().removeIf(effect -> {
            ++effect.age;
            boolean missing=position(effect,1)==null;
            if(!effect.finishing && effect.definition.backend().equals("claude")
                    && (effect.age>=effect.request.durationTicks() || (effect.attached && missing))) {
                effect.finishing=true;effect.finishAge=effect.age;
                effect.durationExpired=effect.age>=effect.request.durationTicks();
                // Detached world particles retain their last valid emitter pose.
                missing=effect.lastPosition==null;
            }
            return effect.age >= (effect.finishing ? effect.finishAge+1200 : effect.request.durationTicks()) || missing;
        });
    }

    /** Compatibility alias, including all newly named attachment points. */
    public net.minecraft.util.math.Vec3d followPosition(ActiveEffect effect, float delta) {
        return position(effect, delta);
    }

    /** Resolve bounded visual anchors. Local +X is left, +Y up, +Z forward at heading zero.
     * Head and shoulders are pose/dimension-based approximations, not animated bone sockets. */
    public net.minecraft.util.math.Vec3d position(ActiveEffect effect, float delta) {
        if (client.world == null || client.player == null) return null;
        if(effect.finishing && effect.attached) return effect.lastPosition;
        if (!Float.isFinite(delta)) return null;
        delta = Math.max(0, Math.min(1, delta));
        var request = effect.request;
        net.minecraft.util.math.Vec3d base;
        double heading = 0, anchorX = 0, anchorY = 0;
        if (request.anchor() == EffectAnchor.WORLD) {
            base = effect.movingPosition==null
                    ? new net.minecraft.util.math.Vec3d(request.x(), request.y(), request.z())
                    : effect.previousMovingPosition.lerp(effect.movingPosition,delta);
        } else {
            var entity = effect.followTarget;
            if (entity == null || entity.getWorld() != client.world || entity.isRemoved() || !entity.isAlive()) return null;
            base = entity.getLerpedPos(delta);
            heading = entity.getYaw(delta);
            if (!Double.isFinite(heading) || !Float.isFinite(entity.getWidth()) || !Float.isFinite(entity.getHeight())
                    || !Double.isFinite(entity.getEyeY() - entity.getY())) return null;
            double eyeHeight = Math.max(0, Math.min(32, entity.getEyeY() - entity.getY()));
            switch (request.anchor()) {
                case HEAD -> anchorY = eyeHeight;
                case LEFT_SHOULDER, RIGHT_SHOULDER -> {
                    anchorY = Math.max(0, eyeHeight - Math.min(.35, entity.getHeight() * .18));
                    anchorX = Math.min(16, entity.getWidth() * .6)
                            * (request.anchor() == EffectAnchor.LEFT_SHOULDER ? 1 : -1);
                }
                default -> { }
            }
        }
        double[] offset = AnchorTransform.rotate(request.offsetX(), request.offsetY(), request.offsetZ(),
                effect.orientation.yaw(), effect.orientation.pitch(), effect.orientation.roll());
        double[] world = AnchorTransform.rotate(offset[0] + anchorX, offset[1] + anchorY, offset[2], heading, 0, 0);
        var position = base.add(world[0], world[1], world[2]);
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || Math.abs(position.x) > dev.portablevfx.protocol.VfxProtocol.MAX_POSITION
                || Math.abs(position.y) > dev.portablevfx.protocol.VfxProtocol.MAX_POSITION
                || Math.abs(position.z) > dev.portablevfx.protocol.VfxProtocol.MAX_POSITION
                || client.player.squaredDistanceTo(position) > MAX_DISTANCE * MAX_DISTANCE) return null;
        effect.lastPosition=position;
        return position;
    }

    public void synchronizeWorld() {
        requireMainThread();
        if (lastWorld != client.world) { clear(); lastWorld = client.world; simulationTicks = 0; }
    }

    public long revision() { return revision; }
    public long simulationTicks() { return simulationTicks; }
    public String status() {
        long claude=library.snapshot().definitions().values().stream().filter(d->d.backend().equals("claude")).count();
        return status+" / loaded="+library.ids().size()+" (Claude="+claude+") / configErrors="+library.configErrors().size();
    }
    public void status(String status) { this.status = status; }
    public void requireMainThread() {
        if (!client.isOnThread()) throw new IllegalStateException("PortableVFX API must run on the Minecraft client thread");
    }

    public static final class ActiveEffect {
        private UUID owner;
        public final PlayEffect request;
        public final EffectDefinition definition;
        public int age;
        public boolean finishing,finishClearLocal,durationExpired;
        private boolean serverOrigin;
        private final EffectControlState control;
        public int finishAge;
        public float[] claudeBasis;
        public boolean attached,renderStarted;
        public double effectWidth,scaleInput,linkLength=-1;
        private net.minecraft.util.math.Vec3d lastPosition;
        private net.minecraft.util.math.Vec3d previousMovingPosition, movingPosition;
        public final dev.portablevfx.protocol.EffectOrientation orientation;
        public final net.minecraft.entity.Entity followTarget;
        ActiveEffect(PlayEffect request, EffectDefinition definition, net.minecraft.entity.Entity followTarget) {
            this.request = request; this.definition = definition; this.followTarget = followTarget;
            orientation=new dev.portablevfx.protocol.EffectOrientation(request);
            control=new EffectControlState(request.instanceId(),request.dimensionId());
        }
    }
}
