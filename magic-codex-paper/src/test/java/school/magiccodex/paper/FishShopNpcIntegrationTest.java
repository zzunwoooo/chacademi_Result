package school.magiccodex.paper;
import java.nio.file.*;import java.util.*;import java.lang.reflect.*;
import org.bukkit.*;import org.bukkit.entity.Player;import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import school.magiccodex.database.DatabaseSettings;import school.magiccodex.protocol.*;
class FishShopNpcIntegrationTest {
 @TempDir Path dir;
 ShopStore store()throws Exception{return new ShopStore(DatabaseSettings.load(dir.resolve("missing.properties")),dir.resolve("shop.db"));}
 @Test void typedBindingChangesAndUnlinksPersistWithoutChangingNormalProducts()throws Exception{
  try(var s=store()){
   s.create("general","일반 상점");String product=s.add("general","상품",new byte[]{1,2,3},"10","5");s.bind("school","citizens:7","general");s.bind("school","tag:other","general");s.bind("wild","citizens:7","general");var before=s.catalog().get("general");
   s.bind("school","citizens:7",ShopNpcTarget.LABEL);assertEquals(ShopNpcTarget.FISH,s.bindings("school").get("citizens:7"));assertEquals("general",s.bindings("wild").get("citizens:7"));assertEquals("general",s.bindings("school").get("tag:other"));
   var after=s.catalog().get("general");assertEquals(before.title(),after.title());assertEquals(before.portrait(),after.portrait());assertEquals(before.revision(),after.revision());assertEquals(product,after.products().getFirst().id());assertArrayEquals(before.products().getFirst().bytes(),after.products().getFirst().bytes());assertEquals("10",after.products().getFirst().buy());assertEquals("5",after.products().getFirst().sell());assertFalse(s.catalog().containsKey(ShopNpcTarget.FISH));
  }
  try(var s=store()){
   assertEquals(ShopNpcTarget.FISH,s.bindings("school").get("citizens:7"));assertFalse(s.unbind("school","citizens:7","general"));assertTrue(s.unbind("school","citizens:7",ShopNpcTarget.LABEL));assertEquals("general",s.bindings("school").get("tag:other"));assertFalse(s.unbind("school","tag:other",ShopNpcTarget.FISH));
  }
  try(var s=store()){assertFalse(s.bindings("school").containsKey("citizens:7"));assertEquals("general",s.bindings("school").get("tag:other"));assertEquals("general",s.bindings("wild").get("citizens:7"));}
 }
 @Test void fishTargetCannotCollideWithAnExistingAsciiShopId()throws Exception{
  assertFalse(ShopNpcTarget.fish("fish_shop"));assertTrue(ShopNpcTarget.fish(ShopNpcTarget.LABEL));try(var s=store()){s.create("fish_shop","기존 일반 상점");s.bind("school","tag:normal","fish_shop");s.bind("school","tag:seller",ShopNpcTarget.FISH);assertEquals("fish_shop",s.bindings("school").get("tag:normal"));assertThrows(IllegalArgumentException.class,()->s.create(ShopNpcTarget.FISH,"collision"));assertThrows(IllegalArgumentException.class,()->s.bind("school","../bad",ShopNpcTarget.FISH));}
 }
 @Test void npcArgumentTakesCitizensNumberAndShowsItBack(){
  assertEquals("citizens:7",ShopBridge.npcKey("7"));assertEquals("tag:seller",ShopBridge.npcKey("tag:seller"));assertEquals("7",ShopBridge.npcShown("citizens:7"));assertEquals("tag:seller",ShopBridge.npcShown("tag:seller"));
  assertTrue(ShopNpcTarget.validKey(ShopBridge.npcKey("7")));assertFalse(ShopNpcTarget.validKey(ShopBridge.npcKey("칠")));
 }
 static void field(Object object,String name,Object value)throws Exception{var f=ShopBridge.class.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 @Test void boundNpcOpensFishUiThroughTheExistingNpcEntryPoint()throws Exception{
  var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(p.isOnline()).thenReturn(true);when(p.hasPermission("magiccodex.shop")).thenReturn(true);when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);when(p.getListeningPluginChannels()).thenReturn(Set.of(FishShopProtocol.RESPONSE));
  var plugin=mock(MagicCodexBridge.class);when(plugin.playerStateReady(p)).thenReturn(true);var bridge=mock(ShopBridge.class,CALLS_REAL_METHODS);field(bridge,"plugin",plugin);field(bridge,"bindings",Map.of("citizens:7",ShopNpcTarget.FISH));field(bridge,"catalog",Map.of());field(bridge,"busy",new HashSet<UUID>());
  var services=mock(ServicesManager.class);var market=mock(FishShopBridge.class);when(services.load(FishShopBridge.class)).thenReturn(market);
  try(var b=mockStatic(Bukkit.class)){b.when(()->Bukkit.getPlayer(id)).thenReturn(p);b.when(Bukkit::getServicesManager).thenReturn(services);assertEquals("OPENED",bridge.openBound(p,7,null));verify(market).open(eq(p),isNull(),any());}
 }
 @Test void normalNpcBindingCannotAccidentallyOpenFishUi()throws Exception{
  var p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(p.isOnline()).thenReturn(true);when(p.hasPermission("magiccodex.shop")).thenReturn(true);when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);when(p.getListeningPluginChannels()).thenReturn(Set.of(FishShopProtocol.RESPONSE));
  var plugin=mock(MagicCodexBridge.class);when(plugin.playerStateReady(p)).thenReturn(true);var bridge=mock(ShopBridge.class,CALLS_REAL_METHODS);field(bridge,"plugin",plugin);field(bridge,"bindings",Map.of("citizens:7","general"));field(bridge,"catalog",Map.of("general",new ShopStore.Shop("general","일반","portrait",1,List.of())));field(bridge,"busy",new HashSet<UUID>());
  var services=mock(ServicesManager.class);var market=mock(FishShopBridge.class);when(services.load(FishShopBridge.class)).thenReturn(market);
  try(var b=mockStatic(Bukkit.class)){b.when(()->Bukkit.getPlayer(id)).thenReturn(p);b.when(Bukkit::getServicesManager).thenReturn(services);assertEquals("BLOCKED",bridge.openBound(p,7,null));verifyNoInteractions(market);}
 }
}