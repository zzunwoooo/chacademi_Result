package kr.chacademy.npc.core;

/**
 * 호감도 단계. 실제 점수는 나중에 MagicCodexBridge가 관리한다.
 */
public enum AffinityStage {
    // 점수 0~100, 30·60·90 은 MagicCodexBridge 상한(그 NPC 퀘스트를 깨야 넘음)과 같은 경계 (2026-10-23)
    STRANGER(0, "낯선 사람", "예의 바르지만 거리감 있게 대한다."),
    ACQUAINTANCE(10, "아는 사이", "편하게 대화한다."),
    FRIEND(30, "친한 사이", "장난도 치고 고민도 털어놓는다."),
    BEST_FRIEND(60, "단짝", "비밀도 털어놓을 만큼 믿는다."),
    SPECIAL(90, "특별한 사이", "설레고 수줍어한다. 표현은 손잡기 정도까지, 노골적인 표현은 절대 하지 않는다.");

    public final int minScore;
    public final String label;
    public final String attitude;

    AffinityStage(int minScore, String label, String attitude) {
        this.minScore = minScore;
        this.label = label;
        this.attitude = attitude;
    }

    /**
     * 점수로 정하는 단계. 퀘스트 단계(heart)는 MagicCodexBridge 가 점수 상한(30·60·90)으로 이미 반영하므로
     * 여기서 다시 깎지 않는다 (예전 20점 하트 이벤트 방식에서는 heart 로 단계를 제한했었음).
     */
    public static AffinityStage of(int score, int heart, boolean romanceable) {
        return of(score, romanceable);
    }

    public static AffinityStage of(int score, boolean romanceable) {
        AffinityStage result = STRANGER;
        for (AffinityStage s : values()) {
            if (score >= s.minScore) {
                result = s;
            }
        }
        if (result == SPECIAL && !romanceable) {
            return BEST_FRIEND;
        }
        return result;
    }
}
