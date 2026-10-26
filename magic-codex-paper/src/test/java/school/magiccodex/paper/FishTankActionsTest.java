package school.magiccodex.paper;
import java.lang.reflect.*;import java.util.*;import java.util.function.*;import java.util.logging.*;
import org.bukkit.entity.Player;import org.bukkit.inventory.*;import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import school.magiccodex.protocol.FishTankProtocol;import school.magiccodex.paper.FishTank.Fish;import school.magiccodex.paper.FishTank.Put;import school.magiccodex.paper.FishTankSql.Action;
class FishTankActionsTest {
 final UUID owner=UUID.randomUUID();final Fish fish=new Fish(UUID.randomUUID().toString(),"fish_001",25.5,1760000000000L,UUID.randomUUID().toString());
 FishTankBridge bridge;FishTank tank;FishingBridge fishing;FishingGear gear;FishShopBridge shop;Player player;PlayerInventory inventory;ItemStack item;Set<UUID> busy;
 static void field(Object obj,String name,Object value)throws Exception{var f=FishTankBridge.class.getDeclaredField(name);f.setAccessible(true);f.set(obj,value);}
 void invoke(String name,FishTankProtocol.Request request)throws Exception{var m=FishTankBridge.class.getDeclaredMethod(name,Player.class,FishTankProtocol.Request.class);m.setAccessible(true);try{m.invoke(bridge,player,request);}catch(InvocationTargetException e){throw new AssertionError(e.getCause());}}
 @BeforeEach void prepare()throws Exception{
  bridge=mock(FishTankBridge.class,CALLS_REAL_METHODS);tank=mock(FishTank.class);fishing=mock(FishingBridge.class);gear=mock(FishingGear.class);shop=mock(FishShopBridge.class);player=mock(Player.class);inventory=mock(PlayerInventory.class);item=mock(ItemStack.class);
  var plugin=mock(MagicCodexBridge.class);var logger=Logger.getAnonymousLogger();logger.setLevel(Level.OFF);when(plugin.getLogger()).thenReturn(logger);
  field(bridge,"plugin",plugin);field(bridge,"fishing",fishing);field(bridge,"shop",shop);busy=new HashSet<>(Set.of(owner));field(bridge,"busy",busy);field(bridge,"open",new HashSet<UUID>());field(bridge,"limits",new HashMap<UUID,long[]>());
  when(player.getUniqueId()).thenReturn(owner);when(player.isOnline()).thenReturn(true);when(player.getInventory()).thenReturn(inventory);when(inventory.firstEmpty()).thenReturn(0);when(inventory.getItem(2)).thenReturn(item);when(item.getAmount()).thenReturn(1);
  when(fishing.tank()).thenReturn(tank);when(fishing.gear()).thenReturn(gear);var data=new FishTank.Tank();data.loaded=true;data.fish.add(fish);when(tank.tank(owner)).thenReturn(data);
  var config=new FishingConfig();var def=mock(FishingConfig.Fish.class);when(def.id()).thenReturn(fish.fish());config.fishById=Map.of(fish.fish(),def);when(fishing.config()).thenReturn(config);
  when(gear.heldFish(item)).thenReturn(new FishingGear.HeldFish(fish.fish(),(float)fish.size(),fish.id(),fish.caughtAt(),fish.token()));
  doAnswer(i->{Consumer<Map<String,Fish>> done=i.getArgument(1);done.accept(Map.of(fish.id(),fish));return null;}).when(tank).itemInfo(anyCollection(),any());
  when(shop.ready()).thenReturn(true);when(shop.price(fish)).thenReturn(100L);
 }
 void putOutcome(Put result){doAnswer(i->{Consumer<Put> done=i.getArgument(2);done.accept(result);return null;}).when(tank).put(eq(owner),any(Fish.class),any());}
 void claim(Action action){doAnswer(i->{Consumer<List<Fish>> done=i.getArgument(3);done.accept(List.of(fish));return null;}).when(tank).remove(eq(owner),anyCollection(),eq(action),any());}
 void finalizeSuccessfully(){doAnswer(i->{Consumer<Boolean> done=i.getArgument(4);done.accept(true);return null;}).when(tank).finish(eq(owner),anyList(),any(Action.class),anyBoolean(),any());}
 @Test void failedInsertLeavesInventoryUntouched()throws Exception{putOutcome(Put.FAILED);invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));verify(inventory,never()).setItem(anyInt(),any());verify(item,never()).setAmount(anyInt());assertFalse(busy.contains(owner));}
 @Test void concurrentFullTankLeavesInventoryUntouched()throws Exception{putOutcome(Put.FULL);invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));verify(inventory,never()).setItem(anyInt(),any());verify(item,never()).setAmount(anyInt());}
 @Test void committedInsertConsumesOnlyAfterDatabaseSuccess()throws Exception{putOutcome(Put.OK);invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));var order=inOrder(tank,inventory);order.verify(tank).put(eq(owner),any(Fish.class),any());order.verify(inventory).setItem(2,null);}
 @Test void duplicatePhysicalItemIsConsumedWithoutAnotherFish()throws Exception{putOutcome(Put.DUPLICATE);invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));verify(inventory).setItem(2,null);verify(tank,times(1)).put(eq(owner),any(Fish.class),any());}
 @Test void slotReplacementDoesNotConsumeAnUnrelatedItem()throws Exception{var other=mock(ItemStack.class);when(inventory.getItem(2)).thenReturn(item,other);when(gear.heldFish(other)).thenReturn(new FishingGear.HeldFish("fish_002",5,UUID.randomUUID().toString(),fish.caughtAt(),""));putOutcome(Put.OK);invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));verify(inventory,never()).setItem(anyInt(),any());verify(other,never()).setAmount(anyInt());}
 @Test void offlineCompletionCannotMutateDisconnectedInventory()throws Exception{when(player.isOnline()).thenReturn(false);putOutcome(Put.OK);invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));verify(inventory,never()).setItem(anyInt(),any());}
 @Test void idOnlyFishUsesDatabaseSizeAndDate()throws Exception{
  when(gear.heldFish(item)).thenReturn(new FishingGear.HeldFish(fish.fish(),0,fish.id(),0,fish.token()));putOutcome(Put.OK);invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));
  verify(tank).put(eq(owner),eq(fish),any());verify(inventory).setItem(2,null);
 }
 @Test void unregisteredFishNeverConsumesItemOrMintsValue()throws Exception{
  doAnswer(i->{Consumer<Map<String,Fish>> done=i.getArgument(1);done.accept(Map.of());return null;}).when(tank).itemInfo(anyCollection(),any());invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));
  verify(tank,never()).put(any(),any(),any());verify(inventory,never()).setItem(anyInt(),any());assertFalse(busy.contains(owner));
 }
 @Test void fullInventoryNeverClaimsAnItemizedFish()throws Exception{when(inventory.firstEmpty()).thenReturn(-1);invoke("itemize",FishTankProtocol.Request.fish(FishTankProtocol.ITEMIZE,fish.id(),12));verify(tank,never()).remove(any(),anyCollection(),any(Action.class),any());verify(fishing,never()).deliver(any(),anyList(),anyString(),any(),any());}
 @Test void inventoryFilledDuringDatabaseWaitRestoresClaim()throws Exception{when(inventory.firstEmpty()).thenReturn(0,-1);when(fishing.fishItem(fish)).thenReturn(item);claim(Action.ITEMIZE);finalizeSuccessfully();invoke("itemize",FishTankProtocol.Request.fish(FishTankProtocol.ITEMIZE,fish.id(),12));verify(tank).finish(eq(owner),eq(List.of(fish)),eq(Action.ITEMIZE),eq(false),any());verify(fishing,never()).deliver(any(),anyList(),anyString(),any(),any());}
 @Test void lockedFishAndStalePricesNeverReachPayment()throws Exception{tank.tank(owner).locks.add(fish.fish());invoke("sell",FishTankProtocol.Request.sell(fish.id(),100,13));verify(tank,never()).remove(any(),anyCollection(),any(Action.class),any());tank.tank(owner).locks.clear();invoke("sell",FishTankProtocol.Request.sell(fish.id(),999,14));verify(shop,never()).deposit(any(),anyLong());}
 @Test void failedPaymentRestoresBeforeAcknowledging()throws Exception{claim(Action.SELL);finalizeSuccessfully();when(shop.deposit(player,100)).thenReturn(false);invoke("sell",FishTankProtocol.Request.sell(fish.id(),100,13));verify(tank).finish(eq(owner),eq(List.of(fish)),eq(Action.SELL),eq(false),any());assertFalse(busy.contains(owner));}
 @Test void ambiguousPaymentKeepsClaimForReconciliation()throws Exception{claim(Action.SELL);when(shop.deposit(player,100)).thenThrow(new RuntimeException("injected uncertain provider outcome"));invoke("sell",FishTankProtocol.Request.sell(fish.id(),100,13));verify(tank,never()).finish(any(),anyList(),any(Action.class),anyBoolean(),any());}
 @Test void quitDoesNotUnlockAnInflightRequestBeforeItsCallback(){bridge.quit(new PlayerQuitEvent(player,""));assertTrue(busy.contains(owner));}

 void missingItemRows(){doAnswer(i->{Consumer<Map<String,Fish>> done=i.getArgument(1);done.accept(Map.of());return null;}).when(tank).itemInfo(anyCollection(),any());}
 @Test void preDatabaseAdminGiftWithMetadataKeepsIdentitySizeAndDate()throws Exception{
  var legacy=new Fish(fish.id(),fish.fish(),fish.size(),fish.caughtAt(),"");
  when(gear.heldFish(item)).thenReturn(new FishingGear.HeldFish(legacy.fish(),(float)legacy.size(),legacy.id(),legacy.caughtAt(),""));
  missingItemRows();putOutcome(Put.OK);invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));
  var order=inOrder(tank,inventory);order.verify(tank).put(eq(owner),eq(legacy),any());order.verify(inventory).setItem(2,null);
 }
 @Test void idOnlyUnknownItemWithNoTokenCannotUseLegacyFallback()throws Exception{
  when(gear.heldFish(item)).thenReturn(new FishingGear.HeldFish(fish.fish(),0,fish.id(),0,""));missingItemRows();
  invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));verify(tank,never()).put(any(),any(),any());verify(inventory,never()).setItem(anyInt(),any());
 }
 @Test void withdrawnItemWithUnknownRowAndOldTokenCannotUseLegacyFallback()throws Exception{
  when(gear.heldFish(item)).thenReturn(new FishingGear.HeldFish(fish.fish(),(float)fish.size(),fish.id(),fish.caughtAt(),"old-withdrawal-token"));missingItemRows();
  invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));verify(tank,never()).put(any(),any(),any());verify(inventory,never()).setItem(anyInt(),any());
 }
 @Test void existingRowWithDifferentTokenNeverFallsBackToLegacyMetadata()throws Exception{
  when(gear.heldFish(item)).thenReturn(new FishingGear.HeldFish(fish.fish(),(float)fish.size(),fish.id(),fish.caughtAt(),""));
  invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));verify(tank,never()).put(any(),any(),any());verify(inventory,never()).setItem(anyInt(),any());
 }
 @Test void unknownLegacyIdWithoutCaughtDateCannotBeMigrated()throws Exception{
  when(gear.heldFish(item)).thenReturn(new FishingGear.HeldFish(fish.fish(),(float)fish.size(),fish.id(),0,""));missingItemRows();
  invoke("insert",FishTankProtocol.Request.insert(2,fish.id(),11));verify(tank,never()).put(any(),any(),any());
 }
 List<FishTankProtocol.Held> sentInventory(Map<String,Fish> rows)throws Exception{
  field(bridge,"open",new HashSet<>(Set.of(owner)));when(tank.capacity()).thenReturn(100);
  when(fishing.config().fishById.get(fish.fish()).name()).thenReturn("Test fish");
  var m=FishTankBridge.class.getDeclaredMethod("send",Player.class,int.class,String.class,Map.class);m.setAccessible(true);m.invoke(bridge,player,FishTankProtocol.KIND_STATE,"",rows);
  var bytes=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(player).sendPluginMessage(any(),eq(FishTankProtocol.RESPONSE),bytes.capture());
  return ((FishTankProtocol.State)FishTankProtocol.decode(bytes.getValue())).inventory();
 }
 @Test void tankInventoryListsPreDatabaseAdminGiftUsingItsOriginalMetadata()throws Exception{
  when(gear.heldFish(item)).thenReturn(new FishingGear.HeldFish(fish.fish(),(float)fish.size(),fish.id(),fish.caughtAt(),""));
  var held=sentInventory(Map.of());assertEquals(1,held.size());assertEquals(fish.id(),held.getFirst().id());assertEquals((float)fish.size(),held.getFirst().size());assertEquals(fish.caughtAt(),held.getFirst().caughtAt());
 }
 @Test void tankInventoryHidesUnknownIdOnlyItemsAndWithdrawnTokens()throws Exception{
  when(gear.heldFish(item)).thenReturn(new FishingGear.HeldFish(fish.fish(),0,fish.id(),0,""));
  var stale=mock(ItemStack.class);when(inventory.getItem(3)).thenReturn(stale);when(gear.heldFish(stale)).thenReturn(new FishingGear.HeldFish(fish.fish(),(float)fish.size(),UUID.randomUUID().toString(),fish.caughtAt(),"old-withdrawal-token"));
  assertTrue(sentInventory(Map.of()).isEmpty());
 }
}
