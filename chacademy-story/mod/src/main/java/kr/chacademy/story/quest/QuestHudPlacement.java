package kr.chacademy.story.quest;
/** 스토리 퀘스트 HUD 기본 자리 계산 (Minecraft 클래스 없음, 테스트 가능). */
final class QuestHudPlacement {
    private QuestHudPlacement() {}
    /**
     * 퀘스트 HUD 칸(y0-9 부터 height, x0~right)이 다른 HUD 상자 {@code other}{x,y,w,h} 와 겹치면,
     * 그 위에 자리가 있으면 위로, 없으면 아래로 옮긴 y0. 겹치지 않거나 상자가 없으면 y0 그대로.
     */
    static float defaultTop(float y0, float x0, float right, float height, float[] other) {
        if (other == null || other.length < 4) return y0;
        float top = y0 - 9;
        boolean overlap = other[0] < right && other[0] + other[2] > x0 && top < other[1] + other[3] && top + height > other[1];
        if (!overlap) return y0;
        float above = other[1] - 4 - height;
        return above >= 8 ? above + 9 : other[1] + other[3] + 4 + 9;
    }
}
