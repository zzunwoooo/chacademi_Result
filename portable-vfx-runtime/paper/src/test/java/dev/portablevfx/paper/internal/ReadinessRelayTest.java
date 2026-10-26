package dev.portablevfx.paper.internal;
import dev.portablevfx.protocol.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReadinessRelayTest {
    private static final String WORLD="minecraft:overworld";
    static class Wire implements RelayEngine.Transport {
        long tick;Map<UUID,RelayEngine.Viewer> online=new HashMap<>();
        public long currentTick(){return tick;}
        public Collection<RelayEngine.Viewer> viewers(String dimension){return online.values();}
        public RelayEngine.Viewer viewer(UUID id){return online.get(id);}
        public boolean send(UUID id,byte[] bytes){return true;}
    }
    private final Wire wire=new Wire();private final RelayEngine engine=new RelayEngine(wire,RelayLimits.DEFAULT);
    private static Set<String> ids(int n){return IntStream.range(0,n).mapToObj(i->"claude:readiness_fixture/effect_"+String.format("%04d",i)).collect(Collectors.toSet());}
    private UUID join(){UUID id=UUID.randomUUID();wire.online.put(id,new RelayEngine.Viewer(id,WORLD,0,0,0,true));engine.hello(id,VfxProtocol.encodeHello());engine.stopIntentHello(id,VfxProtocol.encodeHello());return id;}
    private void upload(UUID player,UUID epoch,long generation,Set<String> ids){for(byte[] p:CatalogReadinessV2.encode(epoch,generation,ids))engine.catalogReadyV2(player,p);}
    @Test void completeChunkedInventoryEnablesCastOnlyAfterFinalValidatedPartAndNeverDowngrades(){
        var inventory=ids(1245);engine.catalogRequirements(inventory);UUID p=join(),epoch=engine.catalogEpoch(p);var frames=CatalogReadinessV2.encode(epoch,1,inventory);assertEquals(2,frames.size());
        engine.catalogReadyV2(p,frames.get(1));assertFalse(engine.supportsCatalogCast(p));engine.catalogReadyV2(p,frames.get(1));assertFalse(engine.supportsCatalogCast(p));
        engine.catalogReadyV2(p,frames.get(0));assertTrue(engine.supportsCatalogCast(p));assertTrue(engine.catalogDiagnostic(p).contains("received=1245, required=1245, missing=0, ready=true"));
        engine.catalogReady(p,CatalogReadiness.encode(Set.of()));assertTrue(engine.supportsCatalogCast(p));
        var changed=new HashSet<>(inventory);changed.add("claude:readiness_fixture/additional");engine.catalogReadyV2(p,CatalogReadinessV2.encode(epoch,2,changed).getFirst());assertTrue(engine.supportsCatalogCast(p));
    }
    @Test void reannouncedGenerationKeepsPreviousReadinessUntilCompleteThenSwaps(){
        var inventory=ids(1245);engine.catalogRequirements(inventory);UUID p=join(),epoch=engine.catalogEpoch(p);upload(p,epoch,1,inventory);assertTrue(engine.supportsCatalogCast(p));
        var frames=CatalogReadinessV2.encode(epoch,2,inventory);engine.catalogReadyV2(p,frames.getFirst());assertTrue(engine.supportsCatalogCast(p));
        engine.catalogReadyV2(p,frames.getLast());assertTrue(engine.supportsCatalogCast(p));
        var missing=ids(1244);var partial=CatalogReadinessV2.encode(epoch,3,missing);engine.catalogReadyV2(p,partial.getFirst());assertTrue(engine.supportsCatalogCast(p));
        for(int i=1;i<partial.size();i++)engine.catalogReadyV2(p,partial.get(i));assertFalse(engine.supportsCatalogCast(p));
        upload(p,epoch,4,inventory);assertTrue(engine.supportsCatalogCast(p));
        engine.catalogReadyV2(p,CatalogReadinessV2.encode(epoch,5,inventory).getFirst());wire.tick=500;engine.tick();assertFalse(engine.supportsCatalogCast(p));
    }
    @Test void catalogReloadAndReconnectRotateServerNonceAndRejectEveryOldGeneration(){
        var inventory=ids(2);engine.catalogRequirements(inventory);UUID p=join(),old=engine.catalogEpoch(p);upload(p,old,1,inventory);assertTrue(engine.supportsCatalogCast(p));
        engine.catalogRequirements(inventory);assertFalse(engine.supportsCatalogCast(p));UUID next=engine.catalogEpoch(p);assertNotEquals(old,next);upload(p,old,2,inventory);assertFalse(engine.supportsCatalogCast(p));upload(p,next,1,inventory);assertTrue(engine.supportsCatalogCast(p));
        engine.forget(p);engine.hello(p,VfxProtocol.encodeHello());engine.stopIntentHello(p,VfxProtocol.encodeHello());UUID connected=engine.catalogEpoch(p);assertNotEquals(next,connected);upload(p,next,2,inventory);assertFalse(engine.supportsCatalogCast(p));upload(p,connected,1,inventory);assertTrue(engine.supportsCatalogCast(p));
    }
    @Test void missingPartsExpireAndLateTailCannotEnableReadiness(){
        var inventory=ids(1245);engine.catalogRequirements(inventory);UUID p=join(),epoch=engine.catalogEpoch(p);var frames=CatalogReadinessV2.encode(epoch,1,inventory);engine.catalogReadyV2(p,frames.getFirst());
        wire.tick=201;engine.tick();assertFalse(engine.supportsCatalogCast(p));assertTrue(engine.catalogDiagnostic(p).contains("timed out"));engine.catalogReadyV2(p,frames.getLast());assertFalse(engine.supportsCatalogCast(p));upload(p,epoch,2,inventory);assertTrue(engine.supportsCatalogCast(p));
    }
    @Test void malformedOrOversizeFramesWithdrawAndRequiredIdsAreStillChecked(){
        var required=ids(1);engine.catalogRequirements(required);UUID p=join(),epoch=engine.catalogEpoch(p);upload(p,epoch,1,required);assertTrue(engine.supportsCatalogCast(p));
        engine.catalogReadyV2(p,new byte[CatalogReadinessV2.MAX_PACKET_BYTES+1]);assertFalse(engine.supportsCatalogCast(p));upload(p,epoch,2,Set.of("claude:other/effect"));assertFalse(engine.supportsCatalogCast(p));upload(p,epoch,3,required);assertTrue(engine.supportsCatalogCast(p));upload(p,epoch,4,Set.of());assertFalse(engine.supportsCatalogCast(p));
    }
    @Test void globalAssemblyCapacityIsBoundedWithoutChangingSpawnOrCastLimits(){
        var inventory=ids(1245);engine.catalogRequirements(inventory);
        for(int i=0;i<RelayEngine.MAX_READY_TRANSFERS;i++){UUID p=join(),epoch=engine.catalogEpoch(p);engine.catalogReadyV2(p,CatalogReadinessV2.encode(epoch,1,inventory).getFirst());}
        UUID refused=join(),epoch=engine.catalogEpoch(refused);upload(refused,epoch,1,inventory);assertFalse(engine.supportsCatalogCast(refused));assertTrue(engine.catalogDiagnostic(refused).contains("assembly capacity"));
        wire.tick=201;engine.tick();upload(refused,epoch,2,inventory);assertTrue(engine.supportsCatalogCast(refused));
    }
    @Test void lateTailBeforePeriodicSweepReleasesItsGlobalAssemblySlot(){
        var inventory=ids(1245);engine.catalogRequirements(inventory);UUID first=null;byte[] tail=null;
        for(int i=0;i<RelayEngine.MAX_READY_TRANSFERS;i++){UUID p=join(),epoch=engine.catalogEpoch(p);var frames=CatalogReadinessV2.encode(epoch,1,inventory);engine.catalogReadyV2(p,frames.getFirst());if(i==0){first=p;tail=frames.getLast();}}
        wire.tick=201;engine.catalogReadyV2(first,tail);assertFalse(engine.supportsCatalogCast(first));
        UUID next=join(),epoch=engine.catalogEpoch(next);upload(next,epoch,1,inventory);assertTrue(engine.supportsCatalogCast(next));
    }
    @Test void nonceNeverGrantedWithoutValidHelloAndMissingStopCannotCommit(){
        UUID p=UUID.randomUUID();wire.online.put(p,new RelayEngine.Viewer(p,WORLD,0,0,0,true));assertNull(engine.catalogEpoch(p));engine.hello(p,VfxProtocol.encodeHello());UUID epoch=engine.catalogEpoch(p);engine.catalogRequirements(ids(1));epoch=engine.catalogEpoch(p);upload(p,epoch,1,ids(1));assertFalse(engine.supportsCatalogCast(p));engine.stopIntentHello(p,VfxProtocol.encodeHello());upload(p,epoch,2,ids(1));assertTrue(engine.supportsCatalogCast(p));
    }
}
