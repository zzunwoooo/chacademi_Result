package school.magiccodex.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 재화 주머니 금액 표 (utility-items.yml money-pouch.tiers) — 순수 로직 (Bukkit 없음).
 * 구간을 가중치로 하나 고르고, 그 안에서 min~max 를 고르게 뽑아 step 단위로 내림 (min 아래로는 안 내려감).
 */
final class MoneyPouchRules {
    record Tier(String name, double weight, long min, long max) {}
    record Roll(Tier tier, long amount) {}

    private MoneyPouchRules() {}

    /** yml getMapList 결과 → 구간 목록. 잘못된 줄은 IllegalArgumentException (한글 문구). */
    static List<Tier> parse(List<Map<?, ?>> rows) {
        var out = new ArrayList<Tier>();
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("money-pouch.tiers 가 비어 있어요");
        for (var row : rows) {
            String name = String.valueOf(row.get("name") == null ? "" : row.get("name")).strip();
            double weight = number(row.get("weight"), "weight");
            long min = Math.round(number(row.get("min"), "min")), max = Math.round(number(row.get("max"), "max"));
            if (name.isEmpty() || name.length() > 30) throw new IllegalArgumentException("money-pouch.tiers name 은 1~30자");
            if (!(weight > 0) || !Double.isFinite(weight)) throw new IllegalArgumentException("money-pouch.tiers " + name + " weight 는 0보다 커야 해요");
            if (min < 1 || max < min || max > 10_000_000) throw new IllegalArgumentException("money-pouch.tiers " + name + " 금액은 1 ≤ min ≤ max ≤ 10,000,000");
            out.add(new Tier(name, weight, min, max));
        }
        return List.copyOf(out);
    }

    private static double number(Object v, String key) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v).strip()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("money-pouch.tiers " + key + " 는 숫자"); }
    }

    static Roll roll(List<Tier> tiers, long step, RandomGenerator random) {
        double total = 0;
        for (var t : tiers) total += t.weight();
        double r = random.nextDouble() * total;
        Tier pick = tiers.get(tiers.size() - 1);
        for (var t : tiers) { r -= t.weight(); if (r < 0) { pick = t; break; } }
        long amount = pick.min() == pick.max() ? pick.min() : pick.min() + (long) Math.floor(random.nextDouble() * (pick.max() - pick.min() + 1));
        return new Roll(pick, rounded(amount, step, pick.min()));
    }

    /** step 단위로 내림, 단 구간 최소 아래로는 안 내림. */
    static long rounded(long amount, long step, long min) {
        if (step <= 1) return amount;
        long r = amount / step * step;
        return Math.max(min, r);
    }

    /** 표의 기댓값 (문서·명령 확인용, step 무시). */
    static double expected(List<Tier> tiers) {
        double total = 0, sum = 0;
        for (var t : tiers) { total += t.weight(); sum += t.weight() * (t.min() + t.max()) / 2.0; }
        return total <= 0 ? 0 : sum / total;
    }
}
