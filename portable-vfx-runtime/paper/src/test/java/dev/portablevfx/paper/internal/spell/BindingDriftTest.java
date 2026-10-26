package dev.portablevfx.paper.internal.spell;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BindingDriftTest {
    private static Map<String, Object> star(Object finishAfter, List<Integer> offset) {
        Map<String, Object> mark = new LinkedHashMap<>();
        mark.put("effect", "claude:fallingstar/mark"); mark.put("finish-after-ticks", finishAfter);
        Map<String, Object> meteor = new LinkedHashMap<>();
        meteor.put("effect", "claude:fallingstar/meteor"); meteor.put("offset", offset);
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("range", 20); binding.put("trajectory", "targeted-meteor");
        binding.put("phases", List.of(mark, meteor)); binding.put("review", "bundled note");
        return binding;
    }

    @Test void identicalBindingsAndDocumentationOnlyChangesAreNotReported() {
        var bundled = Map.<String, Object>of("falling_star", star(-1, List.of(-3, 24, 19)));
        var deployedStar = star(-1, List.of(-3, 24, 19)); deployedStar.put("review", "operator note");
        var deployed = new LinkedHashMap<String, Object>(Map.of("falling_star", deployedStar));
        deployed.put("custom_spell", Map.of("range", 3));
        assertEquals(List.of(), BindingDrift.differing(deployed, bundled));
    }

    @Test void behaviouralDifferencesAndMissingBindingsAreListedSorted() {
        var bundled = Map.<String, Object>of("falling_star", star(-1, List.of(-3, 24, 19)), "wind_push", Map.of("range", 10));
        var staleMark = Map.<String, Object>of("falling_star", star(40, List.of(-3, 24, 19)));
        assertEquals(List.of("falling_star", "wind_push"), BindingDrift.differing(staleMark, bundled));
        var staleOffset = Map.<String, Object>of("falling_star", star(-1, List.of(0, 24, 0)), "wind_push", Map.of("range", 10.0));
        assertEquals(List.of("falling_star"), BindingDrift.differing(staleOffset, bundled), "20 and 20.0 are equal; offsets are not");
    }
}
