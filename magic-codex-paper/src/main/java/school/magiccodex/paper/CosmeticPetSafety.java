package school.magiccodex.paper;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;

/** Only our tagged cosmetic pets; no scans or changes to ordinary mobs. */
final class CosmeticPetSafety implements Listener {
    static boolean pet(Entity entity) {
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter)
            return pet(shooter);
        return entity != null && entity.getScoreboardTags().contains("chacademia_pet");
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void damage(EntityDamageByEntityEvent event) { if (pet(event.getDamager())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void target(EntityTargetEvent event) { if (pet(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void prime(ExplosionPrimeEvent event) { if (pet(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void explode(EntityExplodeEvent event) { if (pet(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void block(EntityChangeBlockEvent event) { if (pet(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void transform(EntityTransformEvent event) { if (pet(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void projectile(ProjectileLaunchEvent event) { if (pet(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void breed(EntityBreedEvent event) { if (pet(event.getMother()) || pet(event.getFather())) event.setCancelled(true); }
}
