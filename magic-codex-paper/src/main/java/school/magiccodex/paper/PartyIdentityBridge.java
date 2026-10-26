package school.magiccodex.paper;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;

/** Sends only server-owned display names over the player's active backend connection. */
final class PartyIdentityBridge implements AutoCloseable {
    private static final String CHANNEL="chacademi:party_identity";
    private final MagicCodexBridge plugin;
    private final BukkitTask task;
    PartyIdentityBridge(MagicCodexBridge plugin){
        this.plugin=plugin;Bukkit.getMessenger().registerOutgoingPluginChannel(plugin,CHANNEL);
        task=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            for(var player:Bukkit.getOnlinePlayers())try{
                player.sendPluginMessage(plugin,CHANNEL,encodeIdentity(plugin.names().name(player),plugin.questHouse(player),plugin.names().registered(player.getUniqueId())));
            }catch(IOException impossible){throw new IllegalStateException(impossible);}
        },20,40);
    }
    static byte[] encodeIdentity(String nickname,int house)throws IOException {
        if(house< -1||house>3||nickname==null||nickname.isBlank()||nickname.length()>32||nickname.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c==0xA7))throw new IllegalArgumentException("Invalid identity");
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeByte(2);out.writeUTF(nickname);out.writeByte(house);return bytes.toByteArray();
    }
    static byte[] encodeIdentity(String display,int house,String registered)throws IOException{
        byte[] legacy=encodeIdentity(display,house);if(registered==null||registered.length()>32||registered.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c==0xA7))throw new IllegalArgumentException("Invalid original nickname");
        var bytes=new ByteArrayOutputStream();bytes.write(legacy);var out=new DataOutputStream(bytes);out.writeUTF(registered);byte[] result=bytes.toByteArray();result[0]=3;return result;
    }
    @Override public void close(){task.cancel();Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin,CHANNEL);}
}
