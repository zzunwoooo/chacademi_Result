package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BalloonRulesTest {
    private static final BalloonRules.Settings S = BalloonRules.DEFAULT;

    @Test void exactTiersGiveBoxes() {
        var r = BalloonRules.reward(S, 100);
        assertEquals(BalloonRules.Kind.BOX, r.kind());
        assertEquals("item:api_box_normal", r.box().item()); assertEquals(1, r.box().amount());
        r = BalloonRules.reward(S, 1000);
        assertEquals("item:api_box_normal", r.box().item()); assertEquals(11, r.box().amount());
        r = BalloonRules.reward(S, 500);
        assertEquals("item:api_box_premium", r.box().item()); assertEquals(1, r.box().amount());
        r = BalloonRules.reward(S, 5000);
        assertEquals("item:api_box_premium", r.box().item()); assertEquals(11, r.box().amount());
    }

    @Test void tenThousandIsDeathWithPremiumForAll() {
        var r = BalloonRules.reward(S, 10_000);
        assertEquals(BalloonRules.Kind.DEATH, r.kind());
        assertEquals("item:api_box_premium", r.box().item()); assertEquals(1, r.box().amount());
    }

    @Test void otherCountsBecomeMileage() {
        for (long c : new long[]{1, 99, 101, 250, 999, 1001, 4999, 9999, 10_001, 20_000}) {
            var r = BalloonRules.reward(S, c);
            assertEquals(BalloonRules.Kind.MILEAGE, r.kind(), "count " + c);
            assertEquals(c, r.mileage());
        }
        assertEquals(BalloonRules.Kind.NONE, BalloonRules.reward(S, 0).kind());
        assertEquals(BalloonRules.Kind.NONE, BalloonRules.reward(S, -5).kind());
        assertEquals(BalloonRules.Kind.NONE, BalloonRules.reward(S, BalloonRules.MAX_MILEAGE + 1).kind());
    }

    @Test void fallbackOffAndDeathOff() {
        var off = BalloonRules.settings(true, S.tiers(), 0, S.deathBox(), false, S.messages());
        assertEquals(BalloonRules.Kind.NONE, BalloonRules.reward(off, 250).kind());
        assertEquals(BalloonRules.Kind.NONE, BalloonRules.reward(off, 10_000).kind());   // 즉사 끔 + 마일리지 끔
        assertEquals(BalloonRules.Kind.BOX, BalloonRules.reward(off, 100).kind());
        assertThrows(IllegalArgumentException.class, () -> BalloonRules.settings(true, S.tiers(), 100, S.deathBox(), true, S.messages()));
    }

    @Test void messagesMatchOwnerExamples() {
        var v = new HashMap<String, String>();
        v.put("count", "1000"); v.put("reward", BalloonRules.reward(S, 1000).box().text());
        assertEquals("[API] 1000개 후원으로 마법상자 11개가 우편함에 지급되었습니다.", BalloonRules.fill(S.messages().box(), v));
        v.put("count", "250"); v.put("reward", "마일리지 250");
        assertEquals("[API] 250개 후원으로 마일리지 250이 지급되었습니다.", BalloonRules.fill(S.messages().mileage(), v));
        v.put("nickname", "준우"); v.put("count", "10000");
        assertEquals("[준우] 님이 10000개 후원으로 사망하였습니다!", BalloonRules.fill(S.messages().announce(), v));
        assertEquals("{unknown}", BalloonRules.fill("{unknown}", v));
    }

    @Test void boxValidationAndStacks() {
        assertThrows(IllegalArgumentException.class, () -> BalloonRules.box("api_box_normal", 1, "x", "t"));
        assertThrows(IllegalArgumentException.class, () -> BalloonRules.box("item:a", 0, "x", "t"));
        assertEquals("item:a", BalloonRules.box(" ITEM:A ", 1, "", "t").item());
        assertEquals(List.of(11), BalloonRules.stacks(11, 64));
        assertEquals(Collections.nCopies(11, 1), BalloonRules.stacks(11, 1));
        assertEquals(List.of(16, 16, 3), BalloonRules.stacks(35, 16));
        assertEquals(1000L, BalloonRules.count("1,000", "t"));
        assertThrows(IllegalArgumentException.class, () -> BalloonRules.count("0", "t"));
    }
}
