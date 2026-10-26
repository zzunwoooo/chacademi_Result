package kr.chacademy.npc.core;

import kr.chacademy.npc.core.Defs.HintDef;
import kr.chacademy.npc.core.Defs.QuestDef;
import kr.chacademy.npc.core.Defs.RumorView;
import kr.chacademy.npc.core.Defs.Turn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 프롬프트 조립. 안 바뀌는 것(공통 규칙 → 캐릭터)을 앞에, 자주 바뀌는 것을 뒤에 둬서 캐시가 맞게 한다.
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    /** 대화 한 번에 필요한 모든 상황 정보 (메인 스레드에서 미리 모아 둔다). */
    public static final class Context {
        public String playerName;          // 프롬프트에 쓰는 호칭
        public String storyChapter;        // "2장"
        public String placeLabel;          // "도서관"
        public String activity;            // "책 정리 중"
        public String partOfDay;           // "오후"
        public String todayMood;           // "좋음"
        public AffinityStage stage = AffinityStage.ACQUAINTANCE;
        public List<QuestDef> allowedQuests = new ArrayList<>();
        public List<HintDef> allowedHints = new ArrayList<>();
        public List<String> memos = new ArrayList<>();
        public List<Turn> lastChat = new ArrayList<>();
        public String lastChatAgo;         // "약 3시간 전"
        public List<RumorView> rumors = new ArrayList<>();
        public long nowMillis;
        public boolean jealous;
        public boolean jealousWithPromise;
        public List<String> promises = new ArrayList<>();
        public boolean generic;            // 버튼 대답 캐시용: 개인 정보 없이 만든다
        public boolean magicAsked;         // 학생이 마법 이야기를 물었다 (마법 버튼 또는 마법 낱말)
        public String likedCriteria;       // #83 liked 판정 기준 (config affinity.chat-reward-criteria), null 이면 안 씀
    }

    /** 공통 규칙 + 캐릭터 = instructions (캐시되는 앞부분). */
    public static String instructions(String commonTemplate, String worldLore, List<String> vibeNotes,
                                      CharacterSheet c, AffinityStage stage) {
        String vibe;
        if (vibeNotes == null || vibeNotes.isEmpty()) {
            vibe = "(아직 특별한 소식 없음)";
        } else {
            StringBuilder sb = new StringBuilder();
            for (String v : vibeNotes) {
                sb.append("- ").append(v).append('\n');
            }
            vibe = sb.toString().trim();
        }
        String common = commonTemplate
                .replace("{world_lore}", worldLore == null ? "" : worldLore.trim())
                .replace("{vibe_notes}", vibe);
        return common.trim() + "\n\n" + characterBlock(c, stage);
    }

    static String characterBlock(CharacterSheet c, AffinityStage stage) {
        CharacterSheet.Persona p = c.persona();
        StringBuilder sb = new StringBuilder("[캐릭터]\n");
        sb.append("- 이름: ").append(c.name()).append('\n');
        if (c.gender() != null && !c.gender().isBlank()) {
            sb.append("- 성별: ").append(c.gender()).append('\n');
        }
        if (p != null) {
            line(sb, "역할", p.role());
            line(sb, "성격", p.personality());
            line(sb, "말투", p.speech());
            list(sb, "좋아하는 것", p.likes());
            list(sb, "싫어하는 것", p.dislikes());
            list(sb, "아는 것", p.knows());
            list(sb, "절대 모르는 것(말하지 않음)", p.neverKnows());
        }
        List<String> samples = new ArrayList<>();
        if (p != null && p.sampleLines() != null) {
            samples.addAll(p.sampleLines());
        }
        if (c.sampleLinesByStage() != null && stage != null) {
            for (Map.Entry<Integer, List<String>> e : c.sampleLinesByStage().entrySet()) {
                if (e.getKey() <= stage.ordinal() && e.getValue() != null) {
                    samples.addAll(e.getValue());
                }
            }
        }
        if (!samples.isEmpty()) {
            sb.append("- 말투 예시:\n");
            for (String s : samples) {
                sb.append("  \"").append(s).append("\"\n");
            }
        }
        return sb.toString().trim();
    }

    /** 대화마다 바뀌는 상황 설명 (첫 user 메시지). */
    public static String contextMessage(CharacterSheet c, Context x) {
        StringBuilder sb = new StringBuilder();
        sb.append("[지금 상황]\n");
        if (x.placeLabel != null) {
            sb.append("- 장소: ").append(x.placeLabel);
            if (x.activity != null) {
                sb.append(" (").append(x.activity).append(')');
            }
            sb.append('\n');
        }
        if (x.partOfDay != null) {
            sb.append("- 시간: ").append(x.partOfDay).append('\n');
        }
        if (x.todayMood != null) {
            sb.append("- 오늘 너의 기분: ").append(x.todayMood).append('\n');
        }
        if (x.generic) {
            sb.append("- 대화 상대: 지나가던 학생 (이름을 부르지 말 것)\n");
        } else {
            sb.append("- 대화 상대: ").append(x.playerName).append(" (이 호칭으로 부를 것)\n");
            if (x.storyChapter != null) {
                sb.append("- 이 학생의 스토리 진행: ").append(x.storyChapter)
                        .append(" (이후 이야기는 절대 말하지 않는다)\n");
            }
            AffinityStage st = x.stage == null ? AffinityStage.ACQUAINTANCE : x.stage;
            sb.append("- 너와의 관계: ").append(st.label).append(" — ").append(st.attitude).append('\n');
        }
        if (x.generic || x.likedCriteria == null || x.likedCriteria.isBlank()) {
            sb.append("- liked: 항상 false.\n");
        } else {
            sb.append("- liked: ").append(x.likedCriteria.trim()).append(" 그럴 때만 true, 아니면 false.\n");
        }

        sb.append("\n[이번 대화에서 허락된 것]\n");
        if (x.allowedQuests.isEmpty()) {
            sb.append("- 퀘스트: 이번에는 주지 않는다. quest는 null.\n");
        } else {
            sb.append("- 부탁(퀘스트)을 꺼내도 된다. 자연스러울 때만, 하나만. 꺼냈다면 quest에 id를 적는다. 보상이나 수치는 말하지 않는다.\n");
            for (QuestDef q : x.allowedQuests) {
                sb.append("  - ").append(q.id()).append(": ").append(q.title());
                if (q.summary() != null && !q.summary().isBlank()) {
                    sb.append(" — ").append(q.summary());
                }
                sb.append('\n');
            }
        }
        if (x.allowedHints.isEmpty()) {
            sb.append("- 마법 힌트: 이번에는 주지 않는다. hint는 null.\n");
            if (x.magicAsked) {
                // 아직 친하지 않거나(호감도 낮음·보통), 들려줄 마법 이야기가 남아 있지 않다
                sb.append("- 학생이 마법 이야기를 물었지만 지금 너는 들려줄 마법 이야기가 떠오르지 않는다. '음… 잘 모르겠는데?'처럼 네 말투로 가볍게 얼버무린다."
                        + " 마법 이름·발견 방법·재료·장소를 지어내지 않는다.\n");
            }
        } else {
            sb.append("- 마법 힌트를 줘도 된다. 마법 이름은 말하지 말고, '그러고 보니 예전에 어떤 마법사가 마법을 쓰는 걸 봤어… 그때 ~해서 ~하더라'처럼"
                    + " 네가 본 목격담으로 네 말투에 맞게 돌려 말한다. 아래 목격담에 없는 내용은 지어내지 않는다. 줬다면 hint에 id.\n");
            for (HintDef h : x.allowedHints) {
                sb.append("  - ").append(h.id()).append(": ").append(hintText(h, x.stage)).append('\n');
            }
        }

        if (!x.generic) {
            if (!x.memos.isEmpty()) {
                sb.append("\n[이 학생에 대한 기억]\n");
                for (String m : x.memos) {
                    sb.append("- ").append(m).append('\n');
                }
            }
            if (!x.promises.isEmpty()) {
                sb.append("\n[이 학생이 너에게 했던 특별한 말]\n");
                for (String p : x.promises) {
                    sb.append("- ").append(p).append('\n');
                }
            }
            if (!x.lastChat.isEmpty()) {
                sb.append("\n[지난번 대화 (").append(x.lastChatAgo == null ? "얼마 전" : x.lastChatAgo)
                        .append(") — 자연스럽게 이어질 때만 언급]\n");
                for (Turn t : x.lastChat) {
                    sb.append(t.isPlayer() ? x.playerName : c.name()).append(": ").append(t.text()).append('\n');
                }
            }
            if (!x.rumors.isEmpty()) {
                sb.append("\n[네가 들은 소문 — 자연스러울 때만 하나 꺼내고, 꺼냈다면 rumor에 번호]\n");
                for (RumorView r : x.rumors) {
                    sb.append("- (").append(r.id()).append(") ")
                            .append(TimeText.ago(r.eventCreatedAt(), x.nowMillis)).append(": ")
                            .append(r.text()).append('\n');
                }
            }
            if (x.jealous) {
                sb.append("\n[지금 기분: 질투]\n");
                if (x.jealousWithPromise) {
                    sb.append("- 이 학생이 너에게 했던 특별한 말과 다른 소문을 들었다. 그 말을 직접 꺼내며 살짝 서운해한다.\n");
                } else {
                    sb.append("- 이 학생이 다른 사람과 가까워졌다는 소문을 들었다. 살짝 섭섭하고 삐친 티를 낸다.\n");
                }
                sb.append("- 귀엽게, 짧게. 비난·협박·집착하는 말은 절대 하지 않는다.\n");
            }
        }
        return sb.toString().trim();
    }

    /** 호감도가 낮을 때(모르는 사이·아는 사이) 재료 원문 대신 쓰는 기본 문장. */
    public static final String VAGUE_HINT_FALLBACK = "아직 아무도 제대로 찾지 못한 마법이 학교 어딘가에 숨어 있다는 소문";

    /**
     * 힌트 한 줄. 호감도 단계가 낮으면(STRANGER·ACQUAINTANCE) 재료 원문을 AI에게 주지 않고
     * hints.yml 의 vague(없으면 기본 문장)만 준다 — AI가 모르는 것은 새어 나갈 수 없다.
     */
    public static String hintText(HintDef h, AffinityStage stage) {
        boolean low = stage == null || stage.ordinal() <= AffinityStage.ACQUAINTANCE.ordinal();
        if (low) {
            String v = h.vague() == null || h.vague().isBlank() ? VAGUE_HINT_FALLBACK : h.vague().trim();
            return "막연한 소문 = " + v + " (이 이상은 너도 모른다. 재료·장소·방법을 지어내지 말고 아주 막연하게)";
        }
        return "목격담 = " + h.materials()
                + (h.difficulty() <= 1 ? " (꽤 구체적으로)" : h.difficulty() >= 3 ? " (아주 막연하게)" : " (적당히 돌려서)");
    }

    /** 응답 JSON 스키마 (구조화 출력). line을 맨 앞에 둬야 스트리밍 때 대사가 먼저 나온다. */
    public static Map<String, Object> replySchema() {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("line", Json.map("type", "string", "description", "NPC 대사, 한국어 1~3문장"));
        props.put("mood", Json.map("type", "integer", "description", "이번 대화로 바뀐 기분 -2~2"));
        props.put("quest", Json.map("type", List.of("string", "null")));
        props.put("hint", Json.map("type", List.of("string", "null")));
        props.put("memo", Json.map("type", List.of("string", "null"), "description", "이 학생에 대해 기억할 한 줄"));
        props.put("promise", Json.map("type", List.of("string", "null"), "description", "학생이 고백·약속·'너밖에 없어' 같은 특별한 말을 했으면 한 줄 요약"));
        props.put("rumor", Json.map("type", List.of("integer", "null")));
        props.put("end", Json.map("type", "boolean"));
        props.put("liked", Json.map("type", "boolean", "description", "학생의 이번 말이 정말 마음에 들었으면 true ([지금 상황]의 liked 기준)"));
        return Json.map(
                "type", "object",
                "properties", props,
                "required", List.of("line", "mood", "quest", "hint", "memo", "promise", "rumor", "end", "liked"),
                "additionalProperties", false);
    }

    private static void line(StringBuilder sb, String label, String v) {
        if (v != null && !v.isBlank()) {
            sb.append("- ").append(label).append(": ").append(v.trim()).append('\n');
        }
    }

    private static void list(StringBuilder sb, String label, List<String> v) {
        if (v != null && !v.isEmpty()) {
            sb.append("- ").append(label).append(": ").append(String.join(", ", v)).append('\n');
        }
    }
}
