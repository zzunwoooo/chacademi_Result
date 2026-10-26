package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.AlchemyAdminProtocol;
import school.magiccodex.protocol.AlchemyAdminProtocol.*;
import school.magiccodex.protocol.AlchemyProtocol;

class AlchemyAdminTest {
    static AlchemyProtocol.Stack s(String k, int n) { return new AlchemyProtocol.Stack(k, n); }

    @Test void requestsRoundTrip() {
        var row = new Row("", "회복 물약", "item:healing_potion", 2, 30, false, 0, List.of(s("item:lily", 1), s("minecraft:sugar", 3)));
        var save = AlchemyAdminProtocol.request(AlchemyAdminProtocol.request(new Request(AlchemyAdminProtocol.SAVE, 4, row, "")));
        assertEquals(row, save.row());
        var del = AlchemyAdminProtocol.request(AlchemyAdminProtocol.request(new Request(AlchemyAdminProtocol.DELETE, 5, null, "recipe_3")));
        assertEquals("recipe_3", del.id());
        assertThrows(IllegalArgumentException.class, () -> new Request(AlchemyAdminProtocol.DELETE, 1, null, ""));
        assertThrows(IllegalArgumentException.class, () -> new Row("Bad Id", "x", "", 1, 0, true, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Row("", "x", "", AlchemyAdminProtocol.MAX_RESULT_AMOUNT + 1, 0, true, 0, List.of()));
    }

    @Test void stateAndResultRoundTrip() {
        var rows = List.of(new Row("recipe_1", "마력 정수", "item:mana_essence", 1, 45, true, 1, List.of(s("item:mana_crystal", 2))),
                new Row("recipe_2", "", "", 1, 0, false, 2, List.of()));
        var state = new State(AlchemyAdminProtocol.KIND_OPEN, 6, List.of(new AlchemyProtocol.Item("item:mana_crystal", new byte[]{7})),
                List.of(new Held("item:mana_crystal", 12, true), new Held("minecraft:sugar", 3, false)), rows, "");
        var back = (State) AlchemyAdminProtocol.decode(AlchemyAdminProtocol.encode(state));
        assertEquals(rows, back.rows()); assertEquals(2, back.inventory().size()); assertFalse(back.inventory().get(1).material());
        var r = (Result) AlchemyAdminProtocol.decode(AlchemyAdminProtocol.encode(new Result(9, true, "recipe_1", "'마력 정수' 저장 완료")));
        assertTrue(r.ok()); assertEquals("recipe_1", r.id());
    }

    @Test void editorLayoutNeverOverlaps() {
        for (int slots = 1; slots <= 8; slots++) {
            var parts = new ArrayList<>(AlchemyAdminLayout.parts(slots).entrySet());
            for (int i = 0; i < parts.size(); i++) for (int j = i + 1; j < parts.size(); j++)
                assertFalse(parts.get(i).getValue().overlaps(parts.get(j).getValue()), slots + " slots: " + parts.get(i).getKey() + " overlaps " + parts.get(j).getKey());
        }
        for (var e : AlchemyAdminLayout.parts(6).entrySet()) {
            if (e.getKey().equals("close")) continue;
            var b = e.getValue();
            boolean inLeft = AlchemyLayoutTest.inside(b, AlchemyLayout.LEFT), inRight = AlchemyLayoutTest.inside(b, AlchemyLayout.RIGHT);
            assertTrue(inLeft || inRight, e.getKey() + " leaves the frames");
        }
        // the circle art clears the name field and the experience row
        float top = AlchemyAdminLayout.CIRCLE_Y - AlchemyAdminLayout.CIRCLE_SIZE / 2, bottom = AlchemyAdminLayout.CIRCLE_Y + AlchemyAdminLayout.CIRCLE_SIZE / 2;
        assertTrue(top >= AlchemyAdminLayout.NAME_BOX.bottom());
        assertTrue(bottom <= AlchemyAdminLayout.EXP_BOX.y());
    }
}
