package school.chacademia.housing.presence;
import com.google.inject.Inject;import com.velocitypowered.api.proxy.*;import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;import com.velocitypowered.api.event.*;import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;import com.velocitypowered.api.event.connection.PluginMessageEvent;import java.io.*;import java.util.*;
/** Read-only online roster. No commands, database, chat, account creation or existing plugin changes. */
public final class HousingPresencePlugin{
 static final MinecraftChannelIdentifier CHANNEL=MinecraftChannelIdentifier.from("chacademi:housing_roster");static final Set<String> SERVERS=Set.of("school","wild","arena");final ProxyServer proxy;
 @Inject public HousingPresencePlugin(ProxyServer proxy){this.proxy=proxy;}
 @Subscribe public void initialize(ProxyInitializeEvent e){proxy.getChannelRegistrar().register(CHANNEL);}
 @Subscribe public void message(PluginMessageEvent e){if(!e.getIdentifier().equals(CHANNEL))return;e.setResult(PluginMessageEvent.ForwardResult.handled());if(!(e.getSource() instanceof ServerConnection source)||!(e.getTarget() instanceof Player player)||source.getPlayer()!=player||player.getCurrentServer().orElse(null)!=source||!SERVERS.contains(source.getServerInfo().getName()))return;
  try(var in=new DataInputStream(new ByteArrayInputStream(e.getData()))){if(in.readUnsignedByte()!=1)return;var nonce=new UUID(in.readLong(),in.readLong());if(in.available()!=0)return;
   var online=proxy.getAllPlayers().stream().filter(p->p.getCurrentServer().map(s->SERVERS.contains(s.getServerInfo().getName())).orElse(false)).map(Player::getUniqueId).distinct().sorted().toList();if(online.size()>50000)return;
   for(int offset=0;offset<Math.max(1,online.size());offset+=512){if(player.getCurrentServer().orElse(null)!=source)return;int end=Math.min(offset+512,online.size());var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(1);out.writeLong(nonce.getMostSignificantBits());out.writeLong(nonce.getLeastSignificantBits());out.writeBoolean(end==online.size());out.writeShort(end-offset);for(int i=offset;i<end;i++){var id=online.get(i);out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}source.sendPluginMessage(CHANNEL,bytes.toByteArray());}
  }catch(IOException|RuntimeException ignored){}
 }
}
