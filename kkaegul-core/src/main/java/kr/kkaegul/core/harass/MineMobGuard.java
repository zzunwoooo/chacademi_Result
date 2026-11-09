package kr.kkaegul.core.harass;

import kr.kkaegul.core.KkaegulCore;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 보물광산 안에서 개인 괴롭히기로 소환된 몬스터 = 부른 사람 전용.
 *  - 그 사람만 노리고, 그 사람에게만 피해를 줌 (화살·폭발 포함)
 *  - 다른 사람이 때려도 피해가 들어가지 않음
 *  - 그 사람이 광산을 나가거나 접속을 끊으면 사라짐
 */
public final class MineMobGuard implements Listener {
    private final KkaegulCore plugin;
    private final NamespacedKey key;
    private final Map<UUID, List<UUID>> spawned = new ConcurrentHashMap<>();

    public MineMobGuard(KkaegulCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "mine_harass_owner");
    }

    public void tag(Entity e, Player owner) {
        e.getPersistentDataContainer().set(key, PersistentDataType.STRING, owner.getUniqueId().toString());
        e.setPersistent(false);
        spawned.computeIfAbsent(owner.getUniqueId(), k -> new CopyOnWriteArrayList<>()).add(e.getUniqueId());
    }

    private UUID owner(Entity e) {
        if (e == null) return null;
        String s = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    /** 피해를 준 실제 주체 (화살 → 쏜 엔티티, 점화된 TNT → 붙인 엔티티) */
    private static Entity source(Entity damager) {
        if (damager instanceof Projectile pr) {
            ProjectileSource s = pr.getShooter();
            return s instanceof Entity en ? en : damager;
        }
        if (damager instanceof TNTPrimed t && t.getSource() != null) return t.getSource();
        return damager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        UUID o = owner(e.getEntity());
        if (o == null || !(e.getTarget() instanceof Player t) || t.getUniqueId().equals(o)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity src = source(e.getDamager());
        // 소환된 몹이 맞을 때: 부른 사람이 때린 것만 들어감
        UUID victimOwner = owner(e.getEntity());
        if (victimOwner != null && !(src instanceof Player pl && pl.getUniqueId().equals(victimOwner))) {
            e.setCancelled(true);
            return;
        }
        // 소환된 몹이 때릴 때: 부른 사람에게만
        UUID attackerOwner = owner(src);
        if (attackerOwner == null) attackerOwner = owner(e.getDamager());
        if (attackerOwner != null && !e.getEntity().getUniqueId().equals(attackerOwner)) e.setCancelled(true);
    }

    /** 슬라임이 쪼개지면 새끼는 표시가 없어 남을 노림 → 쪼개지지 않게 */
    @EventHandler(ignoreCancelled = true)
    public void onSplit(org.bukkit.event.entity.SlimeSplitEvent e) {
        if (owner(e.getEntity()) != null) e.setCancelled(true);
    }

    /** 광산에서 나갈 때·접속 끊을 때: 그 사람 몫 몬스터 정리 */
    public void clear(UUID ownerId) {
        List<UUID> ids = spawned.remove(ownerId);
        if (ids == null) return;
        for (UUID id : ids) { Entity en = Bukkit.getEntity(id); if (en != null) en.remove(); }
    }
}
