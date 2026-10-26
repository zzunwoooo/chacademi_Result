package kr.chacademy.npc.core;

/**
 * 스트리밍으로 조금씩 들어오는 JSON에서 "line" 값만 먼저 꺼낸다.
 * 대사부터 화면에 흘려보내기(타자기 효과) 위해 쓴다.
 */
public final class StreamingLineExtractor {

    private final StringBuilder buffer = new StringBuilder();
    private int emitted = 0;

    /** 새 조각을 넣고, 새로 읽힌 대사 부분(델타)을 돌려준다. 없으면 빈 문자열. */
    public String feed(String chunk) {
        buffer.append(chunk);
        String line = decodeLine(buffer);
        if (line == null || line.length() <= emitted) {
            return "";
        }
        String delta = line.substring(emitted);
        emitted = line.length();
        return delta;
    }

    public String text() {
        return buffer.toString();
    }

    public boolean hasStartedLine() {
        return emitted > 0;
    }

    /**
     * 완성되지 않은 JSON에서도 "line" 값을 지금까지 읽힌 만큼 디코딩한다.
     * "line" 키를 못 찾으면 null.
     */
    public static String decodeLine(CharSequence json) {
        String s = json.toString();
        int key = findKey(s, "line");
        if (key < 0) {
            return null;
        }
        int i = key;
        // 콜론과 공백 건너뛰기
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\t' || s.charAt(i) == '\r')) {
            i++;
        }
        if (i >= s.length() || s.charAt(i) != ':') {
            return i >= s.length() ? "" : null;
        }
        i++;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\t' || s.charAt(i) == '\r')) {
            i++;
        }
        if (i >= s.length()) {
            return "";
        }
        if (s.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder out = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (i + 1 >= s.length()) {
                    break; // 이스케이프가 다음 조각으로 넘어감
                }
                char e = s.charAt(i + 1);
                if (e == 'u') {
                    if (i + 6 > s.length()) {
                        break;
                    }
                    try {
                        out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    } catch (NumberFormatException ex) {
                        return out.toString();
                    }
                    i += 6;
                    continue;
                }
                switch (e) {
                    case 'n': out.append(' '); break;
                    case 't': out.append(' '); break;
                    case 'r': break;
                    default: out.append(e);
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** "line" 키 바로 뒤 위치. 문자열 값 안에 있는 "line"은 무시한다. */
    private static int findKey(String s, String key) {
        String target = "\"" + key + "\"";
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                if (s.startsWith(target, i)) {
                    return i + target.length();
                }
                inString = true;
            }
        }
        return -1;
    }
}
