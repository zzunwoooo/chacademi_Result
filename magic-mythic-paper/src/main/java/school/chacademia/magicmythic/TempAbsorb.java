package school.chacademia.magicmythic;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;

/**
 * Temporary hearts (absorption) from a spell. Since 1.20.5 absorption is capped by the MAX_ABSORPTION attribute, so a
 * transient modifier (one per spell key) raises the cap while it lasts. Same key refreshes - never stacks.
 */
final class TempAbsorb {
    private TempAbsorb() {}
    private static final Map<String, Integer> TOKENS = new HashMap<>();
    private static final Map<String, Object[]> LIVE = new HashMap<>(); // token -> {LivingEntity, key}

    static void give(MagicMythicPlugin plugin, LivingEntity e, String spell, double amount, int ticks) {
        var key = new NamespacedKey("chacademiamagicmythic", "absorb_" + spell);
        var attr = e.getAttribute(Attribute.MAX_ABSORPTION);
        if (attr == null || amount <= 0) return;
        double before = attr.getModifier(key) != null ? attr.getModifier(key).getAmount() : 0;
        attr.removeModifier(key);
        attr.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER));
        // refresh: the old grant from this spell is replaced, other absorption stays
        double other = Math.max(0, e.getAbsorptionAmount() - before);
        e.setAbsorptionAmount(Math.min(attr.getValue(), other + amount));
        String token = e.getUniqueId() + ":" + spell;
        int id = TOKENS.merge(token, 1, Integer::sum);
        LIVE.put(token, new Object[]{e, key});
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (Objects.equals(TOKENS.get(token), id)) { TOKENS.remove(token); LIVE.remove(token); clear(e, key); } }, ticks);
    }

    /** Plugin stop: scheduled removals are cancelled, so take every grant back now. */
    static void clearAll() {
        for (Object[] v : LIVE.values()) clear((LivingEntity) v[0], (NamespacedKey) v[1]);
        LIVE.clear(); TOKENS.clear();
    }

    static void clear(LivingEntity e, NamespacedKey key) {
        var attr = e.getAttribute(Attribute.MAX_ABSORPTION);
        if (attr == null || attr.getModifier(key) == null) return;
        double amount = attr.getModifier(key).getAmount();
        attr.removeModifier(key);
        if (e.isValid()) e.setAbsorptionAmount(Math.max(0, Math.min(attr.getValue(), e.getAbsorptionAmount() - amount)));
    }
}
