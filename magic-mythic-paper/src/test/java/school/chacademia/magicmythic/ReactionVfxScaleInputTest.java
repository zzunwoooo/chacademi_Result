package school.chacademia.magicmythic;
import dev.portablevfx.paper.api.EffectRequest;
import dev.portablevfx.paper.api.PortableVfxService;
import dev.portablevfx.protocol.EffectBasis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ReactionVfxScaleInputTest {
 @Test void authoredPhysicalInputsReachTheRealApiWithUnitTransforms(){
  var plugin=mock(MagicMythicPlugin.class);when(plugin.getConfig()).thenReturn(new YamlConfiguration());when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("scale-test"));
  var portable=mock(Plugin.class);when(portable.isEnabled()).thenReturn(true);
  var api=mock(PortableVfxService.class);var manager=mock(PluginManager.class);when(manager.getPlugin("PortableVFX")).thenReturn(portable);
  var services=mock(ServicesManager.class);when(services.getRegistration(PortableVfxService.class)).thenReturn(new RegisteredServiceProvider<>(PortableVfxService.class,api,ServicePriority.Normal,portable));
  var world=mock(World.class);when(world.getName()).thenReturn("world");var point=new Location(world,1,2,3);
  try(var bukkit=mockStatic(Bukkit.class)){
   bukkit.when(Bukkit::getPluginManager).thenReturn(manager);bukkit.when(Bukkit::getServicesManager).thenReturn(services);
   var bridge=new ReactionVfx(plugin);bridge.areaEffect("claude:sealcircle/seal",point,7d,90f,40,0xFFFFFF);
   var request=ArgumentCaptor.forClass(EffectRequest.class);
   verify(api).startCast(request.capture(),any(EffectBasis.class),anyLong(),eq(0d),eq(7d),eq(-1d));
   assertEquals(1f,request.getValue().scale());assertEquals(0xffffff,request.getValue().rgb());assertEquals(1f,request.getValue().opacity());
   verify(api,never()).play(any(EffectRequest.class));
  }
 }

 @Test void bodyAndAreaUseModelFallbackDimensions()throws Exception{
  var service=mock(PortableVfxService.class);var vfx=ReactionVfxTest.ready(ReactionVfxTest.plugin(new YamlConfiguration()),service);
  ReactionVfxTest.field(vfx,"scaledPlay",PortableVfxService.class.getMethod("startCast",EffectRequest.class,EffectBasis.class,long.class,double.class,double.class,double.class));
  ReactionVfxTest.field(vfx,"effectBasis",EffectBasis.class.getMethod("projectile",double.class,double.class,double.class));
  var entity=mock(org.bukkit.entity.LivingEntity.class);when(entity.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(0,0,0,1.2,3.6,1.2));when(entity.getLocation()).thenAnswer(i->ReactionVfxTest.at());
  var manager=mock(PluginManager.class);
  try(var bukkit=mockStatic(Bukkit.class)){
   bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
   vfx.body(school.magiccodex.protocol.ElementProtocol.Reaction.CRYSTAL,entity,20);
   vfx.area(school.magiccodex.protocol.ElementProtocol.Reaction.STEAM,ReactionVfxTest.at(),3,entity,60);
  }
  var request=ArgumentCaptor.forClass(EffectRequest.class);var scale=ArgumentCaptor.forClass(Double.class);
  verify(service,times(2)).startCast(request.capture(),any(EffectBasis.class),anyLong(),eq(0d),scale.capture(),eq(-1d));
  assertEquals(3.6,scale.getAllValues().get(0),0.000001);
  assertEquals(1.8*(float)(3.6/3.3),scale.getAllValues().get(1),0.000001);
  assertTrue(request.getAllValues().stream().allMatch(r->r.scale()==1f));verify(service,never()).play(any());
 }
}
