package kr.chacademy.story.quest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestHudPlacementTest {
    @Test void keepsDefaultWithoutPartyOrWhenApart() {
        assertEquals(150, QuestHudPlacement.defaultTop(150, 300, 458, 80, null));
        assertEquals(150, QuestHudPlacement.defaultTop(150, 300, 458, 80, new float[]{0, 0, 100, 500}), "party on the left side");
        assertEquals(150, QuestHudPlacement.defaultTop(150, 300, 458, 80, new float[]{330, 300, 130, 100}), "party below");
    }
    @Test void movesAboveOrBelowAnOverlappingPartyBox() {
        float[] party = {330, 180, 130, 140}; // 오른쪽 가운데
        float y0 = QuestHudPlacement.defaultTop(150, 300, 458, 80, party);
        assertTrue(y0 - 9 + 80 <= party[1], "quest box ends above the party box");
        float[] tall = {330, 40, 130, 400};
        float y1 = QuestHudPlacement.defaultTop(150, 300, 458, 80, tall);
        assertTrue(y1 - 9 >= tall[1] + tall[3], "no room above: below the party box");
    }
}
