package school.magiccodex.paper;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import school.magiccodex.protocol.ShinyProtocol;

/** Server-owned identity, persisted on the entity; no client-supplied rarity decisions. */
final class ShinyBridge implements Listener,AutoCloseable {
 private final MagicCodexBridge plugin;private final TamingBridge taming;private final NamespacedKey key;
 private final Map<UUID,LivingEntity> loaded=new HashMap<>();private YamlConfiguration config;private int tick;private org.bukkit.scheduler.BukkitTask task;
 ShinyBridge(MagicCodexBridge p,TamingBridge t){plugin=p;taming=t;key=new NamespacedKey(p,"shiny");if(!new java.io.File(p.getDataFolder(),"shiny.yml").exists())p.saveResource("shiny.yml",false);reload();
  p.getServer().getMessenger().registerOutgoingPluginChannel(p,ShinyProtocol.CHANNEL);Bukkit.getPluginManager().registerEvents(this,p);registerCommands();
  for(var world:Bukkit.getWorlds())for(var e:world.getLivingEntities())remember(e);
  task=Bukkit.getScheduler().runTaskTimer(p,this::tick,10,10);
 }
 private void reload(){var file=new java.io.File(plugin.getDataFolder(),"shiny.yml");var next=new YamlConfiguration();try{next.load(file);}catch(Exception e){if(config!=null)throw new IllegalArgumentException("shiny.yml: "+e.getMessage(),e);plugin.getLogger().warning("shiny.yml 읽기 실패 (기본값 사용): "+e.getMessage());}config=next;}
 boolean isShiny(LivingEntity e){return e.getPersistentDataContainer().getOrDefault(key,PersistentDataType.BYTE,(byte)0)==1||e.getScoreboardTags().contains("chacademia_shiny");}
 private void appearance(LivingEntity e,boolean shiny){
  boolean custom=ShinyModelSwap.customAppearance(e.getScoreboardTags().contains("chacademia_custom"),taming.profileId(e)!=null&&taming.configuredMythic(e));
  if(custom){
   if(Bukkit.getPluginManager().isPluginEnabled("ModelEngine"))CustomShinyModels.apply(e,shiny);
   else if(e.getScoreboardTags().contains("chacademia_custom"))throw new IllegalStateException("ModelEngine is unavailable");
   if(e.getCustomName()!=null)e.setCustomName((shiny?"§e":"§f")+ChatColor.stripColor(e.getCustomName()));
  }
 }
 private void remember(LivingEntity e){if(isShiny(e)){e.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);loaded.put(e.getUniqueId(),e);try{appearance(e,true);}catch(RuntimeException|LinkageError ex){plugin.getLogger().warning("변색종 모델 연결: "+ex.getMessage());}}}
 void mark(LivingEntity e,boolean shiny){appearance(e,shiny);e.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)(shiny?1:0));if(shiny){e.addScoreboardTag("chacademia_shiny");loaded.put(e.getUniqueId(),e);}else{e.removeScoreboardTag("chacademia_shiny");loaded.remove(e.getUniqueId());}for(var p:e.getTrackedBy())send(p,e,shiny);}
 private void send(Player p,LivingEntity e,boolean value){if(p.getListeningPluginChannels().contains(ShinyProtocol.CHANNEL))p.sendPluginMessage(plugin,ShinyProtocol.CHANNEL,ShinyProtocol.encode(new ShinyProtocol.State(e.getEntityId(),e.getUniqueId(),value)));}
 @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void spawn(CreatureSpawnEvent event){
  var e=event.getEntity();var reason=event.getSpawnReason().name();
  Bukkit.getScheduler().runTaskLater(plugin,()->{
   if(!e.isValid())return;if(isShiny(e)){remember(e);return;}
   if(e.getPersistentDataContainer().has(key,PersistentDataType.BYTE)||!config.getBoolean("enabled",true)||!config.getStringList("spawn-reasons").contains(reason))return;
   if(config.getStringList("excluded-worlds").contains(e.getWorld().getName()))return;
   if(reason.equals("DEFAULT")&&e.getType()!=EntityType.ENDER_DRAGON)return;
   String profile=taming.profileId(e);if(profile==null||!taming.allowsShinyRoll(profile))return;
   // CUSTOM is restricted to our configured MythicMobs IDs by profileId.
   if(reason.equals("CUSTOM")&&!taming.configuredMythic(e))return;
   double chance=config.getDouble("chances."+profile,config.getDouble("default-chance",1d/4096));
   if(!Double.isFinite(chance))chance=0;mark(e,ThreadLocalRandom.current().nextDouble()<Math.clamp(chance,0,1));
  },3);
 }
 @EventHandler public void load(EntitiesLoadEvent event){for(var e:event.getEntities())if(e instanceof LivingEntity living)remember(living);}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void transform(EntityTransformEvent event){
  if(!(event.getEntity() instanceof LivingEntity before)||!before.getPersistentDataContainer().has(key,PersistentDataType.BYTE))return;
  boolean value=isShiny(before);for(var after:event.getTransformedEntities())if(after instanceof LivingEntity living)Bukkit.getScheduler().runTask(plugin,()->{if(living.isValid())mark(living,value);});
 }
 @EventHandler public void unload(EntitiesUnloadEvent event){for(var e:event.getEntities())loaded.remove(e.getUniqueId());}
 // Only shiny mobs are announced on track: the client treats every untracked/unknown entity as normal (UUID + entity id check,
 // cleared on unload/JOIN/DISCONNECT). mark(false) still sends an explicit false when a shiny turns normal.
 @EventHandler public void track(PlayerTrackEntityEvent event){if(event.getEntity() instanceof LivingEntity e&&isShiny(e))send(event.getPlayer(),e,true);}
 /** Entities tracked during login were announced before the client registered our channel; resend once it does. */
 @EventHandler public void channel(PlayerRegisterChannelEvent event){
  if(!ShinyProtocol.CHANNEL.equals(event.getChannel()))return;var p=event.getPlayer();
  Bukkit.getScheduler().runTask(plugin,()->{if(!p.isOnline())return;for(var e:List.copyOf(loaded.values()))if(e.isValid()&&e.getTrackedBy().contains(p))send(p,e,true);});
 }
 static int soundIntervalTicks(int seconds){return Math.clamp(seconds,5,300)*2;}
 private void tick(){tick++;
  for(var e:List.copyOf(loaded.values())){
   if(!e.isValid()||e.isDead()){loaded.remove(e.getUniqueId());continue;}
   var viewers=e.getTrackedBy();if(viewers.isEmpty())continue;
   var loc=e.getLocation().add(0,Math.min(e.getHeight()*.6,3),0);
   if(config.getBoolean("particles",true))e.getWorld().spawnParticle(Particle.END_ROD,loc,2,Math.max(.2,Math.min(e.getWidth(),2))*.5,.35,.35,.012);
   if(tick%10==0)for(var p:viewers)send(p,e,true);
   int interval=soundIntervalTicks(config.getInt("sound-interval-seconds",6));
   if(config.getBoolean("sound",true)&&tick%interval==0){
    for(var p:viewers)if(p.getLocation().distanceSquared(loc)<24*24){p.playSound(loc,Sound.BLOCK_AMETHYST_BLOCK_CHIME,.5f,1.3f);p.playSound(loc,Sound.BLOCK_NOTE_BLOCK_CHIME,.25f,1.8f);}
   }
  }
 }
 // ------------------------------------------------------------------ 명령 (2026-10-20 명령어 정리)
 private void registerCommands(){
  if(!CodexCommands.ready(plugin,"/월드관리 변색종"))return;
  ChacaApi.reload(plugin,"변색종","변색종 확률·연출(shiny.yml)",this::reload);
  var node=Commands.literal("변색종");
  for(String action:List.of("지정","해제"))node.then(Commands.literal(action).executes(ctx->{
   var p=CodexCommands.self(ctx);
   LivingEntity e=taming.target(p,16);if(e==null||taming.profileId(e)==null)throw ChacaApi.error("등록된 야생 교화 대상을 바라봐 주세요");
   if(taming.captureLocked(e))throw ChacaApi.error("교화가 끝난 뒤 바꿔 주세요");
   try{mark(e,action.equals("지정"));}catch(RuntimeException|LinkageError ex){plugin.getLogger().warning("변색종 모델 변경 실패: "+ex);throw ChacaApi.error("변색종 전용 모델을 확인해 주세요");}
   ChacaApi.ok(p,action.equals("지정")?"바라보는 몹을 변색종로 바꿨어요":"바라보는 몹을 일반 개체로 바꿨어요");return Command.SINGLE_SUCCESS;
  }));
  ChacaApi.branch(plugin,ChacaApi.월드관리,node,"바라보는 몹을 변색종로 지정·해제","변색종 지정");
 }
 public void close(){if(task!=null)task.cancel();loaded.clear();}
}
