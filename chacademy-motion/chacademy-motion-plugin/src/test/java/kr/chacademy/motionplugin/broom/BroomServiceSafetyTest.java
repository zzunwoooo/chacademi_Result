package kr.chacademy.motionplugin.broom;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.entity.EntityDamageEvent;
import org.mockito.MockedStatic;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class BroomServiceSafetyTest {
 @TempDir Path dir;
 BroomService service; Player player; World world; MockedStatic<Bukkit> bukkit;
 @BeforeEach void setup() throws Exception {
  Files.copy(getClass().getResourceAsStream("/brooms.yml"),dir.resolve("brooms.yml"));
  var plugin=mock(JavaPlugin.class);when(plugin.getDataFolder()).thenReturn(dir.toFile());
  when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
  service=new BroomService(plugin);player=mock(Player.class);world=mock(World.class);
  when(world.getName()).thenReturn("test");when(player.getWorld()).thenReturn(world);
  when(player.getLocation()).thenReturn(new Location(world,0,70,0));when(player.getUniqueId()).thenReturn(UUID.randomUUID());
  when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);when(player.isOnline()).thenReturn(true);
  when(player.getTrackedBy()).thenReturn(Set.of());when(player.getListeningPluginChannels()).thenReturn(Set.of(BroomCodec.CHANNEL_RIDE,BroomCodec.CHANNEL_LIST));
  when(player.hasPermission("chacademy.broom.use")).thenReturn(true);
  bukkit=mockStatic(Bukkit.class);bukkit.when(()->Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
  bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
  var scheduler=mock(BukkitScheduler.class);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
  when(scheduler.runTaskTimer(any(),any(Runnable.class),anyLong(),anyLong())).thenReturn(mock(BukkitTask.class));
 }
 @AfterEach void cleanup(){if(bukkit!=null)bukkit.close();}
 BroomType type(){return service.brooms().values().iterator().next();}
 void own(){when(player.hasPermission("chacademy.broom.own."+type().id())).thenReturn(true);}
 @Test void opOwnsAllWithoutExplicitPermission(){when(player.isOp()).thenReturn(true);assertEquals(30,service.ownedBrooms(player).size());}
 @Test void wildcardOwnsAll(){when(player.hasPermission("chacademy.broom.own.*")).thenReturn(true);assertEquals(30,service.ownedBrooms(player).size());}
 @Test void unownedCannotEquipOrStartUnlimited(){assertFalse(service.equip(player,type()));assertFalse(service.start(player,type(),0));assertTrue(service.start(player,type(),200));}
 @Test void revokedSelectionFallsBackToPractice(){own();assertTrue(service.equip(player,type()));when(player.hasPermission("chacademy.broom.own."+type().id())).thenReturn(false);assertNull(service.ownedBroom(player));}
 @Test void dismountRestoresOriginalFlightFalse(){own();assertTrue(service.start(player,type(),0));service.stop(player);verify(player).setAllowFlight(false);assertFalse(service.isRiding(player));}
 @Test void shutdownRestoresOriginalFlight(){own();when(player.getAllowFlight()).thenReturn(true);assertTrue(service.start(player,type(),0));clearInvocations(player);service.shutdown();verify(player).setAllowFlight(true);verify(player).setFlying(false);}
 @Test void revocationEndsActiveRide() throws Exception {own();assertTrue(service.start(player,type(),0));when(player.hasPermission("chacademy.broom.own."+type().id())).thenReturn(false);var tick=BroomService.class.getDeclaredMethod("tick");tick.setAccessible(true);tick.invoke(service);assertFalse(service.isRiding(player));verify(player).setAllowFlight(false);}
 @Test void fallDamageOnlyCancelledWhileRiding(){own();assertTrue(service.start(player,type(),0));var e=mock(EntityDamageEvent.class);when(e.getEntity()).thenReturn(player);when(e.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);service.onFall(e);verify(e).setCancelled(true);service.stop(player);clearInvocations(e);service.onFall(e);verify(e,never()).setCancelled(anyBoolean());}
}
