package school.magiccodex.paper;

import java.util.*;

/**
 * 보상 칸 계산 (순수 로직, Bukkit 없음 — JUnit 대상). 공통 지급 함수 {@link RewardDelivery} 가 이 계산 하나만 쓴다.
 *
 * <p>배치 규칙은 기존 우편 수령(MailboxBridge.insert)과 같다: 넣을 수 있는 칸 중 같은 아이템 칸에 칸 번호 순으로 먼저 합치고
 * (한 칸 최대 min(64, 최대 스택)), 남으면 빈 칸에 칸 번호 순으로 넣는다. 새 우선순위를 만들지 않는다.
 * usable[i] 가 false 인 칸(잠긴 아공간 쪽·쪽 넘기는 중인 27칸)은 합치기에도 빈 칸에도 절대 쓰지 않는다.</p>
 */
final class RewardPlan {
    private RewardPlan() {}

    /** 아이템 타입에 대한 최소 연산 (서버: ItemStack, 테스트: 가짜 스택). */
    interface Stacks<T> {
        boolean empty(T s);
        boolean similar(T a, T b);
        int amount(T s);
        int max(T s);
        T copy(T s, int amount);
    }

    /**
     * slots 를 고쳐 item 을 넣는다. 반환: 못 넣은 나머지(없으면 null).
     * whole = true 면 전부 들어갈 때만 넣고(우편 첨부처럼 한 덩어리), 아니면 slots 를 바꾸지 않고 item 그대로 돌려준다.
     */
    static <T> T place(List<T> slots, boolean[] usable, T item, Stacks<T> ops, boolean whole) {
        if (item == null || ops.empty(item) || ops.amount(item) <= 0) return null;
        var next = new ArrayList<T>(slots.size());
        for (T s : slots) next.add(s == null || ops.empty(s) ? null : ops.copy(s, ops.amount(s)));
        int left = ops.amount(item);
        int cap = Math.max(1, Math.min(64, ops.max(item)));
        for (int i = 0; i < next.size() && left > 0; i++) {
            if (!allowed(usable, i)) continue;
            T old = next.get(i);
            if (old == null || !ops.similar(old, item)) continue;
            int room = Math.max(0, Math.min(64, ops.max(old)) - ops.amount(old));
            int n = Math.min(left, room);
            if (n > 0) { next.set(i, ops.copy(old, ops.amount(old) + n)); left -= n; }
        }
        for (int i = 0; i < next.size() && left > 0; i++) {
            if (!allowed(usable, i) || next.get(i) != null) continue;
            int n = Math.min(left, cap);
            next.set(i, ops.copy(item, n));
            left -= n;
        }
        if (whole && left > 0) return item;
        for (int i = 0; i < slots.size(); i++) slots.set(i, next.get(i));
        return left <= 0 ? null : ops.copy(item, left);
    }

    /** 여러 아이템을 순서대로 넣는다. 남은 것(순서 유지)을 돌려준다. */
    static <T> List<T> placeAll(List<T> slots, boolean[] usable, List<T> items, Stacks<T> ops, boolean whole) {
        var left = new ArrayList<T>();
        for (T item : items) { T rest = place(slots, usable, item, ops, whole); if (rest != null) left.add(rest); }
        return left;
    }

    /** 남은 아이템을 우편 첨부 단위로 자른다: 한 첨부 최대 min(99, 최대 스택), 우편 한 통 최대 9첨부. */
    static <T> List<List<T>> mailParts(List<T> leftovers, Stacks<T> ops) {
        var attachments = new ArrayList<T>();
        for (T it : leftovers) {
            int left = ops.amount(it), cap = Math.max(1, Math.min(99, ops.max(it)));
            while (left > 0) { int n = Math.min(left, cap); attachments.add(ops.copy(it, n)); left -= n; }
        }
        var parts = new ArrayList<List<T>>();
        for (int i = 0; i < attachments.size(); i += 9) parts.add(List.copyOf(attachments.subList(i, Math.min(attachments.size(), i + 9))));
        return parts;
    }

    /** 플레이어 보관 칸 36개(0~8 핫바, 9~35 지금 쪽 27칸) 중 넣을 수 있는 칸. mainUsable = 지금 쪽 27칸을 써도 되는지(서버 실제 상태). */
    static boolean[] usable(boolean mainUsable) {
        var out = new boolean[36];
        for (int i = 0; i < 36; i++) out[i] = i < 9 || mainUsable;
        return out;
    }

    private static boolean allowed(boolean[] usable, int i) { return usable == null || (i < usable.length && usable[i]); }

    /**
     * 열쇠 차감 계획: 손에 든 칸(held)부터, 그다음 넣을 수 있는 칸을 칸 번호 순으로. matches[i] = 그 칸이 열쇠인지.
     * 반환: 칸 → 뺄 개수. 모자라면 null (아무것도 빼지 않음).
     */
    static Map<Integer, Integer> takePlan(int[] amounts, boolean[] matches, boolean[] usable, int held, int need) {
        var plan = new LinkedHashMap<Integer, Integer>();
        int left = need;
        var order = new ArrayList<Integer>();
        if (held >= 0 && held < amounts.length) order.add(held);
        for (int i = 0; i < amounts.length; i++) if (i != held) order.add(i);
        for (int i : order) {
            if (left <= 0) break;
            if (!matches[i] || amounts[i] <= 0 || (i != held && !allowed(usable, i))) continue;
            int n = Math.min(left, amounts[i]);
            plan.put(i, n);
            left -= n;
        }
        return left > 0 ? null : plan;
    }
}
