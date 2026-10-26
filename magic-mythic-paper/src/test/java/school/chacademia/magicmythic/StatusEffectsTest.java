package school.chacademia.magicmythic;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import school.magiccodex.protocol.StatusProtocol.Status;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class StatusEffectsTest {
 MockedStatic<Bukkit> bukkit; MockedStatic<io.papermc.paper.registry.RegistryAccess> registry; MagicMythicPlugin plugin; Targets targets; StatusEffects statuses;
 @BeforeEach void setup(){
  registry=mockStatic(io.papermc.paper.registry.RegistryAccess.class);
  var access=mock(io.papermc.paper.registry.RegistryAccess.class,call -> java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{org.bukkit.Registry.class},(p,m,a)->null));
  registry.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(access);
  bukkit=mockStatic(Bukkit.class);
  bukkit.when(Bukkit::getCurrentTick).thenReturn(100);
  bukkit.when(Bukkit::getMessenger).thenReturn(mock(Messenger.class));
  bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class,RETURNS_DEEP_STUBS));
  bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
  plugin=mock(MagicMythicPlugin.class);when(plugin.getName()).thenReturn("ChAcademiaMagicMythic");
  when(plugin.getConfig()).thenReturn(new YamlConfiguration());
  targets=mock(Targets.class);when(plugin.targets()).thenReturn(targets);
  statuses=new StatusEffects(plugin);
 }
 @AfterEach void cleanup(){bukkit.close();registry.close();}
 LivingEntity entity(){var e=mock(LivingEntity.class);when(e.getUniqueId()).thenReturn(UUID.randomUUID());when(e.isValid()).thenReturn(true);return e;}
 Player player(){var p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.isValid()).thenReturn(true);when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);return p;}
 @Test void bossControlConvertsToSlowAndRootIsRejected(){
  var e=entity();when(targets.boss(e)).thenReturn(true);
  for(var s:new Status[]{Status.STUN,Status.SLEEP,Status.FEAR}){assertTrue(statuses.apply(null,e,s,40,0));assertFalse(statuses.has(e,s));assertTrue(statuses.has(e,Status.SLOW));statuses.clear(e);}
  assertFalse(statuses.apply(null,e,Status.ROOT,40,0));assertEquals(0,statuses.count());
 }
 @Test void disarmBlocksSpellsButNotArtifactsAndClearRestoresCast(){var p=player();assertTrue(statuses.apply(null,p,Status.DISARM,40,0));assertNotNull(statuses.castBlock(p,"fireball"));assertNull(statuses.artifactBlock(p));statuses.clear(p);assertNull(statuses.castBlock(p,"fireball"));}
 @Test void stunAndSleepBlockBothSpellAndArtifact(){var p=player();for(var s:new Status[]{Status.STUN,Status.SLEEP}){assertTrue(statuses.apply(null,p,s,40,0));assertNotNull(statuses.castBlock(p,"fireball"));assertNotNull(statuses.artifactBlock(p));statuses.clear(p);}}
 @Test void movementLockOnlyBlocksMovementAndFlight(){var p=player();assertTrue(statuses.apply(null,p,Status.MOVEMENT_LOCK,40,0));assertNotNull(statuses.castBlock(p,"gale_dash"));assertNotNull(statuses.castBlock(p,"flight"));assertNull(statuses.castBlock(p,"fireball"));}
 @Test void damageWakesSleep(){var e=entity();statuses.apply(null,e,Status.SLEEP,40,0);var damage=mock(EntityDamageEvent.class);when(damage.getEntity()).thenReturn(e);when(damage.getFinalDamage()).thenReturn(1d);statuses.damaged(damage);assertFalse(statuses.has(e,Status.SLEEP));}
 @Test void recoverySealCancelsRegainAndClearRestoresIt(){var e=entity();statuses.apply(null,e,Status.RECOVERY_SEAL,40,0);var event=mock(EntityRegainHealthEvent.class);when(event.getEntity()).thenReturn(e);statuses.regain(event);verify(event).setCancelled(true);statuses.clear(e);assertFalse(statuses.recoverySealed(e));}
 @Test void magicWeaknessAndManaBurnCooldown(){var e=entity();statuses.apply(null,e,Status.MAGIC_WEAKNESS,40,0);assertEquals(.7,statuses.spellMultiplier(e),1e-9);statuses.apply(null,e,Status.MANA_BURN,40,0);assertFalse(statuses.apply(null,e,Status.MANA_BURN,40,0));statuses.clear(e);assertEquals(1,statuses.spellMultiplier(e));assertTrue(statuses.apply(null,e,Status.MANA_BURN,40,0));}
}