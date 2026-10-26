package dev.portablevfx.client.claude;

import dev.portablevfx.client.EffectRuntime;
import dev.portablevfx.client.api.PortableVfxApi;
import dev.portablevfx.client.definition.EffectLibrary;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/** Generic cosmetic flight controls. No collision tests, gameplay, entity creation or packets. */
public final class ClaudePreviewController {
    private final MinecraftClient client;private final EffectRuntime runtime;private final PortableVfxApi api;private final EffectLibrary library;
    private record Flight(Vec3d direction,double speed) {}
    private final Map<UUID,Flight> flights=new LinkedHashMap<>();
    private static final class GroundFlight {
        final Identifier endEffect;final Vec3d direction;final double speed,distance,hold;
        double elapsed,travelled;
        GroundFlight(Identifier endEffect,Vec3d direction,double speed,double distance,double hold){this.endEffect=endEffect;this.direction=direction;this.speed=speed;this.distance=distance;this.hold=hold;}
    }
    private final Map<UUID,GroundFlight> groundFlights=new LinkedHashMap<>();
    public ClaudePreviewController(MinecraftClient client,EffectRuntime runtime,PortableVfxApi api,EffectLibrary library){this.client=client;this.runtime=runtime;this.api=api;this.library=library;}
    public Optional<UUID> launch(Identifier id,double speed) {
        runtime.requireMainThread();var definition=library.get(id);
        if(client.player==null||definition==null||!definition.backend().equals("claude")||!Double.isFinite(speed)||speed<0||speed>32)return Optional.empty();
        Vec3d direction=client.player.getRotationVec(1).normalize();
        var uuid=api.play(id,client.player.getEyePos().add(direction.multiply(1.5)),1,0);
        uuid.ifPresent(value->{runtime.basis(value,ClaudeFrames.projectile(vector(direction)));flights.put(value,new Flight(direction,speed));});return uuid;
    }
    /** Local visual-only preview. Explicit parameters are not authoritative gameplay timings. */
    public Optional<UUID> groundLaunch(Identifier effect,Identifier endEffect,double width,double speed,double distance,double hold) {
        runtime.requireMainThread();
        if(client.player==null || client.world==null || !Double.isFinite(width)||width<=0||width>64
                || !Double.isFinite(speed)||speed<=0||speed>32 || !Double.isFinite(distance)||distance<=0||distance>128
                || !Double.isFinite(hold)||hold<0||hold>10 || hold+distance/speed>19)return Optional.empty();
        var definition=library.get(endEffect);
        if(definition==null||!definition.backend().equals("claude"))return Optional.empty();
        double yaw=Math.toRadians(client.player.getYaw());Vec3d direction=new Vec3d(-Math.sin(yaw),0,Math.cos(yaw));
        var id=api.play(effect,client.player.getPos().add(direction.multiply(3.6)),1,0);
        id.ifPresent(value->{api.width(value,width);runtime.basis(value,ClaudeFrames.projectile(vector(direction)));
            flights.put(value,new Flight(direction,0));groundFlights.put(value,new GroundFlight(endEffect,direction,speed,distance,hold));});
        return id;
    }
    public Optional<UUID> follow(Identifier effect) {
        runtime.requireMainThread();if(client.player==null)return Optional.empty();
        return api.playAttached(effect,client.player,dev.portablevfx.protocol.EffectAnchor.ENTITY,Vec3d.ZERO,1,0,0);
    }
    /** Independent impact at the observer's feet; it does not finish or mutate the wave. */
    public Optional<UUID> burst(UUID flight,Identifier effect) {
        runtime.requireMainThread();Flight state=flights.get(flight);
        if(client.player==null||state==null||!runtime.contains(flight)||runtime.isFinishing(flight))return Optional.empty();
        var id=api.play(effect,client.player.getPos(),1,0);
        id.ifPresent(value->runtime.basis(value,ClaudeFrames.impact(new double[]{0,1,0},vector(state.direction))));return id;
    }
    public Optional<UUID> impact(UUID flight,Identifier effect,Vec3d normal) {
        runtime.requireMainThread();Flight state=flights.get(flight);Vec3d position=runtime.currentPosition(flight);
        if(state==null||position==null)return Optional.empty();
        var result=api.impact(flight,effect,position,normal==null?state.direction.negate():normal,state.direction);
        if(result.isPresent()){flights.remove(flight);groundFlights.remove(flight);}return result;
    }
    public void tick() {
        if(client.isPaused())return;
        var groundIterator=groundFlights.entrySet().iterator();
        while(groundIterator.hasNext()) {
            var entry=groundIterator.next();UUID id=entry.getKey();GroundFlight state=entry.getValue();
            if(!runtime.contains(id)||runtime.isFinishing(id)){groundIterator.remove();flights.remove(id);continue;}
            double before=state.elapsed;state.elapsed+=.05;
            double moveTime=Math.max(0,state.elapsed-state.hold)-Math.max(0,before-state.hold);
            double step=Math.min(state.distance-state.travelled,state.speed*moveTime);
            Vec3d p=runtime.currentPosition(id);
            if(p==null||!runtime.move(id,p.add(state.direction.multiply(step)))){runtime.finish(id);groundIterator.remove();flights.remove(id);continue;}
            state.travelled+=step;
            if(state.travelled+1e-8>=state.distance) {
                api.impact(id,state.endEffect,runtime.currentPosition(id),new Vec3d(0,1,0),state.direction);
                runtime.finish(id);groundIterator.remove();flights.remove(id);
            }
        }
        flights.entrySet().removeIf(entry->{
            UUID id=entry.getKey();if(!runtime.contains(id)||runtime.isFinishing(id))return true;
            Vec3d p=runtime.currentPosition(id);
            if(p==null||!runtime.move(id,p.add(entry.getValue().direction.multiply(entry.getValue().speed/20)))) {runtime.stop(id);return true;}
            return false;
        });
    }
    private static double[] vector(Vec3d v){return new double[]{v.x,v.y,v.z};}
}
