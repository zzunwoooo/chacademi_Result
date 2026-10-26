package school.chacademia.housing;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.protocol.HousingRankProtocol;
import school.magiccodex.protocol.HousingRankProtocol.*;

/**
 * 하우징 랭킹 채널 (#51). 창은 /하우징랭킹 이 연다 (OPEN). 요청은 사람마다 0.25초에 한 번, 같은 사람의 앞 요청이 끝나기 전 새 요청은
 * 마지막 것만 처리 (generation). 방 이동은 친구가 아니어도 된다 (오너 결정 2026-10-25, /방구경 은 그대로 친구만).
 */
final class HousingRankServer implements PluginMessageListener, Listener, AutoCloseable {
    private final HousingPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();

    private static final class State { Query latest; boolean busy; long next, done; }

    HousingRankServer(HousingPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, HousingRankProtocol.CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, HousingRankProtocol.CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    boolean hasMod(Player p) { return p.getListeningPluginChannels().contains(HousingRankProtocol.CHANNEL); }

    /** /하우징랭킹: 클라에 창을 열라고 알린다. 모드가 없으면 false. */
    boolean open(Player p) {
        if (!hasMod(p)) return false;
        send(p, new Open(HousingRankProtocol.DAILY));
        return true;
    }

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!HousingRankProtocol.CHANNEL.equals(channel)) return;
        ToServer m;
        try { m = HousingRankProtocol.toServer(bytes); } catch (IllegalArgumentException bad) { return; }
        var s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        switch (m) {
            case Query q -> {
                if (s.latest != null && q.generation() <= s.latest.generation()) return;
                s.latest = q;
                if (!s.busy) begin(p, s);
            }
            case Visit v -> visit(p, v.owner());
        }
    }

    private void begin(Player p, State s) {
        var q = s.latest;
        if (q == null || q.generation() <= s.done || !p.isOnline()) return;
        long now = System.currentTimeMillis();
        if (now < s.next) { Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!s.busy) begin(p, s); }, Math.max(1, (s.next - now + 49) / 50)); return; }
        s.next = now + 250; s.busy = true;
        plugin.store().ranking(q.metric(), q.page(), p.getUniqueId(), p.isOp(), page -> {
            s.busy = false;
            if (!p.isOnline() || states.get(p.getUniqueId()) != s) return;
            if (s.latest != q) { begin(p, s); return; }
            s.done = q.generation();
            if (page == null) { send(p, new Page(q.generation(), q.metric(), 0, 1, List.of(), "순위를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.")); return; }
            var rows = new ArrayList<Row>();
            for (var r : page.rows()) rows.add(new Row(r.rank(), r.owner(), r.name(), r.today(), r.total(), r.hearts(), r.visit()));
            send(p, new Page(q.generation(), q.metric(), page.page(), page.pages(), rows, ""));
        });
    }

    /** 오너 결정 (2026-10-25): 랭킹에서는 친구가 아니어도 방에 갈 수 있다. 방이 실제로 있는지는 방을 읽을 때 확인. */
    private void visit(Player p, UUID owner) { plugin.rooms().openFromRanking(p, owner); }

    private void send(Player p, ToClient m) { if (p.isOnline() && hasMod(p)) p.sendPluginMessage(plugin, HousingRankProtocol.CHANNEL, HousingRankProtocol.encode(m)); }

    @EventHandler public void quit(PlayerQuitEvent e) { states.remove(e.getPlayer().getUniqueId()); }

    @Override public void close() {
        states.clear();
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, HousingRankProtocol.CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, HousingRankProtocol.CHANNEL);
    }
}
