package school.chacademia.magicmythic;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * C03 — the single place that decides who is a friend (owner decision 7, 2026-10-21).
 *  - During a 큐디치/결투 match: the match team only ({@link MagicAllyEvent}, answered by ChacaKyudichi). Party ignored.
 *  - Otherwise: members of the same Velocity party (MagicCodexBridge {@code PartyRoster} service, found by reflection).
 *  - Nobody else is an ally anywhere: no heals/buffs/shields/cleanse/health share for non-party players.
 * Friends never take damage, CC, element marks, knockback or pulls from each other ({@link Targets#enemy}).
 * Damage to non-allied players still follows WorldGuard PvP ({@link MagicProtection#harm}).
 */
final class AllyResolver implements Listener {
    private Object roster; private Method sameParty; private boolean resolved;

    /** Pure rule: a match answer wins; otherwise the party. */
    static boolean allied(Boolean match, BooleanSupplier sameParty) {
        return match != null ? match : sameParty.getAsBoolean();
    }

    /** Same side (true for the same player). Main thread. */
    boolean allied(Player a, Player b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        UUID ia = a.getUniqueId(), ib = b.getUniqueId();
        if (ia == null || ib == null) return false;
        return allied(MagicAllyEvent.ask(a, b), () -> sameParty(ia, ib));
    }

    private boolean sameParty(UUID a, UUID b) {
        if (!resolved) resolve();
        if (roster == null) return false;
        try { return (boolean) sameParty.invoke(roster, a, b); }
        catch (ReflectiveOperationException | RuntimeException e) { roster = null; sameParty = null; resolved = false; return false; }
    }

    private void resolve() {
        resolved = true; roster = null; sameParty = null;
        try {
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (!c.getName().equals("school.magiccodex.paper.PartyRoster")) continue;
                var reg = Bukkit.getServicesManager().getRegistration(c);
                if (reg == null) break;
                sameParty = c.getMethod("sameParty", UUID.class, UUID.class);
                roster = reg.getProvider();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { roster = null; sameParty = null; }
    }

    /** MagicCodexBridge (re)loaded: look the party service up again on the next question. */
    @EventHandler public void enable(PluginEnableEvent e) { resolved = false; }
    @EventHandler public void disable(PluginDisableEvent e) { resolved = false; roster = null; sameParty = null; }
}
