package school.magiccodex.paper;
import java.nio.file.*;import java.util.*;import java.util.concurrent.CompletableFuture;import java.util.logging.Logger;
import org.bukkit.*;import org.bukkit.command.PluginCommand;import org.bukkit.entity.Player;import org.bukkit.event.inventory.InventoryType;import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import school.magiccodex.protocol.AlchemyProtocol;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class AlchemyCraftPolicyTest {
 @TempDir Path temp;
 @Test void unknownRecipeWithoutExperienceCannotSpendOrDiscover()throws Exception{run(false,false,false);}
 @Test void knownRecipeWithoutExperienceCannotSpendOrDiscover()throws Exception{run(false,true,false);}
 @Test void successfulCraftSpendsThenDiscovers()throws Exception{run(true,false,false);}
 @Test void missingOutputDoesNotSpendOrDiscover()throws Exception{run(true,false,true);}
 // 2026-10-21: 결과물이 다 안 들어가면 제작 거절 (재료를 뺀 뒤 기준)
 @Test void fullInventoryRefuses(){int[] a=new int[36];Arrays.fill(a,64);int[] m=new int[36];Arrays.fill(m,64);assertFalse(AlchemyBridge.roomFor(a,m,new boolean[36],1,64));}
 @Test void slotFreedByIngredientsAllows(){int[] a=new int[36];Arrays.fill(a,64);a[5]=0;int[] m=new int[36];Arrays.fill(m,64);assertTrue(AlchemyBridge.roomFor(a,m,new boolean[36],1,64));}
 @Test void stackableResultMergesIntoExistingStack(){int[] a=new int[36];Arrays.fill(a,64);a[3]=60;int[] m=new int[36];Arrays.fill(m,64);boolean[] s=new boolean[36];s[3]=true;assertTrue(AlchemyBridge.roomFor(a,m,s,4,64));assertFalse(AlchemyBridge.roomFor(a,m,s,5,64));}
 @Test void unstackableResultNeedsEmptySlots(){int[] a=new int[36];Arrays.fill(a,1);a[0]=0;int[] m=new int[36];Arrays.fill(m,1);boolean[] s=new boolean[36];Arrays.fill(s,true);assertTrue(AlchemyBridge.roomFor(a,m,s,1,1));assertFalse(AlchemyBridge.roomFor(a,m,s,2,1));}
 void run(boolean enough,boolean known,boolean missing)throws Exception{
  try(var registryAccess=mockStatic(io.papermc.paper.registry.RegistryAccess.class)){
   var access=mock(io.papermc.paper.registry.RegistryAccess.class, call -> java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Registry.class},(p,m,a)->null));
   registryAccess.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(access);
  Files.writeString(temp.resolve("alchemy.yml"),"settings:\n  slots: 6\n  material-items: ['item:test_sugar']\nrecipes:\n  fixture:\n    name: Test only\n    result: item:test_result\n    amount: 1\n    exp: 10\n    ingredients:\n      'item:test_sugar': 2\n");
  var plugin=mock(MagicCodexBridge.class);when(plugin.getDataFolder()).thenReturn(temp.toFile());when(plugin.getLogger()).thenReturn(Logger.getLogger("alchemy-fixture"));when(plugin.getCommand(anyString())).thenReturn(mock(PluginCommand.class));
  var server=mock(Server.class,RETURNS_DEEP_STUBS);var player=mock(Player.class,RETURNS_DEEP_STUBS);var id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);when(player.hasPermission("magiccodex.alchemy.use")).thenReturn(true);when(plugin.playerStateReady(player)).thenReturn(true);when(player.getOpenInventory().getTopInventory().getType()).thenReturn(InventoryType.CRAFTING);when(player.getItemOnCursor()).thenReturn(null);when(player.getListeningPluginChannels()).thenReturn(Set.of());when(player.getLevel()).thenReturn(enough?2:0);when(player.getExp()).thenReturn(0f);
  when(player.isPermissionSet("magiccodex.alchemy.recipe.fixture")).thenReturn(known);when(player.hasPermission("magiccodex.alchemy.recipe.fixture")).thenReturn(known);
  var inv=mock(PlayerInventory.class);when(player.getInventory()).thenReturn(inv);var input=mock(ItemStack.class);when(input.getType()).thenReturn(Material.SUGAR);when(input.getAmount()).thenReturn(2);when(inv.getItem(0)).thenReturn(input);when(inv.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
  var output=mock(ItemStack.class);when(output.getAmount()).thenReturn(1);when(output.getMaxStackSize()).thenReturn(64);
  var messenger=server.getMessenger();var manager=server.getPluginManager();var scheduler=server.getScheduler();
  try(var bukkit=mockStatic(Bukkit.class);var grants=mockStatic(ArtifactGrants.class);var items=mockConstruction(ItemsAdderItems.class,(m,c)->{when(m.idOf(input)).thenReturn("item:test_sugar");when(m.create("item:test_result",1)).thenReturn(missing?null:output);})){
   bukkit.when(Bukkit::getMessenger).thenReturn(messenger);bukkit.when(Bukkit::getPluginManager).thenReturn(manager);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
   grants.when(()->ArtifactGrants.grant(id,"magiccodex.alchemy.recipe.fixture")).thenAnswer(i->{verify(inv).addItem(any(ItemStack[].class));verify(player).setTotalExperience(6);return CompletableFuture.completedFuture(null);});
   var bridge=new AlchemyBridge(plugin);var craft=AlchemyBridge.class.getDeclaredMethod("craft",Player.class,AlchemyProtocol.Request.class);craft.setAccessible(true);craft.invoke(bridge,player,new AlchemyProtocol.Request(AlchemyProtocol.CRAFT,1,List.of(new AlchemyProtocol.Stack("item:test_sugar",2))));
   if(enough&&!missing){verify(inv).setItem(0,(ItemStack)null);verify(player).setTotalExperience(6);grants.verify(()->ArtifactGrants.grant(id,"magiccodex.alchemy.recipe.fixture"));}
   else{verify(inv,never()).setItem(anyInt(),any());verify(inv,never()).addItem(any(ItemStack[].class));verify(player,never()).setLevel(anyInt());verify(player,never()).setExp(anyFloat());verify(player,never()).setTotalExperience(anyInt());grants.verifyNoInteractions();}
  }
  }
 }
}
