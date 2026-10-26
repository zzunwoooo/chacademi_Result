package school.magiccodex.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.HousingRules;
import school.magiccodex.protocol.HousingRules.Def;
import school.magiccodex.protocol.HousingRules.Layout;
import school.magiccodex.protocol.HousingRules.Placement;

class HousingRulesTest {
    static final Def BED = new Def("bed", "침대", 0, 0, 2, 1, 6, 0, 0, "", 4);
    static final Def PLANT = new Def("plant", "화분", 1, 0, 1, 1, 6, 0, 0, "", 4);
    static final Def FRAME = new Def("frame", "액자", 2, 0, 1, 1, 10, 0, 0, "", 4);
    static final Def BANNER = new Def("banner", "배너", 2, 0, 1, 1, 20, 0, 0, "", 4);
    static final Def PAPER = new Def("paper", "벽지", 3, 0, 1, 1, 0, 0, 0, "", 4);
    static final Def WOOD = new Def("wood", "바닥", 3, 1, 1, 1, 0, 0, 0, "", 4);
    static final Map<String, Def> DEFS = Map.of("bed", BED, "plant", PLANT, "frame", FRAME, "banner", BANNER, "paper", PAPER, "wood", WOOD);
    static final Map<String, Integer> OWNED = Map.of("bed", 1, "plant", 2, "frame", 1, "banner", 1, "paper", 1, "wood", 1);

    @Test void footprintRotates() {
        assertArrayEquals(new int[] {2, 1}, BED.footprint(0));
        assertArrayEquals(new int[] {1, 2}, BED.footprint(1));
        assertArrayEquals(new int[] {1, 0, 2, 2}, HousingRules.cells(BED, new Placement("bed", 1, 0, 0, 3)));
    }

    @Test void floorBoundsAndOverlap() {
        assertNull(HousingRules.fits(BED, new Placement("bed", 1, 0, 0, 0), 3, List.of(), DEFS::get));
        assertNotNull(HousingRules.fits(BED, new Placement("bed", 2, 0, 0, 0), 3, List.of(), DEFS::get));
        assertNull(HousingRules.fits(BED, new Placement("bed", 2, 0, 1, 1), 3, List.of(), DEFS::get));
        var plant = new Placement("plant", 1, 0, 0, 0);
        assertNotNull(HousingRules.fits(BED, new Placement("bed", 0, 0, 0, 0), 3, List.of(plant), DEFS::get));
        assertNull(HousingRules.fits(BED, new Placement("bed", 0, 0, 1, 0), 3, List.of(plant), DEFS::get));
        // 벽걸이는 바닥 가구와 겹쳐도 된다
        assertNull(HousingRules.fits(BED, new Placement("bed", 0, 0, 0, 0), 3, List.of(new Placement("frame", 0, 0, 0, 0)), DEFS::get));
    }

    @Test void wallItems() {
        assertNull(HousingRules.fits(BANNER, new Placement("banner", 2, 1, 0, 0), 3, List.of(), DEFS::get));
        assertNotNull(HousingRules.fits(BANNER, new Placement("banner", 2, 3, 0, 0), 3, List.of(), DEFS::get));
        assertNotNull(HousingRules.fits(FRAME, new Placement("frame", 3, 0, 0, 1), 3, List.of(), DEFS::get));
        var frame = new Placement("frame", 1, 1, 0, 0);
        assertNotNull(HousingRules.fits(BANNER, new Placement("banner", 1, 0, 0, 0), 3, List.of(frame), DEFS::get));
        assertNull(HousingRules.fits(BANNER, new Placement("banner", 1, 0, 0, 1), 3, List.of(frame), DEFS::get));
        assertNotNull(HousingRules.fits(FRAME, new Placement("frame", 0, 0, 0, 2), 3, List.of(), DEFS::get));
    }

    @Test void validateCountsAndSurfaces() {
        var good = new Layout("paper", "wood", List.of(new Placement("bed", 0, 0, 0, 0), new Placement("plant", 2, 0, 2, 0), new Placement("plant", 0, 0, 2, 0)));
        assertNull(HousingRules.validate(good, 3, DEFS::get, OWNED));
        var tooMany = good.with(List.of(new Placement("plant", 0, 0, 0, 0), new Placement("plant", 1, 0, 0, 0), new Placement("plant", 2, 0, 0, 0)));
        assertNotNull(HousingRules.validate(tooMany, 3, DEFS::get, OWNED));
        assertNotNull(HousingRules.validate(new Layout("wood", "", List.of()), 3, DEFS::get, OWNED));
        assertNotNull(HousingRules.validate(new Layout("", "", List.of(new Placement("ghost", 0, 0, 0, 0))), 3, DEFS::get, OWNED));
    }

    @Test void pruneKeepsWhatStillFits() {
        var layout = new Layout("paper", "", List.of(new Placement("bed", 0, 0, 0, 0), new Placement("plant", 3, 0, 3, 0), new Placement("plant", 0, 0, 1, 0)));
        var pruned = HousingRules.prune(layout, 3, DEFS::get, Map.of("bed", 1, "plant", 2));
        assertEquals("", pruned.wallpaper());
        assertEquals(List.of(new Placement("bed", 0, 0, 0, 0), new Placement("plant", 0, 0, 1, 0)), pruned.placements());
    }

    @Test void textRoundTrip() {
        var layout = new Layout("paper", "wood", List.of(new Placement("bed", 0, 0, 0, 1), new Placement("frame", 2, 1, 0, 1)));
        assertEquals("v1|paper|wood|bed,0,0,0,1;frame,2,1,0,1", HousingRules.encode(layout));
        assertEquals(layout, HousingRules.decode(HousingRules.encode(layout)));
        assertEquals(Layout.EMPTY, HousingRules.decode(""));
        assertEquals(Layout.EMPTY, HousingRules.decode(HousingRules.encode(Layout.EMPTY)));
        assertThrows(IllegalArgumentException.class, () -> HousingRules.decode("v2|||"));
        assertThrows(IllegalArgumentException.class, () -> HousingRules.decode("v1|||bed,0,0"));
    }
}
