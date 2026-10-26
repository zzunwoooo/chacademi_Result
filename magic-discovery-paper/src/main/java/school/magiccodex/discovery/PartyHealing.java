package school.magiccodex.discovery;
import java.io.*;import java.util.*;
import org.bukkit.*;import org.bukkit.entity.*;import org.bukkit.event.*;import org.bukkit.event.entity.*;import org.bukkit.event.player.PlayerQuitEvent;import org.bukkit.potion.PotionEffectType;
/** Server-generated nonce responses; no client party claims or per-hit storage reads. */
final class PartyHealing implements Listener,AutoCloseable {
 static final String CHANNEL="chacademi:party_discovery";
 private final MagicDiscovery plugin;
 private final Map<UUID,PotionRecovery> targets=new HashMap<>();
 private final Map<PotionRecovery,Integer> splashTicks=new HashMap<>();
 private final Set<UUID> effectReady=new HashSet<>();
 private final Map<UUID,Pending> pending=new HashMap<>();
 private record Pending(UUID actor,Set<UUID> healed,int expires){}
 PartyHealing(MagicDiscovery plugin){this.plugin=plugin;Bukkit.getPluginManager().registerEvents(this,plugin);Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,CHANNEL);Bukkit.getMessenger().registerIncomingPluginChannel(plugin,CHANNEL,(channel,player,data)->receive(player,data));}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void splash(PotionSplashEvent e){
  if(!(e.getPotion().getShooter() instanceof Player actor)||!plugin.needed(actor,"life_distribution"))return;
  var heals=e.getPotion().getEffects().stream().filter(effect->effect.getType().equals(PotionEffectType.INSTANT_HEALTH)).toList();if(heals.size()!=1||heals.getFirst().getAmplifier()>4)return;
  var samples=new HashMap<UUID,PotionRecovery.Sample>();
  for(var entity:e.getAffectedEntities())if(entity instanceof Player p&&e.getIntensity(p)>0){double expected=(int)(e.getIntensity(p)*(4<<heals.getFirst().getAmplifier())+0.5);samples.put(p.getUniqueId(),new PotionRecovery.Sample(p.getHealth(),p.getMaxHealth(),expected));}
  if(samples.size()<3||samples.size()>64||!samples.containsKey(actor.getUniqueId())||targets.size()>256||splashTicks.size()>=128)return;
  long observedAt=System.currentTimeMillis();var context=new PotionRecovery(actor.getUniqueId(),samples);splashTicks.put(context,Bukkit.getCurrentTick());
  for(UUID id:samples.keySet()){var previous=targets.put(id,context);if(previous!=null){previous.invalidate(id);context.invalidate(id);}}
  Bukkit.getScheduler().runTask(plugin,()->{
   var health=new HashMap<UUID,Double>();for(UUID id:samples.keySet()){var player=Bukkit.getPlayer(id);if(player!=null)health.put(id,player.getHealth());targets.remove(id,context);effectReady.remove(id);}splashTicks.remove(context);
   var healed=context.actual(health);if(healed.size()>=3&&healed.contains(context.actor))query(context.actor,healed,observedAt);
  });
 }
 @EventHandler(priority=EventPriority.MONITOR) public void instant(io.papermc.paper.event.entity.EntityEffectTickEvent e){UUID id=e.getEntity().getUniqueId();var context=targets.get(id);if(context==null||!e.getType().equals(PotionEffectType.INSTANT_HEALTH))return;if(e.isCancelled()||splashTicks.getOrDefault(context,-1)!=Bukkit.getCurrentTick()||!effectReady.add(id))context.invalidate(id);}
 @EventHandler(priority=EventPriority.MONITOR) public void regain(EntityRegainHealthEvent e){UUID id=e.getEntity().getUniqueId();var context=targets.get(id);if(context!=null)context.regain(id,effectReady.remove(id)&&splashTicks.getOrDefault(context,-1)==Bukkit.getCurrentTick()&&e.getRegainReason()==EntityRegainHealthEvent.RegainReason.MAGIC,e.isCancelled(),e.getAmount());}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageEvent e){var context=targets.get(e.getEntity().getUniqueId());if(context!=null&&e.getFinalDamage()>0)context.invalidate(e.getEntity().getUniqueId());}
 private void query(UUID actor,Set<UUID> healed,long observedAt){var player=Bukkit.getPlayer(actor);if(player==null||pending.size()>=128)return;UUID nonce=UUID.randomUUID();pending.put(nonce,new Pending(actor,healed,Bukkit.getCurrentTick()+40));
  try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(1);out.writeLong(nonce.getMostSignificantBits());out.writeLong(nonce.getLeastSignificantBits());out.writeLong(observedAt);player.sendPluginMessage(plugin,CHANNEL,bytes.toByteArray());}catch(IOException impossible){pending.remove(nonce);}
  Bukkit.getScheduler().runTaskLater(plugin,()->pending.remove(nonce),41);
 }
 private void receive(Player player,byte[] data){if(data.length<18||data.length>1042)return;try{var in=new DataInputStream(new ByteArrayInputStream(data));if(in.readUnsignedByte()!=1)return;UUID nonce=new UUID(in.readLong(),in.readLong());var request=pending.get(nonce);if(request==null||!request.actor().equals(player.getUniqueId())||Bukkit.getCurrentTick()>request.expires())return;int count=in.readUnsignedByte();if(count>64||in.available()!=count*16)return;var members=new HashSet<UUID>();for(int i=0;i<count;i++)if(!members.add(new UUID(in.readLong(),in.readLong())))return;pending.remove(nonce);if(PotionRecovery.qualifies(request.actor(),request.healed(),members)&&plugin.needed(player,"life_distribution"))plugin.signal(request.actor(),"heal.self_and_two_party",1);}catch(IOException ignored){}}
 @EventHandler public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();var context=targets.remove(id);if(context!=null)context.invalidate(id);pending.values().removeIf(p->p.actor().equals(id));}
 public void close(){HandlerList.unregisterAll(this);targets.clear();splashTicks.clear();effectReady.clear();pending.clear();Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin,CHANNEL);Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin,CHANNEL);}
}
