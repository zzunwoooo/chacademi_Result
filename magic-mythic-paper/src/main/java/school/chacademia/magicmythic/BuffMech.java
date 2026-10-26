package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * chabuff{type=...} @self - timed attribute changes on the caster (transient modifiers: never saved, removed when the
 * time ends, on quit and on plugin stop).
 *  giant ticks=300 scale=0.5 reach=1.0 damage=0.3 - body size x(1+scale), melee reach +reach blocks, melee damage x(1+damage) (little_giant)
 *  root  ticks=160                                 - cannot walk or jump (forest_throne keeps the caster on the throne)
 */
final class BuffMech extends Mech {
    BuffMech(MagicMythicPlugin plugin, Params p) { super(plugin, p); }

    private static final Map<UUID, Map<String, Integer>> ACTIVE = new HashMap<>();
    private static int tokens = 0;

    @Override boolean run(Ctx c) {
        LivingEntity e = c.caster();
        if (!plugin.protection().pair(e, e.getLocation())) return false;
        int ticks = Math.clamp(p.i("ticks", 200), 1, 20 * 600);
        String type = p.s("type", "").toLowerCase(Locale.ROOT);
        var mods = new ArrayList<Object[]>(); // {Attribute, amount, Operation}
        switch (type) {
            case "giant" -> {
                mods.add(new Object[]{Attribute.SCALE, p.d("scale", 0.5), AttributeModifier.Operation.ADD_SCALAR});
                mods.add(new Object[]{Attribute.ENTITY_INTERACTION_RANGE, p.d("reach", 1.0), AttributeModifier.Operation.ADD_NUMBER});
                mods.add(new Object[]{Attribute.ATTACK_DAMAGE, p.d("damage", 0.3), AttributeModifier.Operation.ADD_SCALAR});
            }
            case "root" -> {
                mods.add(new Object[]{Attribute.MOVEMENT_SPEED, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1});
                mods.add(new Object[]{Attribute.JUMP_STRENGTH, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1});
                e.setVelocity(new org.bukkit.util.Vector(0, Math.min(0, e.getVelocity().getY()), 0));
            }
            default -> { return false; }
        }
        apply(plugin, e, type, mods, ticks);
        return true;
    }

    private static NamespacedKey key(String type, Attribute a) {
        return new NamespacedKey("chacademiamagicmythic", "buff_" + type + "_" + a.getKey().getKey());
    }

    static void apply(MagicMythicPlugin plugin, LivingEntity e, String type, List<Object[]> mods, int ticks) {
        for (Object[] m : mods) {
            var attr = e.getAttribute((Attribute) m[0]);
            if (attr == null) continue;
            var k = key(type, (Attribute) m[0]);
            attr.removeModifier(k);
            attr.addTransientModifier(new AttributeModifier(k, (double) m[1], (AttributeModifier.Operation) m[2]));
        }
        int token = ++tokens;
        ACTIVE.computeIfAbsent(e.getUniqueId(), x -> new HashMap<>()).put(type, token);
        UUID id = e.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            var mine = ACTIVE.get(id);
            if (mine == null || !Objects.equals(mine.get(type), token)) return; // refreshed by a newer cast
            mine.remove(type);
            if (mine.isEmpty()) ACTIVE.remove(id);
            if (Bukkit.getEntity(id) instanceof LivingEntity le) clear(le, type);
        }, ticks);
    }

    static void clear(LivingEntity e, String type) {
        for (Attribute a : List.of(Attribute.SCALE, Attribute.ENTITY_INTERACTION_RANGE, Attribute.BLOCK_INTERACTION_RANGE, Attribute.ATTACK_DAMAGE, Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH)) {
            var attr = e.getAttribute(a);
            if (attr != null) attr.removeModifier(key(type, a));
        }
    }

    static boolean has(LivingEntity e, String type) {
        var mine = ACTIVE.get(e.getUniqueId());
        return mine != null && mine.containsKey(type);
    }

    static void clearAll(Player p) {
        var mine = ACTIVE.remove(p.getUniqueId());
        if (mine != null) for (String type : mine.keySet()) clear(p, type);
    }
    static void clearAll() {
        for (UUID id : new ArrayList<>(ACTIVE.keySet())) {
            var mine = ACTIVE.remove(id);
            if (Bukkit.getEntity(id) instanceof LivingEntity le) for (String type : mine.keySet()) clear(le, type);
        }
    }
}
