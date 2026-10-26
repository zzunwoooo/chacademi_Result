package dev.portablevfx.paper.internal.spell;

import dev.portablevfx.paper.api.EffectRequest;
import dev.portablevfx.paper.api.PlayResult;
import dev.portablevfx.paper.api.PortableVfxService;
import dev.portablevfx.protocol.EffectBasis;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Bukkit interface proxies keep gameplay, network and ModelEngine dependencies out of the tests. */
class CatalogTargetedCastTest {
    private static final String SPELL="targeted_test",PREFIX="claude:targetedtest/";

    @Test void pluginExposesTheExactServerAdapterSignatures() throws Exception {
        Class<?> plugin=dev.portablevfx.paper.PortableVfxPlugin.class;
        assertEquals(UUID.class,plugin.getMethod("startAuthorizedTargetedSpell",Player.class,String.class,LivingEntity.class).getReturnType());
        assertEquals(boolean.class,plugin.getMethod("finishAuthorizedTargetedSpell",Player.class,UUID.class,String.class,Location.class).getReturnType());
        assertEquals(boolean.class,plugin.getMethod("cancelAuthorizedTargetedSpell",Player.class,UUID.class).getReturnType());
    }

    @Test void exactValidatedBaseWinsOverNearbyRayAndAttachedPhasesFollowIt() throws Exception {
        try(Fixture f=new Fixture()){
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);
            assertNotNull(cast);assertEquals(0,f.rays,"The gameplay ray must not be repeated");
            assertPose(f.play("open").request(),5,64,4);
            f.advance(2);
            assertEquals(f.targetId,f.play("channel").follow());
            assertPose(f.play("channel").request(),5,64,4);
            f.targetAt=new Location(f.world,6,65,5);
            f.advance(1);
            Pose link=f.lastPose(f.play("tether").handle());
            assertEquals(6,link.x(),1e-8);assertEquals(65,link.y(),1e-8);assertEquals(5,link.z(),1e-8);
            Pose offset=f.lastPose(f.play("offset").handle());
            assertEquals(6,offset.x(),1e-8);assertEquals(66,offset.y(),1e-8);assertEquals(5,offset.z(),1e-8);
            assertEquals(0,f.rays);
        }
    }

    @Test void rejectsInvalidDeadWrongWorldUnloadedAndOutOfRangeTargets() throws Exception {
        try(Fixture f=new Fixture()){
            assertNull(f.engine.startTargeted(f.player,SPELL,null));
            f.targetValid=false;assertNull(f.engine.startTargeted(f.player,SPELL,f.target));f.targetValid=true;
            f.targetDead=true;assertNull(f.engine.startTargeted(f.player,SPELL,f.target));f.targetDead=false;
            f.targetAt=new Location(f.otherWorld,5,64,4);assertNull(f.engine.startTargeted(f.player,SPELL,f.target));
            f.targetAt=new Location(f.world,13,64,0);assertNull(f.engine.startTargeted(f.player,SPELL,f.target));
            f.targetAt=new Location(f.world,Double.NaN,64,4);assertNull(f.engine.startTargeted(f.player,SPELL,f.target));
            f.targetAt=new Location(f.world,5,64,4);f.loaded=false;assertNull(f.engine.startTargeted(f.player,SPELL,f.target));
            assertTrue(f.played.isEmpty());assertEquals(0,f.rays);
        }
    }

    @Test void admissionKeepsReadinessOwnerAndCapacityChecks() throws Exception {
        try(Fixture f=new Fixture()){
            assertNull(f.engine.startTargeted(f.player,"missing",f.target));
            f.ready=false;assertNull(f.engine.startTargeted(f.player,SPELL,f.target));assertEquals("readiness withdrawn",f.engine.lastFailure());f.ready=true;
            f.online=false;assertNull(f.engine.startTargeted(f.player,SPELL,f.target));f.online=true;
            f.playerDead=true;assertNull(f.engine.startTargeted(f.player,SPELL,f.target));f.playerDead=false;
            for(int i=0;i<f.engine.limits().maxPerPlayer();i++)assertNotNull(f.engine.startTargeted(f.player,SPELL,f.target));
            assertNull(f.engine.startTargeted(f.player,SPELL,f.target));assertEquals("player active cast capacity",f.engine.lastFailure());
            assertEquals(Boolean.FALSE,f.engine.cast(f.player,SPELL),"Generic and targeted casts share admission capacity");
            f.engine.clear();assertNotNull(f.engine.startTargeted(f.player,SPELL,f.target));
        }
    }

    @Test void initialFailureRollsBackButLaterVisualFailurePreservesGameplaySession() throws Exception {
        try(Fixture f=new Fixture()){
            f.recipients=0;assertNull(f.engine.startTargeted(f.player,SPELL,f.target));
            f.recipients=1;f.rejectEffect="open";assertNull(f.engine.startTargeted(f.player,SPELL,f.target));
            f.rejectEffect=null;
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);
            UUID open=f.play("open").handle();
            f.rejectEffect="channel";f.advance(2);
            assertFalse(f.stopped.contains(open));
            assertTrue(f.engine.phaseFailures()>0);
            assertTrue(f.engine.finishTargeted(f.player,cast,"external:trigger",f.targetAt));
            f.rejectEffect=null;assertNotNull(f.engine.startTargeted(f.player,SPELL,f.target));
        }
    }

    @Test void channelWaitsForServerResultAndRejectsUnscopedTriggers() throws Exception {
        try(Fixture f=new Fixture()){
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);
            f.advance(100); // Past this fixture's automatic sequence expiry and the old 4.2s cutoff.
            assertEquals(0,f.count("fizzle"));assertEquals(0,f.count("bless"));
            assertFalse(f.engine.trigger(cast,"external:trigger"));
            assertEquals(0,f.engine.trigger(f.owner,SPELL,"expire",f.targetAt,f.decoyId));
            assertTrue(f.engine.finishTargeted(f.player,cast,"external:trigger",f.targetAt));
            assertEquals(1,f.count("bless"));assertEquals(0,f.count("fizzle"));
            assertEquals(Boolean.TRUE,f.finished.get(f.play("channel").handle()),"Author clear-stopped-local intent is preserved");
            assertEquals(Boolean.TRUE,f.finished.get(f.play("tether").handle()));
        }
    }

    @Test void successIsExactlyOnceAndScopedToOwnerCastAndWorld() throws Exception {
        try(Fixture f=new Fixture()){
            UUID first=f.engine.startTargeted(f.player,SPELL,f.target),second=f.engine.startTargeted(f.player,SPELL,f.target);
            assertNotNull(first);assertNotNull(second);
            Player other=f.player(UUID.randomUUID());
            assertFalse(f.engine.finishTargeted(other,first,"external:trigger",f.targetAt));
            assertFalse(f.engine.cancelTargeted(other,first));
            assertFalse(f.engine.finishTargeted(f.player,UUID.randomUUID(),"external:trigger",f.targetAt));
            assertFalse(f.engine.finishTargeted(f.player,first,"cast",f.targetAt));
            assertFalse(f.engine.finishTargeted(f.player,first,"external:trigger",new Location(f.otherWorld,5,64,4)));
            assertFalse(f.engine.finishTargeted(f.player,first,"external:trigger",new Location(f.world,Double.POSITIVE_INFINITY,64,4)));
            assertTrue(f.engine.finishTargeted(f.player,first,"external:trigger",f.targetAt));
            assertFalse(f.engine.finishTargeted(f.player,first,"external:trigger",f.targetAt));
            assertFalse(f.engine.finishTargeted(f.player,first,"expire",f.targetAt));
            assertEquals(1,f.count("bless"));assertEquals(0,f.count("fizzle"));
            assertTrue(f.engine.finishTargeted(f.player,second,"expire",f.targetAt));
            assertEquals(1,f.count("fizzle"));
        }
    }

    @Test void failureBlocksLateSuccessAndDropsQueuedChannelStart() throws Exception {
        try(Fixture f=new Fixture()){
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);
            assertTrue(f.engine.finishTargeted(f.player,cast,"expire",f.targetAt));
            assertFalse(f.engine.finishTargeted(f.player,cast,"external:trigger",f.targetAt));
            assertFalse(f.engine.finishTargeted(f.player,cast,"expire",f.targetAt));
            f.advance(3);
            assertEquals(0,f.count("channel"));assertEquals(0,f.count("tether"));
            assertEquals(0,f.count("bless"));assertEquals(1,f.count("fizzle"));assertEquals(1,f.count("snap"));
        }
    }

    @Test void removedTargetStillGetsOneTerminalSequenceAtLastServerPosition() throws Exception {
        try(Fixture f=new Fixture()){
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);f.advance(2);
            Location last=new Location(f.world,7,64,6);
            f.entities.remove(f.targetId);f.targetValid=false;
            assertTrue(f.engine.finishTargeted(f.player,cast,"external:trigger",last));
            last.add(50,50,50); // Caller mutation cannot move the pending terminal phase.
            assertPose(f.play("bless").request(),7,64,6);
            f.advance(1);assertPose(f.play("release").request(),7,64,6);
            assertNull(f.play("release").follow());assertEquals(1,f.count("bless"));
        }
    }

    @Test void missingTerminalPointUsesLastValidTargetSampleAndDoesNotNeedEntity() throws Exception {
        try(Fixture f=new Fixture()){
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);
            f.targetAt=new Location(f.world,8,65,3);f.advance(1);
            f.entities.remove(f.targetId);f.targetValid=false;
            assertTrue(f.engine.finishTargeted(f.player,cast,"expire",null));
            assertPose(f.play("fizzle").request(),8,65,3);
        }
    }

    @Test void cancellationTimeoutAndTerminalDrainLeaveNoReusableHandle() throws Exception {
        try(Fixture f=new Fixture()){
            UUID cancelled=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cancelled);
            UUID open=f.play("open").handle();assertTrue(f.engine.cancelTargeted(f.player,cancelled));
            assertTrue(f.stopped.contains(open));assertFalse(f.engine.cancelTargeted(f.player,cancelled));
            assertFalse(f.engine.finishTargeted(f.player,cancelled,"external:trigger",f.targetAt));
            UUID timeout=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(timeout);f.advance(200);
            assertFalse(f.engine.finishTargeted(f.player,timeout,"external:trigger",f.targetAt));
            assertEquals(0,f.count("bless"));assertEquals(0,f.count("fizzle"));
            UUID terminal=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(terminal);f.advance(2);
            UUID channel=f.play("channel").handle();
            assertTrue(f.engine.finishTargeted(f.player,terminal,"external:trigger",f.targetAt));f.advance(9);
            assertTrue(f.stopped.contains(channel),"Long-lived finished channel handles are cleared when the terminal tail drains");
            assertFalse(f.engine.cancelTargeted(f.player,terminal));
        }
    }

    @Test void quitWorldChangeWorldUnloadAndReloadClearActiveVisuals() throws Exception {
        try(Fixture f=new Fixture()){
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);
            UUID open=f.play("open").handle();f.online=false;f.advance(1);
            assertTrue(f.stopped.contains(open));assertFalse(f.engine.cancelTargeted(f.player,cast));f.online=true;
            cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);
            f.playerAt=new Location(f.otherWorld,0,64,0);f.advance(1);
            assertFalse(f.engine.cancelTargeted(f.player,cast));f.playerAt=new Location(f.world,0,64,0);
            cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);f.engine.removePlayer(f.owner);
            assertFalse(f.engine.cancelTargeted(f.player,cast));
            cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);f.engine.removeWorld(f.worldId);
            assertFalse(f.engine.cancelTargeted(f.player,cast));
            cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);f.engine.clear();
            assertFalse(f.engine.cancelTargeted(f.player,cast));
        }
    }

    @Test void allTargetedEntryPointsRequireServerThread() throws Exception {
        try(Fixture f=new Fixture()){
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);f.primary=false;
            assertThrows(IllegalStateException.class,()->f.engine.startTargeted(f.player,SPELL,f.target));
            assertThrows(IllegalStateException.class,()->f.engine.finishTargeted(f.player,cast,"expire",f.targetAt));
            assertThrows(IllegalStateException.class,()->f.engine.cancelTargeted(f.player,cast));
            f.primary=true;assertTrue(f.engine.cancelTargeted(f.player,cast));
        }
    }

    @Test void ordinaryCastStillUsesRayAndOriginalAutomaticExpiry() throws Exception {
        try(Fixture f=new Fixture()){
            assertEquals(Boolean.TRUE,f.engine.cast(f.player,SPELL));assertEquals(1,f.rays);
            f.advance(2);assertEquals(f.decoyId,f.play("channel").follow());
            f.advance(2);assertEquals(1,f.count("fizzle"));
            assertEquals(0,f.engine.trigger(f.owner,SPELL,"external:trigger",f.targetAt,null));
        }
    }

    @Test void dynamicScaleUsesCastTimeHeightAndPreservesItAfterCaptureRemoval() throws Exception {
        try(Fixture f=new Fixture(dynamicConfiguration())){
            f.targetHeight=.8;
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);
            assertEquals(.8,f.play("open").scaleInput(),1e-8);
            f.targetHeight=2.4;f.advance(2);
            assertEquals(.8,f.play("channel").scaleInput(),1e-8,"Scale is immutable at the actual cast-time height");
            f.entities.remove(f.targetId);f.targetValid=false;
            assertTrue(f.engine.finishTargeted(f.player,cast,"external:trigger",f.targetAt));
            assertEquals(.8,f.play("bless").scaleInput(),1e-8);f.advance(1);
            assertEquals(.8,f.play("release").scaleInput(),1e-8);
        }
    }

    @Test void handToChestLinkTracksLiveHeightAndFreezesLastChestAfterRemoval() throws Exception {
        try(Fixture f=new Fixture(dynamicConfiguration())){
            f.targetHeight=.8;
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);f.advance(2);
            Played tether=f.play("tether");assertPose(tether.request(),.3,65.35,.4);
            assertEndpoint(tether.request().x(),tether.request().y(),tether.request().z(),tether.basis(),tether.linkLength(),5,64+.55*.8,4);
            f.targetHeight=2.4;f.targetAt=new Location(f.world,7,65,6);f.advance(1);
            Pose pose=f.lastPose(tether.handle());
            assertEndpoint(pose.x(),pose.y(),pose.z(),pose.basis(),pose.linkLength(),7,65+.55*2.4,6);
            f.entities.remove(f.targetId);f.targetValid=false;
            Location last=new Location(f.world,8,65,7);
            assertTrue(f.engine.finishTargeted(f.player,cast,"external:trigger",last));f.advance(1);
            Played release=f.play("release");
            assertEndpoint(release.request().x(),release.request().y(),release.request().z(),release.basis(),release.linkLength(),8,65+.55*2.4,7);
            assertTrue(release.basis().forwardY()>.01,"Terminal links keep their endpoint-derived vertical direction");
            f.targetHeight=50;f.targetAt=new Location(f.otherWorld,90,90,90);f.advance(1);
            Pose terminal=f.lastPose(release.handle());
            assertEndpoint(terminal.x(),terminal.y(),terminal.z(),terminal.basis(),terminal.linkLength(),8,65+.55*2.4,7);
        }
    }

    @Test void handAnchorUsesPlayerViewAndRightSideWhenTurning() throws Exception {
        try(Fixture f=new Fixture(dynamicConfiguration())){
            f.playerAt.setYaw(90);
            assertNotNull(f.engine.startTargeted(f.player,SPELL,f.target));f.advance(2);
            assertPose(f.play("tether").request(),-.4,65.35,.3);
        }
    }

    @Test void removedTargetsTerminalAttachedEffectsUseTheirHorizontalFacingInsteadOfCasterPitch() throws Exception {
        for(String event:List.of("external:trigger","expire")){
            try(Fixture f=new Fixture(terminalAttachedConfiguration())){
                f.playerAt.setPitch(70);
                f.targetAt.setYaw(90);f.targetAt.setPitch(-40);
                UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);f.advance(2);
                Location last=f.targetAt.clone();f.entities.remove(f.targetId);f.targetValid=false;
                assertTrue(f.engine.finishTargeted(f.player,cast,event,last));
                Played ending=f.play(event.equals("external:trigger")?"bless":"fizzle");
                assertNull(ending.follow());assertPose(ending.request(),5,64,4);
                assertEquals(1,ending.basis().upY(),1e-6);
                assertEquals(-1,ending.basis().forwardX(),1e-6);assertEquals(0,ending.basis().forwardY(),1e-6);assertEquals(0,ending.basis().forwardZ(),1e-6);
            }
        }
    }

    @Test void terminalAttachedVerticalFacingHasAnUprightDeterministicFallback() throws Exception {
        try(Fixture f=new Fixture(terminalAttachedConfiguration())){
            f.playerAt.setPitch(75);f.targetAt.setYaw(135);f.targetAt.setPitch(90);
            UUID cast=f.engine.startTargeted(f.player,SPELL,f.target);assertNotNull(cast);
            Location last=f.targetAt.clone();f.entities.remove(f.targetId);f.targetValid=false;
            assertTrue(f.engine.finishTargeted(f.player,cast,"expire",last));
            EffectBasis basis=f.play("fizzle").basis();
            assertEquals(1,basis.upY(),1e-6);assertEquals(0,basis.forwardY(),1e-6);assertEquals(1,basis.forwardZ(),1e-6);
        }
    }

    @Test void dynamicInputsRejectUnavailableOrInvalidHeightBeforeAnyVisualStarts() throws Exception {
        try(Fixture f=new Fixture(dynamicConfiguration())){
            for(double height:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,129}){
                f.targetHeight=height;assertNull(f.engine.startTargeted(f.player,SPELL,f.target));
            }
            f.targetHeight=1.8;f.heightUnavailable=true;assertNull(f.engine.startTargeted(f.player,SPELL,f.target));
            assertTrue(f.played.isEmpty());assertTrue(f.engine.lastFailure().contains("target height unavailable"));
            f.heightUnavailable=false;assertNotNull(f.engine.startTargeted(f.player,SPELL,f.target));
        }
    }

    @Test void existingZeroAndPositiveScaleInputsDoNotRequireHeightMeasurement() throws Exception {
        MemoryConfiguration config=configuration();
        List<Map<?,?>> phases=config.getConfigurationSection(SPELL).getMapList("phases");
        List<Map<String,Object>> updated=new ArrayList<>();
        for(Map<?,?> original:phases){Map<String,Object> p=new LinkedHashMap<>();original.forEach((k,v)->p.put(k.toString(),v));if(p.get("effect").equals(PREFIX+"channel"))p.put("scale-input",2.25);updated.add(p);}
        config.getConfigurationSection(SPELL).set("phases",updated);
        try(Fixture f=new Fixture(config)){
            f.heightUnavailable=true;
            assertNotNull(f.engine.startTargeted(f.player,SPELL,f.target));f.advance(2);
            assertEquals(0,f.play("open").scaleInput());assertEquals(2.25,f.play("channel").scaleInput());
        }
    }

    @Test void scaleSentinelAndLinkAnchorsAreStrictlyValidated() {
        for(String source:List.of("caster","target","caster-hand","target-chest")){
            MemoryConfiguration config=new MemoryConfiguration();config.createSection(SPELL).set("phases",List.of(Map.of("effect",PREFIX+"tether","scale-input",-1,"link-source",source,"link-target",source)));
            assertDoesNotThrow(()->CatalogVisualEngine.read(config));
        }
        for(String key:List.of("link-source","link-target")){
            MemoryConfiguration config=new MemoryConfiguration();config.createSection(SPELL).set("phases",List.of(Map.of("effect",PREFIX+"tether",key,"misspelled-anchor")));
            assertThrows(IllegalArgumentException.class,()->CatalogVisualEngine.read(config));
        }
        for(double scale:new double[]{-2,-.5,Double.NaN,129}){
            MemoryConfiguration config=new MemoryConfiguration();config.createSection(SPELL).set("phases",List.of(Map.of("effect",PREFIX+"open","scale-input",scale)));
            assertThrows(IllegalArgumentException.class,()->CatalogVisualEngine.read(config));
        }
    }

    private static void assertPose(EffectRequest r,double x,double y,double z){
        assertEquals(x,r.x(),1e-8);assertEquals(y,r.y(),1e-8);assertEquals(z,r.z(),1e-8);
    }
    private static void assertEndpoint(double x,double y,double z,EffectBasis basis,double length,double targetX,double targetY,double targetZ){
        assertNotNull(basis);assertTrue(length>=0);
        // EffectBasis intentionally uses wire-sized floats.
        assertEquals(targetX,x+basis.forwardX()*length,1e-5);assertEquals(targetY,y+basis.forwardY()*length,1e-5);assertEquals(targetZ,z+basis.forwardZ()*length,1e-5);
    }
    private record Played(EffectRequest request,UUID handle,UUID follow,double scaleInput,EffectBasis basis,double linkLength){}
    private record Pose(UUID handle,double x,double y,double z,EffectBasis basis,double linkLength){}
    private static final class Fixture implements AutoCloseable {
        final UUID owner=UUID.randomUUID(),worldId=UUID.randomUUID(),targetId=UUID.randomUUID(),decoyId=UUID.randomUUID();
        final List<Played> played=new ArrayList<>();final List<Pose> poses=new ArrayList<>();
        final Map<UUID,Boolean> finished=new HashMap<>();final List<UUID> stopped=new ArrayList<>();
        final Map<UUID,Entity> entities=new HashMap<>();
        boolean primary=true,ready=true,online=true,playerDead=false,targetValid=true,targetDead=false,loaded=true;
        boolean heightUnavailable;double targetHeight=1.8;
        int rays,recipients=1;String rejectEffect;
        final World world=world(worldId),otherWorld=world(UUID.randomUUID());
        Location playerAt=new Location(world,0,64,0),targetAt=new Location(world,5,64,4);
        final Player player=player(owner);
        final LivingEntity target=living(targetId,true),decoy=living(decoyId,false);
        final CatalogVisualEngine engine;
        final Field serverField;final Object previousServer;

        Fixture() throws Exception {this(configuration());}
        Fixture(MemoryConfiguration config) throws Exception {
            entities.put(targetId,target);entities.put(decoyId,decoy);
            Server server=proxy(Server.class,(method,args)->switch(method){
                case "isPrimaryThread" -> primary;
                case "getPlayer" -> owner.equals(args[0])?player:null;
                case "getEntity" -> entities.get(args[0]);
                default -> throw new AssertionError(method);
            });
            PortableVfxService service=proxy(PortableVfxService.class,(method,args)->switch(method){
                case "owner" -> {assertNotNull(args[0]);assertNotNull(args[1]);yield null;}
            case "supportsCatalogCast" -> ready;
                case "startCast","startFollowCast" -> {
                    EffectRequest r=(EffectRequest)args[0];
                    if(r.effectId().equals(PREFIX+rejectEffect))throw new RejectedExecutionException("test play budget");
                    UUID handle=UUID.randomUUID();played.add(new Played(r,handle,args[1] instanceof UUID id?id:null,(Double)args[4],args[1] instanceof EffectBasis basis?basis:null,args.length>5?(Double)args[5]:-1));
                    yield new PlayResult(handle,recipients,0,0);
                }
                case "updateCast" -> {poses.add(new Pose((UUID)args[0],(Double)args[1],(Double)args[2],(Double)args[3],(EffectBasis)args[4],args.length>5?(Double)args[5]:-1));yield 1;}
                case "finishCast" -> {finished.put((UUID)args[0],args.length>1&&Boolean.TRUE.equals(args[1]));yield true;}
                case "stop" -> {stopped.add((UUID)args[0]);yield true;}
                default -> throw new AssertionError(method);
            });
            engine=new CatalogVisualEngine(service,CatalogVisualEngine.read(config));
            serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);
            previousServer=serverField.get(null);serverField.set(null,server);
        }
        private World world(UUID id){return proxy(World.class,(method,args)->switch(method){
            case "getUID" -> id;
            case "getKey" -> NamespacedKey.minecraft("test");
            case "isChunkLoaded" -> loaded;
            case "rayTrace" -> {rays++;yield new RayTraceResult(decoy.getLocation().toVector(),decoy);}
            default -> throw new AssertionError(method);
        });}
        Player player(UUID id){return proxy(Player.class,(method,args)->switch(method){
            case "getUniqueId" -> id;
            case "getWorld" -> playerAt.getWorld();
            case "getLocation" -> playerAt.clone();
            case "getEyeLocation" -> playerAt.clone().add(0,1.6,0);
            case "isDead" -> playerDead;
            case "isOnline" -> online;
            default -> throw new AssertionError(method);
        });}
        private LivingEntity living(UUID id,boolean actual){return proxy(LivingEntity.class,(method,args)->switch(method){
            case "getUniqueId" -> id;
            case "getWorld" -> actual?targetAt.getWorld():world;
            case "getLocation" -> actual?targetAt.clone():new Location(world,0,64,2);
            case "isValid" -> !actual||targetValid;
            case "isDead" -> actual&&targetDead;
            case "getHeight" -> {if(actual&&heightUnavailable)throw new IllegalStateException("test height unavailable");yield actual?targetHeight:.6;}
            default -> throw new AssertionError(method);
        });}
        void advance(int ticks){for(int i=0;i<ticks;i++)engine.tick();}
        long count(String effect){return played.stream().filter(p->p.request().effectId().equals(PREFIX+effect)).count();}
        Played play(String effect){return played.stream().filter(p->p.request().effectId().equals(PREFIX+effect)).reduce((a,b)->b).orElseThrow();}
        Pose lastPose(UUID handle){return poses.stream().filter(p->p.handle().equals(handle)).reduce((a,b)->b).orElseThrow();}
        @Override public void close() throws Exception {try{engine.clear();}finally{serverField.set(null,previousServer);}}
    }
    private static MemoryConfiguration configuration(){
        MemoryConfiguration root=new MemoryConfiguration();var s=root.createSection(SPELL);
        s.set("range",12);s.set("max-ticks",200);s.set("sequence.sustainStart",.1);s.set("holdTicks",2);
        List<Map<String,Object>> phases=new ArrayList<>();
        phases.add(phase("open","cast","target","static",0,20));
        phases.add(phase("channel","cast","target","attached",2,200));
        Map<String,Object> offset=phase("offset","cast","target","attached",2,200);offset.put("offset",List.of(0,1,0));phases.add(offset);
        Map<String,Object> tether=phase("tether","cast","link","link",2,200);tether.put("link-source","target");tether.put("link-target","caster");phases.add(tether);
        for(String[] ending:List.of(new String[]{"bless","external:trigger"},new String[]{"fizzle","expire"})){
            Map<String,Object> phase=phase(ending[0],ending[1],"impact","impact",0,8);
            phase.put("stop-effects",List.of(PREFIX+"open",PREFIX+"channel",PREFIX+"offset",PREFIX+"tether"));phase.put("clear-stopped-local",true);phases.add(phase);
        }
        phases.add(phase("release","external:trigger","target","attached",1,8));
        phases.add(phase("snap","expire","impact","impact",1,8));
        s.set("phases",phases);return root;
    }
    private static MemoryConfiguration dynamicConfiguration(){
        MemoryConfiguration config=configuration();var section=config.getConfigurationSection(SPELL);
        List<Map<String,Object>> phases=new ArrayList<>();
        for(Map<?,?> original:section.getMapList("phases")){
            Map<String,Object> phase=new LinkedHashMap<>();original.forEach((k,v)->phase.put(k.toString(),v));phase.put("scale-input",-1);
            if(phase.get("effect").equals(PREFIX+"tether")||phase.get("effect").equals(PREFIX+"release")){
                phase.put("role","link");phase.put("anchor","link");phase.put("link-source","caster-hand");phase.put("link-target","target-chest");
            }
            phases.add(phase);
        }
        section.set("phases",phases);return config;
    }
    private static MemoryConfiguration terminalAttachedConfiguration(){
        MemoryConfiguration config=configuration();var section=config.getConfigurationSection(SPELL);
        List<Map<String,Object>> phases=new ArrayList<>();
        for(Map<?,?> original:section.getMapList("phases")){
            Map<String,Object> phase=new LinkedHashMap<>();original.forEach((k,v)->phase.put(k.toString(),v));
            if(phase.get("effect").equals(PREFIX+"bless")||phase.get("effect").equals(PREFIX+"fizzle")){
                phase.put("role","attached");phase.put("anchor","target");
            }
            phases.add(phase);
        }
        section.set("phases",phases);return config;
    }
    private static Map<String,Object> phase(String effect,String trigger,String anchor,String role,int delay,int ttl){
        Map<String,Object> p=new LinkedHashMap<>();p.put("effect",PREFIX+effect);p.put("trigger",trigger);p.put("anchor",anchor);p.put("role",role);p.put("delay-ticks",delay);p.put("duration-ticks",ttl);return p;
    }
    private interface Call {Object invoke(String method,Object[] args);}
    private static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
        if(m.getName().equals("equals"))return p==a[0];
        if(m.getName().equals("hashCode"))return System.identityHashCode(p);
        if(m.getName().equals("toString"))return type.getSimpleName();
        return call.invoke(m.getName(),a);
    }));}
}
