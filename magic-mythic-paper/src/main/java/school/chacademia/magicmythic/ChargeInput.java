package school.chacademia.magicmythic;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * 338 화염 투창 등 "누르고 있기 충전" 마법 (2026-10-25). The MagicCodex client charges while the player holds left click in the
 * aim preview and, right before it sends the cast, reports `<spell id>;<charge 0..1>` (UTF-8) on chacademia:spell_charge.
 * The mechanic takes it with {@link #take}: a report older than 2 s, for another spell, or missing (old client) = -1.
 */
final class ChargeInput implements PluginMessageListener {
    static final String CHANNEL = "chacademia:spell_charge";
    private static final Map<UUID, Object[]> last = new HashMap<>(); // player -> {spell, charge, tick}
    private final MagicMythicPlugin plugin;

    ChargeInput(MagicMythicPlugin plugin) { this.plugin = plugin; }
    void register() { Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this); }
    void unregister() { Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this); last.clear(); }

    @Override public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || message.length > 128) return;
        String[] f = new String(message, StandardCharsets.UTF_8).trim().split(";");
        if (f.length != 2 || !f[0].matches("[a-z0-9_]{1,64}")) return;
        double charge;
        try { charge = Double.parseDouble(f[1]); } catch (NumberFormatException e) { return; }
        if (!Double.isFinite(charge)) return;
        Object[] v = {f[0], Math.clamp(charge, 0, 1), (long) Bukkit.getCurrentTick()};
        UUID id = player.getUniqueId();
        if (Bukkit.isPrimaryThread()) last.put(id, v); else Bukkit.getScheduler().runTask(plugin, () -> last.put(id, v));
    }

    /** Charge 0..1 reported for this spell in the last 2 s (consumed), or -1 when there is none. */
    static double take(Player p, String spell) {
        Object[] v = last.remove(p.getUniqueId());
        if (v == null || !spell.equals(v[0]) || Bukkit.getCurrentTick() - (long) v[2] > 40) return -1;
        return (double) v[1];
    }
}
