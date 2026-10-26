package school.magiccodex.paper;
import java.util.*;import java.util.function.Predicate;
import org.bukkit.*;import org.bukkit.entity.Player;import org.bukkit.event.*;import org.bukkit.event.player.PlayerQuitEvent;import org.bukkit.persistence.PersistentDataType;import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.GeneralPresetProtocol;import school.magiccodex.protocol.GeneralPresetModeProtocol;
/** Main-thread gate shared by the Z key and settings button. No client cooldown assertion is accepted. */
final class GeneralPresetBridge implements PluginMessageListener,Listener,AutoCloseable {
 private final MagicCodexBridge plugin;private final ManaService mana;private final Predicate<UUID> casting;private final NamespacedKey key;
 private final Map<UUID,Long> sequence=new HashMap<>(),next=new HashMap<>();
 GeneralPresetBridge(MagicCodexBridge p,ManaService mana,Predicate<UUID> casting){plugin=p;this.mana=mana;this.casting=casting;key=new NamespacedKey(p,"normal_magic_preset");var m=p.getServer().getMessenger();m.registerIncomingPluginChannel(p,GeneralPresetProtocol.REQUEST,this);m.registerOutgoingPluginChannel(p,GeneralPresetProtocol.RESPONSE);m.registerIncomingPluginChannel(p,GeneralPresetModeProtocol.REQUEST,this);m.registerOutgoingPluginChannel(p,GeneralPresetModeProtocol.RESPONSE);p.getServer().getPluginManager().registerEvents(this,p);}
 static boolean combat(UUID id){var plugin=Bukkit.getPluginManager().getPlugin("ChacaKyudichi");if(plugin==null||!plugin.isEnabled())return false;try{return (Boolean)plugin.getClass().getMethod("generalPresetLocked",UUID.class).invoke(plugin,id);}catch(ReflectiveOperationException|RuntimeException e){return true;}}
 public void onPluginMessageReceived(String channel,Player player,byte[] bytes){
  boolean mode=channel.equals(GeneralPresetModeProtocol.REQUEST);if((!mode&&!channel.equals(GeneralPresetProtocol.REQUEST))||!Bukkit.isPrimaryThread())return;
  final long seq;final int target;try{if(mode){var r=GeneralPresetModeProtocol.request(bytes);seq=r.sequence();target=r.target();}else{var r=GeneralPresetProtocol.request(bytes);seq=r.sequence();target=r.target();}}catch(IllegalArgumentException e){return;}
  var id=player.getUniqueId();long now=System.currentTimeMillis();if(seq<=sequence.getOrDefault(id,0L)||now<next.getOrDefault(id,0L))return;sequence.put(id,seq);next.put(id,now+150);
  int stored=player.getPersistentDataContainer().getOrDefault(key,PersistentDataType.INTEGER,1);int selected=mode&&GeneralPresetModeProtocol.valid(stored)?stored:stored==2?2:1;
  boolean ready=plugin.playerStateReady(player)&&!player.isDead()&&player.getGameMode()!=GameMode.SPECTATOR;
  int status=mode?GeneralPresetModePolicy.status(selected,target,ready,combat(id),casting.test(id),mana.account(id).cooldowns,now):GeneralPresetPolicy.status(selected,target,ready,combat(id),casting.test(id),mana.account(id).cooldowns,now);
  if(status==GeneralPresetProtocol.OK&&target!=0){selected=target;if(stored!=selected)player.getPersistentDataContainer().set(key,PersistentDataType.INTEGER,selected);}
  String response=mode?GeneralPresetModeProtocol.RESPONSE:GeneralPresetProtocol.RESPONSE;
  if(player.getListeningPluginChannels().contains(response))player.sendPluginMessage(plugin,response,mode?GeneralPresetModeProtocol.encode(new GeneralPresetModeProtocol.Response(seq,selected,status)):GeneralPresetProtocol.encode(new GeneralPresetProtocol.Response(seq,selected,status)));
 }
 @EventHandler public void quit(PlayerQuitEvent e){sequence.remove(e.getPlayer().getUniqueId());next.remove(e.getPlayer().getUniqueId());}
 public void close(){plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin,GeneralPresetProtocol.REQUEST,this);plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin,GeneralPresetModeProtocol.REQUEST,this);HandlerList.unregisterAll(this);sequence.clear();next.clear();}
}
