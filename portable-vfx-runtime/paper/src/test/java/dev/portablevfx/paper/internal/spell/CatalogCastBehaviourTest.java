package dev.portablevfx.paper.internal.spell;

import dev.portablevfx.paper.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 시전 엔진의 상한·핸들 해제·무시청자·귀환·대상 필터를 가짜 서비스/월드로 검증한다. */
class CatalogCastBehaviourTest {
    private interface Call {Object invoke(String method,Object[] args);}
    private interface Trace {RayTraceResult run(Location start,Vector direction,double distance,Predicate<Object> filter);}
    private static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
        if(m.getName().equals("equals"))return p==a[0];
        if(m.getName().equals("hashCode"))return System.identityHashCode(p);
        if(m.getName().equals("toString"))return type.getSimpleName();
        return call.invoke(m.getName(),a);
    }));}

    /** 평지 월드 하나, 시전자 한 명, 기록하는 가짜 PortableVfxService. */
    private static final class Scene {
        final UUID owner=UUID.randomUUID(),worldId=UUID.randomUUID();
        final List<EffectRequest> requests=new ArrayList<>();final List<String> played=new ArrayList<>();final Map<String,UUID> handles=new HashMap<>();
        final List<UUID> finished=new ArrayList<>(),stopped=new ArrayList<>();
        final Map<UUID,Entity> entities=new HashMap<>();final List<UUID> removedEntities=new ArrayList<>();
        final Set<String> noViewer=new HashSet<>(),deferredOnly=new HashSet<>(),refused=new HashSet<>();
        final Set<UUID> hidden=new HashSet<>();
        boolean stopThrows,dead;int teleports;
        float yaw;double x;
        Trace trace=(start,direction,distance,filter)->null;
        @SuppressWarnings("unchecked")
        final World world=proxy(World.class,(method,args)->switch(method){
            case "getUID" -> worldId;
            case "getKey" -> NamespacedKey.minecraft("test");
            case "isChunkLoaded" -> true;
            case "getMinHeight" -> -64;
            case "rayTrace" -> trace.run((Location)args[0],(Vector)args[1],(Double)args[2],(Predicate<Object>)args[6]);
            case "rayTraceBlocks" -> null;
            case "getNearbyEntities" -> new ArrayList<>(entities.values());
            case "spawn" -> spawnAnchor((Location)args[0],(Consumer<Object>)args[2]);
            default -> throw new AssertionError(method);
        });
        final Player player=proxy(Player.class,(method,args)->switch(method){
            case "getUniqueId" -> owner;
            case "getWorld" -> world;
            case "getLocation" -> new Location(world,x,64,0,yaw,0);
            case "getEyeLocation" -> new Location(world,x,65.6,0,yaw,0);
            case "isDead" -> dead;
            case "isOnline","isValid" -> true;
            case "canSee" -> !hidden.contains(((Entity)args[0]).getUniqueId());
            case "getGameMode" -> GameMode.SURVIVAL;
            default -> throw new AssertionError(method);
        });
        final Server server=proxy(Server.class,(method,args)->switch(method){
            case "getPluginManager" -> proxy(org.bukkit.plugin.PluginManager.class,(m,a)-> { if(m.equals("getPlugin")) return null; throw new AssertionError(m); });
            case "isPrimaryThread" -> true;
            case "getPlayer" -> player;
            case "getEntity" -> entities.get((UUID)args[0]);
            default -> throw new AssertionError(method);
        });
        final PortableVfxService service=proxy(PortableVfxService.class,(method,args)->switch(method){
            case "owner" -> {assertNotNull(args[0]);assertNotNull(args[1]);yield null;}
            case "supportsCatalogCast" -> true;
            case "startCast","startFollowCast" -> {
                requests.add((EffectRequest)args[0]);String effect=((EffectRequest)args[0]).effectId();
                if(refused.contains(effect))throw new java.util.concurrent.RejectedExecutionException("per-tick play request limit reached");
                played.add(effect);UUID handle=UUID.randomUUID();handles.put(effect,handle);
                yield noViewer.contains(effect)?new PlayResult(handle,0,0,64):deferredOnly.contains(effect)?new PlayResult(handle,0,0,64,1):new PlayResult(handle,1,0,64);
            }
            case "updateCast" -> 1;
            case "finishCast" -> {finished.add((UUID)args[0]);yield true;}
            case "stop" -> {if(stopThrows)throw new IllegalStateException("PortableVFX is not enabled");stopped.add((UUID)args[0]);yield true;}
            default -> throw new AssertionError(method);
        });
        Object spawnAnchor(Location at,Consumer<Object> setup){
            UUID id=UUID.randomUUID();Set<String> tags=new HashSet<>();Location[] where={at.clone()};
            ArmorStand stand=proxy(ArmorStand.class,(method,args)->switch(method){
                case "getUniqueId" -> id;
                case "getLocation" -> where[0].clone();
                case "teleport" -> {teleports++;where[0]=((Location)args[0]).clone();yield true;}
                case "remove" -> {entities.remove(id);removedEntities.add(id);yield null;}
                case "addScoreboardTag" -> tags.add((String)args[0]);
                case "getScoreboardTags" -> tags;
                case "isMarker","isValid" -> true;
                case "isDead" -> false;
                case "getNearbyEntities" -> List.of();
                case "setVisible","setMarker","setGravity","setInvulnerable","setSilent","setPersistent","setCollidable" -> null;
                default -> throw new AssertionError(method);
            });
            setup.accept(stand);entities.put(id,stand);return stand;
        }
        Monster monster(double mx,double my,double mz,boolean dead){
            UUID id=UUID.randomUUID();
            Monster monster=proxy(Monster.class,(method,args)->switch(method){
                case "getUniqueId" -> id;
                case "getLocation","getEyeLocation" -> new Location(world,mx,my,mz);
                case "getWorld" -> world;
                case "isDead" -> dead;
                case "isValid" -> !dead;
                case "getScoreboardTags" -> Set.of();
                default -> throw new AssertionError(method);
            });
            entities.put(id,monster);return monster;
        }
        CatalogVisualEngine engine(MemoryConfiguration bindings,CatalogVisualEngine.Limits limits){return new CatalogVisualEngine(service,CatalogVisualEngine.read(bindings),limits);}
        void run(Runnable body) throws Exception {
            var field=Bukkit.class.getDeclaredField("server");field.setAccessible(true);Object previous=field.get(null);field.set(null,server);
            try{body.run();}finally{field.set(null,previous);}
        }
    }

    private static Map<String,Object> phase(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private static MemoryConfiguration spell(String id,double range,String trajectory,List<Map<String,Object>> phases){
        MemoryConfiguration root=new MemoryConfiguration();add(root,id,range,trajectory,phases);return root;
    }
    private static void add(MemoryConfiguration root,String id,double range,String trajectory,List<Map<String,Object>> phases){
        var section=root.createSection(id);section.set("range",range);section.set("speed",1);
        if(trajectory!=null)section.set("trajectory",trajectory);
        section.set("phases",phases);
    }
    private static final CatalogVisualEngine.Limits SMALL=new CatalogVisualEngine.Limits(3,2,16,5);

    @Test void limitsComeFromConfigurationAndSlotsAreReleasedWhenACastEnds() throws Exception {
        Scene scene=new Scene();
        var engine=scene.engine(spell("aura",15,null,List.of(phase("effect","claude:test/aura","anchor","caster","duration-ticks",6))),SMALL);
        scene.run(()->{
            assertEquals(true,engine.cast(scene.player,"aura"));assertEquals(true,engine.cast(scene.player,"aura"));
            assertEquals(false,engine.cast(scene.player,"aura"),"third cast exceeds max-casts-per-player=2");
            assertTrue(engine.lastFailure(scene.owner).startsWith("player active cast capacity"));
            assertEquals(2,engine.activeCasts());
            for(int i=0;i<7;i++)engine.tick();
            assertEquals(0,engine.activeCasts());
            assertEquals(true,engine.cast(scene.player,"aura"),"the per-player counter is decremented when casts end");
            engine.clear();assertEquals(0,engine.activeCasts());
            assertEquals(true,engine.cast(scene.player,"aura"));assertEquals(true,engine.cast(scene.player,"aura"));
        });
        assertEquals(new CatalogVisualEngine.Limits(1,1,16,0),new CatalogVisualEngine.Limits(-5,0,1,-1),"limits are clamped to safe minimums");
        assertEquals(new CatalogVisualEngine.Limits(8192,64,4096,1200),new CatalogVisualEngine.Limits(1<<30,1<<30,1<<30,1<<30));
        assertEquals(512,CatalogVisualEngine.Limits.DEFAULT.maxCasts());assertEquals(12,CatalogVisualEngine.Limits.DEFAULT.maxPerPlayer());
    }

    @Test void finishedProjectileNoLongerHoldsTheCastUntilItsFullTtl() throws Exception {
        Scene scene=new Scene();
        var engine=scene.engine(spell("bolt",2,null,List.of(
                phase("effect","claude:test/projectile","anchor","projectile","role","projectile","duration-ticks",600),
                phase("trigger","impact","effect","claude:test/impact","anchor","impact","role","impact","duration-ticks",10))),SMALL);
        scene.run(()->{
            assertEquals(true,engine.cast(scene.player,"bolt"));
            for(int i=0;i<4;i++)engine.tick();
            assertTrue(scene.played.contains("claude:test/impact"));
            assertTrue(scene.finished.contains(scene.handles.get("claude:test/projectile")));
            assertEquals(1,engine.activeCasts());
            for(int i=0;i<12;i++)engine.tick();
            // 예전에는 끝난 투사체가 600 tick 동안 시전(플레이어당 8칸 중 1칸)을 붙잡았다.
            assertEquals(0,engine.activeCasts());
        });
    }

    @Test void naturallyExpiredPhaseEndsTheCastWithoutAHardStop() throws Exception {
        Scene scene=new Scene();
        var engine=scene.engine(spell("aura",15,null,List.of(phase("effect","claude:test/aura","anchor","caster","duration-ticks",5))),SMALL);
        scene.run(()->{
            assertEquals(true,engine.cast(scene.player,"aura"));
            for(int i=0;i<4;i++)engine.tick();
            assertEquals(1,engine.activeCasts());
            engine.tick();
            // 릴레이 핸들은 같은 TTL 로 스스로 만료된다. 여기서 STOP 을 보내면 클라이언트의 잔여 입자를 잘라낼 수 있다.
            assertTrue(scene.stopped.isEmpty(),"natural TTL expiry must not hard-stop the client effect");
            assertEquals(0,engine.activeCasts());
            // 수명이 남은 단계는 시전이 취소될 때 여전히 STOP 된다.
            assertEquals(true,engine.cast(scene.player,"aura"));engine.tick();engine.removePlayer(scene.owner);
            assertEquals(List.of(scene.handles.get("claude:test/aura")),scene.stopped);
        });
    }

    @Test void laterPhaseWithoutViewersOrWithAnErrorDoesNotCancelTheCast() throws Exception {
        Scene scene=new Scene();
        var engine=scene.engine(spell("combo",15,null,List.of(
                phase("effect","claude:test/begin","anchor","caster","duration-ticks",40),
                phase("effect","claude:test/far","anchor","target","delay-ticks",2,"duration-ticks",40),
                phase("effect","claude:test/refused","anchor","target","delay-ticks",3,"duration-ticks",40),
                phase("effect","claude:test/late","anchor","target","delay-ticks",4,"duration-ticks",40))),SMALL);
        scene.noViewer.add("claude:test/far");scene.refused.add("claude:test/refused");
        scene.run(()->{
            assertEquals(true,engine.cast(scene.player,"combo"));
            for(int i=0;i<5;i++)engine.tick();
            assertEquals(List.of("claude:test/begin","claude:test/far","claude:test/late"),scene.played);
            assertEquals(1,engine.activeCasts());
            assertEquals(1,engine.noViewerPhases());assertEquals(1,engine.phaseFailures());assertEquals(0,engine.tickFailures());
            assertTrue(engine.lastTickFailure().contains("claude:test/refused"));
            assertFalse(scene.stopped.contains(scene.handles.get("claude:test/begin")));
        });
    }

    @Test void throttledViewersCountAsSuccessButNoEligibleViewerAtAllIsRefused() throws Exception {
        Scene scene=new Scene();
        MemoryConfiguration bindings=spell("queued",15,null,List.of(phase("effect","claude:test/queued","anchor","caster","duration-ticks",40)));
        add(bindings,"unseen",15,null,List.of(phase("effect","claude:test/unseen","anchor","caster","duration-ticks",40)));
        add(bindings,"refused",15,null,List.of(phase("effect","claude:test/refused","anchor","caster","duration-ticks",40)));
        var engine=scene.engine(bindings,SMALL);
        scene.deferredOnly.add("claude:test/queued");scene.noViewer.add("claude:test/unseen");scene.refused.add("claude:test/refused");
        scene.run(()->{
            assertEquals(true,engine.cast(scene.player,"queued"),"a PLAY queued for a throttled viewer is a successful cast");
            assertEquals(false,engine.cast(scene.player,"unseen"));
            assertEquals("no eligible viewer in range",engine.lastFailure(scene.owner));
            assertEquals(false,engine.cast(scene.player,"refused"));
            assertTrue(engine.lastFailure().startsWith("phase exception RejectedExecutionException"));
            assertEquals(1,engine.activeCasts(),"refused casts do not leak slots");
            assertNull(engine.cast(scene.player,"unknown_spell"));
            assertEquals("",engine.lastFailure(UUID.randomUUID()));
        });
    }

    @Test void returningBladePassesBlocksOnTheWayBackAndFiresReturnToCaster() throws Exception {
        Scene scene=new Scene();
        var engine=scene.engine(spell("blade",3,"out-and-back",List.of(
                phase("effect","claude:test/blade","anchor","projectile","role","projectile","duration-ticks",600),
                phase("trigger","external:return_to_caster","effect","claude:test/catch","anchor","impact","role","impact","duration-ticks",20))),SMALL);
        // 돌아오는 방향(-z)에는 항상 블록이 있다: 예전에는 여기서 TTL 까지 멈췄다.
        scene.trace=(start,direction,distance,filter)->direction.getZ()<0?new RayTraceResult(start.toVector(),BlockFace.SOUTH):null;
        scene.run(()->{
            assertEquals(true,engine.cast(scene.player,"blade"));
            for(int i=0;i<12;i++)engine.tick();
            assertTrue(scene.played.contains("claude:test/catch"),"return_to_caster must fire");
            assertTrue(scene.finished.contains(scene.handles.get("claude:test/blade")));
        });
        // 시전자가 계속 달아나도 귀환은 유한 시간 안에 끝난다.
        Scene runaway=new Scene();
        var chase=runaway.engine(spell("blade",3,"out-and-back",List.of(
                phase("effect","claude:test/blade","anchor","projectile","role","projectile","duration-ticks",600),
                phase("trigger","external:return_to_caster","effect","claude:test/catch","anchor","impact","role","impact","duration-ticks",20))),SMALL);
        runaway.run(()->{
            assertEquals(true,chase.cast(runaway.player,"blade"));
            for(int i=0;i<80;i++){runaway.x-=2;chase.tick();}
            assertTrue(runaway.played.contains("claude:test/catch"));
        });
    }

    @Test void aimIgnoresHiddenDeadAndAnchorEntities() throws Exception {
        Scene scene=new Scene();
        var engine=scene.engine(spell("mark",15,null,List.of(phase("effect","claude:test/mark","anchor","target","role","attached","duration-ticks",40))),SMALL);
        Monster hidden=scene.monster(0,65,5,false),dead=scene.monster(0,65,6,true),visible=scene.monster(0,65,7,false);
        scene.hidden.add(hidden.getUniqueId());
        ArmorStand anchor=(ArmorStand)scene.spawnAnchor(new Location(scene.world,0,65,4),stand->((ArmorStand)stand).addScoreboardTag(CatalogVisualEngine.ANCHOR_TAG));
        List<Entity> order=List.of(anchor,hidden,dead,visible);
        // 실제 월드처럼 필터를 통과한 가장 가까운 엔티티를 맞힌다.
        scene.trace=(start,direction,distance,filter)->order.stream().filter(filter::test).findFirst().map(e->new RayTraceResult(e.getLocation().toVector(),e)).orElse(null);
        scene.run(()->{
            assertEquals(true,engine.cast(scene.player,"mark"));
            scene.hidden.add(visible.getUniqueId());
            assertEquals(false,engine.cast(scene.player,"mark"),"a vanished entity cannot be selected as the target");
            assertEquals("requires living target",engine.lastFailure(scene.owner));
        });
    }

    @Test void disableCleanupRemovesVirtualAnchorsEvenWhenTheServiceRefuses() throws Exception {
        Scene scene=new Scene();
        MemoryConfiguration bindings=spell("familiar",15,null,List.of(phase("effect","claude:test/familiar","anchor","external","duration-ticks",400)));
        bindings.getConfigurationSection("familiar").createSection("virtual-anchor").set("offset",List.of(1,1,0));
        var engine=scene.engine(bindings,SMALL);
        scene.run(()->{
            assertEquals(true,engine.cast(scene.player,"familiar"));assertEquals(true,engine.cast(scene.player,"familiar"));
            assertEquals(2,scene.entities.size());
            assertTrue(scene.entities.values().stream().allMatch(e->e.getScoreboardTags().contains(CatalogVisualEngine.ANCHOR_TAG)));
            // 가만히 선 시전자의 앵커는 수렴한 뒤 다시 teleport 하지 않는다.
            for(int i=0;i<60;i++)engine.tick();
            int settled=scene.teleports;
            for(int i=0;i<20;i++)engine.tick();
            assertEquals(settled,scene.teleports);
            scene.yaw=90;engine.tick();assertTrue(scene.teleports>settled,"moving or turning still updates the anchor");
            // onDisable: 서비스는 isEnabled()==false 라 예외를 던진다. 그래도 엔티티는 지워져야 한다.
            scene.stopThrows=true;
            engine.clear();
            assertEquals(0,scene.entities.size());assertEquals(2,scene.removedEntities.size());assertEquals(0,engine.activeCasts());
            scene.stopThrows=false;
            assertEquals(true,engine.cast(scene.player,"familiar"));
            assertEquals(0,engine.shutdown());
            assertEquals(0,scene.entities.size());assertEquals(0,engine.activeCasts());
            assertTrue(scene.stopped.isEmpty(),"shutdown never calls the (disabled) service");
        });
    }

    @Test void scheduledExpireKeepsTheCastUntilItFiresThenFreesTheSlot() throws Exception {
        Scene scene=new Scene();
        MemoryConfiguration bindings=spell("ward",15,null,List.of(
                phase("effect","claude:test/ward","anchor","caster","duration-ticks",4),
                phase("trigger","expire","effect","claude:test/ward_end","anchor","caster","duration-ticks",3)));
        bindings.getConfigurationSection("ward").createSection("sequence").set("sustainStart",0.5);
        bindings.getConfigurationSection("ward").set("holdTicks",10);
        var engine=scene.engine(bindings,SMALL);
        scene.run(()->{
            assertEquals(true,engine.cast(scene.player,"ward"));
            for(int i=0;i<19;i++)engine.tick();
            assertEquals(1,engine.activeCasts(),"the cast waits for its scheduled expire even with no live phase");
            assertFalse(scene.played.contains("claude:test/ward_end"));
            engine.tick();
            assertEquals(1,scene.played.stream().filter("claude:test/ward_end"::equals).count());
            for(int i=0;i<4;i++)engine.tick();
            assertEquals(0,engine.activeCasts());
            assertEquals(1,scene.played.stream().filter("claude:test/ward_end"::equals).count(),"expire fires exactly once");
        });
    }

    @Test void slotsAndAnchorsAreAlwaysReleasedOnDeathQuitWorldUnloadAndFailedStarts() throws Exception {
        Scene scene=new Scene();
        MemoryConfiguration bindings=spell("familiar",15,null,List.of(phase("effect","claude:test/familiar","anchor","external","duration-ticks",400)));
        bindings.getConfigurationSection("familiar").createSection("virtual-anchor").set("offset",List.of(1,1,0));
        add(bindings,"bolt",4,null,List.of(
                phase("effect","claude:test/projectile","anchor","projectile","role","projectile","duration-ticks",600),
                phase("trigger","impact","effect","claude:test/impact","anchor","impact","role","impact","duration-ticks",10)));
        var engine=scene.engine(bindings,new CatalogVisualEngine.Limits(4,2,16,5));
        scene.run(()->{
            // 시작 실패(예산 거부·무시청자)는 앵커와 칸을 남기지 않는다. 여러 번 반복해도 카운터가 어긋나지 않는다.
            for(int i=0;i<10;i++){
                scene.refused.add("claude:test/familiar");assertEquals(false,engine.cast(scene.player,"familiar"));scene.refused.clear();
                scene.noViewer.add("claude:test/familiar");assertEquals(false,engine.cast(scene.player,"familiar"));scene.noViewer.clear();
            }
            assertEquals(0,engine.activeCasts());assertEquals(0,scene.entities.size());assertEquals(20,scene.removedEntities.size());
            // 사망
            assertEquals(true,engine.cast(scene.player,"familiar"));assertEquals(true,engine.cast(scene.player,"bolt"));
            assertEquals(false,engine.cast(scene.player,"bolt"),"per-player cap");
            scene.dead=true;engine.tick();scene.dead=false;
            assertEquals(0,engine.activeCasts());assertEquals(0,scene.entities.size());
            // 접속 종료/월드 이동
            assertEquals(true,engine.cast(scene.player,"familiar"));assertEquals(true,engine.cast(scene.player,"familiar"));
            engine.removePlayer(scene.owner);
            assertEquals(0,engine.activeCasts());assertEquals(0,scene.entities.size());
            // 월드 언로드
            assertEquals(true,engine.cast(scene.player,"familiar"));engine.removeWorld(scene.worldId);
            assertEquals(0,engine.activeCasts());assertEquals(0,scene.entities.size());
            // 칸이 음수/양수로 밀리지 않았다: 정확히 2개까지 다시 시전할 수 있다.
            assertEquals(true,engine.cast(scene.player,"bolt"));assertEquals(true,engine.cast(scene.player,"bolt"));
            assertEquals(false,engine.cast(scene.player,"bolt"));
            // 투사체가 사거리 끝에서 끝나고 충돌 단계까지 재생한 뒤 시전이 스스로 끝난다(600 tick 을 기다리지 않는다).
            for(int i=0;i<30;i++)engine.tick();
            assertEquals(0,engine.activeCasts());
            assertEquals(2,scene.played.stream().filter("claude:test/impact"::equals).count());
            assertEquals(true,engine.cast(scene.player,"bolt"));assertEquals(true,engine.cast(scene.player,"bolt"));
            assertEquals(false,engine.cast(scene.player,"bolt"));
        });
    }

    @Test void lenientReadSkipsOnlyBrokenSpells() {
        MemoryConfiguration root=spell("good",15,null,List.of(phase("effect","claude:test/good","duration-ticks",40)));
        add(root,"bad_effect",15,null,List.of(phase("effect","minecraft:not_claude")));
        add(root,"bad_range",500,null,List.of(phase("effect","claude:test/x")));
        add(root,"bad_vector",15,null,List.of(phase("effect","claude:test/y","offset",List.of("a","b","c"))));
        root.set("not_a_map",5);
        Map<String,String> problems=new LinkedHashMap<>();
        var plans=CatalogVisualEngine.readLenient(root,problems);
        assertEquals(Set.of("good"),plans.keySet());
        assertEquals(Set.of("bad_effect","bad_range","bad_vector","not_a_map"),problems.keySet());
        assertThrows(RuntimeException.class,()->CatalogVisualEngine.read(root));
    }

    @Test void serverCancellationDiscardsDelayedFormationAndFinishesExistingHandles()throws Exception {
        Scene scene=new Scene();var engine=scene.engine(spell("chant",30,null,List.of(
            phase("effect","claude:test/first","anchor","caster","duration-ticks",200),
            phase("effect","claude:test/later","anchor","caster","delay-ticks",40,"duration-ticks",100))),SMALL);
        scene.run(()->{assertEquals(true,engine.cast(scene.player,"chant"));assertEquals(1,engine.trigger(scene.owner,"chant","external:cancel",null,null));for(int i=0;i<60;i++)engine.tick();});
        assertEquals(List.of("claude:test/first"),scene.played);assertTrue(scene.finished.contains(scene.handles.get("claude:test/first")));
    }
    @Test void worldStaticStructureOffsetsIgnoreCasterFacingAndUseConfirmedAim()throws Exception {
        Scene scene=new Scene();scene.yaw=90;var engine=scene.engine(spell("chant",30,null,List.of(
            phase("effect","claude:test/structure","anchor","impact","role","world-static","delay-ticks",8,"duration-ticks",40,"offset",List.of(0,14,6)))),SMALL);
        scene.run(()->{assertEquals(true,engine.cast(scene.player,"chant"));engine.trigger(scene.owner,"chant","external:aim",new Location(scene.world,10,64,20),null);for(int i=0;i<8;i++)engine.tick();});
        assertEquals(1,scene.requests.size());var request=scene.requests.getFirst();assertEquals(10,request.x());assertEquals(78,request.y());assertEquals(26,request.z());
    }

    @Test void externalOnlyPlanWaitsForServerAndSnapshotsDelayedEventPositions()throws Exception {
        Scene scene=new Scene();var binding=spell("harvest",15,"server-events",List.of(
            phase("effect","claude:test/harvest","trigger","external:harvest","anchor","impact","delay-ticks",4,"duration-ticks",10)));
        binding.set("harvest.sequence.sustainStart",0);binding.set("harvest.holdTicks",20);
        var engine=scene.engine(binding,SMALL);
        scene.run(()->{assertEquals(true,engine.cast(scene.player,"harvest"));assertTrue(scene.played.isEmpty());
            assertEquals(1,engine.trigger(scene.owner,"harvest","external:harvest",new Location(scene.world,1,64,1),null));
            assertEquals(1,engine.trigger(scene.owner,"harvest","external:harvest",new Location(scene.world,2,64,2),null));
            for(int i=0;i<4;i++)engine.tick();assertEquals(2,scene.requests.size());assertEquals(1,scene.requests.get(0).x());assertEquals(2,scene.requests.get(1).x());
            for(int i=0;i<30;i++)engine.tick();assertEquals(0,engine.activeCasts());
        });
    }
}
