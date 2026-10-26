package school.magiccodex.paper;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * One place that re-applies every derived stat when a player arrives on this server (school/wild switch, relog) or
 * when something asks for it (2026-10-15).
 *
 * Why: bonuses (accessories, wands, artifacts, ...) are never saved as numbers; each server rebuilds them from the
 * shared state (codex_player_state). Until they are rebuilt, the game and ManaAccount clamp current health/mana to
 * the bare maximum, so a player with "+30% max health" or "+50 max mana" lost the extra on every server switch.
 *
 * Flow on arrival (PlayerStateBridge, after the shared row is applied and the player is marked ready):
 *   1. every registered stat source runs in order ({@link #register}) - they set their keyed modifiers,
 *   2. current mana and health go back to the saved values (never above the new maximum; health only if the player
 *      was not hurt while the state was loading),
 *   3. after-arrival hooks run (artifact kept item, client resync, ...).
 * New stat features only need {@link #register} (and keyed, idempotent modifiers) - nothing else.
 * {@link #refresh} is the same step 1 for "something changed" (equip, unequip, level up...).
 */
public final class PlayerStats implements Listener {
    /** What the shared state remembered from the previous server. health null = unknown (dead, old row). */
    record Arrival(double mana, Double health) {}

    private final MagicCodexBridge plugin;
    private final ManaService mana;
    private final Map<String, Consumer<Player>> sources = new LinkedHashMap<>();
    private final Map<String, Consumer<Player>> after = new LinkedHashMap<>();
    /** Health when the player joined this server (to tell "hurt while loading" from "clamped on arrival"). */
    private final Map<UUID, Double> joinHealth = new HashMap<>();

    PlayerStats(MagicCodexBridge plugin, ManaService mana) {
        this.plugin = plugin; this.mana = mana;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** A derived stat source (keyed modifiers). Runs on arrival and on {@link #refresh}. Same name replaces. */
    public void register(String name, Consumer<Player> source) { sources.put(name, source); }
    /** Runs once after arrival, after stats and current values are back. Same name replaces. */
    public void afterArrival(String name, Consumer<Player> hook) { after.put(name, hook); }

    /** Re-applies every stat source now (server thread). */
    public void refresh(Player p) {
        if (!p.isOnline()) return;
        for (var e : sources.entrySet()) run(e.getKey(), e.getValue(), p);
    }

    /** Arrival on this server: rebuild all bonuses, then give back the saved current mana/health. */
    void arrived(Player p, Arrival saved) {
        refresh(p);
        UUID id = p.getUniqueId();
        mana.snapshot(id).ifPresent(s -> {
            if (saved.mana() > s.current()) mana.setCurrent(id, Math.min(saved.mana(), s.maximum()));
        });
        Double joined = joinHealth.remove(id);
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        boolean untouched = joined == null || p.getHealth() >= joined - 0.01;
        if (saved.health() != null && max != null && !p.isDead() && untouched && saved.health() > p.getHealth())
            p.setHealth(Math.min(max.getValue(), saved.health()));
        for (var e : after.entrySet()) run(e.getKey(), e.getValue(), p);
    }

    private void run(String name, Consumer<Player> task, Player p) {
        try { task.accept(p); }
        catch (RuntimeException e) { plugin.getLogger().warning("능력치 갱신 실패 (" + name + "): " + e); }
    }

    @EventHandler(priority = EventPriority.LOWEST) public void join(PlayerJoinEvent e) { joinHealth.put(e.getPlayer().getUniqueId(), e.getPlayer().getHealth()); }
    @EventHandler public void quit(PlayerQuitEvent e) { joinHealth.remove(e.getPlayer().getUniqueId()); }
}
