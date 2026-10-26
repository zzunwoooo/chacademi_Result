package school.chacademia.gimmick;

import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import school.chacademia.magicmythic.MagicImpactEvent;

/** Registered only when ChAcademiaMagicMythic is enabled: spell hits light braziers, wake monuments, spin turbines (Signals.magic). */
final class MagicHook implements Listener {
    private final GimmickPlugin plugin;
    private final Map<String, Long> lastHit = new HashMap<>(); // gimmick id -> tick

    private final SpellActions actions;
    MagicHook(GimmickPlugin plugin) {
        this.plugin = plugin;
        this.actions = new SpellActions(plugin);
        plugin.getServer().getPluginManager().registerEvents(actions, plugin);
        Inputs.extraStanders = school.chacademia.magicmythic.MagicMythicApi::shadowDoubles;
    }
    SpellActions actions() { return actions; }

    /** A HIT is consumed (cancelled) only when a gimmick actually reacted. BOLT/ZONE are not decided here. */
    static boolean consumesHit(MagicImpactEvent.Kind kind, boolean fired) { return kind == MagicImpactEvent.Kind.HIT && fired; }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void impact(MagicImpactEvent e) {
        var hits = plugin.world().near(e.getLocation(), Math.max(0.3, e.getRadius()));
        if (hits.isEmpty()) return;
        long now = plugin.signals().now();
        long gap = plugin.getConfig().getLong("magic-hit-gap-ticks", 10);
        if (lastHit.size() > 4096) lastHit.values().removeIf(t -> t < now - 200);
        Player who = e.getCaster() instanceof Player p ? p : null;
        String element = e.getElement() == null ? "" : e.getElement().toLowerCase(Locale.ROOT);
        for (Gimmick g : hits) {
            if(g.attached()||who==null||!plugin.protection().pair(who,g.location()))continue;
            if (e.getKind() == MagicImpactEvent.Kind.BOLT && plugin.getConfig().getBoolean("magic-blocks-bolts", true)) e.setCancelled(true);
            Long last = lastHit.get(g.id);
            if (last != null && now - last < gap) continue;
            lastHit.put(g.id, now);
            boolean fired = plugin.signals().magic(g, element);
            // HIT contract (MagicImpactEvent): cancel = "this hit was used by a gimmick". Only when a gimmick really
            // fired (after the gap check); mob damage of the same hit is never affected.
            if (consumesHit(e.getKind(), fired)) e.setCancelled(true);
            if (plugin.debug()) plugin.getLogger().info("[마법] " + g.id + " <- " + element + " (" + e.getKind() + ")");
        }
    }
}
