package school.magiccodex.client;

import java.util.*;
import school.magiccodex.client.AlchemyLayout.Box;

/**
 * Recipe editor (/연금술관리) geometry on the same 1672x941 alchemy panel. Left frame: name field, the alchemy circle
 * (smaller than the player window to make room) with the ingredient slots and the result ring, the experience field and
 * the "use" checkbox, the message line, 저장 / 삭제. Right frame: header, search, count + 새 레시피, six recipe rows.
 */
final class AlchemyAdminLayout {
    private AlchemyAdminLayout() {}

    static final Box NAME_BOX = new Box(236, 127, 560, 46);
    static final float LABEL_X = 176, NAME_Y = 150, ID_RIGHT = 1006;

    static final float CIRCLE_X = 589, CIRCLE_Y = 424, CIRCLE_SIZE = 480, SLOT_RADIUS = 196, SLOT_W = 100, SLOT_H = 92;
    static final Box RESULT = Box.centered(CIRCLE_X, CIRCLE_Y, 150, 146);
    static Box slot(int index, int count) {
        double angle = count == 6 ? AlchemyLayout.SLOT_ANGLES[index] : 90 - 360.0 * index / count;
        return Box.centered(CIRCLE_X + (float) (SLOT_RADIUS * Math.cos(Math.toRadians(angle))),
                CIRCLE_Y - (float) (SLOT_RADIUS * Math.sin(Math.toRadians(angle))), SLOT_W, SLOT_H);
    }

    static final float ROW_Y = 694;
    static final Box EXP_BOX = new Box(300, 672, 150, 44);
    static final float EXP_HINT_X = 462;
    static final Box CHECK = new Box(704, 680, 28, 28), CHECK_HIT = new Box(698, 672, 250, 44);
    static final float CHECK_LABEL_X = 744;
    static final float MESSAGE_Y = 748;
    static final Box SAVE = Box.centered(450, 812, 240, 62), DELETE = Box.centered(728, 812, 240, 62);

    static final Box COUNT = new Box(1074, 252, 230, 28);
    static final Box NEW = new Box(1330, 244, 170, 44);

    /** Named boxes that must not overlap (AlchemyAdminLayoutTest). The picker is modal and checked separately. */
    static Map<String, Box> parts(int slots) {
        var map = new LinkedHashMap<String, Box>();
        map.put("name", new Box(LABEL_X - 4, NAME_BOX.y(), ID_RIGHT - LABEL_X + 8, NAME_BOX.h()));
        for (int i = 0; i < slots; i++) map.put("slot" + i, slot(i, slots));
        map.put("result", RESULT);
        map.put("exp", new Box(LABEL_X - 4, EXP_BOX.y(), EXP_HINT_X + 200 - LABEL_X, EXP_BOX.h()));
        map.put("check", CHECK_HIT);
        map.put("message", Box.centered(CIRCLE_X, MESSAGE_Y, 840, 26));
        map.put("save", SAVE); map.put("delete", DELETE);
        map.put("header", Box.centered(AlchemyLayout.RIGHT.cx(), AlchemyLayout.HEADER_Y, 440, 40));
        map.put("search", AlchemyLayout.SEARCH_BOX);
        map.put("count", COUNT); map.put("new", NEW);
        for (int i = 0; i < AlchemyLayout.ROWS; i++) map.put("row" + i, AlchemyLayout.row(i));
        map.put("scroll", AlchemyLayout.SCROLL);
        map.put("footer", Box.centered(AlchemyLayout.RIGHT.cx(), AlchemyLayout.FOOTER_Y, 440, 24));
        map.put("close", AlchemyLayout.CLOSE);
        return map;
    }
}
