package kr.chacademy.storyplugin;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 컷신·대화를 보는 동안 (재생 신호를 보낸 때부터 끝·중단·종료까지) 플레이어를 지킨다.
 * 화면이 잠겨 있어서 플레이어는 아무것도 할 수 없으므로: 피해 없음, 몹이 노리지 않음, 배고픔이 줄지 않음,
 * 제자리 고정 (수평으로만 — 떨어지는 것은 막지 않는다. 물약 효과는 쓰지 않는다).
 * 사망·접속 종료·월드 이동 때, 그리고 너무 오래 (기본 15분) 지나면 저절로 풀린다. 메인 스레드에서만 쓴다.
 */
final class StoryProtection implements Listener {
    /** 이 거리(블록)보다 많이 수평으로 움직이면 되돌린다. */
    private static final double HOLD_EPSILON = 0.05;

    private static final class Hold {
        Location anchor;
        final long since;

        Hold(Location anchor, long since) {
            this.anchor = anchor;
            this.since = since;
        }
    }

    private final Map<UUID, Hold> holds = new HashMap<>();
    private final BooleanSupplier enabled;
    private final LongSupplier maxMillis;

    StoryProtection(BooleanSupplier enabled, LongSupplier maxMillis) {
        this.enabled = enabled;
        this.maxMillis = maxMillis;
    }

    /** 재생 신호를 보낼 때. 이미 보호 중이면 시간만 그대로 둔다. */
    void enter(Player p) {
        if (!enabled.getAsBoolean()) return;
        holds.putIfAbsent(p.getUniqueId(), new Hold(p.getLocation(), System.currentTimeMillis()));
    }

    void release(Player p) {
        holds.remove(p.getUniqueId());
    }

    void release(UUID id) {
        holds.remove(id);
    }

    boolean inStory(UUID id) {
        return holds.containsKey(id);
    }

    void clear() {
        holds.clear();
    }

    /** 1초마다: 너무 오래된 보호를 푼다 (끝 신호가 영영 안 와도 무적으로 남지 않게). */
    void tick() {
        if (holds.isEmpty()) return;
        long max = maxMillis.getAsLong();
        long now = System.currentTimeMillis();
        boolean on = enabled.getAsBoolean();
        for (Iterator<Hold> it = holds.values().iterator(); it.hasNext(); ) {
            Hold h = it.next();
            if (!on || (max > 0 && now - h.since > max)) it.remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !holds.containsKey(p.getUniqueId())) return;
        // 낭떠러지 밖(VOID)과 /kill 은 막지 않는다
        String cause = e.getCause().name();
        if (cause.equals("VOID") || cause.equals("KILL") || cause.equals("SUICIDE")) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && holds.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && holds.containsKey(p.getUniqueId()) && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Hold h = holds.get(e.getPlayer().getUniqueId());
        if (h == null) return;
        Location to = e.getTo();
        if (to.getWorld() != h.anchor.getWorld()) return;
        double dx = to.getX() - h.anchor.getX(), dz = to.getZ() - h.anchor.getZ();
        if (dx * dx + dz * dz <= HOLD_EPSILON * HOLD_EPSILON) return;
        // 수평 위치만 되돌린다. 높이와 시선은 그대로 (떨어지는 중이면 계속 떨어진다)
        Location back = to.clone();
        back.setX(h.anchor.getX());
        back.setZ(h.anchor.getZ());
        e.setTo(back);
    }

    /** 다른 플러그인·명령어의 순간이동은 허용하고, 고정 위치를 도착 지점으로 옮긴다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Hold h = holds.get(e.getPlayer().getUniqueId());
        if (h != null && e.getTo() != null) h.anchor = e.getTo().clone();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        holds.remove(e.getEntity().getUniqueId());
    }

    /**
     * 죽어 있는 동안 재생 신호가 나가면 (클라는 되살아난 뒤에 보여 준다) 고정 위치가 죽은 자리로 잡힌다.
     * 되살아난 자리로 옮기지 않으면 첫 움직임에 죽은 자리의 x·z 로 끌려간다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Hold h = holds.get(e.getPlayer().getUniqueId());
        if (h != null) h.anchor = e.getRespawnLocation().clone();
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        holds.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        holds.remove(e.getPlayer().getUniqueId());
    }
}
