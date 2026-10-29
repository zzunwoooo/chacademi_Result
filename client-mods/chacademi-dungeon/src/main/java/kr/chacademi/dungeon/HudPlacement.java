package kr.chacademi.dungeon;

/**
 * 던전 HUD 기본 자리 (Minecraft 클래스 없음). 다른 HUD 의 실제 상자(사용자가 옮긴 위치·크기·GUI 배율 반영)를 보고
 * 겹치지 않는 곳을 고른다. 사용자가 이 HUD 자체를 옮겼으면 MagicCodex 쪽이 그 위치를 쓴다 (여기는 기본 자리만).
 * 상자 = {x, y, w, h} (GUI 좌표), 없으면 null.
 */
final class HudPlacement {
    private HudPlacement() {}

    static final float MARGIN = 6, GAP = 4;
    /** 오른쪽 위 상태 아이콘(버프·디버프) 줄 아래. */
    static final float MENU_TOP = 26;

    /** 던전 메뉴: 오른쪽 위. 스토리 퀘스트창·파티 상자와 겹치면 그 위로, 위에 자리가 없으면 아래로. */
    static float[] menu(float sw, float sh, float w, float h, float[] quest, float[] party) {
        float x = sw - MARGIN - w, y = MENU_TOP;
        for (int pass = 0; pass < 2; pass++)
            for (float[] other : new float[][]{quest, party}) {
                if (!HudLink.overlaps(other, x, y, w, h)) continue;
                float above = other[1] - GAP - h;
                y = above >= MARGIN ? above : other[1] + other[3] + GAP;
            }
        return new float[]{x, clamp(y, MARGIN, sh - h - MARGIN), w, h};
    }

    /**
     * 던전 단계창: 스토리 퀘스트창 바로 아래 (없으면 스토리 퀘스트 기본 자리 = 위에서 30%).
     * 아래에 자리가 없으면 스토리 퀘스트창 왼쪽에 나란히. 메뉴·파티 상자와도 겹치지 않게.
     */
    static float[] quest(float sw, float sh, float w, float h, float[] quest, float[] menu, float[] party) {
        float x = sw - MARGIN - w;
        float y = quest != null ? quest[1] + quest[3] + GAP * 2 : sh * 0.30f;
        float bottomLimit = sh - 52; // 핫바·채팅 입력줄 위
        for (int pass = 0; pass < 3; pass++)
            for (float[] other : new float[][]{menu, party}) {
                if (HudLink.overlaps(other, x, y, w, h)) y = other[1] + other[3] + GAP;
            }
        if (y + h > bottomLimit) {
            // 아래에 자리가 없음: 스토리 퀘스트창(또는 메뉴) 왼쪽으로
            float[] anchor = quest != null ? quest : menu;
            x = (anchor != null ? anchor[0] : sw - MARGIN) - GAP * 2 - w;
            y = anchor != null ? anchor[1] : sh * 0.30f;
            if (HudLink.overlaps(party, x, y, w, h)) y = party[1] + party[3] + GAP;
        }
        return new float[]{Math.max(MARGIN, x), clamp(y, MARGIN, Math.max(MARGIN, bottomLimit - h)), w, h};
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
