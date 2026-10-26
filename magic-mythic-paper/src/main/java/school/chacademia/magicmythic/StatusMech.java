package school.chacademia.magicmythic;

import org.bukkit.entity.LivingEntity;
import school.magiccodex.protocol.StatusProtocol.Status;

/**
 * chastatus{type=stun;ticks=40;power=0} - puts a status effect ({@link StatusEffects}) on the targeted enemy.
 * Not used by any spell yet (owner 2026-10-19: effects only, spells are connected later); ready for mob spells.
 * type: stun root disarm slow burn recovery_seal magic_weakness gravity movement_lock poison
 *       physical_weakness blindness potion_seal mana_burn sleep fear (Korean names work too)
 */
final class StatusMech extends Mech {
    StatusMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    @Override boolean run(Ctx c) {
        Status s = Status.of(p.s("type", ""));
        if (s == null || !(c.target() instanceof LivingEntity t) || !plugin.targets().enemy(c.caster(), t)) return false;
        if (!plugin.targets().hurt(c.caster(), t, 0)) return false; // same protection probe as marks
        return plugin.status().apply(c.caster(), t, s, p.i("ticks", 60), p.d("power", 0));
    }
}
