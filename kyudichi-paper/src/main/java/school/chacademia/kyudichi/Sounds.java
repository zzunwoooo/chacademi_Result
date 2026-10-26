package school.chacademia.kyudichi;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * 큐디치 sounds (owner 2026-10-17). MagicCodex clients play the mod's own sounds (assets magiccodex:kyudichi.*:
 * whistle, goal horn + crowd, snitch chime, bludger hit…); players without the mod hear a close vanilla sound instead.
 */
final class Sounds {
    private Sounds() {}

    /** Everyone in the match and its spectators, at their own position (not positional). */
    static void all(Match m, String key, Sound fallback, float volume, float pitch) {
        for (Player p : audience(m)) play(m, p, p.getLocation(), key, fallback, volume, pitch);
    }

    /** Heard around a point (players and spectators within 64 blocks). */
    static void at(Match m, Location at, String key, Sound fallback, float volume, float pitch) {
        for (Player p : audience(m)) if (p.getWorld().equals(at.getWorld()) && p.getLocation().distanceSquared(at) <= 64 * 64)
            play(m, p, at, key, fallback, volume, pitch);
    }

    static void whistle(Match m) { all(m, "whistle_short", Sound.BLOCK_NOTE_BLOCK_FLUTE, 1, 2f); }

    private static void play(Match m, Player p, Location at, String key, Sound fallback, float volume, float pitch) {
        if (m.plugin.net().hasMod(p)) p.playSound(at, "magiccodex:kyudichi." + key, SoundCategory.PLAYERS, volume, pitch);
        else if (fallback != null) p.playSound(at, fallback, SoundCategory.PLAYERS, volume, pitch);
    }

    private static List<Player> audience(Match m) {
        var out = new ArrayList<>(m.onlinePlayers());
        for (var id : m.spectators) { Player p = Bukkit.getPlayer(id); if (p != null && !out.contains(p)) out.add(p); }
        return out;
    }
}
