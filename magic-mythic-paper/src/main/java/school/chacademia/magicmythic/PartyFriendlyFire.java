package school.chacademia.magicmythic;

import java.util.ArrayList;
import java.util.Collection;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 오픈 전 #23 (오너 결정 7, 2026-10-21: 아군 = 파티원, 큐디치·결투 중에는 경기 팀): 아군끼리의 "마법이 아닌" 피해 경로를 막는다.
 * 마법 피해·상태이상·원소 표식·반응·넉백은 이미 {@link Targets#enemy} 한 곳에서 걸러진다. 여기서는 그 밖의 경로:
 *  - 근접(검·휩쓸기), 화살·삼지창·폭죽 등 투사체, 플레이어가 붙인 TNT, 길들인 늑대(주인의 파티원 공격)
 *  - 불붙이기(화염 인챈트·불화살)
 *  - 해로운 투척·잔류 물약
 * 막는 것은 "공격자와 피해자가 서로 다른 플레이어이고 같은 편"일 때뿐이다. 적·몹·환경 피해는 그대로라 무적이 되지 않는다.
 */
final class PartyFriendlyFire implements Listener {
    private final MagicMythicPlugin plugin;

    PartyFriendlyFire(MagicMythicPlugin plugin) { this.plugin = plugin; }

    /** 피해를 준 플레이어: 본인, 쏜 사람, TNT 를 붙인 사람, 잔류 물약을 던진 사람, 길들인 동물의 주인. 없으면 null. */
    static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) return p;
        if (damager instanceof Tameable pet && pet.isTamed() && pet.getOwner() instanceof Player p) return p;
        return null;
    }

    /** 서로 다른 두 플레이어가 같은 편(파티, 경기 중이면 경기 팀)인지. */
    private boolean friends(Player a, Entity victim) {
        if (a == null || !(victim instanceof Player b) || a.equals(b)) return false;
        var allies = plugin.allies();
        return allies != null && allies.allied(a, b);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent e) {
        if (friends(attacker(e.getDamager()), e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void combust(EntityCombustByEntityEvent e) {
        if (friends(attacker(e.getCombuster()), e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void splash(PotionSplashEvent e) {
        if (!(e.getPotion().getShooter() instanceof Player thrower) || !harmful(e.getPotion().getEffects())) return;
        for (LivingEntity hit : e.getAffectedEntities()) if (friends(thrower, hit)) e.setIntensity(hit, 0);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void cloud(AreaEffectCloudApplyEvent e) {
        AreaEffectCloud cloud = e.getEntity();
        if (!(cloud.getSource() instanceof Player thrower)) return;
        var effects = new ArrayList<PotionEffect>(cloud.getCustomEffects());
        if (cloud.getBasePotionType() != null) effects.addAll(cloud.getBasePotionType().getPotionEffects());
        if (!harmful(effects)) return;
        e.getAffectedEntities().removeIf(hit -> friends(thrower, hit));
    }

    static boolean harmful(Collection<PotionEffect> effects) {
        for (PotionEffect fx : effects) if (fx.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) return true;
        return false;
    }
}
