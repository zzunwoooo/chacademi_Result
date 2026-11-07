package kr.kkaegul.core.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** 확률 유틸. 모든 확률은 퍼센트(0~100) 또는 가중치로 받는다. */
public final class Rng {
    private Rng() {}

    public static ThreadLocalRandom r() { return ThreadLocalRandom.current(); }

    /** min~max (양 끝 포함) */
    public static int range(int min, int max) {
        if (max < min) { int t = min; min = max; max = t; }
        return r().nextInt(min, max + 1);
    }

    public static long range(long min, long max) {
        if (max < min) { long t = min; min = max; max = t; }
        return r().nextLong(min, max + 1);
    }

    /** percent: 0~100 */
    public static boolean chance(double percent) {
        if (percent <= 0) return false;
        if (percent >= 100) return true;
        return r().nextDouble() * 100.0 < percent;
    }

    public static <T> T pick(List<T> list) {
        if (list == null || list.isEmpty()) return null;
        return list.get(r().nextInt(list.size()));
    }

    /** 가중치 맵에서 하나 고른다. 가중치 합이 0이면 null. */
    public static <T> T weighted(Map<T, ? extends Number> weights) {
        double total = 0;
        for (Number n : weights.values()) total += Math.max(0, n.doubleValue());
        if (total <= 0) return null;
        double roll = r().nextDouble() * total;
        T last = null;
        for (Map.Entry<T, ? extends Number> e : weights.entrySet()) {
            double w = Math.max(0, e.getValue().doubleValue());
            if (w <= 0) continue;
            last = e.getKey();
            roll -= w;
            if (roll < 0) return e.getKey();
        }
        return last;
    }
}
