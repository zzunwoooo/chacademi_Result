package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.LootBoxProtocol;
import school.magiccodex.protocol.LootBoxProtocol.*;

class LootBoxProtocolTest {
    @Test void playRoundTripTenResults() {
        var results = new ArrayList<Result>();
        for (int i = 0; i < 10; i++) results.add(new Result(i % 4, new byte[]{(byte) i, 1}, "보상" + i));
        var p = LootBoxProtocol.play(LootBoxProtocol.encode(new Play(5, "box", "API 상자", 3, results, "", 100)));
        assertEquals(10, p.results().size()); assertEquals(3, p.grade()); assertEquals(2, p.results().get(6).grade());
        assertArrayEquals(new byte[]{9, 1}, p.results().get(9).item());
        assertEquals(100, p.speed());
    }

    @Test void playCarriesLargeRealItemsUnchanged() {
        var results = new ArrayList<Result>();
        for (int i = 0; i < 10; i++) results.add(new Result(0, new byte[30000], "x"));
        assertEquals(10, LootBoxProtocol.play(LootBoxProtocol.encode(new Play(1, "b", "t", 0, results, "", 100))).results().size());
    }

    @Test void playRejectsEmptyAndBadGrade() {
        byte[] empty = LootBoxProtocol.encode(new Play(1, "b", "t", 0, List.of(), "", 100));
        assertThrows(IllegalArgumentException.class, () -> LootBoxProtocol.play(empty));
        byte[] bad = LootBoxProtocol.encode(new Play(1, "b", "t", 4, List.of(new Result(0, new byte[]{1}, "x")), "", 100));
        assertThrows(IllegalArgumentException.class, () -> LootBoxProtocol.play(bad));
    }

    @Test void adminRequestBounds() {
        var ok = new Request(LootBoxProtocol.ADD, 1, 9, "box", 1, -1, 2, 3, 10, 4, "minecraft:diamond", "", 0);
        assertEquals(ok, LootBoxProtocol.request(LootBoxProtocol.encode(ok)));
        for (var bad : List.of(new Request(LootBoxProtocol.ADD, 1, 9, "box", 1, -1, 2, 0, 10, 4, "", "", 0),
                new Request(LootBoxProtocol.EDIT, 1, 9, "box", 1, -1, 2, 1, 10, -1, "", "", 0),
                new Request(LootBoxProtocol.LIST, 1, 9, "BOX!", 1, -1, 0, 0, 0, -1, "", "", 0),
                new Request(LootBoxProtocol.RENAME, 1, 9, "box", 1, -1, 0, 0, 0, -1, "", " ", 0)))
            assertThrows(IllegalArgumentException.class, () -> LootBoxProtocol.request(LootBoxProtocol.encode(bad)));
    }

    @Test void adminResponseRoundTrip() {
        var r = new Response(2, 9, "box", 4, "", List.of(new Box("box", "API 상자", 2, true)), "API 상자", "ItemsAdder item:key",
                new byte[]{1}, 11, new long[]{10, 1, 0, 0}, List.of(new Reward(0, 0, 1, 10, "vanilla minecraft:diamond", new byte[]{2})), 0, -1);
        var back = LootBoxProtocol.response(LootBoxProtocol.encode(r));
        assertEquals(11, back.totalWeight()); assertEquals(1, back.rewards().size()); assertEquals(10, back.gradeWeights()[0]);
    }
}
