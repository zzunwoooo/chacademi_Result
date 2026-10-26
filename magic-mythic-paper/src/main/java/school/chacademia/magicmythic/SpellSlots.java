package school.chacademia.magicmythic;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Which spells a player has in their (up to 6) magic key slots. The server never knew this before: the slots live
 * in the MagicCodex client. Slot-equipped passives (미라지 슈터, 광석 재련, 화염의 정복자, 자동 보급 ...) and
 * 별빛 재현 need it.
 *
 * Sources, by config passive-slots.mode:
 *   slots   (default) - the client reports its slots on channel chacademia:spell_slots
 *                       payload = UTF-8 "id1,id2,...", max 6 ids, sent on join and whenever the key settings change
 *                       (client side is a MagicCodex proposal: docs/handoff/proposals/CLIENT_SPELL_SLOTS_20261014.md)
 *   learned           - every learned spell (permission magic.learned.<id>) counts as equipped (temporary fallback)
 * Admins can force entries for tests: /유저관리 <닉네임> 마법슬롯 <마법...|비우기>
 * Whatever the source, a spell only counts when the player also has its magic.learned.<id> permission.
 */
final class SpellSlots implements Listener, PluginMessageListener {
    static final String CHANNEL = "chacademia:spell_slots";
    static final int LIMIT = 6;
    private final MagicMythicPlugin plugin;
    private final Map<UUID, List<String>> reported = new HashMap<>();
    private final Map<UUID, List<String>> forced = new HashMap<>();

    SpellSlots(MagicMythicPlugin plugin) { this.plugin = plugin; }

    void register() {
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
    }
    void unregister() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
    }

    String mode() { return plugin.getConfig().getString("passive-slots.mode", "slots").toLowerCase(Locale.ROOT); }

    @Override public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || message.length > 1024) return;
        List<String> ids = parse(new String(message, StandardCharsets.UTF_8));
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) reported.put(player.getUniqueId(), ids); });
    }

    /** "a,b,c" -> valid distinct ids, at most LIMIT. Invalid entries are dropped. */
    static List<String> parse(String raw) {
        var out = new ArrayList<String>();
        if (raw == null) return out;
        for (String part : raw.split("[,\\n]")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_-]{1,64}") || out.contains(id)) continue;
            out.add(id);
            if (out.size() >= LIMIT) break;
        }
        return out;
    }

    void force(Player p, List<String> ids) {
        if (ids == null || ids.isEmpty()) forced.remove(p.getUniqueId()); else forced.put(p.getUniqueId(), List.copyOf(ids));
    }

    /** Equipped and learned. */
    boolean equipped(Player p, String id) {
        if (!p.hasPermission("magic.learned." + id)) return false;
        var f = forced.get(p.getUniqueId());
        if (f != null) return f.contains(id);
        if (mode().equals("learned")) return true;
        var r = reported.get(p.getUniqueId());
        return r != null && r.contains(id);
    }

    /** Equipped + learned spells, in slot order (learned mode: unknown order, empty list). */
    List<String> list(Player p) {
        var f = forced.get(p.getUniqueId());
        var src = f != null ? f : reported.getOrDefault(p.getUniqueId(), List.of());
        var out = new ArrayList<String>();
        for (String id : src) if (p.hasPermission("magic.learned." + id)) out.add(id);
        return out;
    }

    boolean known(Player p) { return forced.containsKey(p.getUniqueId()) || reported.containsKey(p.getUniqueId()); }

    @EventHandler public void quit(PlayerQuitEvent e) {
        reported.remove(e.getPlayer().getUniqueId());
        forced.remove(e.getPlayer().getUniqueId());
    }
}
