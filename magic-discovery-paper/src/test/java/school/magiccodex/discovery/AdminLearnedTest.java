package school.magiccodex.discovery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AdminLearnedTest {
    @TempDir Path directory;

    @Test void unlearnPersistsAndRediscoveryKeepsFirstNotificationAndDeliveredRewardHistory() throws Exception {
        Path file=directory.resolve("history.db");UUID first=UUID.randomUUID(),second=UUID.randomUUID();long token;
        try(var store=new DiscoveryStore(file)){
            var original=store.acquire(first,"wind",Map.of("cast.wind",50d),false).get();token=original.acquisition().token();
            assertTrue(store.reserve(first,token).get());store.delivered(first,token).get();store.acknowledge(first,token).get();
            store.setLearned(first,"wind",false,Set.of("cast.wind"),Map.of()).get();
        }
        try(var store=new DiscoveryStore(file)){
            var loaded=store.load(first).get();assertFalse(loaded.learnedOverrides().get("wind"));
            assertEquals(50d,loaded.retryProgress().get("wind").get("cast.wind"));
            assertEquals(50d,loaded.progress().get("cast.wind"));
            assertEquals(token,loaded.acquired().getFirst().token());assertTrue(loaded.acquired().getFirst().first());
            var rediscovered=store.acquire(first,"wind",Map.of("cast.wind",100d),true).get();
            assertFalse(rediscovered.created());assertEquals(token,rediscovered.acquisition().token());
            assertEquals(3,rediscovered.acquisition().reward());assertTrue(rediscovered.acquisition().notified());
            assertTrue(store.load(first).get().learnedOverrides().get("wind"));assertFalse(store.reserve(first,token).get());
            var laterPlayer=store.acquire(second,"wind",Map.of(),false).get().acquisition();
            assertFalse(laterPlayer.first());assertEquals(0,laterPlayer.reward());
            assertEquals(1,store.load(first).get().acquired().size());
        }
    }

    @Test void adminGrantDoesNotMintAFirstOrRewardAndOfflineResetUsesSavedProgress() throws Exception {
        UUID admin=UUID.randomUUID(),natural=UUID.randomUUID();
        try(var store=new DiscoveryStore(directory.resolve("grant.db"))){
            store.save(admin,Map.of("pickup.items",90d,"unrelated",7d)).get();
            store.setLearned(admin,"wind",true,Set.of("pickup.items"),Map.of()).get();
            var granted=store.load(admin).get();assertTrue(granted.learnedOverrides().get("wind"));assertTrue(granted.acquired().isEmpty());
            assertTrue(store.acquire(natural,"wind",Map.of(),false).get().acquisition().first());
            var baseline=store.setLearned(admin,"wind",false,Set.of("pickup.items"),Map.of()).get();
            assertEquals(Map.of("pickup.items",90d),baseline);assertEquals(7d,store.load(admin).get().progress().get("unrelated"));
        }
    }

    @Test void queuedAdminResetWinsOverAnEarlierAcquisitionAndLatestAdminWriteWins() throws Exception {
        UUID player=UUID.randomUUID();
        try(var store=new DiscoveryStore(directory.resolve("ordering.db"))){
            var acquisition=store.acquire(player,"wind",Map.of("cast.wind",5d),true);
            var reset=store.setLearned(player,"wind",false,Set.of("cast.wind"),Map.of());
            acquisition.get();reset.get();assertFalse(store.load(player).get().learnedOverrides().get("wind"));
            var grant=store.setLearned(player,"wind",true,Set.of("cast.wind"),Map.of());
            var resetAgain=store.setLearned(player,"wind",false,Set.of("cast.wind"),Map.of("cast.wind",8d));
            grant.get();resetAgain.get();var loaded=store.load(player).get();assertFalse(loaded.learnedOverrides().get("wind"));
            assertEquals(8d,loaded.retryProgress().get("wind").get("cast.wind"));assertEquals(1,loaded.acquired().size());
        }
    }

    @Test void playerFirstRewardCannotBeReissuedAfterUnlearning() throws Exception {
        UUID player=UUID.randomUUID();
        try(var store=new DiscoveryStore(directory.resolve("player-first.db"))){
            store.acquire(UUID.randomUUID(),"wind",Map.of(),true).get();
            var original=store.acquire(player,"wind",Map.of(),true).get().acquisition();assertFalse(original.first());
            assertTrue(store.reserve(player,original.token()).get());store.delivered(player,original.token()).get();
            store.setLearned(player,"wind",false,Set.of(),Map.of()).get();
            var again=store.acquire(player,"wind",Map.of(),true).get();assertFalse(again.created());
            assertEquals(original.token(),again.acquisition().token());assertEquals(3,again.acquisition().reward());
        }
    }

    @Test void resetRequiresFreshSpellSpecificProgressWithoutResettingSharedCounters() {
        var learned=new LearnedSpells();var target=spell("wind",Map.of("lesson.wind",3d),List.of());
        var sibling=spell("other",Map.of("lesson.wind",1d),List.of());
        learned.set("wind",false,Map.of("lesson.wind",3d));
        assertFalse(learned.learned("wind",true));assertFalse(learned.meets(target,Map.of("lesson.wind",3d)));
        assertTrue(learned.meets(sibling,Map.of("lesson.wind",3d)));assertFalse(learned.meets(target,Map.of("lesson.wind",5d)));
        assertTrue(learned.meets(target,Map.of("lesson.wind",6d)));
        learned.set("wind",true,Map.of());assertTrue(learned.learned("wind",true));assertEquals(0d,learned.baseline("wind","lesson.wind"));
    }

    @Test void repeatedStateAndPassiveMetaChecksCannotUndoAReset() {
        var learned=new LearnedSpells();var stateSpell=spell("circle",Map.of("state.circle",6d),List.of());
        learned.set("circle",false,Map.of("state.circle",6d));
        assertFalse(learned.meets(stateSpell,Map.of("state.circle",6d)));assertTrue(learned.meets(stateSpell,Map.of("state.circle",7d)));
        var meta=spell("meta",Map.of(),List.of("magic.learned.wind"));learned.set("meta",false,Map.of());
        assertFalse(learned.canCheckMeta(meta,false));assertTrue(learned.canCheckMeta(meta,true));
        learned.set("circle",false,Map.of("state.circle",9d));
        assertFalse(learned.meets(stateSpell,Map.of("state.circle",9d)));
        assertTrue(learned.meets(stateSpell,Map.of("state.circle",9d),true));
    }

    @Test void adminMutationWinsOverStaleSessionLoad() {
        var learned=new LearnedSpells();learned.set("wind",false,Map.of("cast.wind",12d));
        learned.restore(Map.of("wind",true,"other",true),Map.of("wind",Map.of("cast.wind",4d)));
        assertFalse(learned.learned("wind",true));assertEquals(12d,learned.baseline("wind","cast.wind"));
        assertTrue(learned.learned("other",false));
    }

    @Test void slowPermissionGrantCannotOverwriteNewerRemovalAndFailureDoesNotBlockLaterWrites() {
        var queue=new PermissionWriteQueue();var player=UUID.randomUUID();var first=new CompletableFuture<Void>();
        var calls=new ArrayList<String>();
        queue.enqueue(player,()->{calls.add("grant");return first;});
        var removal=queue.enqueue(player,()->{calls.add("remove");return CompletableFuture.completedFuture(null);});
        assertEquals(List.of("grant"),calls);assertFalse(removal.isDone());
        first.completeExceptionally(new IllegalStateException("failed old grant"));removal.join();
        assertEquals(List.of("grant","remove"),calls);
        queue.enqueue(player,()->{calls.add("new grant");return CompletableFuture.completedFuture(null);}).join();
        assertEquals(List.of("grant","remove","new grant"),calls);
    }

    @Test void adminCatalogUsesAllRegisteredCastsWithoutInventingConditionsOrLegacyAliases() {
        var catalog=new AdminSpellCatalog();var discovery=spell("old_discovery",Map.of("pickup.items",3d),List.of());
        var legacy=spell("legacy_discovery",Map.of("pickup.items",5d),List.of());
        Map<String,Definitions.Spell> conditions=Map.of(discovery.id(),discovery,legacy.id(),legacy);
        catalog.replace(conditions,Map.of("new_cast",Map.of("name","신규 시전","permission","magic.learned.new_cast","enabled","false"),
                "old_discovery",Map.of("name","현재 시전 이름","permission","magic.learned.old_discovery"),
                "bad/id",Map.of("name","잘못된 ID","permission","magic.learned.bad")));
        assertEquals(Set.of("old_discovery","new_cast"),catalog.names().keySet());
        assertEquals("신규 시전",catalog.names().get("new_cast"));assertEquals("현재 시전 이름",catalog.names().get("old_discovery"));
        assertEquals("magic.learned.new_cast",catalog.get("new_cast").permission());
        assertEquals(Set.of("old_discovery","legacy_discovery"),conditions.keySet());
        assertFalse(catalog.names().containsKey("legacy_discovery"));assertTrue(catalog.permissionEntries().containsKey("legacy_discovery"));
        assertThrows(UnsupportedOperationException.class,()->catalog.names().put("x","x"));
        catalog.replace(conditions,Map.of());assertTrue(catalog.names().isEmpty());
        catalog.replace(conditions,null);assertNull(catalog.get("new_cast"));assertNotNull(catalog.get("old_discovery"));assertNotNull(catalog.get("legacy_discovery"));
    }

    private static Definitions.Spell spell(String id,Map<String,Double> requirements,List<String> prerequisites){
        return new Definitions.Spell(id,id,"magic.learned."+id,"magic:test.png","wind","",requirements,prerequisites,1);
    }
}
