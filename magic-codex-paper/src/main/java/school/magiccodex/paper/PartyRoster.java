package school.magiccodex.paper;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Party membership on the game server (2026-10-15). The party lives in Velocity (chacademi-party-velocity); the proxy
 * pushes each player's party to the server they are on through {@code chacademi:party_roster} (on change, and every
 * 5 s). Velocity drops that channel when a client sends it, so only the proxy can write here.
 *
 * Public API: {@link PartyRoster} is registered as a Bukkit service (Bukkit.getServicesManager().load(PartyRoster.class)).
 * Other plugins (magic-mythic) can find it by reflection. Read from any thread.
 * Wire format (same as chacademi-party-velocity PartyRoster, PartyRosterTest pins it):
 *   byte 1, byte n (0 = no party) [, long×2 party id, byte leader index, n × long×2 member uuid]
 * Without the updated Velocity plugin nobody is ever in a party (artifacts treat everyone as solo).
 */
public final class PartyRoster implements PluginMessageListener, Listener {
    public static final String CHANNEL = "chacademi:party_roster";
    /** A party as the proxy last reported it. members includes the player. */
    public record Party(UUID id, UUID leader, List<UUID> members) {
        public Party { members = List.copyOf(members); }
        public boolean contains(UUID player) { return members.contains(player); }
    }
    private final Map<UUID, Party> parties = new ConcurrentHashMap<>();

    PartyRoster(MagicCodexBridge plugin) {
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getServicesManager().register(PartyRoster.class, this, plugin, ServicePriority.Normal);
    }

    /** The player's party, or empty when solo / unknown. */
    public Optional<Party> party(UUID player) { return Optional.ofNullable(parties.get(player)); }
    /** Party members including the player itself (just the player when solo). */
    public List<UUID> members(UUID player) { var p = parties.get(player); return p == null ? List.of(player) : p.members(); }
    /** True when both are in the same party (false for the same player). */
    public boolean sameParty(UUID a, UUID b) {
        if (a.equals(b)) return false;
        var p = parties.get(a);
        return p != null && p.contains(b);
    }
    /** True when in a party with at least one other member. */
    public boolean inParty(UUID player) { var p = parties.get(player); return p != null && p.members().size() > 1; }
    /** Other party members online on this server. */
    public List<Player> onlineMates(Player player) {
        var p = parties.get(player.getUniqueId());
        if (p == null) return List.of();
        var out = new ArrayList<Player>();
        for (UUID id : p.members()) {
            if (id.equals(player.getUniqueId())) continue;
            var other = Bukkit.getPlayer(id);
            if (other != null && other.isOnline()) out.add(other);
        }
        return out;
    }

    @Override public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!CHANNEL.equals(channel)) return;
        try {
            var party = decode(data);
            if (party == null || !party.contains(player.getUniqueId())) parties.remove(player.getUniqueId());
            else parties.put(player.getUniqueId(), party);
        } catch (IOException | RuntimeException ignored) { /* malformed: keep the last good value */ }
    }

    static Party decode(byte[] data) throws IOException {
        if (data.length < 2 || data.length > 2 + 17 + 64 * 16) throw new IOException("size");
        try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readUnsignedByte() != 1) throw new IOException("version");
            int n = in.readUnsignedByte();
            if (n == 0) return null;
            if (n > 64) throw new IOException("members");
            UUID id = new UUID(in.readLong(), in.readLong());
            int leader = in.readUnsignedByte();
            var members = new ArrayList<UUID>(n);
            for (int i = 0; i < n; i++) members.add(new UUID(in.readLong(), in.readLong()));
            if (leader >= n || in.available() != 0) throw new IOException("leader/trailing");
            return new Party(id, members.get(leader), members);
        }
    }

    @EventHandler public void quit(PlayerQuitEvent e) { parties.remove(e.getPlayer().getUniqueId()); }
}
