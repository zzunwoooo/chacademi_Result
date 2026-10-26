package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.AlchemyProtocol;
import school.magiccodex.protocol.AlchemyProtocol.*;
import school.magiccodex.protocol.AlchemyRules;

class AlchemyProtocolTest {
    static AlchemyProtocol.Stack s(String key, int n) { return new AlchemyProtocol.Stack(key, n); }

    @Test void requestRoundTripsAndRejectsBadInput() {
        var r = AlchemyProtocol.request(AlchemyProtocol.request(new Request(AlchemyProtocol.CRAFT, 7, List.of(s("item:lily", 1), s("minecraft:sugar", 3)))));
        assertEquals(AlchemyProtocol.CRAFT, r.action()); assertEquals(7, r.sequence());
        assertEquals(List.of(s("item:lily", 1), s("minecraft:sugar", 3)), r.slots());
        assertThrows(IllegalArgumentException.class, () -> s("Lily", 1));
        assertThrows(IllegalArgumentException.class, () -> s("item:lily", 65));
        assertThrows(IllegalArgumentException.class, () -> new Request(AlchemyProtocol.CRAFT, 1, Collections.nCopies(9, s("item:a", 1))));
        assertThrows(IllegalArgumentException.class, () -> AlchemyProtocol.request(new byte[]{0, 0, 0, 9}));
    }

    @Test void stateAndResultRoundTrip() {
        var recipe = new AlchemyProtocol.Recipe("healing_potion", "회복 물약", "item:healing_potion", 1, 30, List.of(s("item:lily", 1), s("item:salt", 2)));
        var state = new State(AlchemyProtocol.KIND_OPEN, 6, List.of(new Item("item:lily", new byte[]{1, 2, 3}), new Item("item:healing_potion", new byte[0])),
                List.of(new AlchemyProtocol.Material("item:lily", 5, true), new AlchemyProtocol.Material("item:salt", 0, false)), List.of(recipe), 4, "");
        var back = (State) AlchemyProtocol.decode(AlchemyProtocol.encode(state));
        assertEquals(6, back.slots()); assertEquals(4, back.locked()); assertEquals(List.of(recipe), back.recipes());
        assertArrayEquals(new byte[]{1, 2, 3}, back.items().get(0).preview());
        assertEquals(new AlchemyProtocol.Material("item:lily", 5, true), back.materials().get(0));
        var result = (Result) AlchemyProtocol.decode(AlchemyProtocol.encode(new Result(9, AlchemyProtocol.DISCOVERED, "healing_potion", "item:healing_potion", 1, true, "새로운 레시피 발견! · 회복 물약")));
        assertEquals(AlchemyProtocol.DISCOVERED, result.outcome()); assertTrue(result.mailed()); assertEquals("healing_potion", result.recipeId());
        var nothing = (Result) AlchemyProtocol.decode(AlchemyProtocol.encode(new Result(1, AlchemyProtocol.NOTHING, "", "", 0, false, "아무 반응이 없다.")));
        assertEquals("", nothing.recipeId());
    }

    @Test void largeStateFitsOnePacket() {
        var items = new ArrayList<Item>(); var recipes = new ArrayList<AlchemyProtocol.Recipe>();
        for (int i = 0; i < 100; i++) items.add(new Item("item:material_" + i, new byte[AlchemyProtocol.MAX_PREVIEW]));
        for (int i = 0; i < 200; i++) recipes.add(new AlchemyProtocol.Recipe("recipe_" + i, "레시피 이름 " + i, "item:result_" + i, 1, i,
                List.of(s("item:material_" + i % 100, 2), s("item:material_" + (i + 1) % 100, 1), s("item:material_" + (i + 2) % 100, 64))));
        byte[] frame = AlchemyProtocol.encode(new State(AlchemyProtocol.KIND_STATE, 6, items, List.of(), recipes, 50, ""));
        assertTrue(frame.length <= AlchemyProtocol.MAX_BYTES);
        assertEquals(200, ((State) AlchemyProtocol.decode(frame)).recipes().size());
    }

    @Test void orderNeverMattersAndSameItemsAddUp() {
        var recipe = Map.of("item:lily", 1, "item:salt", 2);
        assertTrue(AlchemyRules.matches(recipe, AlchemyRules.merge(List.of(s("item:salt", 1), s("item:lily", 1), s("item:salt", 1)))));
        assertTrue(AlchemyRules.matches(recipe, AlchemyRules.merge(List.of(s("item:salt", 2), s("item:lily", 1)))));
        assertFalse(AlchemyRules.matches(recipe, AlchemyRules.merge(List.of(s("item:salt", 3), s("item:lily", 1)))));
    }

    @Test void nearHints() {
        var recipe = Map.of("a:a", 1, "a:b", 1, "a:c", 2);
        assertEquals(AlchemyProtocol.NEAR_AMOUNT, AlchemyRules.closeness(recipe, Map.of("a:a", 1, "a:b", 1, "a:c", 1)));
        assertEquals(AlchemyProtocol.NEAR, AlchemyRules.closeness(recipe, Map.of("a:a", 1, "a:b", 1)));              // one missing
        assertEquals(AlchemyProtocol.NEAR, AlchemyRules.closeness(recipe, Map.of("a:a", 1, "a:b", 1, "a:x", 2)));     // one swapped
        assertEquals(AlchemyProtocol.NEAR, AlchemyRules.closeness(recipe, Map.of("a:a", 1, "a:b", 1, "a:c", 2, "a:x", 1))); // one extra
        assertEquals(-1, AlchemyRules.closeness(recipe, Map.of("a:a", 1)));                                          // two missing
        assertEquals(-1, AlchemyRules.closeness(recipe, Map.of("a:a", 1, "a:x", 1, "a:y", 1)));                      // two swapped
        assertEquals(-1, AlchemyRules.closeness(recipe, recipe));                                                   // exact is a match, not a hint
        assertEquals(-1, AlchemyRules.closeness(Map.of("a:a", 1), Map.of("a:x", 1)));                                // nothing shared
        assertEquals(AlchemyProtocol.NEAR_AMOUNT, AlchemyRules.hint(List.of(Map.of("a:z", 1), recipe), Map.of("a:a", 2, "a:b", 1, "a:c", 2)));
        assertEquals(AlchemyProtocol.NOTHING, AlchemyRules.hint(List.of(recipe), Map.of("a:q", 1)));
    }

    @Test void vanillaExperience() {
        assertEquals(0, AlchemyRules.pointsAt(0));
        assertEquals(7, AlchemyRules.pointsAt(1));
        assertEquals(352, AlchemyRules.pointsAt(16));   // vanilla table: level 16 = 352 points
        assertEquals(1395, AlchemyRules.pointsAt(30));  // level 30 = 1395 points
        assertEquals(1507, AlchemyRules.pointsAt(31));
        assertEquals(1395 + 56, AlchemyRules.total(30, .5f));
        for (long t : new long[]{0, 6, 7, 351, 352, 1000, 1395, 1450, 99999}) {
            float[] lp = AlchemyRules.fromTotal(t);
            assertEquals(t, AlchemyRules.total((int) lp[0], lp[1]), "points " + t);
        }
    }
}
