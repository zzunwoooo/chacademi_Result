package school.magiccodex.paper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 운영 기록(메모 #92)의 글자 다루기 — 순수 로직 (Bukkit·DB 없음, JUnit 대상).
 * 상세는 한 줄 JSON 객체 {"키":"값",...} 로 저장한다 (값은 전부 문자열). 대시보드가 그대로 JSON 으로 읽는다.
 */
final class AuditText {
    private AuditText() {}

    /** 설명 칸 최대 글자 (DB VARCHAR(255)). */
    static final int SUMMARY_MAX = 255;
    /** 상세 값 하나의 최대 글자. */
    static final int VALUE_MAX = 512;
    /** 상세 JSON 전체 최대 글자 (넘으면 뒤쪽 키를 버리고 "_잘림":"예" 를 붙인다). */
    static final int DETAIL_MAX = 4000;

    /** 앞뒤 공백·줄바꿈·탭을 정리하고 max 글자로 자른다 (잘리면 끝에 …). null → "". */
    static String clip(String text, int max) {
        if (text == null) return "";
        String s = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** k1, v1, k2, v2 … → 순서 있는 맵. 값이 null 인 쌍은 뺀다. 홀수 개면 마지막 키는 버린다. */
    static Map<String, String> pairs(Object... kv) {
        var out = new LinkedHashMap<String, String>();
        if (kv == null) return out;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null || kv[i + 1] == null) continue;
            out.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return out;
    }

    /** 맵 → 한 줄 JSON 객체. 값은 문자열로 저장하고 VALUE_MAX 로 자른다. 전체가 DETAIL_MAX 를 넘으면 뒤 키를 버린다. */
    static String json(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) return "{}";
        var out = new StringBuilder(64).append('{');
        boolean first = true, cut = false;
        for (var e : detail.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            var piece = new StringBuilder();
            if (!first) piece.append(',');
            quote(piece, clip(e.getKey(), 64));
            piece.append(':');
            quote(piece, clip(String.valueOf(e.getValue()), VALUE_MAX));
            if (out.length() + piece.length() + 16 > DETAIL_MAX) { cut = true; break; }
            out.append(piece);
            first = false;
        }
        if (cut) { if (!first) out.append(','); quote(out, "_잘림"); out.append(':'); quote(out, "예"); }
        return out.append('}').toString();
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    /** DB 가 안 될 때 쓰는 일별 파일 한 줄: 시각(ISO)\t서버\tuuid\t닉\t종류\t설명\t상세JSON. */
    static String fileLine(String isoTime, String server, String player, String name, String kind, String summary, String detail) {
        return String.join("\t", isoTime, tab(server), tab(player), tab(name), tab(kind), tab(summary), tab(detail));
    }

    private static String tab(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    /** 관리 명령 경로가 "보기만 하는" 명령인지 (기록하지 않음). 경로 예: "/유저관리 <닉네임> 일일제한 보기". */
    static boolean readOnlyCommand(String path) {
        if (path == null) return true;
        for (String token : path.split(" ")) {
            switch (token) {
                case "보기", "목록", "상태", "기록", "조회", "검색", "도움말", "찾기", "현황", "미리보기", "정보", "거래기록", "NPC목록", "질문" -> { return true; }
                default -> { }
            }
        }
        return false;
    }
}
