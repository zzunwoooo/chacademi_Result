package school.magiccodex.paper;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.api.skills.Skill;
import io.lumine.mythic.api.skills.SkillMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
/** Executes the actual onEnable and actual SpellRuntime prepare/execute.
 * Unrelated services, Bukkit/Mythic transport and VFX renderer are test boundaries. */
class CatalogStartupRuntimeTest {
 @TempDir Path temp;
 @Test void catalogStartupCreatesRealRuntimeAndNormalCastReachesMythicGameplay() throws Exception {run(true);}
 @Test void legacyStartupStillCreatesRealRuntime() throws Exception {run(false);}
 @Test void missingDependencyKeepsFallbackWithNoGameplay() throws Exception {run(true,false);}
 private void run(boolean catalog) throws Exception {run(catalog,true);}
 private void run(boolean catalog,boolean dependency) throws Exception {
  Files.writeString(temp.resolve("spell-runtime.yml"),"spells:\n  light_leap:\n    name: Leap\n    skill: CHA_light_leap\n    target: SELF\n    range: 12\n    utility: false\n    base-damage: 0\n    power-ratio: 0\n");
  var plugin=mock(MagicCodexBridge.class,CALLS_REAL_METHODS);var server=mock(Server.class,RETURNS_DEEP_STUBS);var manager=server.getPluginManager();
  doReturn(server).when(plugin).getServer();doReturn(temp.toFile()).when(plugin).getDataFolder();doReturn(new YamlConfiguration()).when(plugin).getConfig();doReturn(Logger.getLogger("startup-test")).when(plugin).getLogger();doNothing().when(plugin).saveDefaultConfig();
  doReturn(mock(PluginCommand.class)).when(plugin).getCommand(anyString());
  var warned=MagicCodexBridge.class.getDeclaredField("missingRuntimeRoutes");warned.setAccessible(true);warned.set(plugin,new HashSet<String>());
  when(manager.isPluginEnabled("MythicMobs")).thenReturn(dependency);
  var player=mock(Player.class);var location=new Location(mock(World.class),0,64,0);when(player.getLocation()).thenReturn(location);
  doReturn(42d).when(plugin).magicPower(player);doReturn(3d).when(plugin).magicHaste(player);
  var order=new ArrayList<String>();var mm=mock(MythicBukkit.class,RETURNS_DEEP_STUBS);
  when(mm.getSkillManager().getSkill("CHA_light_leap")).thenAnswer(i->{order.add("prepare");return Optional.of(mock(Skill.class));});
  var metadata=mock(SkillMetadata.class,RETURNS_DEEP_STUBS);
  when(mm.getAPIHelper().castSkill(eq(player),eq("CHA_light_leap"),eq(player),eq(location),any(),any(),eq(1f),any())).thenAnswer(i->{
   assertEquals(List.of(),i.getArgument(4));assertEquals(List.of(location),i.getArgument(5));
   java.util.function.Consumer<SkillMetadata> callback=i.getArgument(7);callback.accept(metadata);order.add("execute");return true;
  });
  var scopes=new ArrayList<AutoCloseable>();
  try(var bukkit=mockStatic(Bukkit.class);var mythic=mockStatic(MythicBukkit.class);var protection=mockStatic(MagicProtectionLink.class);var visual=mockStatic(CatalogVfxLink.class);var commands=mockStatic(ChacaApi.class)){
   bukkit.when(Bukkit::getPluginManager).thenReturn(manager);mythic.when(MythicBukkit::inst).thenReturn(mm);
   protection.when(()->MagicProtectionLink.cast(player)).thenReturn(true);protection.when(()->MagicProtectionLink.at(player,location)).thenReturn(true);
   visual.when(()->CatalogVfxLink.cast(plugin,player,"light_leap",null)).thenAnswer(i->{order.add("vfx");return true;});
   for(String name:List.of("NameplateBridge","CommandHub","PartyRoster","PlayerStats","JournalBridge","DisplayNames","WalletBridge","StatsBridge","SchoolBridge","SocialBridge","PartyIdentityBridge","PartyVitalsBridge","AscensionBridge","TemperatureBridge","PetBridge","ClimateService","EquipmentBridge","AppraisalBridge","EnhancementBridge","ReconfigurationBridge","PlayerStateBridge","TamingBridge","QuestBridge","DialogueBridge","NpcSocialService","TitleBridge","MailboxBridge","ShopBridge","ShopAdminBridge","MileageBridge","AdminCommandBridge","SpellUtility","SpellSounds"))
    scopes.add(mockConstruction(Class.forName("school.magiccodex.paper."+name)));
   try(var mana=mockConstruction(ManaBridge.class,(m,c)->when(m.isCatalogMode()).thenReturn(catalog))){
    plugin.onEnable();verify(manager,never()).disablePlugin(plugin);assertEquals(1,mana.constructed().size());verify(mana.constructed().getFirst()).bindSpellCommand();
    assertEquals(Boolean.TRUE,plugin.runtimeCast(player,"light_leap"));
    visual.verify(()->CatalogVfxLink.cast(plugin,player,"light_leap",null));
    if(!dependency){assertEquals(List.of("vfx"),order);return;}
    assertEquals(List.of("prepare","vfx","execute"),order);
    verify(metadata.getVariables()).putDouble("magic_power",42d);verify(metadata.getVariables()).putDouble("damage",0d);verify(metadata.getVariables()).putDouble("magic_haste",3d);
    order.clear();protection.when(()->MagicProtectionLink.at(player,location)).thenReturn(false);
    assertEquals(Boolean.FALSE,plugin.runtimeCast(player,"light_leap"));assertEquals(List.of("prepare"),order,"refused target/region cannot reach VFX or gameplay");
   }
  }finally{Collections.reverse(scopes);for(var scope:scopes)scope.close();}
 }
}
