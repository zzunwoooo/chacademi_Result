package school.chacademia.wildlife;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * 커스텀 야생 몹은 대부분 미몹 Type: HUSK라 근접으로 때리면 바닐라 허스크처럼 허기를 건다.
 * 차카데미 몹(chacademia_custom 태그)이 때린 그 틱에 들어오는 허기(원인 ATTACK)만 막는다.
 * 다른 허기(바닐라 허스크, 썩은 고기, 마법 등)는 그대로.
 */
final class NoHuskHunger implements Listener {
    static final String TAG = "chacademia_custom";
    private final Map<UUID, Integer> hitTick = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (damager instanceof Projectile p && p.getShooter() instanceof Entity shooter) damager = shooter;
        if (!(damager instanceof Husk) || !damager.getScoreboardTags().contains(TAG)) return;
        if (hitTick.size() > 512) hitTick.clear();
        hitTick.put(e.getEntity().getUniqueId(), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void effect(EntityPotionEffectEvent e) {
        if (e.getCause() != EntityPotionEffectEvent.Cause.ATTACK || e.getNewEffect() == null
                || !e.getNewEffect().getType().equals(PotionEffectType.HUNGER)) return;
        Integer tick = hitTick.remove(e.getEntity().getUniqueId());
        if (tick != null && Bukkit.getCurrentTick() - tick <= 1) e.setCancelled(true);
    }
}
