package school.magiccodex.paper;

import java.io.File;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import io.lumine.mythic.bukkit.MythicBukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.CastAimProtocol;

/** MythicMobs owns combat/effects. This bridge only authenticates routes, captures stats and performs inventory/block utilities. */
final class SpellRuntime implements AutoCloseable {

    private final MagicCodexBridge plugin;
    private final SpellUtility utility;
    private Map<String,SpellRules.Rule> rules;
    SpellRuntime(MagicCodexBridge plugin)throws Exception{
        this.plugin=plugin;utility=new SpellUtility(plugin);
        if(!new File(plugin.getDataFolder(),"spell-runtime.yml").exists())plugin.saveResource("spell-runtime.yml",false);
        reload();
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin,CastAimProtocol.CHANNEL,inbox);
        plugin.getServer().getPluginManager().registerEvents(inbox,plugin);
        ManaBridge.runtime=new ManaBridge.Runtime(this::reload,this::routeCount,this);
    }
    int routeCount(){return rules.size();}
    /** spell-runtime.yml 을 다시 읽는다 (검증에 실패하면 예외, 기존 경로 유지). MythicMobs YAML 은 /mm reload 가 따로 읽는다. */
    void reload()throws Exception{var next=SpellRules.load(new File(plugin.getDataFolder(),"spell-runtime.yml"));rules=next;utility.reloadRecipes();}
    private static boolean enemy(Entity entity){
        return entity instanceof Monster||entity instanceof Slime||entity instanceof Ghast||entity instanceof Phantom||entity instanceof Shulker||entity instanceof EnderDragon;
    }
    /** Validated cast: route + chosen targets. ok=false means refuse (message already sent). */
    record Plan(boolean ok,SpellRules.Rule rule,Entity target,Location location){}
    private static final Plan REFUSED=new Plan(false,null,null,null);
    /** null: not a runtime spell. Never changes the world. */
    Plan prepare(Player p,String id){
        var r=rules.get(id);if(r==null)return null;
        if(!Bukkit.getPluginManager().isPluginEnabled("MythicMobs"))return REFUSED;
        var skill=MythicBukkit.inst().getSkillManager().getSkill(r.skill());
        if(skill.isEmpty()){plugin.getLogger().warning("마법 실행 경로 "+id+"의 MythicMobs 스킬 "+r.skill()+"을 찾지 못했습니다 (/mm reload 확인).");CodexCommands.warn(p,"magic-fail","지금은 이 마법을 쓸 수 없어요");return REFUSED;}
        Entity target=null;Location location=p.getLocation();boolean air=false;
        if(r.target()==SpellRules.Target.AIM||r.target()==SpellRules.Target.ENTITY||r.target()==SpellRules.Target.AIR_ENTITY||r.target()==SpellRules.Target.BURNING_ENTITY){
            // C04: the client's aim preview (shoulder camera, the point the player saw) is used when it passes the server checks.
            var chosen=clientAim(p,r);
            if(chosen!=null){target=chosen.entity();location=chosen.point();air=chosen.air();}
            else{
            // Fallback (old client, or the client point failed the checks): the server's own eye ray.
            var trace=p.getWorld().rayTrace(p.getEyeLocation(),p.getEyeLocation().getDirection(),r.range(),FluidCollisionMode.NEVER,true,.55,e->!e.equals(p)&&MagicProtectionLink.target(p,e));
            target=trace==null?null:trace.getHitEntity();location=trace==null?p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(r.range())):trace.getHitPosition().toLocation(p.getWorld());
            // Nothing under the aim: a listener (큐디치 공중 조준) may move the point to a chosen distance (same checks as a client point).
            if(trace==null&&r.target()==SpellRules.Target.AIM){
                var initial=location.clone();var aim=new MagicAimPointEvent(p,id,r.range(),initial);Bukkit.getPluginManager().callEvent(aim);
                var picked=aim.getLocation();
                if(picked!=null&&picked!=initial&&valid(p,picked,r,AimCheck.slack(airborne(p)),false)){location=picked;air=true;}
            }
            // ModelEngine mobs (owner 2026-10-19: "타겟 마법이 모델 쓰는 몹한테 안 써짐"): the vanilla ray only knows the small
            // base box, and a hit on the model's hitbox Interaction/Display is not the mob. Same rule as the taming/target-HUD
            // ray: sub-hitboxes and hitbox entities resolve to the modeled base mob.
            if(r.target()!=SpellRules.Target.AIM&&!(target instanceof LivingEntity)){
                var tm=plugin.tamingBridge();LivingEntity modeled=null;
                if(tm!=null)try{modeled=tm.target(p,r.range());}catch(RuntimeException|LinkageError ignored){}
                if(modeled!=null&&MagicProtectionLink.target(p,modeled)){target=modeled;location=modeled.getLocation().add(0,modeled.getHeight()*.5,0);}
                else target=null;
            }
            }
            if(r.target()!=SpellRules.Target.AIM&&target==null){CodexCommands.warn(p,"magic-target","시야 안의 대상을 바라봐 주세요");return REFUSED;}
            if(r.target()==SpellRules.Target.AIR_ENTITY&&((LivingEntity)target).isOnGround()){CodexCommands.warn(p,"magic-target","공중에 있는 대상에게 써 주세요");return REFUSED;}
            if(r.target()==SpellRules.Target.BURNING_ENTITY&&!(target instanceof LivingEntity burning&&MagicProtectionLink.status(burning,"burn"))){CodexCommands.warn(p,"magic-target","화상 상태인 대상에게 써 주세요");return REFUSED;}
            // 1-6 #6: ground zones cast in the air land on the ground below (20 blocks); no ground = the zone stays at the aim height.
            if(r.ground()&&target==null&&!air)location=groundBelow(location);
        }
        if(!MagicProtectionLink.at(p,location))return REFUSED;
        // 생활 마법 (LifeSpells): refuse before the visual and the mana charge when there is nothing to do.
        if(r.utility()){String why=utility.refusal(p,id);if(why!=null){CodexCommands.warn(p,"magic-target",why);return REFUSED;}}
        return new Plan(true,r,target,location);
    }
    // ------------------------------------------------------------------ C04 client aim (magiccodex:cast_aim)
    private record Chosen(Entity entity,Location point,boolean air){}
    private record Received(CastAimProtocol.Aim aim,int tick){}
    private final Map<UUID,Received> aims=new HashMap<>();
    /** The aim sent just before this cast (same spell, at most 10 ticks old), used once. Server thread. */
    private CastAimProtocol.Aim takeAim(Player p,String id){
        var got=aims.remove(p.getUniqueId());
        return got==null||!got.aim().spell().equals(id)||Bukkit.getCurrentTick()-got.tick()>10?null:got.aim();
    }
    final class AimInbox implements PluginMessageListener,Listener{
        @Override public void onPluginMessageReceived(String channel,Player player,byte[] data){
            if(!CastAimProtocol.CHANNEL.equals(channel))return;
            try{aims.put(player.getUniqueId(),new Received(CastAimProtocol.decode(data),Bukkit.getCurrentTick()));}catch(IllegalArgumentException ignored){}
        }
        @EventHandler public void quit(PlayerQuitEvent e){aims.remove(e.getPlayer().getUniqueId());}
    }
    private final AimInbox inbox=new AimInbox();

    /** The client's point/target when it passes range, direction and sight checks against the server eyes; else null. */
    private Chosen clientAim(Player p,SpellRules.Rule r){
        var a=takeAim(p,r.id());if(a==null)return null;
        var world=p.getWorld();boolean airborne=airborne(p);double slack=AimCheck.slack(airborne);
        Location point=new Location(world,a.x(),a.y(),a.z());
        Entity entity=null;
        if(a.target()!=null){
            Entity found=Bukkit.getEntity(a.target());
            if(!(found instanceof LivingEntity le)||found.getWorld()!=world||!le.isValid()||le.isDead()||found.equals(p)
                    ||!MagicProtectionLink.target(p,found)||!AimCheck.onEntity(found.getBoundingBox(),point.toVector(),slack))return null;
            entity=found;
        }else if(r.target()!=SpellRules.Target.AIM)return null; // unit spells need the client's target
        boolean air=false;
        if(entity==null&&a.air()){
            // 큐디치 공중 조준: only the arena plugin may keep a point in the air (it answers with its own wheel point).
            var initial=point.clone();var event=new MagicAimPointEvent(p,r.id(),r.range(),initial);Bukkit.getPluginManager().callEvent(event);
            var picked=event.getLocation();
            if(picked!=null&&picked!=initial){point=picked;air=true;}
        }
        return valid(p,point,r,slack,r.ground()&&entity==null&&!air)?new Chosen(entity,point,air):null;
    }
    /** Same world, loaded, in range (horizontal for ground spells), in front of the server look ray, nothing solid in between. */
    private static boolean valid(Player p,Location point,SpellRules.Rule r,double slack,boolean ground){
        var world=p.getWorld();
        if(point.getWorld()!=world||!Double.isFinite(point.getX())||!Double.isFinite(point.getY())||!Double.isFinite(point.getZ())
                ||!world.isChunkLoaded(point.getBlockX()>>4,point.getBlockZ()>>4))return false;
        var eye=p.getEyeLocation();var from=eye.toVector();var to=point.toVector();
        if(!AimCheck.inRange(from,to,r.range(),slack,ground)||!AimCheck.inFront(from,eye.getDirection(),to,slack))return false;
        var delta=to.clone().subtract(from);double distance=delta.length();
        if(distance<1e-3)return true;
        var block=world.rayTraceBlocks(eye,delta.normalize(),distance,FluidCollisionMode.NEVER,true);
        Double blocked=block==null?null:block.getHitPosition().distance(from);
        return AimCheck.clear(distance,blocked,slack>AimCheck.SLACK_GROUND?2.0:1.0);
    }
    /** Flying, gliding, riding (broom) or in the air: the server eyes may lag the client's by a few blocks. */
    @SuppressWarnings("deprecation")
    private static boolean airborne(Player p){return p.isFlying()||p.isGliding()||p.getVehicle()!=null||!p.isOnGround();}
    /** Ground under a point within {@link AimCheck#GROUND_SNAP} blocks (collision shapes), or the point itself. */
    static Location groundBelow(Location at){
        var world=at.getWorld();
        if(world==null||!world.isChunkLoaded(at.getBlockX()>>4,at.getBlockZ()>>4))return at;
        var hit=world.rayTraceBlocks(at.clone().add(0,.05,0),new org.bukkit.util.Vector(0,-1,0),AimCheck.GROUND_SNAP,FluidCollisionMode.NEVER,true);
        return hit==null?at:hit.getHitPosition().toLocation(world);
    }

    boolean execute(Player p,Plan plan){
        if(!MagicProtectionLink.at(p,plan.location())||(plan.target()!=null&&!MagicProtectionLink.target(p,plan.target())))return false;
        var r=plan.rule();var mm=MythicBukkit.inst();
        double power=plugin.magicPower(p);
        boolean changed=false;
        if(r.utility()){
            if(!utility.apply(p,r.id())){CodexCommands.warn(p,"magic-target","마법을 쓸 대상이나 재료가 없어요");return false;}
            changed=true;
        }
        try{
            final Entity chosen=plan.target();
            boolean okay=mm.getAPIHelper().castSkill(p,r.skill(),p,p.getLocation(),chosen==null?List.of():List.of(chosen),List.of(plan.location()),1f,data->{
                data.getVariables().putDouble("magic_power",power);
                data.getVariables().putDouble("damage",r.damage(power));
                data.getVariables().putDouble("pvp_scale",r.pvpScale(power)); // 전투 스펙 2026-10-26: 플레이어 대상 피해 배율 (MagicMythic Targets)
                data.getVariables().putDouble("magic_haste",plugin.magicHaste(p));
            });
            return changed||okay;
        }catch(RuntimeException error){
            plugin.getLogger().warning("마법 실행 실패: "+r.id()+" / "+error.getClass().getSimpleName());
            // A committed utility must not become free because its cosmetic dispatch failed.
            return changed;
        }
    }
    /** Kept for callers outside the catalogue path. */
    Boolean cast(Player p,String id){
        Plan plan=prepare(p,id);
        if(plan==null)return null;
        return plan.ok()&&execute(p,plan);
    }
    public void close(){
        try{plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin,CastAimProtocol.CHANNEL,inbox);}catch(RuntimeException ignored){}
        HandlerList.unregisterAll(inbox);aims.clear();
        var r=ManaBridge.runtime;if(r!=null&&r.owner()==this)ManaBridge.runtime=null;utility.close();rules=Map.of();}
}
