package school.chacademia.magicmythic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * 큐디치 전투 보정 (오픈 전 #33, 오너 결정 2026-10-25). 큐디치 경기 중 선수에게만, 평소·1:1 결투에는 적용 안 함.
 * <ol>
 *   <li>투사체형 마법(chabolt) 속도 ×{@code projectile-speed} (기본 1.5) — 시전자가 큐디치 선수일 때.</li>
 *   <li>비행 중(빗자루·겉날개·비행) 큐디치 선수의 피격 범위 ×{@code flying-hitbox} (기본 1.5) — 몸 상자를 가운데 기준으로 키운다.
 *       투사체와 광역 즉발기(범위 판정 {@link Targets#enemiesNear}) 모두 같은 상자로 판정.</li>
 *   <li>유도 투사체(homing=true, 대상을 찍어 쏜 것)는 대상을 끝까지 따라간다. 놓치는 경우는 두 가지뿐:
 *       대상이 순간이동(한 틱에 {@code teleport-blocks} 이상 이동)했거나, 맞기 직전({@code dodge-ticks} 틱 안에 닿을 거리)에
 *       회피 기동(한 틱 사이 비행 속도가 {@code dodge-speed-change} 블록/틱 이상 바뀜 — 급선회·대시·급정지)을 했을 때.
 *       놓치면 그 방향으로 곧게 날아간다. 따라가는 동안에는 사거리 대신 {@code homing-seconds} 초까지 난다.</li>
 * </ol>
 * 설정: MagicMythic config.yml {@code quidditch-combat:} (없으면 위 기본값).
 */
final class QuidditchCombat {
    /** 지금 설정. 플러그인이 켜질 때·새로고침 때 바꾼다. 테스트·플러그인 밖에서는 기본값. */
    static volatile QuidditchCombat current = new QuidditchCombat(true, 1.5, 1.5, true, 4, 3, 0.6, 5);

    final boolean enabled, homingLock;
    final double projectileSpeed, flyingHitbox, teleportBlocks, dodgeSpeedChange;
    final int dodgeTicks, homingTicks;

    QuidditchCombat(boolean enabled, double projectileSpeed, double flyingHitbox, boolean homingLock,
                    double teleportBlocks, int dodgeTicks, double dodgeSpeedChange, double homingSeconds) {
        this.enabled = enabled;
        this.projectileSpeed = Math.clamp(projectileSpeed, 1, 4);
        this.flyingHitbox = Math.clamp(flyingHitbox, 1, 4);
        this.homingLock = homingLock;
        this.teleportBlocks = Math.clamp(teleportBlocks, 1, 64);
        this.dodgeTicks = Math.clamp(dodgeTicks, 0, 40);
        this.dodgeSpeedChange = Math.clamp(dodgeSpeedChange, 0.05, 10);
        this.homingTicks = (int) Math.round(Math.clamp(homingSeconds, 1, 20) * 20);
    }

    static QuidditchCombat load(ConfigurationSection c) {
        if (c == null) return new QuidditchCombat(true, 1.5, 1.5, true, 4, 3, 0.6, 5);
        return new QuidditchCombat(c.getBoolean("enabled", true), c.getDouble("projectile-speed", 1.5), c.getDouble("flying-hitbox", 1.5),
            c.getBoolean("homing-lock", true), c.getDouble("teleport-blocks", 4), c.getInt("dodge-ticks", 3),
            c.getDouble("dodge-speed-change", 0.6), c.getDouble("homing-seconds", 5));
    }

    // ------------------------------------------------------------------ 경기 판정 (한 틱에 한 번만 묻는다)

    private static final Map<UUID, Object[]> CACHE = new HashMap<>();
    private static int cacheTick = Integer.MIN_VALUE;

    /** 큐디치 경기 중인 선수인지 (경기 플러그인이 답함). 메인 스레드. */
    static boolean inQuidditch(Entity e) {
        if (!(e instanceof Player p) || !current.enabled || !Bukkit.isPrimaryThread()) return false;
        int tick = Bukkit.getCurrentTick();
        if (tick != cacheTick) { CACHE.clear(); cacheTick = tick; }
        Object[] hit = CACHE.get(p.getUniqueId());
        if (hit == null) { hit = new Object[] { MagicMatchEvent.QUIDDITCH.equals(MagicMatchEvent.ask(p)) }; CACHE.put(p.getUniqueId(), hit); }
        return (Boolean) hit[0];
    }

    /** 비행 중: 빗자루를 타고 있거나 겉날개 활공·비행 중. */
    static boolean flying(Player p) {
        return p.isGliding() || p.isFlying() || BroomLink.riding(p);
    }

    /** chabolt 속도 배수 (시전자가 큐디치 선수일 때). */
    static double speedFactor(LivingEntity caster) {
        return inQuidditch(caster) ? current.projectileSpeed : 1;
    }

    /** 판정에 쓰는 몸 상자: 비행 중인 큐디치 선수면 가운데 기준으로 키운 상자. */
    static BoundingBox hitBox(Entity e) {
        BoundingBox box = e.getBoundingBox();
        if (current.flyingHitbox <= 1 || !(e instanceof Player p) || !flying(p) || !inQuidditch(p)) return box;
        return scaled(box, current.flyingHitbox);
    }

    /** 상자를 가운데 기준으로 factor 배 (순수 계산, 테스트용). */
    static BoundingBox scaled(BoundingBox b, double factor) {
        double hx = b.getWidthX() * (factor - 1) / 2, hy = b.getHeight() * (factor - 1) / 2, hz = b.getWidthZ() * (factor - 1) / 2;
        return b.clone().expand(hx, hy, hz);
    }

    /** 키운 상자 때문에 주변 검색 반경에 더할 여유 (블록). */
    static double searchMargin() { return current.flyingHitbox <= 1 ? 0 : 1.0 * current.flyingHitbox; }

    // ------------------------------------------------------------------ 유도 고정

    /**
     * 유도 투사체가 대상을 놓쳤는지 (순수 계산, 테스트용).
     * @param moved      대상이 지난 틱부터 움직인 거리 (블록)
     * @param oldVel     지난 틱 비행 속도 (null = 모름)
     * @param newVel     이번 틱 비행 속도 (null = 모름)
     * @param distance   투사체와 대상 사이 거리
     * @param speed      투사체 속도 (블록/틱)
     */
    static boolean lost(QuidditchCombat q, double moved, Vector oldVel, Vector newVel, double distance, double speed) {
        if (moved >= q.teleportBlocks) return true;                                  // 순간이동
        if (oldVel == null || newVel == null || q.dodgeTicks <= 0) return false;
        boolean close = distance <= speed * q.dodgeTicks + 1.5;                      // 맞기 직전
        return close && oldVel.clone().subtract(newVel).length() >= q.dodgeSpeedChange;  // 회피 기동
    }

    /**
     * 대상의 지금 빗자루 비행 속도 (빗자루가 아니면 null — 플레이어 걷기 속도는 서버가 정확히 모르므로 회피 판정에 안 쓴다.
     * 그때는 순간이동만 놓침으로 친다).
     */
    static Vector velocity(LivingEntity e) {
        return e instanceof Player p ? BroomLink.velocity(p) : null;
    }
}
