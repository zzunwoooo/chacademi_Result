package school.magiccodex.party;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import school.magiccodex.protocol.TravelLinkProtocol;

/**
 * 지도 이동 서버 옮기기 (2026-10-23). A game server (MagicCodexBridge TravelBridge) has already checked the point, the
 * unlock and its rules and written the ticket to the shared DB; this only connects the player to the named Velocity
 * server. The destination server reads the ticket and places the player — the proxy never handles coordinates and
 * never reports success. On failure it answers the origin server through the player's current connection.
 *
 * travel.properties (Velocity data directory, restart after editing):
 *   travel-servers = Velocity server names a player may be sent to (default school,wild,wild2)
 * Only messages from a trusted game server about its own connected player are accepted; client copies are dropped.
 */
final class TravelRelay {
    static final MinecraftChannelIdentifier LINK = MinecraftChannelIdentifier.from(TravelLinkProtocol.LINK);
    interface Host { boolean enqueue(Runnable task); boolean trusted(String server); boolean blocked(UUID player); }

    private final ProxyServer proxy;
    private final Logger log;
    private final Host host;
    private final Object plugin;
    private final Set<String> targets = new HashSet<>();
    /** Players being connected right now → since when (worker thread). Entries older than a minute are stale (a lost callback). */
    private final Map<UUID, Long> moving = new HashMap<>();

    TravelRelay(ProxyServer proxy, Logger log, Host host, Object plugin) { this.proxy = proxy; this.log = log; this.host = host; this.plugin = plugin; }

    void initialize(Path directory) {
        var path = directory.resolve("travel.properties");
        var p = new Properties();
        try {
            if (Files.exists(path)) try (var r = Files.newBufferedReader(path)) { p.load(r); }
            else { p.setProperty("travel-servers", "school,wild1,wild2"); try (var w = Files.newBufferedWriter(path)) { p.store(w, "지도 이동: 보낼 수 있는 Velocity 서버 이름 (쉼표). 고친 뒤 프록시 재시작."); } }
        } catch (IOException e) { log.warn("travel.properties 읽기 실패 — 기본값 사용", e); }
        for (String s : p.getProperty("travel-servers", "school,wild1,wild2").split(",")) if (!s.isBlank()) targets.add(s.strip());
        for (String s : targets) if (proxy.getServer(s).isEmpty()) log.warn("travel.properties: Velocity 에 없는 서버 " + s + " (그 서버로의 지도 이동은 '서버를 찾을 수 없어요' 로 실패)");
        proxy.getChannelRegistrar().register(LINK);
    }

    @Subscribe public void message(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(LINK)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());   // never forwarded, in either direction
        if (!(event.getSource() instanceof ServerConnection source) || !(event.getTarget() instanceof Player player)) return;
        String from = source.getServerInfo().getName();
        if (!host.trusted(from) || source.getPlayer() != player || player.getCurrentServer().orElse(null) != source) return;
        TravelLinkProtocol.Message m;
        try { m = TravelLinkProtocol.decode(event.getData().clone()); } catch (IllegalArgumentException invalid) { return; }
        if (!(m instanceof TravelLinkProtocol.Transfer t) || !t.player().equals(player.getUniqueId())) return;
        host.enqueue(() -> transfer(player, source, t));
    }

    private void transfer(Player p, ServerConnection origin, TravelLinkProtocol.Transfer t) {
        UUID id = p.getUniqueId();
        if (proxy.getPlayer(id).orElse(null) != p) return;
        if (!targets.contains(t.server())) { fail(p, origin, t, TravelLinkProtocol.NO_SERVER, ""); return; }
        if (origin.getServerInfo().getName().equals(t.server())) { fail(p, origin, t, TravelLinkProtocol.OTHER, "이미 그 서버에 있어요"); return; }
        if (host.blocked(id)) { fail(p, origin, t, TravelLinkProtocol.BUSY, "큐디치 경기·매칭 중에는 서버를 옮길 수 없어요"); return; }
        long now = System.currentTimeMillis();
        Long since = moving.get(id);
        if (since != null && now - since < 60_000) { fail(p, origin, t, TravelLinkProtocol.BUSY, "이미 서버를 옮기는 중이에요"); return; }
        moving.put(id, now);
        Optional<RegisteredServer> target = proxy.getServer(t.server());
        if (target.isEmpty()) { moving.remove(id); fail(p, origin, t, TravelLinkProtocol.NO_SERVER, ""); return; }
        RegisteredServer server = target.get();
        // ping first: a closed server answers at once instead of after the connect timeout; full is refused here too
        server.ping().orTimeout(4, TimeUnit.SECONDS).whenComplete((ping, err) -> host.enqueue(() -> {
            if (err != null || ping == null) { moving.remove(id); fail(p, origin, t, TravelLinkProtocol.OFFLINE, ""); return; }
            var players = ping.getPlayers().orElse(null);
            if (players != null && players.getMax() > 0 && players.getOnline() >= players.getMax()) {
                moving.remove(id); fail(p, origin, t, TravelLinkProtocol.REFUSED, "정원이 가득 찼어요"); return;
            }
            if (proxy.getPlayer(id).orElse(null) != p || p.getCurrentServer().orElse(null) != origin) { moving.remove(id); return; }   // left or moved meanwhile
            p.createConnectionRequest(server).connect().whenComplete((r, error) -> host.enqueue(() -> {
                moving.remove(id);
                if (error == null && r.isSuccessful()) { log.info("지도 이동: " + p.getUsername() + " " + origin.getServerInfo().getName() + " → " + t.server()); return; }
                String why = error != null ? "" : r.getReasonComponent().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).orElse("");
                if (why.length() > 100) why = why.substring(0, 100);
                log.warn("지도 이동 실패: " + p.getUsername() + " → " + t.server() + " " + (error != null ? error : r.getStatus() + " " + why));
                int reason = error != null ? TravelLinkProtocol.OFFLINE : switch (r.getStatus()) {
                    case SERVER_DISCONNECTED -> TravelLinkProtocol.REFUSED;
                    case CONNECTION_IN_PROGRESS, ALREADY_CONNECTED -> TravelLinkProtocol.BUSY;
                    default -> TravelLinkProtocol.OTHER;
                };
                // still on the origin (a refused connect keeps the player there): tell it, so the ticket is FAILED
                var currentServer = p.getCurrentServer().orElse(null);
                if (currentServer != null) fail(p, currentServer, t, reason, why);
            }));
        }));
    }

    private void fail(Player p, ServerConnection to, TravelLinkProtocol.Transfer t, int reason, String detail) {
        try { to.sendPluginMessage(LINK, TravelLinkProtocol.encode(new TravelLinkProtocol.Failed(p.getUniqueId(), t.ticket(), reason, detail))); }
        catch (RuntimeException e) { log.warn("지도 이동 실패 알림을 보내지 못했어요: " + p.getUsername(), e); }
    }

    void disconnect(UUID id) { host.enqueue(() -> moving.remove(id)); }
}
