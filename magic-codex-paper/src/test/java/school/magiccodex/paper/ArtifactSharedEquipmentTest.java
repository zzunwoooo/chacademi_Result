package school.magiccodex.paper;
import java.nio.file.*;import java.util.*;import java.util.logging.Logger;import org.bukkit.*;import org.bukkit.command.PluginCommand;import org.bukkit.entity.Player;import org.bukkit.persistence.*;import org.bukkit.plugin.PluginManager;import org.bukkit.plugin.messaging.Messenger;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class ArtifactSharedEquipmentTest {
 @TempDir Path temp;
 @Test void staleLocalPermissionCannotResurrectSharedRevokeAndRawEquipmentIsPreserved()throws Exception{run(false);}
 @Test void databaseOutageDoesNotErasePersistedEquipmentAndRecoveryRestoresIt()throws Exception{run(true);}
 void run(boolean outage)throws Exception{
  Files.writeString(temp.resolve("artifacts.yml"),"settings:\n  max-equipped: 1\nartifacts:\n  a:\n    name: Fixture\n    grade: common\n");
  var plugin=mock(MagicCodexBridge.class);when(plugin.getName()).thenReturn("MagicCodexBridge");when(plugin.getDataFolder()).thenReturn(temp.toFile());when(plugin.getLogger()).thenReturn(Logger.getLogger("artifact-fixture"));var command=mock(PluginCommand.class);when(plugin.getCommand(anyString())).thenReturn(command);
  var player=mock(Player.class);UUID id=UUID.randomUUID();when(player.getUniqueId()).thenReturn(id);when(player.hasPermission(anyString())).thenReturn(true);when(plugin.playerStateReady(player)).thenReturn(true);var data=mock(PersistentDataContainer.class);when(player.getPersistentDataContainer()).thenReturn(data);when(data.getOrDefault(any(NamespacedKey.class),eq(PersistentDataType.STRING),eq(""))).thenReturn("a");when(player.getListeningPluginChannels()).thenReturn(Set.of());
  var messenger=mock(Messenger.class);var manager=mock(PluginManager.class);
  try(var bukkit=mockStatic(Bukkit.class);var services=mockConstruction(ArtifactOwnershipService.class);var items=mockConstruction(ItemsAdderItems.class)){
   bukkit.when(Bukkit::getMessenger).thenReturn(messenger);bukkit.when(Bukkit::getPluginManager).thenReturn(manager);var bridge=new ArtifactBridge(plugin);var service=services.constructed().getFirst();when(service.owns(id,"a")).thenReturn(false);
   assertFalse(bridge.owns(player,"a"));assertTrue(bridge.equipped(player).isEmpty());verify(data,never()).remove(any());verify(data,never()).set(any(),any(),any());
   when(player.isOp()).thenReturn(true);assertTrue(bridge.owns(player,"a"));assertEquals(List.of("a"),bridge.equipped(player));when(player.isOp()).thenReturn(false);assertFalse(bridge.owns(player,"a"));
   if(outage){when(service.owns(id,"a")).thenReturn(true);assertEquals(List.of("a"),bridge.equipped(player));verify(data,never()).remove(any());}
   else{var change=ArtifactBridge.class.getDeclaredMethod("ownershipChanged",Player.class);change.setAccessible(true);change.invoke(bridge,player);verify(data,never()).remove(any());verify(plugin).artifactsChanged(player);}
  }
 }
}
