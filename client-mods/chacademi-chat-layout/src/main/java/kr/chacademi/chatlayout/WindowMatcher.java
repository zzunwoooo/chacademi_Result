package kr.chacademi.chatlayout;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Associates saved layouts with native windows without mutating either state or claims. */
public final class WindowMatcher {
    private WindowMatcher() {
    }

    public static WindowState find(List<WindowState> existing, String candidateSignature,
                                   Set<String> claimed, double candidateX, double candidateY) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(candidateSignature, "candidateSignature");
        Objects.requireNonNull(claimed, "claimed");
        if (!Double.isFinite(candidateX) || !Double.isFinite(candidateY)) {
            throw new IllegalArgumentException("candidate coordinates must be finite");
        }

        WindowState best = null;
        double closest = Double.POSITIVE_INFINITY;
        for (WindowState state : existing) {
            if (unavailable(state, claimed) || !candidateSignature.equals(state.signature)) continue;
            double distance = distance(state, candidateX, candidateY);
            if (best == null || distance < closest) {
                best = state;
                closest = distance;
            }
        }
        if (best != null) return best;

        Set<String> candidateNames = names(candidateSignature);
        double bestScore = 0;
        closest = Double.POSITIVE_INFINITY;
        for (WindowState state : existing) {
            if (unavailable(state, claimed)) continue;
            Set<String> savedNames = names(state.signature);
            int common = 0;
            for (String name : candidateNames) if (savedNames.contains(name)) common++;
            if (common == 0) continue;
            double score = 2.0 * common / (candidateNames.size() + savedNames.size());
            double distance = distance(state, candidateX, candidateY);
            if (score > bestScore || (score == bestScore && distance < closest)) {
                best = state;
                bestScore = score;
                closest = distance;
            }
        }
        return best;
    }

    private static boolean unavailable(WindowState state, Set<String> claimed) {
        return state == null || (state.id != null && claimed.contains(state.id));
    }

    private static Set<String> names(String signature) {
        Set<String> names = new HashSet<>();
        if (signature == null || signature.isEmpty()) return names;
        String[] parts = signature.split("\u001F", -1);
        int limit = parts.length;
        if (parts[limit - 1].matches("#[0-9]+")) limit--;
        for (int index = 0; index < limit; index++) {
            if (!parts[index].isEmpty()) names.add(parts[index]);
        }
        return names;
    }

    private static double distance(WindowState state, double x, double y) {
        double distance = Math.hypot(state.x - x, state.y - y);
        return Double.isFinite(distance) ? distance : Double.POSITIVE_INFINITY;
    }
}
