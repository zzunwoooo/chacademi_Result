package school.chacademia.magicmythic;
import dev.portablevfx.paper.api.*;import dev.portablevfx.protocol.EffectBasis;import org.bukkit.*;import org.bukkit.entity.Player;import org.bukkit.plugin.*;import org.bukkit.util.Vector;import org.junit.jupiter.api.Test;import org.mockito.ArgumentCaptor;import java.util.UUID;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class GameplayVisualTest {
 @Test void actualProjectilePositionMovesAndFinishesExactlyOnce(){
  var service=mock(PortableVfxService.class);var plugin=mock(Plugin.class);when(plugin.isEnabled()).thenReturn(true);var manager=mock(PluginManager.class);when(manager.getPlugin("PortableVFX")).thenReturn(plugin);var services=mock(ServicesManager.class);when(services.getRegistration(PortableVfxService.class)).thenReturn(new RegisteredServiceProvider<>(PortableVfxService.class,service,ServicePriority.Normal,plugin));
  var world=mock(World.class);when(world.getName()).thenReturn("world");var player=mock(Player.class);var owner=UUID.randomUUID();when(player.getUniqueId()).thenReturn(owner);var handle=UUID.randomUUID();when(service.startCast(any(EffectRequest.class),any(EffectBasis.class),anyLong())).thenReturn(new PlayResult(handle,1,0,64));
  try(var b=mockStatic(Bukkit.class)){b.when(Bukkit::getPluginManager).thenReturn(manager);b.when(Bukkit::getServicesManager).thenReturn(services);
   var visual=GameplayVisual.projectile(player,"flint_sorting",new Location(world,1,65,2),new Vector(0,0,1));assertNotNull(visual);
   visual.move(new Location(world,1,65,3),new Vector(0,0,1));visual.close();visual.close();
   var request=ArgumentCaptor.forClass(EffectRequest.class);verify(service).startCast(request.capture(),any(EffectBasis.class),anyLong());assertEquals("claude:flintsorting/projectile",request.getValue().effectId());verify(service).owner(handle,owner);verify(service).updateCast(eq(handle),eq(1d),eq(65d),eq(3d),any(EffectBasis.class));verify(service,times(1)).finishCast(handle,true);
  }
 }
 @Test void existingSpellRoutesNeverEnterNewProjectileAdapter(){assertNull(GameplayVisual.projectile(null,"fireball",null,null));}
}
