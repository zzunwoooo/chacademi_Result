package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 사망 시 아공간 배낭 다른 쪽 떨어뜨리기 (오너 2026-10-26): 고를 쪽·빈 쪽·증거 파일 왕복. */
class BagDeathDropsTest {
    private static byte[] page(byte[]... slots) {
        var list = new ArrayList<byte[]>();
        for (int i = 0; i < BagPageCodec.SLOTS; i++) list.add(i < slots.length ? slots[i] : new byte[0]);
        return BagPageCodec.encodePage(list);
    }

    @Test void picksEveryNonActivePageWithItems() {
        byte[][] pages = {page(new byte[]{1}), BagPageCodec.emptyPage(), page(new byte[]{2}, new byte[]{3})};
        assertEquals(List.of(1, 3), BagDeathDrops.pagesToDrop(pages, 2));
        assertEquals(List.of(3), BagDeathDrops.pagesToDrop(pages, 1));
        byte[][] blankItems = {page(), BagPageCodec.emptyPage(), BagPageCodec.emptyPage()};
        assertTrue(BagDeathDrops.pagesToDrop(blankItems, 2).isEmpty());   // 칸만 있고 아이템 없음 = 할 것 없음
    }

    @Test void emptiedIsAValidTransition() {
        byte[][] next = BagDeathDrops.emptied();
        assertEquals(3, next.length);
        for (byte[] p : next) assertTrue(BagPageCodec.isEmpty(p));
    }

    @Test void evidenceRoundTrip(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        byte[][] pages = {page(new byte[]{9, 9}), BagPageCodec.emptyPage(), page(new byte[]{7})};
        Path f = BagDeathDrops.save(dir, new BagDeathDrops.Evidence(id, 42, "wild/world 1,2,3", pages));
        assertEquals(BagDeathDrops.file(dir, id, 42), f);
        var back = BagDeathDrops.decode(Files.readAllBytes(f));
        assertEquals(id, back.owner());
        assertEquals(42, back.seq());
        assertEquals("wild/world 1,2,3", back.where());
        assertArrayEquals(pages[2], back.pages()[2]);
        assertThrows(java.io.IOException.class, () -> BagDeathDrops.decode(new byte[]{2}));
    }
}
