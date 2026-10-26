package school.chacademia.kyudichi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * 관전: vanilla spectator mode (invisible, no collision, no interaction), kept inside the arena bounds, own chat,
 * left-click a player to see through their eyes (vanilla). Limit per match {@link #LIMIT} (커스텀 경기는 제한 없음).
 */
final class Spectate {
    static final int LIMIT = 20;
    private final KyudichiPlugin plugin;
    private final Map<UUID, Matches.Pending> back = new HashMap<>();
    Spectate(KyudichiPlugin plugin) { this.plugin = plugin; }

    String enter(Player p, Match m) {
        if (m.players.containsKey(p.getUniqueId())) return "자기 경기는 관전할 수 없어요.";
        if (plugin.matches().of(p.getUniqueId()) != null) return "경기 중에는 관전할 수 없어요.";
        Match cur = plugin.matches().spectating(p.getUniqueId());
        if (cur == m) return "이미 관전 중이에요.";
        if (cur != null) leave(p);
        // #62: 커스텀 경기만 관전 인원 제한 없음 (일반 경기는 그대로 LIMIT)
        if (!m.custom() && m.spectators.size() >= LIMIT) return "관전 인원이 가득 찼어요 (" + LIMIT + "명).";
        back.put(p.getUniqueId(), new Matches.Pending(p.getLocation(), p.getGameMode()));
        m.spectators.add(p.getUniqueId());
        p.setGameMode(GameMode.SPECTATOR);
        Vector c = m.arena.bounds.center();
        p.teleport(new Location(m.world, c.getX(), Math.min(m.arena.bounds.maxY() - 2, c.getY() + 8), c.getZ()));
        p.showBossBar(m.bar);
        plugin.net().send(p, m.matchMessage(2));
        m.sendShrines();
        Link.notice(p, "system", "관전 중", "선수를 좌클릭하면 그 사람 시점, Shift로 나오기 · 관전 화면의 나가기 버튼으로 끝내기");
        return null;
    }

    void leave(Player p) {
        Match m = plugin.matches().spectating(p.getUniqueId());
        if (m != null) { m.spectators.remove(p.getUniqueId()); p.hideBossBar(m.bar); }
        var b = back.remove(p.getUniqueId());
        if (b == null) return;
        p.setSpectatorTarget(null);
        p.setGameMode(b.mode() == null || b.mode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : b.mode());
        if (b.to().getWorld() != null) p.teleport(b.to());
        plugin.link().done(p.getUniqueId());
    }

    boolean spectating(UUID id) { return back.containsKey(id); }

    /** Every few ticks: keep free-flying spectators inside the arena. */
    void tick() {
        for (Match m : plugin.matches().all()) for (UUID id : m.spectators) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.getSpectatorTarget() != null) continue;
            Vector in = m.arena.bounds.clampInside(p.getLocation().toVector(), 0.5);
            if (in != null) { Location l = p.getLocation(); p.teleport(new Location(m.world, in.getX(), in.getY(), in.getZ(), l.getYaw(), l.getPitch())); }
        }
    }
}
