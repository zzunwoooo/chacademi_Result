package kr.chacademy.storyplugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 서버가 가진 대화 흐름 (dialogue.yml 에서 흐름에 필요한 것만: 장면, 대사 줄 수, 선택지, next, need, redirect, 이벤트).
 * 클라가 보낸 진행·이벤트·끝 패킷이 이 흐름에서 실제로 가능한지 {@link Walk} 로 검사한다.
 *
 * <p>읽는 규칙은 모드의 DialogueLoader / 대화 진행기와 같아야 한다 (한쪽을 고치면 다른 쪽도). Bukkit 없이 테스트한다.
 */
final class StoryGraph {
    /** 모드가 호감도 기록이 없는 NPC 에 쓰는 값 (모드 Affinity.DEFAULT). */
    static final int CLIENT_DEFAULT_AFFINITY = 25;
    static final int MAX_CHOICES = 6;
    static final int MAX_HOPS = 20;
    static final int MAX_SCENES = 2000;
    /** 대사 한 줄의 글자 수 한도 (모드 DialogueParser.MAX_LINE_CHARS 와 같게). */
    static final int MAX_LINE_CHARS = 1500;
    /** dialogue.yml 안의 호감도 한 항목 (흐름 계산용) 한도. FORMAT.md "호감도 한도". */
    static final int CLIENT_CHANGE_LIMIT = 20;

    record Need(String npc, int min, Integer max) {
        boolean test(Map<String, Integer> affinity) {
            int v = affinity.getOrDefault(npc, CLIENT_DEFAULT_AFFINITY);
            return v >= min && (max == null || v <= max);
        }
    }

    record Change(String npc, int add) {}

    record Choice(String to, Need need, List<Change> affinity, String event) {}

    record Redirect(Need need, String to) {}

    record Scene(String id, List<Redirect> redirects, int lines, List<Choice> choices, String next, String event,
                 List<Change> affinity) {
        int lastLine() {
            return Math.max(0, lines - 1);
        }
    }

    final String id;
    final String start;
    final Map<String, Scene> scenes;

    private StoryGraph(String id, String start, Map<String, Scene> scenes) {
        this.id = id;
        this.start = start;
        this.scenes = scenes;
    }

    /** 이 대화에서 나올 수 있는 모든 이벤트 이름 (선택지 + 장면). */
    Set<String> events() {
        Set<String> out = new LinkedHashSet<>();
        for (Scene s : scenes.values()) {
            if (!s.event.isEmpty()) out.add(s.event);
            for (Choice c : s.choices) if (!c.event.isEmpty()) out.add(c.event);
        }
        return out;
    }

    // ================================================================ 읽기

    /**
     * yml 을 읽은 Map 에서 흐름을 만든다. 규칙에 어긋나면 errors 에 "어느 키가 왜" 를 적고 null.
     * warnings 는 열 수는 있지만 고치는 게 좋은 것.
     */
    static StoryGraph parse(String id, Map<?, ?> root, List<String> errors, List<String> warnings) {
        int before = errors.size();
        if (root == null) {
            errors.add("yml 이 비어 있음");
            return null;
        }
        Object declared = root.get("id");
        if (declared != null && !id.equals(declared.toString())) warnings.add("파일 안의 id (" + declared + ") 가 폴더 이름 (" + id + ") 과 다름");
        Map<String, Scene> scenes = new LinkedHashMap<>();
        if (root.get("scenes") instanceof Map<?, ?> scm) {
            if (scm.size() > MAX_SCENES) errors.add("장면이 너무 많음: " + scm.size());
            else for (var e : scm.entrySet()) {
                String sid = String.valueOf(e.getKey());
                if (!StorySafety.validId(sid)) {
                    errors.add("장면 id 는 소문자·숫자·_·- 1~64자: \"" + clip(sid) + "\"");
                    continue;
                }
                Map<?, ?> v = e.getValue() instanceof Map<?, ?> x ? x : Map.of();
                List<Redirect> redirects = new ArrayList<>();
                for (Object o : list(v.get("redirect"))) {
                    if (o instanceof Map<?, ?> rm) {
                        Need need = need(rm, "scenes." + sid + ".redirect", errors);
                        if (need != null) redirects.add(new Redirect(need, str(rm.get("to"), "")));
                    }
                }
                int lines = 0;
                for (Object o : list(v.get("lines"))) {
                    if (!(o instanceof Map<?, ?> lm)) continue;
                    lines++;
                    // 모드가 열지 못하는 대사 길이는 서버에서도 미리 알려 준다 (MagicCodex 대화창 한 화면 한도)
                    if (str(lm.get("text"), "").length() > MAX_LINE_CHARS) errors.add("대사가 너무 김 (최대 " + MAX_LINE_CHARS + "자): scenes." + sid + ".lines[" + lines + "]");
                }
                List<Choice> choices = new ArrayList<>();
                int index = 0;
                for (Object o : list(v.get("choices"))) {
                    if (o instanceof Map<?, ?> cm) {
                        index++;
                        String where = "scenes." + sid + ".choices[" + index + "]";
                        Need need = cm.get("need") instanceof Map<?, ?> nm ? need(nm, where + ".need", errors) : null;
                        choices.add(new Choice(str(cm.get("to"), ""), need, changes(cm.get("affinity"), where + ".affinity", errors),
                                eventName(cm.get("event"), where + ".event", errors)));
                    }
                }
                if (choices.size() > MAX_CHOICES) errors.add("선택지는 " + MAX_CHOICES + "개까지: scenes." + sid + " (" + choices.size() + "개)");
                scenes.put(sid, new Scene(sid, List.copyOf(redirects), lines, List.copyOf(choices), str(v.get("next"), ""),
                        eventName(v.get("event"), "scenes." + sid + ".event", errors),
                        changes(v.get("affinity"), "scenes." + sid + ".affinity", errors)));
            }
        }
        if (scenes.isEmpty() && errors.size() == before) errors.add("장면이 없음");
        String start = scenes.isEmpty() ? "" : str(root.get("start"), scenes.keySet().iterator().next());
        if (!scenes.isEmpty() && !scenes.containsKey(start)) errors.add("시작 장면이 없음: \"" + clip(start) + "\"");
        if (errors.size() > before) return null;

        // 흐름 점검 (열 수는 있음)
        Map<String, String> eventOwner = new HashMap<>();
        for (Scene s : scenes.values()) {
            if (!s.next.isEmpty() && !scenes.containsKey(s.next)) warnings.add("scenes." + s.id + ".next 가 없는 장면을 가리킴 (거기서 대화가 끝남): " + clip(s.next));
            if (!s.event.isEmpty() && eventOwner.put(s.event, "scenes." + s.id) != null) warnings.add("이벤트 이름이 여러 곳에서 쓰임 (한 번만 실행됨): " + s.event);
            for (Redirect r : s.redirects) {
                if (!scenes.containsKey(r.to)) warnings.add("scenes." + s.id + ".redirect 가 없는 장면을 가리킴 (무시됨): " + clip(r.to));
            }
            for (int i = 0; i < s.choices.size(); i++) {
                Choice c = s.choices.get(i);
                if (!c.to.isEmpty() && !scenes.containsKey(c.to)) warnings.add("scenes." + s.id + ".choices[" + (i + 1) + "].to 가 없는 장면을 가리킴 (거기서 대화가 끝남): " + clip(c.to));
                if (!c.event.isEmpty() && eventOwner.put(c.event, "scenes." + s.id) != null) warnings.add("이벤트 이름이 여러 곳에서 쓰임 (한 번만 실행됨): " + c.event);
                // 이벤트 이름이 없는 선택지 둘이 같은 곳으로 가면 서버는 어느 쪽을 골랐는지 알 수 없다
                for (int j = 0; j < i; j++) {
                    Choice o = s.choices.get(j);
                    // (to 가 달라도 redirect·대사 없는 장면을 거쳐 같은 장면에 닿을 수 있으므로 to 는 따지지 않는다)
                    if (c.event.isEmpty() && o.event.isEmpty() && !c.affinity.equals(o.affinity)) {
                        warnings.add("scenes." + s.id + " 의 선택지 " + (j + 1) + "·" + (i + 1) + " 는 이벤트 이름이 없는데 호감도가 다름 — 같은 장면에 닿으면 서버가 어느 쪽인지 알 수 없어 뒤의 need 판정이 클라와 어긋날 수 있음 (event 를 붙여 주세요. 편집기는 자동으로 붙임)");
                    }
                }
            }
        }
        return new StoryGraph(id, start, java.util.Collections.unmodifiableMap(scenes));
    }

    private static Need need(Map<?, ?> m, String where, List<String> errors) {
        String npc = str(m.get("npc"), "");
        if (npc.isEmpty()) return null;
        Object rawMax = m.get("max");
        Integer max = rawMax == null || String.valueOf(rawMax).isBlank() ? null : (int) num(rawMax, 100, where + ".max", errors);
        return new Need(npc, (int) num(m.get("min"), -1000, where + ".min", errors), max);
    }

    /** affinity: [{npc, add}, ...] 또는 예전 형식 {npc, add} 또는 {npc이름: 값}. 한 항목 ±20, 8개까지 (모드와 같음). */
    private static List<Change> changes(Object o, String where, List<String> errors) {
        List<Change> out = new ArrayList<>();
        if (o instanceof Map<?, ?> m && m.containsKey("npc")) o = List.of(m);
        if (o instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) addChange(out, String.valueOf(e.getKey()), num(e.getValue(), 0, where, errors));
        } else {
            for (Object x : list(o)) if (x instanceof Map<?, ?> am) addChange(out, str(am.get("npc"), ""), num(am.get("add"), 0, where, errors));
        }
        return List.copyOf(out);
    }

    private static void addChange(List<Change> out, String npc, double v) {
        int add = (int) Math.max(-CLIENT_CHANGE_LIMIT, Math.min(CLIENT_CHANGE_LIMIT, Math.round(v)));
        if (!npc.isBlank() && add != 0 && out.size() < 8) out.add(new Change(npc.strip(), add));
    }

    private static String eventName(Object o, String where, List<String> errors) {
        String s = str(o, "").trim();
        if (s.isEmpty()) return "";
        if (!StorySafety.validId(s)) {
            errors.add("이벤트 이름은 소문자·숫자·_·- 1~64자: " + where + " = \"" + clip(s) + "\"");
            return "";
        }
        return s;
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static double num(Object o, double def, String where, List<String> errors) {
        double v = def;
        if (o instanceof Number n) v = n.doubleValue();
        else if (o != null) {
            try {
                v = Double.parseDouble(o.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            errors.add("숫자가 아님 (NaN/Infinity): " + where);
            return def;
        }
        return v;
    }

    private static String str(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static String clip(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    // ================================================================ 진행 검사

    /** 한 번의 이동 계산 결과: 차례로 들어가는 장면들, (대화가 끝나면) 끝 장면, 이동 뒤의 호감도·방문 기록. */
    private record Plan(List<String> chain, String finish, Map<String, Integer> affinity, Set<String> visited,
                        Set<String> events, boolean guessed) {}

    /**
     * 한 플레이어의 대화 한 번. 모드의 진행기와 같은 규칙으로 따라가며 패킷이 가능한 것인지 본다.
     * 호감도는 서버가 대화를 열 때 보낸 값에서 시작해 dialogue.yml 의 affinity 를 모드와 똑같이 더한다
     * (need / redirect 는 이 값으로 계산). 메인 스레드에서만 쓴다.
     */
    static final class Walk {
        private final StoryGraph g;
        private Map<String, Integer> affinity;
        private Set<String> visited = new HashSet<>();
        /** 지금 머물러 있는 장면. "" = 아직 첫 장면 보고 전. */
        String scene = "";
        int line = 0;
        /** 계산해 둔 다음 이동 (클라의 진행 보고로 확정되기 전). */
        private Plan plan;
        /** 방금 확정한 이동에서 들어간 장면들 가운데 아직 진행 보고를 받지 않은 것 (차례대로). 늦게 오는 보고를 한 번씩만 인정한다. */
        private List<String> lateReports = new ArrayList<>();
        private Set<String> lastEvents = Set.of();
        /** 방금 떠난 장면에서, 진행 보고보다 늦게 와도 인정할 선택지 이벤트. */
        private final Set<String> lateChoiceEvents = new HashSet<>();
        /** 이번 방문에서 선택지 이벤트를 이미 하나 받음 (장면 방문마다 하나만). */
        private boolean choiceEventUsed;
        /** 대화가 끝나야 하는 장면 (null = 아직 진행 중). */
        private String finishAt;
        /** 지금 장면에 들어왔다는 진행 보고를 이미 받음 (같은 장면 0번 줄 보고가 또 오면 "자기 자신으로 가는 선택지" 다). */
        private boolean entryReported;

        /** resumeScene 이 그래프에 있으면 거기서 이어서 (redirect·진입 효과 없이), 아니면 처음부터. */
        Walk(StoryGraph g, Map<String, Integer> affinity, String resumeScene, int resumeLine) {
            this.g = g;
            this.affinity = new HashMap<>(affinity);
            Scene s = resumeScene == null || resumeScene.isEmpty() ? null : g.scenes.get(resumeScene);
            if (s != null) {
                scene = s.id;
                visited.add(s.id);
                line = Math.max(0, Math.min(resumeLine, s.lastLine()));
            } else {
                plan = simulate(g.start, 0, null, new HashMap<>(this.affinity), new HashSet<>(), false);
            }
        }

        boolean finished() {
            return finishAt != null;
        }

        private List<Choice> visible(Scene s, Map<String, Integer> a) {
            List<Choice> out = new ArrayList<>();
            for (Choice c : s.choices) if (c.need == null || c.need.test(a)) out.add(c);
            return out;
        }

        /** 모드 진행기의 enter(): redirect → 진입 호감도·이벤트 → 대사도 선택지도 없으면 next 로. */
        private Plan simulate(String target, int hops, String current, Map<String, Integer> a, Set<String> v, boolean guessed) {
            List<String> chain = new ArrayList<>();
            Set<String> events = new LinkedHashSet<>();
            String cur = current, id = target, finish;
            while (true) {
                Scene s = g.scenes.get(id);
                if (s == null || hops > MAX_HOPS) {
                    finish = cur == null ? id : cur;
                    break;
                }
                Redirect taken = null;
                for (Redirect r : s.redirects) {
                    if (r.need.test(a) && g.scenes.containsKey(r.to) && !r.to.equals(id)) {
                        taken = r;
                        break;
                    }
                }
                if (taken != null) {
                    id = taken.to;
                    hops++;
                    continue;
                }
                cur = s.id;
                chain.add(cur);
                if (!s.affinity.isEmpty() && v.add(s.id)) for (Change c : s.affinity) a.merge(c.npc, c.add, Integer::sum);
                if (!s.event.isEmpty()) events.add(s.event);
                if (s.lines == 0 && visible(s, a).isEmpty()) {
                    if (s.next.isEmpty() || !g.scenes.containsKey(s.next)) {
                        finish = cur;
                        break;
                    }
                    id = s.next;
                    hops++;
                    continue;
                }
                finish = null;
                break;
            }
            return new Plan(chain, finish, a, v, events, guessed);
        }

        private Plan planChoice(Scene s, Choice c, boolean guessed) {
            Map<String, Integer> a = new HashMap<>(affinity);
            for (Change ch : c.affinity) a.merge(ch.npc, ch.add, Integer::sum);
            if (c.to.isEmpty() || !g.scenes.containsKey(c.to)) return new Plan(List.of(), s.id, a, new HashSet<>(visited), Set.of(), guessed);
            return simulate(c.to, 0, s.id, a, new HashSet<>(visited), guessed);
        }

        private Plan planNext(Scene s, boolean guessed) {
            if (s.next.isEmpty() || !g.scenes.containsKey(s.next)) {
                return new Plan(List.of(), s.id, new HashMap<>(affinity), new HashSet<>(visited), Set.of(), guessed);
            }
            return simulate(s.next, 1, s.id, new HashMap<>(affinity), new HashSet<>(visited), guessed);
        }

        private void commit(Plan p) {
            affinity = p.affinity;
            visited = p.visited;
            lateReports = new ArrayList<>(p.chain);
            lastEvents = p.events;
            if (!p.chain.isEmpty()) {
                scene = p.chain.get(p.chain.size() - 1);
                line = 0;
                choiceEventUsed = false;
                entryReported = false;
            }
            finishAt = p.finish;
            plan = null;
        }

        /** 이벤트 이름 없이 (또는 이벤트가 늦게 오는) 선택지·next 로 가능한 이동 가운데 조건에 맞는 첫 번째. */
        private Plan guess(Scene s, java.util.function.Predicate<Plan> wanted, boolean allowEvented) {
            List<Choice> vis = visible(s, affinity);
            if (vis.isEmpty()) {
                Plan p = planNext(s, true);
                return wanted.test(p) ? p : null;
            }
            Plan found = null;
            Set<String> late = new HashSet<>();
            // 이벤트 이름이 없는 선택지를 먼저 본다 (이름이 있는 선택지는 보통 이벤트 패킷이 먼저 온다)
            for (int pass = 0; pass < (allowEvented ? 2 : 1); pass++) {
                for (Choice c : vis) {
                    if (c.event.isEmpty() != (pass == 0)) continue;
                    if (pass == 1 && choiceEventUsed) continue;
                    Plan p = planChoice(s, c, true);
                    if (!wanted.test(p)) continue;
                    if (found == null) found = p;
                    if (!c.event.isEmpty()) late.add(c.event);
                }
            }
            if (found != null) {
                lateChoiceEvents.clear();
                if (!choiceEventUsed) lateChoiceEvents.addAll(late);
            }
            return found;
        }

        /** 기다리던 늦은 보고면 그것과 그 앞의 것을 지우고 true. */
        private boolean consumeReport(String to) {
            int i = lateReports.indexOf(to);
            if (i < 0) return false;
            lateReports.subList(0, i + 1).clear();
            return true;
        }

        /**
         * 진행 보고로 들어온 장면 to 에 맞는 이동. 클라는 들어가는 장면을 차례로 보고하므로 "처음 들어가는 장면이 to" 인
         * 이동을 먼저 찾고 (다른 선택지가 대사 없는 장면을 거쳐 to 에 닿는 경우와 헷갈리지 않게), 없으면 지나가는 장면까지 본다.
         */
        private Plan guessEntering(Scene s, String to) {
            Plan p = guess(s, x -> !x.chain.isEmpty() && x.chain.get(0).equals(to), true);
            return p != null ? p : guess(s, x -> x.chain.contains(to), true);
        }

        /** dialogue_progress: 지금 장면 안에서 줄이 넘어갔거나, 가능한 다음 장면에 들어갔는지. */
        boolean progress(String to, int ln) {
            if (to == null || ln < 0) return false;
            if (plan != null && plan.chain.contains(to)) {
                commit(plan);
                consumeReport(to);
                if (to.equals(scene)) {
                    line = Math.min(ln, g.scenes.get(scene).lastLine());
                    entryReported = true;
                }
                return true;
            }
            if (finishAt == null && to.equals(scene)) {
                Scene s = g.scenes.get(scene);
                if (ln > s.lastLine()) return false;
                if (ln == 0 && line == 0 && entryReported && (plan == null || plan.guessed)) {
                    // 0번 줄에 머물러 있는데 0번 줄 보고가 또 옴: 이벤트 이름 없는 선택지로 같은 장면에 다시 들어온 것
                    // (그 선택지의 호감도를 서버 계산에도 반영해야 뒤의 need 판정이 클라와 같다)
                    Plan again = guessEntering(s, to);
                    if (again != null) {
                        commit(again);
                        consumeReport(to);
                        if (to.equals(scene)) entryReported = true;
                    }
                    return true;
                }
                if (ln >= line) {
                    line = ln;
                    entryReported = true;
                    consumeReport(to);
                    return true;
                }
                // 줄 번호가 줄어드는 건 같은 장면으로 다시 들어온 경우뿐
            } else if (ln == 0 && consumeReport(to)) {
                return true; // 대사 없이 지나간 장면의 늦은 보고 (한 번만: 같은 길을 또 가면 새 이동으로 계산해야 한다)
            }
            if (finishAt != null || scene.isEmpty() || (plan != null && !plan.guessed)) return false;
            Plan p = guessEntering(g.scenes.get(scene), to);
            if (p == null) return false;
            commit(p);
            consumeReport(to);
            if (to.equals(scene)) {
                line = Math.min(ln, g.scenes.get(scene).lastLine());
                entryReported = true;
            }
            return true;
        }

        /** dialogue_event: 지금 고를 수 있는 선택지의 이벤트이거나, 들어가는 장면의 이벤트인지. */
        boolean event(String name) {
            if (name == null || name.isEmpty()) return false;
            if (plan != null && plan.events.contains(name)) return true;
            if (lastEvents.contains(name)) return true;
            if (finishAt == null && !scene.isEmpty() && (plan == null || plan.guessed)) {
                Scene s = g.scenes.get(scene);
                if (!choiceEventUsed) {
                    for (Choice c : visible(s, affinity)) {
                        if (c.event.equals(name)) {
                            choiceEventUsed = true;
                            lateChoiceEvents.clear();
                            plan = planChoice(s, c, false);
                            return true;
                        }
                    }
                }
                // 장면 이벤트가 진행 보고보다 먼저 온 경우: 이벤트 이름 없는 선택지 / next 로 들어가는 장면
                // (이름이 있는 선택지는 그 선택지 이벤트가 먼저 와야 한다 — 다른 갈래의 장면 이벤트를 미리 받지 못하게)
                Plan p = guess(s, x -> x.events.contains(name), false);
                if (p != null) {
                    plan = p;
                    return true;
                }
            }
            if (lateChoiceEvents.contains(name)) {
                lateChoiceEvents.clear();
                return true;
            }
            return false;
        }

        /** dialogue_done: lastScene 이 실제로 대화가 끝날 수 있는 장면인지. */
        boolean done(String last) {
            if (last == null) return false;
            if (plan != null && last.equals(plan.finish)) {
                commit(plan);
                return true;
            }
            if (finishAt != null) return finishAt.equals(last);
            if (scene.isEmpty() || (plan != null && !plan.guessed)) return false;
            Plan p = guess(g.scenes.get(scene), x -> last.equals(x.finish), true);
            if (p == null) return false;
            commit(p);
            return true;
        }
    }
}
