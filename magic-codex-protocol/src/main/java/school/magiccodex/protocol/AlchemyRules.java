package school.magiccodex.protocol;

import java.util.*;

/**
 * Pure alchemy rules shared by the server (deciding) and the client (previewing): ingredient order never matters,
 * only which items and how many in total; vanilla experience points.
 */
public final class AlchemyRules {
    private AlchemyRules() {}

    /** Sum the slots by item key (same key in two slots counts together). Order of first appearance is kept. */
    public static Map<String, Integer> merge(List<AlchemyProtocol.Stack> stacks) {
        var map = new LinkedHashMap<String, Integer>();
        for (var s : stacks) map.merge(s.key(), s.amount(), Integer::sum);
        return map;
    }

    public static boolean matches(Map<String, Integer> recipe, Map<String, Integer> slots) {
        return !recipe.isEmpty() && recipe.equals(slots);
    }

    /**
     * How close the slots are to a recipe that they do not match exactly:
     * NEAR_AMOUNT = the same ingredients but different amounts; NEAR = at most one ingredient missing and at most
     * one extra (one swap), keeping all but one of the recipe's ingredients; otherwise -1.
     */
    public static int closeness(Map<String, Integer> recipe, Map<String, Integer> slots) {
        if (recipe.isEmpty() || slots.isEmpty() || matches(recipe, slots)) return -1;
        if (recipe.keySet().equals(slots.keySet())) return AlchemyProtocol.NEAR_AMOUNT;
        int shared = 0, extra = 0;
        for (String key : slots.keySet()) if (recipe.containsKey(key)) shared++; else extra++;
        int missing = recipe.size() - shared;
        if (missing <= 1 && extra <= 1 && shared >= Math.max(1, recipe.size() - 1)) return AlchemyProtocol.NEAR;
        return -1;
    }

    /** Best hint over many recipes: NEAR_AMOUNT beats NEAR; NOTHING when nothing is close. */
    public static int hint(Collection<Map<String, Integer>> recipes, Map<String, Integer> slots) {
        int best = AlchemyProtocol.NOTHING;
        for (var r : recipes) {
            int c = closeness(r, slots);
            if (c == AlchemyProtocol.NEAR_AMOUNT) return c;
            if (c == AlchemyProtocol.NEAR) best = c;
        }
        return best;
    }

    // ------------------------------------------------------------------ vanilla experience points

    /** Points needed to go from this level to the next (vanilla). */
    public static int toNext(int level) {
        if (level >= 30) return 112 + (level - 30) * 9;
        if (level >= 15) return 37 + (level - 15) * 5;
        return 7 + level * 2;
    }

    /** Points needed to reach the start of this level from zero. */
    public static long pointsAt(int level) {
        long total = 0;
        for (int i = 0; i < Math.max(0, level); i++) total += toNext(i);
        return total;
    }

    /** Total points shown by a level and the bar progress (0..1). */
    public static long total(int level, float progress) {
        float p = Float.isFinite(progress) ? Math.clamp(progress, 0f, 1f) : 0f;
        return pointsAt(level) + Math.round(p * toNext(Math.max(0, level)));
    }

    /** Level and bar progress for a point total: {level, progress}. */
    public static float[] fromTotal(long total) {
        long left = Math.max(0, total);
        int level = 0;
        while (left >= toNext(level) && level < 1_000_000) { left -= toNext(level); level++; }
        return new float[]{level, (float) left / toNext(level)};
    }
}
