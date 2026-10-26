package school.chacademia.magicmythic;
import org.junit.jupiter.api.Test;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** C03 (owner decision 7): party = ally, match team overrides the party, nobody else is an ally. */
class AllyResolverTest {
 @Test void matchAnswerOverridesPartyAndNoAnswerUsesParty(){
  assertTrue(AllyResolver.allied(null,()->true));assertFalse(AllyResolver.allied(null,()->false));
  assertFalse(AllyResolver.allied(false,()->true));   // 경기 중 상대 팀: 같은 파티여도 적
  assertTrue(AllyResolver.allied(true,()->false));    // 경기 중 같은 팀: 랜덤 매칭이라 파티가 달라도 아군
 }
 final World world=mock(World.class);
 <T extends LivingEntity>T entity(Class<T> type){T e=mock(type);when(e.isValid()).thenReturn(true);when(e.getLocation()).thenReturn(new Location(world,1,64,0));when(e.getScoreboardTags()).thenReturn(Set.of());if(e instanceof Player p)when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);return e;}
 @Test void partyMateIsAllyNeverEnemyAndStrangerIsNeverAlly(){
  var plugin=mock(MagicMythicPlugin.class);var config=new YamlConfiguration();when(plugin.getConfig()).thenReturn(config);
  when(plugin.protection()).thenReturn(new MagicProtection((at,f)->null));
  var allies=mock(AllyResolver.class);when(plugin.allies()).thenReturn(allies);
  var t=new Targets(plugin);var caster=entity(Player.class);var mate=entity(Player.class);var stranger=entity(Player.class);var cow=entity(Cow.class);
  when(allies.allied(caster,mate)).thenReturn(true);when(allies.allied(caster,stranger)).thenReturn(false);
  assertTrue(t.ally(caster,mate));assertFalse(t.enemy(caster,mate));
  assertFalse(t.ally(caster,stranger));assertTrue(t.enemy(caster,stranger)); // 피해는 WorldGuard PvP가 정함
  assertTrue(t.ally(caster,caster));assertFalse(t.ally(caster,cow));assertTrue(t.enemy(caster,cow));
  var mob=entity(Zombie.class);assertFalse(t.ally(mob,mate));assertTrue(t.enemy(mob,mate)); // 몹 시전자는 파티와 무관
 }
}
