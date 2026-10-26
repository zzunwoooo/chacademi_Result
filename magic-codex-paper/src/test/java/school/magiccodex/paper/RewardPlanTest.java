package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 공통 지급 칸 계산 (RewardPlan) — 개발프롬프트 6 의 인벤/아공간 조합을 가짜 스택으로 검사한다. */
class RewardPlanTest {
    record S(String id, int n, int max) {}
    static final RewardPlan.Stacks<S> OPS = new RewardPlan.Stacks<>() {
        public boolean empty(S s) { return s == null || s.n() <= 0; }
        public boolean similar(S a, S b) { return a.id().equals(b.id()); }
        public int amount(S s) { return s.n(); }
        public int max(S s) { return s.max(); }
        public S copy(S s, int amount) { return new S(s.id(), amount, s.max()); }
    };
    static List<S> slots() { return new ArrayList<>(Collections.nCopies(36, (S) null)); }
    static int total(List<S> slots, String id) { return slots.stream().filter(Objects::nonNull).filter(s -> s.id().equals(id)).mapToInt(S::n).sum(); }

    @Test void freeInventoryTakesEverythingHotbarFirst() {
        var slots = slots();
        var left = RewardPlan.placeAll(slots, RewardPlan.usable(true), List.of(new S("a", 10, 64), new S("b", 1, 1)), OPS, false);
        assertTrue(left.isEmpty());
        assertEquals(new S("a", 10, 64), slots.get(0));
        assertEquals(new S("b", 1, 1), slots.get(1));
    }

    @Test void hotbarFullUsesUnlockedActivePage() {
        var slots = slots();
        for (int i = 0; i < 9; i++) slots.set(i, new S("x", 64, 64));
        var left = RewardPlan.placeAll(slots, RewardPlan.usable(true), List.of(new S("a", 5, 64)), OPS, false);
        assertTrue(left.isEmpty());
        assertEquals(new S("a", 5, 64), slots.get(9));
    }

    @Test void lockedOrSwitchingPageNeverGetsASingleSlot() {
        var slots = slots();
        for (int i = 0; i < 9; i++) slots.set(i, new S("x", 64, 64));
        slots.set(20, new S("a", 1, 64)); // 같은 아이템이 잠긴 쪽에 있어도 합치지 않는다
        var left = RewardPlan.placeAll(slots, RewardPlan.usable(false), List.of(new S("a", 5, 64)), OPS, false);
        assertEquals(List.of(new S("a", 5, 64)), left);
        assertEquals(new S("a", 1, 64), slots.get(20));
        for (int i = 9; i < 36; i++) if (i != 20) assertNull(slots.get(i));
    }

    @Test void partialMergeThenRestToMailWithoutResendingWhole() {
        var slots = slots();
        for (int i = 0; i < 36; i++) slots.set(i, new S("x", 64, 64));
        slots.set(3, new S("a", 60, 64));
        var left = RewardPlan.placeAll(slots, RewardPlan.usable(true), List.of(new S("a", 10, 64)), OPS, false);
        assertEquals(new S("a", 64, 64), slots.get(3));
        assertEquals(List.of(new S("a", 6, 64)), left); // 들어간 4개는 우편으로 다시 보내지 않는다
    }

    @Test void bothFullEverythingToMail() {
        var slots = slots();
        for (int i = 0; i < 36; i++) slots.set(i, new S("x", 64, 64));
        var items = List.of(new S("a", 3, 64), new S("b", 1, 1));
        assertEquals(items, RewardPlan.placeAll(slots, RewardPlan.usable(true), items, OPS, false));
    }

    @Test void wholeModeLeavesSlotsUntouched() {
        var slots = slots();
        for (int i = 0; i < 35; i++) slots.set(i, new S("x", 64, 64));
        var before = new ArrayList<>(slots);
        assertNotNull(RewardPlan.place(slots, RewardPlan.usable(true), new S("a", 65, 64), OPS, true));
        assertEquals(before, slots);
        assertNull(RewardPlan.place(slots, RewardPlan.usable(true), new S("a", 64, 64), OPS, true));
        assertEquals(new S("a", 64, 64), slots.get(35));
    }

    @Test void conservesAmounts() {
        var slots = slots();
        for (int i = 0; i < 30; i++) slots.set(i, new S("x", 64, 64));
        var items = Collections.nCopies(7, new S("a", 64, 64));
        var left = RewardPlan.placeAll(slots, RewardPlan.usable(true), items, OPS, false);
        assertEquals(7 * 64, total(slots, "a") + left.stream().mapToInt(S::n).sum());
    }

    @Test void mailPartsRespectAttachmentAndMailLimits() {
        var parts = RewardPlan.mailParts(List.of(new S("a", 200, 64), new S("b", 3, 1), new S("c", 1, 16)), OPS);
        int attachments = parts.stream().mapToInt(List::size).sum();
        assertEquals(4 + 3 + 1, attachments);
        assertTrue(parts.stream().allMatch(p -> p.size() <= 9));
        assertEquals(200, parts.stream().flatMap(List::stream).filter(s -> s.id().equals("a")).mapToInt(S::n).sum());
    }

    @Test void takePlanUsesHeldSlotFirstAndRefusesWhenShort() {
        int[] amounts = new int[36]; boolean[] keys = new boolean[36];
        amounts[2] = 4; keys[2] = true; amounts[0] = 3; keys[0] = true; amounts[15] = 10; keys[15] = true;
        var plan = RewardPlan.takePlan(amounts, keys, RewardPlan.usable(true), 2, 10);
        assertEquals(List.of(2, 0, 15), List.copyOf(plan.keySet()));
        assertEquals(10, plan.values().stream().mapToInt(Integer::intValue).sum());
        assertNull(RewardPlan.takePlan(amounts, keys, RewardPlan.usable(false), 2, 10)); // 잠긴/넘기는 중인 27칸 열쇠는 쓰지 않음
        assertNull(RewardPlan.takePlan(amounts, keys, RewardPlan.usable(true), 2, 18));
    }
}
