package school.chacademia.magicmythic;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BroomLinkTest {
 public interface Api {boolean broomFlight(Player player,int ticks);boolean isRidingBroom(Player player);void stopBroom(Player player);}
 @Test void onlyMissingCapabilityReturnsLegacyFallback(){
  var manager=mock(PluginManager.class);var player=mock(Player.class);
  try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::getPluginManager).thenReturn(manager);assertNull(BroomLink.flight(player,200));
   Plugin p=mock(Plugin.class,withSettings().extraInterfaces(Api.class));when(p.isEnabled()).thenReturn(true);when(manager.getPlugin("ChacademyMotion")).thenReturn(p);
   when(player.getListeningPluginChannels()).thenReturn(Set.of());assertNull(BroomLink.flight(player,200));
   when(player.getListeningPluginChannels()).thenReturn(Set.of("chacademy:broom_ride"));when(((Api)p).broomFlight(player,200)).thenReturn(false);assertEquals(Boolean.FALSE,BroomLink.flight(player,200));
   when(((Api)p).broomFlight(player,200)).thenReturn(true);assertEquals(Boolean.TRUE,BroomLink.flight(player,200));
  }
 }
 @Test void pluginErrorCannotBypassDenialThroughVanilla(){
  var manager=mock(PluginManager.class);var player=mock(Player.class);Plugin p=mock(Plugin.class,withSettings().extraInterfaces(Api.class));
  when(p.isEnabled()).thenReturn(true);when(manager.getPlugin("ChacademyMotion")).thenReturn(p);when(player.getListeningPluginChannels()).thenReturn(Set.of("chacademy:broom_ride"));
  when(((Api)p).broomFlight(player,200)).thenThrow(new IllegalStateException());
  try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::getPluginManager).thenReturn(manager);assertEquals(Boolean.FALSE,BroomLink.flight(player,200));}
 }
}
