package school.chacademia.magicmythic;
import org.junit.jupiter.api.Test;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TargetsTest {
 final World world=mock(World.class);
 final MagicMythicPlugin plugin=mock(MagicMythicPlugin.class);
 final Player caster=entity(Player.class,0);
 Targets targets(MagicProtection protection){
  var config=new YamlConfiguration();config.set("pvp-worlds",List.of());
  config.set("excluded-tags",List.of("chacademia_pet"));config.set("excluded-mythic-prefixes",List.of("CA_"));
  when(plugin.getConfig()).thenReturn(config);when(plugin.protection()).thenReturn(protection);return new Targets(plugin);
 }
 <T extends LivingEntity>T entity(Class<T> type,double x){T e=mock(type);when(e.isValid()).thenReturn(true);when(e.getLocation()).thenReturn(new Location(world,x,64,0));when(e.getScoreboardTags()).thenReturn(Set.of());if(e instanceof Player p)when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);return e;}
 @Test void animalsPlayersOwnedPetsAndCustomMobsAreAllowedDespiteLegacyConfig(){
  var t=targets(new MagicProtection((at,f)->null));
  var wolf=entity(Wolf.class,1);when(wolf.isTamed()).thenReturn(true);when(wolf.getScoreboardTags()).thenReturn(Set.of("chacademia_pet"));
  for(var e:List.of(entity(Cow.class,1),entity(Player.class,2),wolf,entity(Zombie.class,3)))assertTrue(t.enemy(caster,e));
  verify(wolf,never()).isTamed();assertFalse(t.enemy(caster,caster));assertFalse(t.enemy(caster,mock(Item.class)));
 }
 @Test void npcDeadInvalidCreativeSpectatorAndArmorStandStayExcluded(){
  var t=targets(new MagicProtection((at,f)->null));var npc=entity(Villager.class,1);when(npc.hasMetadata("NPC")).thenReturn(true);assertFalse(t.enemy(caster,npc));
  var tagged=entity(Player.class,1);when(tagged.getScoreboardTags()).thenReturn(Set.of("chacademia_npc"));assertFalse(t.enemy(caster,tagged));
  var dead=entity(Cow.class,1);when(dead.isDead()).thenReturn(true);assertFalse(t.enemy(caster,dead));
  var invalid=entity(Cow.class,1);when(invalid.isValid()).thenReturn(false);assertFalse(t.enemy(caster,invalid));
  assertFalse(t.enemy(caster,entity(ArmorStand.class,1)));
  for(var mode:List.of(GameMode.CREATIVE,GameMode.SPECTATOR)){var player=entity(Player.class,1);when(player.getGameMode()).thenReturn(mode);assertFalse(t.enemy(caster,player));}
 }
 @Test void areaSelectionAndFinalDamageRecheckMovingTargetsAndProtection(){
  boolean[] denied={false};var protection=mock(MagicProtection.class);
  when(protection.harm(any(),any())).thenAnswer(i->!denied[0]);when(protection.damage(any(),any(),anyDouble())).thenReturn(true);
  var t=targets(protection);var cow=entity(Cow.class,1);when(cow.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(0,63,0,2,65,2));
  var npc=entity(Villager.class,1);when(npc.hasMetadata("NPC")).thenReturn(true);
  when(world.getNearbyEntities(any(Location.class),anyDouble(),anyDouble(),anyDouble())).thenReturn(List.of(cow,npc,caster));
  assertEquals(List.of(cow),t.enemiesNear(caster,new Location(world,0,64,0),5,10));assertTrue(t.hurt(caster,cow,7));
  denied[0]=true;assertFalse(t.enemy(caster,cow));assertFalse(t.hurt(caster,cow,7));assertTrue(t.enemiesNear(caster,new Location(world,0,64,0),5,10).isEmpty());verify(protection,times(1)).damage(caster,cow,7);
 }
 @Test void worldguardPvpDenyIsStillRespectedWithoutGlobalWorldAllowlist(){
  assertFalse(targets(new MagicProtection((at,f)->f.equals("pvp")?false:null)).enemy(caster,entity(Player.class,2)));
  assertFalse(targets(new MagicProtection((at,f)->f.equals("magic")?false:null)).enemy(caster,entity(Cow.class,2)));
 }
}
