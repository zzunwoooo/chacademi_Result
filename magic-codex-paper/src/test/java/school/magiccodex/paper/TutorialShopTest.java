package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import school.magiccodex.database.DatabaseSettings;

/** 피피 튜토리얼 전용 재료 상점 (메모 #12): 설정·판정과 상점 DB 의 대상·구매 집계. */
class TutorialShopTest {
    @TempDir Path dir;

    private static TutorialShop.Config parse(String text) throws Exception {
        var y = new YamlConfiguration(); y.loadFromString(text); return TutorialShop.parse(y);
    }

    @Test void emptyShopMeansOff() throws Exception {
        var c = parse("shop: \"\"\ngeneral-shops: [pipi]\nrequired: {}\n");
        assertFalse(c.enabled());
        assertFalse(c.redirects("pipi"));
        assertEquals(TutorialShop.DEFAULT_SIGNAL, c.signal());
    }

    @Test void redirectsOnlyTheGeneralShops() throws Exception {
        var c = parse("shop: pipi_tutorial\ngeneral-shops: [pipi]\nrequired:\n  교복: 1\n  견습 지팡이: 2\n");
        assertTrue(c.redirects("pipi"));
        assertFalse(c.redirects("other"));
        assertFalse(c.redirects("pipi_tutorial"));
        assertEquals(Map.of("교복", 1, "견습 지팡이", 2), c.required());
        assertThrows(IllegalArgumentException.class, () -> parse("shop: \"Bad Id\"\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("shop: a\nrequired:\n  교복: 0\n"));
    }

    @Test void completeNeedsEveryRequiredAmount() {
        var products = List.of(new ShopStore.Product("p1", "교복", new byte[]{1}, "10", ""), new ShopStore.Product("p2", "견습 지팡이", new byte[]{1}, "20", ""),
                new ShopStore.Product("p3", "판매만", new byte[]{1}, "", "5"));
        var missing = new ArrayList<String>();
        var need = TutorialShop.requiredIds(products, Map.of("교복", 1, "견습 지팡이", 2, "판매만", 1), missing);
        assertEquals(Map.of("p1", 1, "p2", 2), need);
        assertEquals(List.of("판매만"), missing);
        assertFalse(TutorialShop.complete(need, Map.of("p1", 1, "p2", 1)));   // 일부만 사면 넘어가지 않는다
        assertTrue(TutorialShop.complete(need, Map.of("p1", 3, "p2", 2)));
        assertFalse(TutorialShop.complete(Map.of(), Map.of("p1", 1)));        // 필요 재료 미정 = 신호 없음
    }

    @Test void storeCountsOnlyPaidBuysAfterTheStart() throws Exception {
        UUID owner = UUID.randomUUID();
        try (var store = new ShopStore(DatabaseSettings.load(dir.resolve("none.properties")), dir.resolve("shop.db"))) {
            store.create("tut", "튜토리얼");
            String a = store.add("tut", "교복", new byte[]{1}, "10", "");
            long rev = store.catalog().get("tut").revision();
            var before = store.prepare(owner, UUID.randomUUID().toString(), "tut", rev, a, 1, false, "s");
            store.state(before.id(), "prepared", "paying"); store.state(before.id(), "paying", "paid"); store.state(before.id(), "paid", "done");
            assertEquals(0, store.tutorialStarted(owner));
            Thread.sleep(5);
            long started = store.tutorialStart(owner, false);
            assertEquals(started, store.tutorialStart(owner, false));            // 시작명령이 다시 실행돼도 기록 유지
            assertTrue(store.bought(owner, Set.of(a), started).isEmpty());       // 대상이 되기 전 구매는 세지 않는다
            var paid = store.prepare(owner, UUID.randomUUID().toString(), "tut", rev, a, 2, false, "s");
            store.state(paid.id(), "prepared", "paying"); store.state(paid.id(), "paying", "paid");
            assertEquals(Map.of(a, 2), store.bought(owner, Set.of(a), started));  // paid already counts before delivery
            assertThrows(IllegalStateException.class, () -> store.prepare(owner, UUID.randomUUID().toString(), "tut", rev, a, 5, false, "s"),
                    "an unfinished paid order must continue to block a second purchase");
            store.state(paid.id(), "paid", "done");
            var failed = store.prepare(owner, UUID.randomUUID().toString(), "tut", rev, a, 5, false, "s");
            store.state(failed.id(), "prepared", "failed");
            assertEquals(Map.of(a, 2), store.bought(owner, Set.of(a), started));  // 결제 안 된(failed) 구매는 빠진다
            assertTrue(store.tutorialEnd(owner));
            assertEquals(0, store.tutorialStarted(owner));
            assertFalse(store.tutorialEnd(owner));
        }
    }
}
