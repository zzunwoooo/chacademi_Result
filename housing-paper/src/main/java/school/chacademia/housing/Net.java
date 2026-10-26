package school.chacademia.housing;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.HousingProtocol;

/** chacademi:housing 플러그인 채널. MagicCodex 클라이언트가 없으면 창을 열 수 없다 (채팅으로 안내). */
final class Net implements PluginMessageListener {
    private final HousingPlugin plugin;
    Net(HousingPlugin plugin) { this.plugin = plugin; }

    void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, HousingProtocol.CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, HousingProtocol.CHANNEL, this);
    }
    void unregister() {
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, HousingProtocol.CHANNEL);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, HousingProtocol.CHANNEL, this);
    }

    boolean hasMod(Player p) { return p.getListeningPluginChannels().contains(HousingProtocol.CHANNEL); }

    void send(Player p, HousingProtocol.Message m) {
        if (!p.isOnline() || !hasMod(p)) return;
        try { p.sendPluginMessage(plugin, HousingProtocol.CHANNEL, HousingProtocol.encode(m)); }
        catch (IllegalArgumentException e) { plugin.getLogger().warning("하우징 패킷 인코딩 실패: " + e.getMessage()); }
    }

    void result(Player p, boolean ok, String message) { send(p, new HousingProtocol.Result(ok, message)); }

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!HousingProtocol.CHANNEL.equals(channel)) return;
        HousingProtocol.Request r;
        try { r = HousingProtocol.decodeRequest(bytes); } catch (IllegalArgumentException e) { return; }
        plugin.rooms().handle(p, r);
    }
}
