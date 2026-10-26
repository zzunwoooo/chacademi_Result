package school.chacademia.kyudichi;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.KyudichiProtocol;

/** chacademi:kyudichi plugin channel. Players without the MagicCodex client get the vanilla fallback (boss bar, F-key action bar, chat). */
final class Net implements PluginMessageListener {
    private final KyudichiPlugin plugin;
    Net(KyudichiPlugin plugin) { this.plugin = plugin; }

    void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, KyudichiProtocol.CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, KyudichiProtocol.CHANNEL, this);
    }
    void unregister() {
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, KyudichiProtocol.CHANNEL);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, KyudichiProtocol.CHANNEL, this);
    }

    boolean hasMod(Player p) { return p.getListeningPluginChannels().contains(KyudichiProtocol.CHANNEL); }

    void send(Player p, KyudichiProtocol.Message m) {
        if (!hasMod(p)) return;
        try { p.sendPluginMessage(plugin, KyudichiProtocol.CHANNEL, KyudichiProtocol.encode(m)); }
        catch (IllegalArgumentException e) { plugin.getLogger().warning("큐디치 패킷 인코딩 실패: " + e.getMessage()); }
    }

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!KyudichiProtocol.CHANNEL.equals(channel)) return;
        KyudichiProtocol.Request r;
        try { r = KyudichiProtocol.decodeRequest(bytes); } catch (IllegalArgumentException e) { return; }
        // 관전 화면의 나가기 버튼 (예전 /큐디치 관전 나가기)
        if (r instanceof KyudichiProtocol.SpectateLeave) { if (plugin.spectate().spectating(p.getUniqueId())) plugin.spectate().leave(p); return; }
        Match m = plugin.matches().of(p.getUniqueId());
        if (m == null || !m.playing(p.getUniqueId())) return; // counted as left (back after 60 s): no votes, pings or ready
        switch (r) {
            case KyudichiProtocol.PingRequest ping -> m.ping(p, ping);
            case KyudichiProtocol.Forfeit f -> Link.notice(p, "system", "경기 포기", m.forfeit(p, f.yes()));
            case KyudichiProtocol.SpectateLeave x -> { }
            case KyudichiProtocol.Ready ready -> m.ready(p, ready.ready());
            case KyudichiProtocol.Aim aim -> { MatchPlayer mp = m.get(p.getUniqueId()); if (mp != null) { mp.aim = new org.bukkit.util.Vector(aim.x(), aim.y(), aim.z()); mp.aimAt = m.now; } }
        }
    }
}
