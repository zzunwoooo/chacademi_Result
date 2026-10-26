package school.chacademia.wildlife;
import org.junit.jupiter.api.Test;
import org.bukkit.Bukkit;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WildEventSafetyTest {
 @Test void hungerSuppressionTracksOnlyTaggedHuskAttacks()throws Exception{
  var listener=new NoHuskHunger();var victim=mock(Player.class);var id=UUID.randomUUID();when(victim.getUniqueId()).thenReturn(id);var event=mock(EntityDamageByEntityEvent.class);when(event.getEntity()).thenReturn(victim);var husk=mock(Husk.class);when(event.getDamager()).thenReturn(husk);when(husk.getScoreboardTags()).thenReturn(Set.of());var f=NoHuskHunger.class.getDeclaredField("hitTick");f.setAccessible(true);
  try(var b=mockStatic(Bukkit.class)){b.when(Bukkit::getCurrentTick).thenReturn(100);listener.hit(event);assertTrue(((Map<?,?>)f.get(listener)).isEmpty());when(husk.getScoreboardTags()).thenReturn(Set.of("chacademia_custom"));listener.hit(event);assertEquals(100,((Map<?,?>)f.get(listener)).get(id));}
 }
 @Test void ordinaryPotionEffectsAndNaturalExpirationRemainUntouched(){var listener=new NoHuskHunger();for(var cause:List.of(EntityPotionEffectEvent.Cause.POTION_DRINK,EntityPotionEffectEvent.Cause.EXPIRATION)){var effect=mock(EntityPotionEffectEvent.class);when(effect.getCause()).thenReturn(cause);listener.effect(effect);verify(effect,never()).setCancelled(true);}}
}
