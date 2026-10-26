package school.chacademia.magicmythic;
import dev.portablevfx.paper.api.*;import dev.portablevfx.protocol.EffectBasis;
import java.nio.file.*;import java.util.*;import org.bukkit.*;import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class TelegraphCommandScaleInputTest {
 @TempDir Path temp;
 private MagicMythicPlugin plugin()throws Exception{
  var plugin=ReactionVfxTest.plugin(new YamlConfiguration());when(plugin.getDataFolder()).thenReturn(temp.resolve("ChAcademiaMagicMythic").toFile());
  module("sealcircle",4,.25,4);module("frostlaser",16,.25,4);return plugin;
 }
 private void module(String slug,double reference,double min,double max)throws Exception{
  Path file=temp.resolve("PortableVFX/effects/"+slug+"/vfx.json");Files.createDirectories(file.getParent());Files.writeString(file,"{\"scale\":{\"input\":\"areaRadius\",\"reference\":"+reference+",\"min\":"+min+",\"max\":"+max+"}}");
 }
 private ReactionVfx ready(MagicMythicPlugin plugin,PortableVfxService service)throws Exception{
  var vfx=ReactionVfxTest.ready(plugin,service);
  ReactionVfxTest.field(vfx,"scaledPlay",PortableVfxService.class.getMethod("startCast",EffectRequest.class,EffectBasis.class,long.class,double.class,double.class,double.class));
  ReactionVfxTest.field(vfx,"effectBasis",EffectBasis.class.getMethod("projectile",double.class,double.class,double.class));return vfx;
 }
 @Test void shortcutsAndGenericMultiplierReachNativeScaleInputWithUnitTransforms()throws Exception{
  var plugin=plugin();var service=mock(PortableVfxService.class);var vfx=ready(plugin,service);
  var reactions=mock(Reactions.class);when(reactions.vfx()).thenReturn(vfx);when(plugin.reactions()).thenReturn(reactions);
  var telegraphs=mock(Telegraphs.class);when(telegraphs.id()).thenReturn(1);when(plugin.telegraphs()).thenReturn(telegraphs);
  var server=mock(Server.class);var scheduler=mock(BukkitScheduler.class);when(server.getScheduler()).thenReturn(scheduler);when(plugin.getServer()).thenReturn(server);
  var world=mock(World.class);when(world.getName()).thenReturn("world");var player=mock(Player.class);
  when(player.getWorld()).thenReturn(world);when(player.getLocation()).thenAnswer(i->new Location(world,1,64,2,30,0));when(player.getEyeLocation()).thenAnswer(i->new Location(world,1,65.6,2,30,0));
  try(var ground=mockStatic(Telegraphs.class)){
   ground.when(()->Telegraphs.ground(any(Location.class))).thenAnswer(i->((Location)i.getArgument(0)).getY());
   TelegraphCommand.run(plugin,player,"봉인진",null);
   TelegraphCommand.run(plugin,player,"빙핵레이저",null);
   String result=TelegraphCommand.fx(plugin,player,"sealcircle/seal","원형내발",7f,0f,24,2,2f);assertTrue(result.startsWith("예고 +"),result);
  }
  var tasks=ArgumentCaptor.forClass(Runnable.class);verify(scheduler,times(3)).runTaskLater(eq(plugin),tasks.capture(),anyLong());tasks.getAllValues().forEach(Runnable::run);
  var requests=ArgumentCaptor.forClass(EffectRequest.class);var input=ArgumentCaptor.forClass(Double.class);
  verify(service,times(3)).startCast(requests.capture(),any(EffectBasis.class),anyLong(),eq(0d),input.capture(),eq(-1d));
  assertEquals(List.of(7d,24d,8d),input.getAllValues());assertTrue(requests.getAllValues().stream().allMatch(r->r.scale()==1f));verify(service,never()).play(any());
 }
 @Test void missingMalformedAndOutOfBoundsDescriptorsRejectInsteadOfGuessing()throws Exception{
  var vfx=ready(plugin(),mock(PortableVfxService.class));assertEquals(7,vfx.authoredScaleInput("claude:sealcircle/seal",1.75));
  assertThrows(IllegalArgumentException.class,()->vfx.authoredScaleInput("claude:unknown/system",2));assertThrows(IllegalArgumentException.class,()->vfx.authoredScaleInput("claude:../sealcircle/seal",2));assertThrows(IllegalArgumentException.class,()->vfx.authoredScaleInput("claude:sealcircle/seal",8));
  module("invalid",0,.25,4);assertThrows(IllegalArgumentException.class,()->vfx.authoredScaleInput("claude:invalid/system",2));
 }
 @Test void reloadInvalidatesTheAuthoredReferenceCache()throws Exception{
  var vfx=ready(plugin(),mock(PortableVfxService.class));assertEquals(8,vfx.authoredScaleInput("claude:sealcircle/seal",2));module("sealcircle",5,.25,4);assertEquals(8,vfx.authoredScaleInput("claude:sealcircle/seal",2));vfx.reload();assertEquals(10,vfx.authoredScaleInput("claude:sealcircle/seal",2));
 }
}