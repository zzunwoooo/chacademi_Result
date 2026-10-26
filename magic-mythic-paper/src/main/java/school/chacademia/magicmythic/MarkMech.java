package school.chacademia.magicmythic;

import java.util.Locale;
import org.bukkit.entity.LivingEntity;

/**
 * chamark{type=ash_brand|wedge|prison|swords;...}
 *  ash_brand: ticks=60 r=3 cooldown=200 mult  |  wedge: ticks=600 r=2.5 mult knock up
 *  prison: ticks=100  |  swords: count=3 ticks=600 mult (use @self)
 */
final class MarkMech extends Mech {
    MarkMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        Marks.Type type;
        try { type = Marks.Type.valueOf(p.s("type", "ash_brand").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return false; }
        LivingEntity holder;
        if (type == Marks.Type.SWORDS) holder = c.caster();
        else if (c.target() instanceof LivingEntity le && plugin.targets().enemy(c.caster(), le)) holder = le;
        else return false;
        return plugin.marks().add(type, c.caster(), holder, c.dmg(p), p);
    }
}
