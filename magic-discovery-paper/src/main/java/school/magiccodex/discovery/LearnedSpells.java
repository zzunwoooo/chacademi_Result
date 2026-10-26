package school.magiccodex.discovery;

import java.util.HashMap;
import java.util.Map;

/** Current knowledge and retry progress are separate from immutable acquisition/reward history. */
final class LearnedSpells {
    private final Map<String, Boolean> overrides = new HashMap<>();
    private final Map<String, Map<String, Double>> retries = new HashMap<>();

    void restore(Map<String, Boolean> stored, Map<String, Map<String, Double>> baselines) {
        // A load may finish after an admin write. Already-applied writes win over that snapshot.
        stored.forEach(overrides::putIfAbsent);
        baselines.forEach(retries::putIfAbsent);
    }

    void set(String spell, boolean learned, Map<String, Double> baseline) {
        overrides.put(spell, learned);
        retries.put(spell, Map.copyOf(baseline));
    }

    boolean learned(String spell, boolean acquired) { return overrides.getOrDefault(spell, acquired); }
    boolean retrying(String spell) { return Boolean.FALSE.equals(overrides.get(spell)); }
    Map<String, Double> retryBaseline(String spell) { return retries.getOrDefault(spell, Map.of()); }

    double baseline(String spell, String key) {
        return retrying(spell) ? retries.getOrDefault(spell, Map.of()).getOrDefault(key, 0d) : 0d;
    }

    boolean canCheckMeta(Definitions.Spell spell, boolean verifiedAction) {
        return !retrying(spell.id()) || verifiedAction;
    }

    boolean meets(Definitions.Spell spell, Map<String, Double> progress) {return meets(spell,progress,false);}

    boolean meets(Definitions.Spell spell, Map<String, Double> progress, boolean verifiedAction) {
        if (!retrying(spell.id())) return Definitions.meets(spell, progress);
        boolean hasCounter = spell.requirements().keySet().stream().anyMatch(key -> !ConditionRules.stateLike(key));
        if (!spell.requirements().entrySet().stream().allMatch(entry -> {
            String key = entry.getKey();
            double value = progress.getOrDefault(key, 0d);
            return (ConditionRules.stateLike(key) ? value : value - baseline(spell.id(), key)) >= entry.getValue();
        })) return false;
        // Merely republishing the same state after reconnect must not undo an admin reset.
        return hasCounter || verifiedAction || spell.requirements().isEmpty() || spell.requirements().keySet().stream()
                .anyMatch(key -> Double.compare(progress.getOrDefault(key, 0d), baseline(spell.id(), key)) != 0);
    }
}
