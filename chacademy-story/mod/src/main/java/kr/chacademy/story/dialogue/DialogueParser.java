package kr.chacademy.story.dialogue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 읽어 들인 dialogue.yml (Map) 을 검사해서 {@link Dialogue} 로 만든다. 파일·Minecraft 를 쓰지 않아 단위 테스트가 된다.
 * 규칙은 FORMAT.md "이름 규칙" 과 같아야 한다 (서버 플러그인도 같은 규칙으로 읽는다).
 */
public final class DialogueParser {
    /** 대화 id · 장면 id · 이벤트 이름: 소문자, 숫자, _, - 로 1~64자. */
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_\\-]{1,64}");
    /** 한 장면의 선택지 수. MagicCodex 대화창이 보여 줄 수 있는 만큼. */
    public static final int MAX_CHOICES = 6;
    /** 대사 한 줄의 글자 수. MagicCodex 대화창 한 프레임 한도(1600자)보다 조금 작게 ({player} 등이 바뀌며 늘어날 여유). */
    public static final int MAX_LINE_CHARS = 1500;

    private DialogueParser() {}

    /** @throws IOException 형식이 틀리면. 메시지에 문제가 된 키를 적는다 */
    public static Dialogue parse(String id, Object root) throws IOException {
        if (id == null || !ID_PATTERN.matcher(id).matches()) throw new IOException("잘못된 대화 id: " + id);
        if (!(root instanceof Map<?, ?> m)) throw new IOException("yml 이 비어 있음");

        Map<String, Dialogue.Speaker> speakers = new LinkedHashMap<>();
        if (m.get("speakers") instanceof Map<?, ?> sm) {
            for (var e : sm.entrySet()) {
                String sid = String.valueOf(e.getKey());
                Map<?, ?> v = e.getValue() instanceof Map<?, ?> x ? x : Map.of();
                Map<String, String> portraits = new LinkedHashMap<>();
                if (v.get("portraits") instanceof Map<?, ?> pm) {
                    for (var pe : pm.entrySet()) {
                        String img = String.valueOf(pe.getValue());
                        checkImage(img);
                        portraits.put(String.valueOf(pe.getKey()), img);
                    }
                }
                speakers.put(sid, new Dialogue.Speaker(sid, str(v.get("name"), sid), str(v.get("npc"), ""), portraits));
            }
        }

        Map<String, Dialogue.Scene> scenes = new LinkedHashMap<>();
        if (m.get("scenes") instanceof Map<?, ?> scm) {
            for (var e : scm.entrySet()) {
                String sid = String.valueOf(e.getKey());
                if (!ID_PATTERN.matcher(sid).matches()) {
                    throw new IOException("장면 id 형식 오류: scenes." + sid + " (소문자·숫자·_·- 로 1~64자)");
                }
                String at = "scenes." + sid;
                Map<?, ?> v = e.getValue() instanceof Map<?, ?> x ? x : Map.of();
                List<Dialogue.Redirect> redirects = new ArrayList<>();
                int ri = 0;
                for (Object o : list(v.get("redirect"))) {
                    if (o instanceof Map<?, ?> rm) {
                        Dialogue.Need need = need(rm, at + ".redirect[" + ri + "]");
                        if (need != null) redirects.add(new Dialogue.Redirect(need, str(rm.get("to"), "")));
                    }
                    ri++;
                }
                List<Dialogue.Line> lines = new ArrayList<>();
                int li = 0;
                for (Object o : list(v.get("lines"))) {
                    if (o instanceof Map<?, ?> lm) {
                        String text = str(lm.get("text"), "");
                        if (text.length() > MAX_LINE_CHARS) {
                            throw new IOException("대사가 너무 김: " + at + ".lines[" + li + "] (" + text.length() + "자, 최대 "
                                    + MAX_LINE_CHARS + "자. 대사를 나눠 주세요)");
                        }
                        lines.add(new Dialogue.Line(str(lm.get("who"), ""), str(lm.get("face"), ""), text));
                    }
                    li++;
                }
                List<Dialogue.Choice> choices = new ArrayList<>();
                int ci = 0;
                for (Object o : list(v.get("choices"))) {
                    if (o instanceof Map<?, ?> cm) {
                        String cat = at + ".choices[" + ci + "]";
                        Dialogue.Need need = cm.get("need") instanceof Map<?, ?> nm ? need(nm, cat + ".need") : null;
                        choices.add(new Dialogue.Choice(str(cm.get("text"), "…"), str(cm.get("to"), ""), need,
                                affinity(cm.get("affinity"), cat + ".affinity"), eventName(cm.get("event"), cat + ".event")));
                    }
                    ci++;
                }
                if (choices.size() > MAX_CHOICES) {
                    throw new IOException("선택지가 너무 많음: " + at + ".choices (" + choices.size() + "개, 최대 " + MAX_CHOICES
                            + "개. MagicCodex 대화창이 보여 줄 수 있는 수)");
                }
                scenes.put(sid, new Dialogue.Scene(sid, redirects, lines, choices, str(v.get("next"), ""),
                        eventName(v.get("event"), at + ".event"), affinity(v.get("affinity"), at + ".affinity")));
            }
        }
        if (scenes.isEmpty()) throw new IOException("장면이 없음");
        String start = str(m.get("start"), scenes.keySet().iterator().next());
        if (!scenes.containsKey(start)) throw new IOException("시작 장면이 없음: " + start);

        // dim / type_speed / type_sound / type_sound_volume 은 예전 자체 대화창용 값이다.
        // 지금은 MagicCodex 대화창이 그리므로 읽지 않는다 (있어도 오류가 아니다).
        return new Dialogue(id, str(m.get("title"), id), start, speakers, scenes, DialogueCommands.parse(m.get("commands")), str(m.get("description"), ""), str(m.get("chapter"), ""));
    }

    private static Dialogue.Need need(Map<?, ?> m, String at) throws IOException {
        String npc = str(m.get("npc"), "");
        if (npc.isEmpty()) return null;
        Integer max = m.get("max") == null || String.valueOf(m.get("max")).isBlank() ? null : whole(num(m.get("max"), 100, at + ".max"));
        return new Dialogue.Need(npc, whole(num(m.get("min"), -1000, at + ".min")), max);
    }

    /** affinity: [{npc, add}, ...] 또는 예전 형식 {npc, add} 또는 {npc이름: 값}. 한 번에 ±20 까지. */
    private static List<Dialogue.AffinityChange> affinity(Object o, String at) throws IOException {
        List<Dialogue.AffinityChange> out = new ArrayList<>();
        if (o instanceof Map<?, ?> m && m.containsKey("npc")) o = List.of(m);
        if (o instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) add(out, String.valueOf(e.getKey()), num(e.getValue(), 0, at + "." + e.getKey()));
        } else {
            int i = 0;
            for (Object x : list(o)) {
                if (x instanceof Map<?, ?> am) add(out, str(am.get("npc"), ""), num(am.get("add"), 0, at + "[" + i + "].add"));
                i++;
            }
        }
        return List.copyOf(out);
    }

    private static void add(List<Dialogue.AffinityChange> out, String npc, double v) {
        int add = (int) Math.max(-20, Math.min(20, Math.round(v)));
        if (!npc.isBlank() && add != 0 && out.size() < 8) out.add(new Dialogue.AffinityChange(npc.strip(), add));
    }

    /** 비어 있으면 이벤트 없음. 적었는데 규칙에 안 맞으면 오류 (서버가 같은 이름으로 명령·호감도를 찾기 때문에 조용히 버리지 않는다). */
    private static String eventName(Object o, String at) throws IOException {
        String s = str(o, "").trim();
        if (s.isEmpty()) return "";
        if (!ID_PATTERN.matcher(s).matches()) {
            throw new IOException("이벤트 이름 형식 오류: " + at + " = \"" + s + "\" (소문자·숫자·_·- 로 1~64자)");
        }
        return s;
    }

    private static void checkImage(String name) throws IOException {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) throw new IOException("그림 이름에 경로 불가: " + name);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) throw new IOException("그림은 PNG만: " + name);
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    /** 숫자가 아니면 기본값. NaN·무한대는 오류 (조건·호감도 계산이 조용히 틀어지지 않게). */
    private static double num(Object o, double def, String at) throws IOException {
        double v = def;
        if (o instanceof Number n) v = n.doubleValue();
        else if (o != null) {
            try {
                v = Double.parseDouble(o.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new IOException("숫자가 아님(NaN/무한대): " + at);
        return v;
    }

    private static int whole(double v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    private static String str(Object o, String def) {
        return o == null ? def : o.toString();
    }
}
