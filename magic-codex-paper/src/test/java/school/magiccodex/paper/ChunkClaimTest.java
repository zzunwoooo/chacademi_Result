package school.magiccodex.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import school.magiccodex.database.DatabaseSettings;
import school.magiccodex.protocol.ChunkClaimProtocol;
import school.magiccodex.protocol.ChunkClaimProtocol.*;

/** 야생 청크 점유 (메모 #88): 규칙·메모리 표·저장소(SQLite)·패킷. */
class ChunkClaimTest {
    @TempDir Path dir;
    private static final DatabaseSettings SQLITE = new DatabaseSettings(false, "", "", "");
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

    @Test void chunkKeysRoundTripNegativeCoordinates() {
        for (int[] xz : new int[][]{{0, 0}, {-1, -1}, {123456, -98765}, {-30_000_000 >> 4, 30_000_000 >> 4}}) {
            long k = ChunkClaimRules.key(xz[0], xz[1]);
            assertEquals(xz[0], ChunkClaimRules.keyX(k)); assertEquals(xz[1], ChunkClaimRules.keyZ(k));
        }
        assertNotEquals(ChunkClaimRules.key(1, 0), ChunkClaimRules.key(0, 1));
        assertEquals(-1, ChunkClaimRules.chunk(-1)); assertEquals(-1, ChunkClaimRules.chunk(-16)); assertEquals(-2, ChunkClaimRules.chunk(-17)); assertEquals(0, ChunkClaimRules.chunk(15));
    }

    @Test void exclusionCircleTouchesChunks() {
        // 중심 (0,0) 반경 20: 청크 (1,0) = 블록 16..32 → 가장 가까운 점 16 ≤ 20, 청크 (2,0) = 32.. → 32 > 20
        assertTrue(ChunkClaimRules.overlaps(0, 0, 0, 0, 20));
        assertTrue(ChunkClaimRules.overlaps(1, 0, 0, 0, 20));
        assertFalse(ChunkClaimRules.overlaps(2, 0, 0, 0, 20));
        assertTrue(ChunkClaimRules.overlaps(-2, 0, 0, 0, 20));    // 블록 -32..-16 → 가장 가까운 점 -16
        assertFalse(ChunkClaimRules.overlaps(-3, 0, 0, 0, 20));
        assertFalse(ChunkClaimRules.overlaps(1, 1, 0, 0, 20));    // 대각선 (16,16) → 22.6
        assertFalse(ChunkClaimRules.overlaps(0, 0, 0, 0, 0), "반경 0 = 꺼진 구역");
    }

    @Test void configRestrictionUsesWorldListAndExclusions() {
        var d = ChunkClaimRules.DEFAULT;
        var s = new ChunkClaimRules.Settings(true, d.item(), d.itemName(), Set.of("wild"), Set.of("world"),
                List.of(new ChunkClaimRules.Exclusion("world", 100, 100, 10)), 0, 1, true, Set.of(), 1500, "소유 가능", "제한", Map.of());
        assertTrue(ChunkClaimRules.configRestricted(s, "world_nether", 0, 0), "대상 세계 밖");
        assertFalse(ChunkClaimRules.configRestricted(s, "world", 0, 0));
        assertTrue(ChunkClaimRules.configRestricted(s, "world", 6, 6), "제외 구역 (블록 96..112)");
        assertTrue(ChunkClaimRules.configRestricted(s, "other", 6, 6), "다른 세계의 같은 좌표는 대상 밖이라 제한");
        assertTrue(s.activeOn("wild")); assertFalse(s.activeOn("school")); assertFalse(s.activeOn(""));
        assertEquals("가나님의 땅이에요", s.message("denied", Map.of("name", "가나")));
        assertEquals("한 사람당 3칸까지예요", s.message("limit-body", Map.of("max", "3")));
    }

    @Test void blockModifyingItems() {
        for (String m : List.of("FLINT_AND_STEEL", "BONE_MEAL", "IRON_HOE", "DIAMOND_SHOVEL", "NETHERITE_AXE", "COW_SPAWN_EGG", "ARMOR_STAND", "ITEM_FRAME", "OAK_BOAT", "MINECART", "RED_DYE"))
            assertTrue(ChunkClaimRules.modifiesBlocks(m), m);
        for (String m : List.of("BREAD", "BOW", "SHIELD", "POTION", "DIAMOND_SWORD", "STICK", "PAPER", ""))
            assertFalse(ChunkClaimRules.modifiesBlocks(m), m);
        assertFalse(ChunkClaimRules.modifiesBlocks("IRON_PICKAXE"), "곡괭이는 도끼가 아님 (블록 부수기는 BlockBreakEvent 가 막음)");
    }

    @Test void indexOwnersTrustAndCounts() {
        var i = new ChunkClaimRules.Index();
        i.put("world", 3, -4, a, 1);
        i.put("world", 4, -4, a, 2);
        i.put("world2", 3, -4, b, 3);
        assertEquals(a, i.owner("world", 3, -4)); assertEquals(b, i.owner("world2", 3, -4)); assertNull(i.owner("world", 5, -4));
        assertEquals(2, i.count(a)); assertEquals(1, i.count(b)); assertEquals(3, i.size());
        assertTrue(i.allowed(a, "world", 3, -4)); assertFalse(i.allowed(b, "world", 3, -4)); assertTrue(i.allowed(b, "world", 9, 9), "주인 없음");
        i.trust(a, b, true);
        assertTrue(i.allowed(b, "world", 4, -4), "허용 명단은 주인의 모든 청크");
        assertFalse(i.allowed(c, "world", 4, -4));
        i.trust(a, b, false);
        assertFalse(i.allowed(b, "world", 4, -4));
        assertEquals(a, i.remove("world", 3, -4)); assertNull(i.remove("world", 3, -4));
        assertEquals(1, i.count(a));
        i.replaceTrust(Map.of(a, List.of(c)));
        assertTrue(i.trusted(a, c)); assertFalse(i.trusted(a, b));
        assertEquals(1, i.claimsOf(a).size());
    }

    @Test void storeClaimIsFirstComeAndRespectsLimit() throws Exception {
        try (var s = new ChunkClaimStore(SQLITE, dir.resolve("chunk-claims.db"))) {
            assertEquals(ChunkClaimStore.ClaimResult.CLAIMED, s.claim("world", 1, 2, a, 2, "wild", 10));
            assertEquals(ChunkClaimStore.ClaimResult.TAKEN, s.claim("world", 1, 2, b, 0, "wild", 11), "같은 청크는 먼저 넣은 사람만");
            assertEquals(ChunkClaimStore.ClaimResult.CLAIMED, s.claim("world", 1, 3, a, 2, "wild", 12));
            assertEquals(ChunkClaimStore.ClaimResult.LIMIT, s.claim("world", 1, 4, a, 2, "wild", 13), "최대 2칸");
            assertEquals(ChunkClaimStore.ClaimResult.CLAIMED, s.claim("world", 1, 4, a, 0, "wild", 14), "0 = 제한 없음");
            assertEquals(3, s.claimsOf(a).size());
            assertEquals(3, s.load("wild", List.of("world")).size());
            assertTrue(s.load("wild", List.of("other")).isEmpty());
            // 검수 2026-10-26: 야생2(wild2)는 다른 서버 — 같은 세계 이름·같은 좌표라도 따로 점유되고, 야생1 목록에 섞이지 않는다
            assertEquals(ChunkClaimStore.ClaimResult.CLAIMED, s.claim("world", 1, 2, b, 0, "wild2", 16), "다른 서버의 같은 좌표는 따로");
            assertTrue(s.load("wild", List.of("world")).stream().noneMatch(r -> r.server().equals("wild2")));
            assertEquals(1, s.load("wild2", List.of("world")).size());
            assertNull(s.release("wild2", "world", 1, 2, a), "야생2 의 그 땅은 b 것");
            assertNull(s.release("wild", "world", 1, 2, b), "남의 땅은 판매 못 함");
            assertEquals(a, s.release("wild", "world", 1, 2, a));
            assertNull(s.release("wild", "world", 1, 2, a), "두 번 판매 안 됨 (땅문서 두 장 방지)");
            assertEquals(a, s.release("wild", "world", 1, 3, null), "관리자 해제");
            assertEquals(b, s.release("wild2", "world", 1, 2, b));
            assertEquals(ChunkClaimStore.ClaimResult.CLAIMED, s.claim("world", 1, 2, b, 0, "wild", 15), "판 땅은 다시 점유 가능");
            assertTrue(s.trust(a, b, true, 1)); assertFalse(s.trust(a, b, true, 2)); assertTrue(s.trust(a, c, true, 3));
            assertEquals(Set.of(b, c), new HashSet<>(s.trust().get(a)));
            assertTrue(s.trust(a, b, false, 4)); assertFalse(s.trust(a, b, false, 5));
            assertEquals(List.of(c), s.trust().get(a));
        }
        try (var s = new ChunkClaimStore(SQLITE, dir.resolve("chunk-claims.db"))) {
            assertEquals(2, s.load("wild", List.of("world")).size(), "다시 열어도 그대로");
        }
    }

    @Test void protocolRoundTrip() {
        var cells = new ArrayList<Cell>();
        for (int i = 0; i < 9; i++) cells.add(i == 4 ? new Cell(ChunkClaimProtocol.MINE, "가나다") : i == 0 ? new Cell(ChunkClaimProtocol.RESTRICTED, "") : i == 1 ? new Cell(ChunkClaimProtocol.OTHER, "라마") : new Cell(ChunkClaimProtocol.FREE, ""));
        var view = new View(true, "world", -3, 7, 1, "소유 가능", "제한", cells);
        assertEquals(view, ChunkClaimProtocol.decode(ChunkClaimProtocol.encode(view)));
        assertEquals("가나다", ((View) ChunkClaimProtocol.decode(ChunkClaimProtocol.encode(view))).cell(0, 0).owner());
        assertEquals(ChunkClaimProtocol.RESTRICTED, view.cell(-1, -1).status());
        assertEquals(View.HIDDEN, ChunkClaimProtocol.decode(ChunkClaimProtocol.encode(View.HIDDEN)));
        var land = new Land(true, 1, -2, 3, 0, List.of(new Person(b, "손님", ChunkClaimProtocol.F_ONLINE)), List.of(new Person(c, "친구", ChunkClaimProtocol.F_TRUSTED)));
        assertEquals(land, ChunkClaimProtocol.decode(ChunkClaimProtocol.encode(land)));
        assertEquals(Land.OUTSIDE, ChunkClaimProtocol.decode(ChunkClaimProtocol.encode(Land.OUTSIDE)));
        assertEquals(new Result(true, "됐어요"), ChunkClaimProtocol.decode(ChunkClaimProtocol.encode(new Result(true, "됐어요"))));
        for (var act : List.of(ChunkClaimProtocol.Action.trust(a, true), ChunkClaimProtocol.Action.trust(a, false), ChunkClaimProtocol.Action.sell(-5, 9)))
            assertEquals(act, ChunkClaimProtocol.decodeAction(ChunkClaimProtocol.encode(act)));
        assertThrows(IllegalArgumentException.class, () -> new View(true, "world", 0, 0, 1, "a", "b", List.of()), "칸 수가 맞아야 함");
        assertThrows(IllegalArgumentException.class, () -> new Cell(ChunkClaimProtocol.OTHER, ""), "주인 있는 칸은 이름 필요");
        assertThrows(IllegalArgumentException.class, () -> ChunkClaimProtocol.decodeAction(new byte[]{1, 9}));
        byte[] bytes = ChunkClaimProtocol.encode(land);
        assertThrows(IllegalArgumentException.class, () -> ChunkClaimProtocol.decode(Arrays.copyOf(bytes, bytes.length + 1)), "남는 바이트");
        assertEquals("a가나", ChunkClaimProtocol.cut("§a가나\n", 32));
    }
}
