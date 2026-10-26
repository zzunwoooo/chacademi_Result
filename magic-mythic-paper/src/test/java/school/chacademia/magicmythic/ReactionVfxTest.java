package school.chacademia.magicmythic;
import dev.portablevfx.paper.api.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;
import school.magiccodex.protocol.ElementProtocol.Reaction;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReactionVfxTest {
 static void field(Object o,String name,Object value)throws Exception {var f=ReactionVfx.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
 static ReactionVfx ready(MagicMythicPlugin plugin,PortableVfxService service)throws Exception {
  var vfx=new ReactionVfx(plugin);field(vfx,"resolved",true);field(vfx,"service",service);
  field(vfx,"request",EffectRequest.class.getConstructor(String.class,String.class,double.class,double.class,double.class,float.class,int.class,float.class,float.class,float.class,int.class,float.class,double.class));
  field(vfx,"play",PortableVfxService.class.getMethod("play",EffectRequest.class));return vfx;
 }
 static MagicMythicPlugin plugin(YamlConfiguration config){var p=mock(MagicMythicPlugin.class);when(p.getConfig()).thenReturn(config);when(p.getLogger()).thenReturn(mock(Logger.class));return p;}
 static Location at(){var w=mock(World.class);when(w.getName()).thenReturn("world");return new Location(w,1,64,2);}
 @Test void everyReactionProducesOneDistinctValidRequest()throws Exception {
  var service=mock(PortableVfxService.class);var vfx=ready(plugin(new YamlConfiguration()),service);var at=at();
  for(var reaction:Reaction.values())vfx.area(reaction,at,3,null,30);
  var requests=ArgumentCaptor.forClass(EffectRequest.class);verify(service,times(15)).play(requests.capture());
  var ids=new HashSet<String>();for(var req:requests.getAllValues()){assertTrue(ids.add(req.effectId()));assertEquals(48,req.radius());assertEquals("world",req.worldName());}
  assertEquals(Set.of("steam","wildfire","magma","flare","ember_curse","shock","mud","rainbow","abyss","sandstorm","flash","void_gust","crystal","bind","eclipse"),ids.stream().map(id->id.substring(id.indexOf('/')+1)).collect(java.util.stream.Collectors.toSet()));
 }
 @Test void offAndReloadPreventPlayback()throws Exception {
  var config=new YamlConfiguration();config.set("reactions.vfx.enabled",false);var service=mock(PortableVfxService.class);var vfx=ready(plugin(config),service);
  vfx.area(Reaction.STEAM,at(),3,null,30);verifyNoInteractions(service);assertEquals("반응 VFX 꺼짐",vfx.summary());
  config.set("reactions.vfx.enabled",true);vfx=ready(plugin(config),service);vfx.area(Reaction.STEAM,at(),3,null,30);verify(service,times(1)).play(any(EffectRequest.class));
  config.set("reactions.vfx.enabled",false);vfx.reload();vfx.area(Reaction.STEAM,at(),3,null,30);verifyNoMoreInteractions(service);
 }
 @Test void missingPluginAndBudgetExhaustionDoNotBreakGameplay()throws Exception {
  var p=plugin(new YamlConfiguration());try(var b=mockStatic(Bukkit.class)){b.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));assertDoesNotThrow(()->new ReactionVfx(p).area(Reaction.STEAM,at(),3,null,30));}
  var service=mock(PortableVfxService.class);when(service.play(any(EffectRequest.class))).thenThrow(new RejectedExecutionException());var vfx=ready(p,service);
  assertDoesNotThrow(()->vfx.area(Reaction.STEAM,at(),3,null,30));verify(p.getLogger(),never()).warning(anyString());
 }
 @Test void boltUsesHorizontalDirectionAndDistanceOnce()throws Exception {
  var service=mock(PortableVfxService.class);var vfx=ready(plugin(new YamlConfiguration()),service);var from=mock(LivingEntity.class);var to=mock(LivingEntity.class);var origin=at();
  when(from.getLocation()).thenAnswer(i->origin.clone());when(to.getLocation()).thenAnswer(i->origin.clone().add(3,0,0));when(from.getHeight()).thenReturn(1.8);when(to.getHeight()).thenReturn(1.8);
  vfx.bolt(from,to);var request=ArgumentCaptor.forClass(EffectRequest.class);verify(service).play(request.capture());assertEquals(-90,request.getValue().yaw());assertEquals(1,request.getValue().scale());assertEquals("claude:elementreactions/shock",request.getValue().effectId());
 }
}
