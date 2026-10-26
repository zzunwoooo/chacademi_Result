package dev.portablevfx.paper.internal.spell;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Read-only comparison of a deployed spell-bindings.yml against the bundled default. Never
 * rewrites the deployed file: operators merge by hand (see README.ko.md).
 */
public final class BindingDrift {
    /** Provenance/review notes do not change playback and are ignored. */
    static final Set<String> DOCUMENTATION_KEYS = Set.of("evidence", "review", "numeric-provenance",
            "timing-provenance", "anchor-verification");

    private BindingDrift() {}

    /**
     * Spell IDs whose deployed binding differs from (or is missing versus) the bundled default.
     * Inputs are plain YAML trees (Map/List/scalars) keyed by spell ID. IDs only present in the
     * deployed file are operator additions and are not reported.
     */
    public static List<String> differing(Map<String, ?> deployed, Map<String, ?> bundled) {
        Set<String> out = new TreeSet<>();
        for (var entry : bundled.entrySet()) {
            Object mine = deployed.get(entry.getKey());
            if (mine == null || !same(strip(mine), strip(entry.getValue()))) out.add(entry.getKey());
        }
        return new ArrayList<>(out);
    }

    private static Object strip(Object binding) {
        if (!(binding instanceof Map<?, ?> map)) return binding;
        var copy = new java.util.LinkedHashMap<Object, Object>(map);
        copy.keySet().removeAll(DOCUMENTATION_KEYS);
        return copy;
    }

    static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) return false;
            for (Object key : x.keySet()) if (!same(x.get(key), y.get(key))) return false;
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        return a == null ? b == null : a.equals(b);
    }
}
