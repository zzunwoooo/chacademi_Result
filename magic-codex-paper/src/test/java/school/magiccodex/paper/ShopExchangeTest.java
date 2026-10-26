package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** 교환 상품 비용 계산 (#98, 2026-10-26). Bukkit 없이 도는 순수 로직만. */
class ShopExchangeTest {
    @Test void parseAcceptsKeyAndCount() {
        var c = ShopExchange.parse("item:artifact_shard 10");
        assertEquals("item:artifact_shard", c.item());
        assertEquals(10, c.count());
        assertEquals(new ShopExchange.Cost("minecraft:diamond", 3), ShopExchange.parse("diamond ×3"));
        assertThrows(IllegalArgumentException.class, () -> ShopExchange.parse("item:artifact_shard"));
        assertThrows(IllegalArgumentException.class, () -> ShopExchange.parse("item:artifact_shard 0"));
        assertThrows(IllegalArgumentException.class, () -> ShopExchange.parse("Item Shard 10"));
    }

    @Test void validateAllowsOffOrBothValid() {
        ShopExchange.validate("", 0);
        ShopExchange.validate("item:artifact_shard", 80);
        assertThrows(IllegalArgumentException.class, () -> ShopExchange.validate("item:artifact_shard", 0));
        assertThrows(IllegalArgumentException.class, () -> ShopExchange.validate("", 5));
        assertThrows(IllegalArgumentException.class, () -> ShopExchange.validate("item:artifact_shard", ShopExchange.MAX_COUNT + 1));
    }

    @Test void takeRemovesOnlyThatKeyFromTheFront() {
        String[] keys = {"item:artifact_shard", null, "item:other", "item:artifact_shard"};
        int[] amounts = {6, 64, 5, 64};
        assertEquals(70, ShopExchange.held(keys, amounts, "item:artifact_shard"));
        assertArrayEquals(new int[]{0, 64, 5, 60}, ShopExchange.take(keys, amounts, "item:artifact_shard", ShopExchange.total(10, 1)));
        assertNull(ShopExchange.take(keys, amounts, "item:artifact_shard", ShopExchange.total(10, 8)));
        assertArrayEquals(new int[]{6, 64, 5, 64}, amounts, "모자라면 원래 배열은 그대로");
    }
}
