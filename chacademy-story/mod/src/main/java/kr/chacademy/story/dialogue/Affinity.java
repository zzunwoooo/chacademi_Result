package kr.chacademy.story.dialogue;

import java.util.HashMap;
import java.util.Map;

/** 호감도 값 주고받기. 서버가 대화를 열 때 "npc=점수,npc=점수" 로 보내 준다. */
public final class Affinity {
    /** 기록이 없을 때 점수. ChacaNPC 의 "아는 사이"(25)와 같게. */
    public static final int DEFAULT = 25;

    private Affinity() {}

    /** 서버가 로컬 대화를 열 때 글자 값에 함께 보낸 MagicCodex 호감도 ("@affinity:npc" → 점수). 이 PC 에 저장된 값은 쓰지 않는다. */
    public static final String VAR_PREFIX = "@affinity:";

    public static Map<String, Integer> fromVars(Map<String, String> vars) {
        Map<String, Integer> map = new HashMap<>();
        if (vars == null) return map;
        for (var e : vars.entrySet()) {
            if (!e.getKey().startsWith(VAR_PREFIX) || e.getKey().length() == VAR_PREFIX.length()) continue;
            try {
                map.put(e.getKey().substring(VAR_PREFIX.length()), Integer.parseInt(e.getValue().trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }

    /** 글자 바꿔 넣기에는 호감도 항목을 넘기지 않는다. */
    public static Map<String, String> withoutAffinity(Map<String, String> vars) {
        Map<String, String> out = new java.util.LinkedHashMap<>(vars);
        out.keySet().removeIf(k -> k.startsWith(VAR_PREFIX));
        return out;
    }

    public static Map<String, Integer> decode(String data) {
        Map<String, Integer> map = new HashMap<>();
        if (data == null || data.isBlank()) return map;
        for (String part : data.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                map.put(part.substring(0, eq).trim(), Integer.parseInt(part.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }
}
