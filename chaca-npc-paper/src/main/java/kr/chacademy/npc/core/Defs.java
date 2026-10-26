package kr.chacademy.npc.core;

import java.util.List;

/**
 * 퀘스트·힌트·대화 턴 같은 작은 데이터 묶음.
 */
public final class Defs {

    private Defs() {
    }

    /** AI가 제안할 수 있는 퀘스트 (quests.yml). 내용과 보상은 고정, AI는 말로만 꺼낸다. */
    public record QuestDef(String id, String title, String summary, int minScore, List<String> acceptCommands) {
    }

    /**
     * 마법 힌트 (hints.yml). difficulty 1 = 구체적(쉬움) ~ 3 = 막연함(어려움).
     * vague = 호감도가 낮을 때 재료 대신 AI에게 주는 막연한 설명 (없으면 "" → 서버가 만든 기본 문장).
     */
    public record HintDef(String id, String spell, String topic, String materials, int difficulty, String vague) {
    }

    /** 대화 한 턴. speaker = "player" 또는 "npc" */
    public record Turn(String speaker, String text) {
        public boolean isPlayer() {
            return "player".equals(speaker);
        }
    }

    /** 프롬프트에 넣을 소문 하나. */
    public record RumorView(long id, String text, String type, long eventCreatedAt) {
        public boolean isRomance() {
            return "heart".equals(type) || "ending".equals(type) || "gift_romance".equals(type);
        }
    }

    /**
     * 대화 추천 버튼. action 이 비어 있으면 AI 대사 버튼(기존), "shop:<상점ID>" 면 대사 대신 그 상점을 연다 (2026-10-25, 피피 "물건 좀 볼 수 있어요?").
     */
    public record Button(String id, String label, String action) {
        public Button(String id, String label) { this(id, label, ""); }
        public Button { action = action == null ? "" : action.strip(); }
        /** "shop:<상점ID>" 이면 상점 ID, 아니면 null. */
        public String shop() {
            if (!action.startsWith("shop:")) return null;
            String id = action.substring(5).strip();
            if (id.equals("물고기판매소") || id.equals("@fish_shop")) return "물고기판매소"; // 물고기 판매소 (셀마 등)
            return id.matches("[A-Za-z0-9_:.-]{1,64}") ? id : null;
        }
        /** "lesson:<수업키>" 이면 수업 키 (MagicCodexBridge lessons.yml, 2026-10-26 교수 "수업 받으러 왔어요"), 아니면 null. */
        public String lesson() {
            if (!action.startsWith("lesson:")) return null;
            String id = action.substring(7).strip();
            return id.matches("[a-z0-9_-]{1,48}") ? id : null;
        }
        public boolean aiReply() { return action.isEmpty(); }
    }
}
