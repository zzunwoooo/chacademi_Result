package school.chacademia.magicmythic;

import java.util.Locale;

/**
 * chaguard{type=lethal|reflect|block|deflect|thorns|anchor|counter|ward|invuln|homing;ticks=..;r=4;reduce=0.5;heal=6;push=1.2;extra=0} @self
 *  thorns: damage = Bridge damage x mult. ward: per=absorption per projectile (default Bridge damage), reduce=cap, extra=shield ticks.
 *  counter: extra = stun ticks.
 */
final class GuardMech extends Mech {
    GuardMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        Guards.Type type;
        try { type = Guards.Type.valueOf(p.s("type", "lethal").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return false; }
        var guards = plugin.guards();
        if(!plugin.protection().pair(c.caster(),c.caster().getLocation()))return false;
        double damage = type == Guards.Type.WARD ? p.d("per", c.dmg(p)) : c.dmg(p);
        guards.add(c.caster(), new Guards.Guard(type, guards.now() + Math.clamp(p.i("ticks", 100), 1, 20 * 3600),
            p.d("r", 4), p.d("reduce", 0.5), p.d("heal", 6), p.d("push", 1.2), damage, p.i("extra", 0), null, p.s("spell", "")));
        return true;
    }
}
