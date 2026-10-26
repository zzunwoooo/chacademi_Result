package school.chacademia.magicmythic;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * 빗자루 위에서 쏘는 직선 투사체 보정 (chabolt).
 *
 * <ol>
 *   <li><b>속도 상속</b>: 탄 사람 비행 속도 중 쏘는 방향 성분만큼 투사체가 더 빠르다 (옆·뒤로 쏘면 그대로).
 *       그래서 앞으로 쏜 투사체가 내 비행 속도에 따라잡히지 않고 조준선도 그대로 유지된다.</li>
 *   <li><b>지연 보정</b>: 빗자루는 내 PC 가 움직이므로 서버가 아는 위치는 핑만큼 늦다.
 *       투사체가 첫 틱에 "비행 속도 × (핑 + 1틱)" 구간(최대 8블록)을 판정하며 지나가 내 화면에서 몸 앞에서 나가게 한다.
 *       그 구간의 벽·적에는 정상적으로 맞는다.</li>
 * </ol>
 * PortableVFX 의 투사체 연출(CatalogVisualEngine/BroomCarrier)도 같은 공식을 써서 보이는 위치와 판정 위치가 어긋나지 않는다.
 * 빗자루를 타지 않으면 아무것도 바꾸지 않는다.
 */
final class CarrierShot {
    static final double MAX_LEAD_TICKS = 8, MAX_LEAD_BLOCKS = 8;

    private CarrierShot() {}

    /** 핑(왕복 ms)을 앞당길 틱으로: 핑 + 서버 처리 1틱, 1~8틱. */
    static double leadTicks(int pingMs) {
        return Math.max(1, Math.min(MAX_LEAD_TICKS, Math.max(0, pingMs) / 50.0 + 1));
    }

    /** 출발점을 옮길 거리 벡터 (최대 8블록). */
    static Vector leadOffset(Vector velocity, int pingMs) {
        Vector off = velocity.clone().multiply(leadTicks(pingMs));
        double len = off.length();
        if (len > MAX_LEAD_BLOCKS) off.multiply(MAX_LEAD_BLOCKS / len);
        return off;
    }

    /** 쏘는 방향(정규화)으로의 비행 속도 성분. 뒤로 쏘면 0. */
    static double extraSpeed(Vector velocity, Vector dir) {
        double d = velocity.dot(dir);
        return Double.isFinite(d) ? Math.max(0, d) : 0;
    }

    /**
     * 출발점을 앞당길 거리 벡터. 빗자루를 타지 않으면 null.
     * 투사체는 첫 틱에 이 구간을 판정하며 지나가므로(벽·바로 앞 적에 정상적으로 맞음) 별도 레이 검사는 하지 않는다.
     */
    static Vector lead(LivingEntity caster) {
        if (!(caster instanceof Player player)) return null;
        Vector v = BroomLink.velocity(player);
        if (v == null || v.lengthSquared() < 1e-6) return null;
        Vector off = leadOffset(v, player.getPing());
        return off.lengthSquared() < 1e-6 ? null : off;
    }

    /** 방향(정규화)이 정해진 뒤의 추가 속도 (블록/틱). 빗자루를 타지 않으면 0. */
    static double extra(LivingEntity caster, Vector dir) {
        if (!(caster instanceof Player player)) return 0;
        Vector v = BroomLink.velocity(player);
        return v == null ? 0 : extraSpeed(v, dir);
    }
}
