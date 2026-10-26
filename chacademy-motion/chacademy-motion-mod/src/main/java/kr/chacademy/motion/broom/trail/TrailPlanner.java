package kr.chacademy.motion.broom.trail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 누구의 궤적을 어떤 단계(full / lite)로 보여줄지 정한다. 마인크래프트 타입 없음(단위 테스트 가능).
 *
 * <ul>
 *   <li>나 자신: 항상 포함, 항상 full (예산 계산에는 넣음).</li>
 *   <li>진입: 거리 ≤ {@link #ENTER_RADIUS} 이고 가까운 순위 ≤ {@link #MAX_SHOWN}, 자리가 있을 때.</li>
 *   <li>이탈: 거리 &gt; {@link #EXIT_RADIUS} 또는 순위 &gt; {@link #EXIT_RANK} 상태가 {@link #EXIT_HOLD_TICKS} 동안 이어질 때
 *       (경계에서 켜졌다 꺼졌다 반복하지 않게). 거리 &gt; {@link #HARD_RADIUS} 이면 바로.
 *       카메라 시야는 보지 않는다 (고개를 돌려도 끊기지 않게).</li>
 *   <li>full/lite: 가까운 몇 명만 full. 바꾼 뒤 {@link #LOD_HOLD_TICKS} 동안은 다시 안 바꿈.</li>
 *   <li>입자 예산: 예상 입자 수 합이 {@link #PARTICLE_BUDGET} 를 넘으면 먼 사람부터 lite 로, 그래도 넘으면 먼 사람부터 숨김.
 *       lite→full 승격은 승격 후에도 예산의 {@link #PROMOTE_FRACTION} 이하일 때만.</li>
 * </ul>
 */
public final class TrailPlanner {
    public static final double ENTER_RADIUS = 40, EXIT_RADIUS = 48, HARD_RADIUS = 64;
    public static final int MAX_SHOWN = 24, EXIT_RANK = 28, EXIT_HOLD_TICKS = 20;
    public static final double FULL_ENTER_RADIUS = 20, FULL_EXIT_RADIUS = 26;
    public static final int FULL_ENTER_RANK = 3, FULL_EXIT_RANK = 5, LOD_HOLD_TICKS = 60;
    /** PortableVFX 프레임 전체 입자 한도 8192 의 약 1/4. 마법 VFX 자리를 남긴다. */
    public static final double PARTICLE_BUDGET = 2000, PROMOTE_FRACTION = 0.8;

    /** 이번 틱의 후보 한 명. speed = 블록/초, type = 이 사람이 쓸 궤적. */
    public record Candidate(UUID id, boolean self, double distance, double speed, TrailConfig.TrailType type) {}

    private static final class Slot {
        boolean full;
        long lodSince;
        long exitSince = -1;
    }

    private final Map<UUID, Slot> shown = new HashMap<>();

    /** @return 보여줄 사람 → full 여부 (self 먼저, 그다음 가까운 순) */
    public Map<UUID, Boolean> plan(List<Candidate> candidates, long now) {
        List<Candidate> others = new ArrayList<>();
        Candidate self = null;
        for (Candidate c : candidates) {
            if (c.self()) self = c;
            else others.add(c);
        }
        others.sort(Comparator.comparingDouble(Candidate::distance));
        Map<UUID, Integer> rank = new HashMap<>();
        for (int i = 0; i < others.size(); i++) rank.put(others.get(i).id(), i + 1);

        // 후보에서 빠진 사람(내림·월드 밖)은 즉시 정리
        final UUID selfId = self == null ? null : self.id();
        shown.keySet().removeIf(id -> !rank.containsKey(id) && !id.equals(selfId));

        // 1) 이탈 판정 (유지 시간 적용)
        for (Candidate c : others) {
            Slot s = shown.get(c.id());
            if (s == null) continue;
            int r = rank.get(c.id());
            boolean leaving = c.distance() > EXIT_RADIUS || r > EXIT_RANK;
            if (c.distance() > HARD_RADIUS) {
                shown.remove(c.id());
            } else if (leaving) {
                if (s.exitSince < 0) s.exitSince = now;
                if (now - s.exitSince >= EXIT_HOLD_TICKS) shown.remove(c.id());
            } else {
                s.exitSince = -1;
            }
        }
        // 2) 나 자신
        if (self != null) {
            Slot s = shown.computeIfAbsent(self.id(), k -> new Slot());
            s.full = true;
        }
        // 3) 진입 (가까운 순)
        for (Candidate c : others) {
            if (shown.containsKey(c.id())) continue;
            if (shown.size() >= MAX_SHOWN) break;
            if (c.distance() <= ENTER_RADIUS && rank.get(c.id()) <= MAX_SHOWN) {
                Slot s = new Slot();
                s.full = false;
                s.lodSince = now - LOD_HOLD_TICKS; // 처음엔 바로 full 승격 가능
                shown.put(c.id(), s);
            }
        }
        // 4) 예산 계산 + full/lite
        double total = 0;
        if (self != null) total += self.type().cost(true, self.speed());
        List<Candidate> visible = new ArrayList<>();
        for (Candidate c : others) if (shown.containsKey(c.id())) visible.add(c);
        for (Candidate c : visible) total += c.type().cost(shown.get(c.id()).full, c.speed());
        for (Candidate c : visible) {
            Slot s = shown.get(c.id());
            int r = rank.get(c.id());
            if (now - s.lodSince < LOD_HOLD_TICKS) continue;
            if (s.full && (c.distance() > FULL_EXIT_RADIUS || r > FULL_EXIT_RANK)) {
                total += c.type().cost(false, c.speed()) - c.type().cost(true, c.speed());
                s.full = false;
                s.lodSince = now;
            } else if (!s.full && c.distance() <= FULL_ENTER_RADIUS && r <= FULL_ENTER_RANK) {
                double after = total + c.type().cost(true, c.speed()) - c.type().cost(false, c.speed());
                if (after <= PARTICLE_BUDGET * PROMOTE_FRACTION) {
                    total = after;
                    s.full = true;
                    s.lodSince = now;
                }
            }
        }
        // 5) 예산 초과: 먼 사람부터 lite, 그다음 숨김 (유지 시간 무시)
        for (int i = visible.size() - 1; i >= 0 && total > PARTICLE_BUDGET; i--) {
            Candidate c = visible.get(i);
            Slot s = shown.get(c.id());
            if (s.full) {
                total += c.type().cost(false, c.speed()) - c.type().cost(true, c.speed());
                s.full = false;
                s.lodSince = now;
            }
        }
        for (int i = visible.size() - 1; i >= 0 && total > PARTICLE_BUDGET; i--) {
            Candidate c = visible.get(i);
            total -= c.type().cost(false, c.speed());
            shown.remove(c.id());
        }

        Map<UUID, Boolean> out = new LinkedHashMap<>();
        if (self != null && shown.containsKey(self.id())) out.put(self.id(), true);
        for (Candidate c : others) {
            Slot s = shown.get(c.id());
            if (s != null) out.put(c.id(), s.full);
        }
        return out;
    }

    /** 예상 입자 수 (디버그·테스트용). */
    public static double estimate(List<Candidate> candidates, Map<UUID, Boolean> plan) {
        double t = 0;
        for (Candidate c : candidates) {
            Boolean full = plan.get(c.id());
            if (full != null) t += c.type().cost(full, c.speed());
        }
        return t;
    }

    public void clear() {
        shown.clear();
    }
}
