package school.chacademia.kyudichi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** Running matches (one per arena) and the shared tick. */
final class Matches {
    private final KyudichiPlugin plugin;
    private final Map<String, Match> byArena = new TreeMap<>();
    /** Players who were offline when their match ended: restore on next join. */
    private final Map<UUID, Pending> pending = new HashMap<>();
    record Pending(Location to, GameMode mode) {}
    private BukkitTask task;

    Matches(KyudichiPlugin plugin) { this.plugin = plugin; }

    void startTicking() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1); }

    private void tick() {
        for (Match m : List.copyOf(byArena.values())) {
            try { m.tick(); }
            catch (RuntimeException e) {
                plugin.getLogger().severe("큐디치 경기 오류, 경기 무효 처리 (" + m.arena.name + "): " + e);
                e.printStackTrace();
                try { m.end(null, Match.EndReason.VOID); }
                catch (RuntimeException again) {
                    // end() itself failed: drop the match and still tell the proxy (players go home, nothing recorded)
                    plugin.getLogger().severe("큐디치 경기 무효 처리도 실패 (" + m.arena.name + "): " + again);
                    byArena.remove(m.arena.name);
                    try { m.cleanupWorld(); } catch (RuntimeException ignored) { }
                    for (var mp : m.players.values()) {
                        Player p = Bukkit.getPlayer(mp.id);
                        if (p != null && p.isOnline() && mp.online && !mp.left) try { m.restore(p, mp); } catch (RuntimeException ignored) { }
                    }
                    try { plugin.link().matchEnded(m); } catch (RuntimeException ignored) { }
                }
            }
        }
    }

    Match of(UUID player) {
        for (Match m : byArena.values()) if (m.players.containsKey(player)) return m;
        return null;
    }
    Match spectating(UUID player) {
        for (Match m : byArena.values()) if (m.spectators.contains(player)) return m;
        return null;
    }
    Match inArena(String arena) { return byArena.get(arena); }
    Collection<Match> all() { return byArena.values(); }

    /** Starts a match. `teams` = players per team in slot order. Returns an error message or null. */
    String start(Arena arena, Map<Team, List<Player>> teams, Match.Options opt) { return start(arena, teams, opt, Map.of()); }

    /** `absent` = matched players who have not arrived yet: they join as disconnected (60 s reconnect window from the start). */
    String start(Arena arena, Map<Team, List<Player>> teams, Match.Options opt, Map<Team, List<UUID>> absent) {
        if (byArena.containsKey(arena.name)) return "이미 경기 중인 경기장이에요.";
        var problems = arena.problems();
        if (!problems.isEmpty()) return "경기장 설정이 덜 됐어요: " + String.join(", ", problems);
        World w = Bukkit.getWorld(arena.world());
        if (w == null) return "월드 " + arena.world() + " 이(가) 없어요.";
        for (var list : teams.values()) for (Player p : list) {
            if (of(p.getUniqueId()) != null) return plugin.hooks().nick(p) + " 님은 이미 경기 중이에요.";
            if (spectating(p.getUniqueId()) != null) plugin.spectate().leave(p);
        }
        Rules rules = plugin.rules();
        var match = new Match(plugin, arena, rules, opt, w);
        for (Team t : Team.values()) for (Player p : teams.getOrDefault(t, List.of())) {
            var row = plugin.store().cached(p.getUniqueId(), plugin.hooks().nick(p), arena.duel ? RatingStore.DUEL : RatingStore.MODE);
            match.add(p, t, row.rating());
        }
        for (Team t : Team.values()) for (UUID id : absent.getOrDefault(t, List.of())) {
            String nick = plugin.hooks().nick(Bukkit.getOfflinePlayer(id));
            var row = plugin.store().cached(id, nick, arena.duel ? RatingStore.DUEL : RatingStore.MODE);
            match.addAbsent(id, nick, t, row.rating());
        }
        byArena.put(arena.name, match);
        match.start();
        return null;
    }


    void ended(Match m) {
        byArena.remove(m.arena.name);
        for (var mp : m.players.values()) {
            Player p = Bukkit.getPlayer(mp.id);
            if ((p == null || !p.isOnline()) && mp.returnTo != null) pending.put(mp.id, new Pending(mp.returnTo, mp.returnMode));
        }
    }

    /** Join: back into a running match, or restore after a match that ended while offline. */
    void joined(Player p) {
        Match m = of(p.getUniqueId());
        if (m != null && (m.rejoin(p) || m.lateReturn(p))) return;
        var back = pending.remove(p.getUniqueId());
        if (back != null) Bukkit.getScheduler().runTask(plugin, () -> {
            if (back.mode() != null) p.setGameMode(back.mode());
            if (back.to().getWorld() != null) p.teleport(back.to());
        });
    }

    void stopAll(String why) {
        for (Match m : new ArrayList<>(byArena.values())) { plugin.getLogger().info("경기 중지 (" + why + "): " + m.arena.name); m.end(null, Match.EndReason.VOID); }
        if (task != null) task.cancel();
    }
}
