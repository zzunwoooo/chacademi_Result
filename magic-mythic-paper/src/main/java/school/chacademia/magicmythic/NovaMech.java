package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.entity.Player;

/**
 * chanova{type=<spell id>;pack=<vfx pack>;...} — the 325~387 spells (source of truth: tools/nova_spells.py + tools/nova/*.py).
 * Each tier lives in its own class (NovaBasic 325~335, NovaMid 336~345, NovaMid2 346~355, NovaHigh 356~367, NovaUlt 368~373, NovaLife / NovaLife2 374~387)
 * and registers `type -> Impl`. Helpers (damage, status, VFX events, travelling shots, timers) are in {@link Nova}.
 * Player spells only (the Bridge casts them); a mob caster does nothing.
 */
final class NovaMech extends Mech {
    interface Impl { boolean run(Nova n); }
    static final Map<String, Impl> TYPES = new HashMap<>();
    private static boolean registered;

    /** Called once from onEnable: fills TYPES and lets each tier register its listeners / state. */
    static void enable(MagicMythicPlugin plugin) {
        if (!registered) {
            registered = true;
            NovaBasic.register(TYPES); NovaMid.register(TYPES); NovaMid2.register(TYPES); NovaHigh.register(TYPES); NovaUlt.register(TYPES); NovaLife.register(TYPES); NovaLife2.register(TYPES); NovaLife3.register(TYPES);
        }
        NovaBasic.enable(plugin); NovaMid.enable(plugin); NovaMid2.enable(plugin); NovaHigh.enable(plugin); NovaUlt.enable(plugin); NovaLife.enable(plugin); NovaLife2.enable(plugin); NovaLife3.enable(plugin);
    }

    NovaMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        if (!(c.caster() instanceof Player pl)) return false;
        Impl impl = TYPES.get(p.s("type", "").toLowerCase(Locale.ROOT));
        return impl != null && impl.run(new Nova(plugin, p, c, pl));
    }
}
