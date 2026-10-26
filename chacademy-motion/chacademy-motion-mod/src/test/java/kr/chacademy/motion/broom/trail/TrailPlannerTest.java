package kr.chacademy.motion.broom.trail;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TrailPlannerTest {
    static final TrailConfig.TrailType LEGEND = new TrailConfig.TrailType("legend", "claude:broomtrails/legend", "claude:broomtrails/legend_lite", 18.8, 3.5, 4.2);
    static final TrailConfig.TrailType BASIC = new TrailConfig.TrailType("basic", "claude:broomtrails/basic", "claude:broomtrails/basic_lite", 6.1, 1.8, 0);
    static final UUID SELF = new UUID(0, 1);

    static TrailPlanner.Candidate c(UUID id, double dist, double speed, TrailConfig.TrailType t) {
        return new TrailPlanner.Candidate(id, id.equals(SELF), dist, speed, t);
    }

    static List<UUID> ids(int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new UUID(1, i));
        return out;
    }

    @Test void selfAlwaysShownAndFullEvenWhenOthersFill() {
        var p = new TrailPlanner();
        List<TrailPlanner.Candidate> cs = new ArrayList<>();
        cs.add(c(SELF, 0, 20, BASIC));
        for (UUID id : ids(40)) cs.add(c(id, 5 + cs.size(), 20, BASIC));
        Map<UUID, Boolean> plan = p.plan(cs, 0);
        assertEquals(Boolean.TRUE, plan.get(SELF));
        assertTrue(plan.size() <= TrailPlanner.MAX_SHOWN);
        assertEquals(SELF, plan.keySet().iterator().next());
    }

    @Test void boundaryDoesNotFlicker() {
        var p = new TrailPlanner();
        UUID a = new UUID(2, 1);
        assertTrue(p.plan(List.of(c(a, 39, 10, BASIC)), 0).containsKey(a));
        // 진입(40)과 이탈(48) 사이를 오가도 유지
        for (int t = 1; t < 200; t++) {
            double d = (t % 2 == 0) ? 41 : 47.9;
            assertTrue(p.plan(List.of(c(a, d, 10, BASIC)), t).containsKey(a), "tick " + t);
        }
        // 이탈 거리 밖이 짧게(유지 시간 미만) 이어지면 유지
        assertTrue(p.plan(List.of(c(a, 49, 10, BASIC)), 300).containsKey(a));
        assertTrue(p.plan(List.of(c(a, 49, 10, BASIC)), 300 + TrailPlanner.EXIT_HOLD_TICKS - 1).containsKey(a));
        assertTrue(p.plan(List.of(c(a, 45, 10, BASIC)), 330).containsKey(a));
        // 계속 밖이면 사라짐
        for (int t = 400; t < 400 + TrailPlanner.EXIT_HOLD_TICKS; t++) p.plan(List.of(c(a, 50, 10, BASIC)), t);
        assertFalse(p.plan(List.of(c(a, 50, 10, BASIC)), 400 + TrailPlanner.EXIT_HOLD_TICKS).containsKey(a));
        // 다시 들어오려면 진입 거리(40) 이내여야 함
        assertFalse(p.plan(List.of(c(a, 45, 10, BASIC)), 500).containsKey(a));
        assertTrue(p.plan(List.of(c(a, 40, 10, BASIC)), 501).containsKey(a));
    }

    @Test void hardRadiusRemovesImmediately() {
        var p = new TrailPlanner();
        UUID a = new UUID(2, 2);
        p.plan(List.of(c(a, 10, 10, BASIC)), 0);
        assertFalse(p.plan(List.of(c(a, 70, 10, BASIC)), 1).containsKey(a));
    }

    @Test void rank24vs25SwapDoesNotFlicker() {
        var p = new TrailPlanner();
        List<UUID> people = ids(25);
        List<TrailPlanner.Candidate> cs = new ArrayList<>();
        for (int i = 0; i < 25; i++) cs.add(c(people.get(i), 10 + i * 0.5, 5, BASIC));
        Map<UUID, Boolean> first = p.plan(cs, 0);
        assertEquals(24, first.size());
        UUID in24 = people.get(23), out25 = people.get(24);
        assertTrue(first.containsKey(in24));
        // 24·25번째가 번갈아 가까워져도 표시 중인 사람은 유지 (순위 28 까지 허용)
        for (int t = 1; t < 100; t++) {
            cs.set(23, c(in24, t % 2 == 0 ? 21.6 : 21.4, 5, BASIC));
            cs.set(24, c(out25, t % 2 == 0 ? 21.4 : 21.6, 5, BASIC));
            Map<UUID, Boolean> plan = p.plan(cs, t);
            assertTrue(plan.containsKey(in24), "tick " + t);
            assertEquals(24, plan.size());
        }
    }

    @Test void onlyNearestFewAreFullAndLodHasHold() {
        var p = new TrailPlanner();
        List<UUID> people = ids(6);
        List<TrailPlanner.Candidate> cs = new ArrayList<>();
        for (int i = 0; i < 6; i++) cs.add(c(people.get(i), 5 + i, 5, BASIC));
        Map<UUID, Boolean> plan = p.plan(cs, 0);
        long full = plan.values().stream().filter(b -> b).count();
        assertEquals(TrailPlanner.FULL_ENTER_RANK, full);
        assertTrue(plan.get(people.get(0)));
        assertFalse(plan.get(people.get(5)));
        // 1등이 잠깐 멀어져도 유지 시간 동안은 full 유지
        cs.set(0, c(people.get(0), 30, 5, BASIC));
        assertTrue(p.plan(cs, 10).get(people.get(0)));
        assertFalse(p.plan(cs, TrailPlanner.LOD_HOLD_TICKS + 1).get(people.get(0)));
    }

    @Test void particleBudgetDemotesThenHidesFarthest() {
        var p = new TrailPlanner();
        List<TrailPlanner.Candidate> cs = new ArrayList<>();
        cs.add(c(SELF, 0, 39, LEGEND));
        for (UUID id : ids(23)) cs.add(c(id, 3 + cs.size(), 39, LEGEND)); // 전원 전설 + 최고속 부스트
        Map<UUID, Boolean> plan = p.plan(cs, 0);
        assertTrue(TrailPlanner.estimate(cs, plan) <= TrailPlanner.PARTICLE_BUDGET, "estimate " + TrailPlanner.estimate(cs, plan));
        assertTrue(plan.get(SELF));
        assertTrue(plan.size() < 24, "some hidden by budget");
        // 남은 사람은 가까운 순서
        var kept = new ArrayList<>(plan.keySet());
        for (int i = 2; i < kept.size(); i++) {
            UUID before = kept.get(i - 1), now = kept.get(i);
            double prev = cs.stream().filter(x -> x.id().equals(before)).findFirst().get().distance();
            double cur = cs.stream().filter(x -> x.id().equals(now)).findFirst().get().distance();
            assertTrue(prev <= cur);
        }
    }

    @Test void typicalCrowdFitsBudgetAtCruise() {
        var p = new TrailPlanner();
        List<TrailPlanner.Candidate> cs = new ArrayList<>();
        cs.add(c(SELF, 0, 20, LEGEND));
        for (UUID id : ids(23)) cs.add(c(id, 3 + cs.size(), 20, BASIC));
        Map<UUID, Boolean> plan = p.plan(cs, 0);
        assertEquals(24, plan.size());
        assertTrue(TrailPlanner.estimate(cs, plan) <= TrailPlanner.PARTICLE_BUDGET);
    }

    @Test void droppedCandidatesAreForgotten() {
        var p = new TrailPlanner();
        UUID a = new UUID(3, 3);
        p.plan(List.of(c(a, 5, 5, BASIC)), 0);
        assertTrue(p.plan(List.of(), 1).isEmpty());
        // 다시 타면 진입 규칙부터
        assertTrue(p.plan(List.of(c(a, 5, 5, BASIC)), 2).containsKey(a));
    }
}
