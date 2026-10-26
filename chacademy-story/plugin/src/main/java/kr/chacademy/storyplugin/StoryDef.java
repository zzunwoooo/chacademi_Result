package kr.chacademy.storyplugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 대화 하나의 서버 쪽 정의: 흐름 (dialogue.yml, 없을 수 있음) + 명령어·호감도 (server_commands.yml).
 * 서버를 켤 때와 /서버관리 새로고침 스토리 때만 파일에서 읽어 메모리에 두고, 패킷을 받을 때는 파일을 건드리지 않는다.
 * 만든 뒤에는 바뀌지 않는다. Bukkit 없이 테스트한다.
 */
final class StoryDef {
    /** 호감도 한 항목 한도 (FORMAT.md "호감도 한도"): 선택지·장면 이벤트 ±20, 결말·끝 ±50. 편집기와 같다. */
    static final int EVENT_AFFINITY_LIMIT = 20;
    static final int END_AFFINITY_LIMIT = 50;
    static final int MAX_COMMANDS = 64;

    final String id;
    /** null = 서버에 dialogue.yml 이 없음 (require-server-graph: false 일 때만 검증 없이 열린다). */
    final StoryGraph graph;
    final List<String> npcs;
    final List<String> end;
    final Map<String, List<String>> endings;
    final Map<String, List<String>> events;
    final Map<String, Integer> affinityEnd;
    final Map<String, Map<String, Integer>> affinityEndings;
    final Map<String, Map<String, Integer>> affinityEvents;
    /** 대화별 allow-replay. null = config 의 allow-replay 를 따름. */
    final Boolean allowReplay;

    private StoryDef(String id, StoryGraph graph, List<String> npcs, List<String> end, Map<String, List<String>> endings,
                     Map<String, List<String>> events, Map<String, Integer> affinityEnd,
                     Map<String, Map<String, Integer>> affinityEndings, Map<String, Map<String, Integer>> affinityEvents,
                     Boolean allowReplay) {
        this.id = id;
        this.graph = graph;
        this.npcs = npcs;
        this.end = end;
        this.endings = endings;
        this.events = events;
        this.affinityEnd = affinityEnd;
        this.affinityEndings = affinityEndings;
        this.affinityEvents = affinityEvents;
        this.allowReplay = allowReplay;
    }

    /** 서버 파일에 명령어나 호감도가 적힌 이벤트인지. */
    boolean declares(String event) {
        return events.containsKey(event) || affinityEvents.containsKey(event);
    }

    /** commands = server_commands.yml 을 읽은 Map (없으면 null). warnings 에 고칠 점을 적는다. */
    static StoryDef parse(String id, Map<?, ?> commands, StoryGraph graph, List<String> warnings) {
        Map<?, ?> c = commands == null ? Map.of() : commands;
        List<String> npcs = new ArrayList<>();
        for (Object o : list(c.get("npcs"))) {
            String n = String.valueOf(o);
            if (StorySafety.NPC.matcher(n).matches() && !npcs.contains(n) && npcs.size() < 16) npcs.add(n);
            else warnings.add("npcs 항목 무시: " + clip(n));
        }
        Map<String, List<String>> endings = commandMap(c.get("endings"), "endings", warnings);
        Map<String, List<String>> events = commandMap(c.get("events"), "events", warnings);
        Map<?, ?> aff = c.get("affinity") instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Integer> affEnd = scores(aff.get("end"), END_AFFINITY_LIMIT, "affinity.end", warnings);
        Map<String, Map<String, Integer>> affEndings = scoreMap(aff.get("endings"), END_AFFINITY_LIMIT, "affinity.endings", warnings);
        Map<String, Map<String, Integer>> affEvents = scoreMap(aff.get("events"), EVENT_AFFINITY_LIMIT, "affinity.events", warnings);
        Object replay = c.get("allow-replay");
        Boolean allowReplay = replay == null ? null : replay instanceof Boolean b ? b : Boolean.parseBoolean(replay.toString().trim());
        if (graph != null) {
            var known = graph.events();
            for (String e : events.keySet()) if (!known.contains(e)) warnings.add("events." + e + ": dialogue.yml 에 없는 이벤트 (실행되지 않음)");
            for (String e : affEvents.keySet()) if (!known.contains(e)) warnings.add("affinity.events." + e + ": dialogue.yml 에 없는 이벤트 (적용되지 않음)");
            for (String s : endings.keySet()) if (!graph.scenes.containsKey(s)) warnings.add("endings." + s + ": dialogue.yml 에 없는 장면 (실행되지 않음)");
            for (String s : affEndings.keySet()) if (!graph.scenes.containsKey(s)) warnings.add("affinity.endings." + s + ": dialogue.yml 에 없는 장면 (적용되지 않음)");
        }
        return new StoryDef(id, graph, List.copyOf(npcs), commandList(c.get("end"), "end", warnings), endings, events,
                affEnd, affEndings, affEvents, allowReplay);
    }

    private static List<String> commandList(Object o, String where, List<String> warnings) {
        List<String> out = new ArrayList<>();
        if (o instanceof String s) o = List.of(s);
        for (Object x : list(o)) {
            if (x == null) continue;
            String cmd = x.toString();
            if (cmd.isBlank()) continue;
            if (cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0 || cmd.length() > 1000) {
                warnings.add(where + ": 줄바꿈이 있거나 너무 긴 명령어 무시");
                continue;
            }
            if (out.size() >= MAX_COMMANDS) {
                warnings.add(where + ": 명령어는 " + MAX_COMMANDS + "개까지 (나머지 무시)");
                break;
            }
            out.add(cmd);
        }
        return List.copyOf(out);
    }

    private static Map<String, List<String>> commandMap(Object o, String where, List<String> warnings) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!StorySafety.validId(key)) {
                    warnings.add(where + " 의 이름은 소문자·숫자·_·- 1~64자 (무시됨): \"" + clip(key) + "\"");
                    continue;
                }
                out.put(key, commandList(e.getValue(), where + "." + key, warnings));
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private static Map<String, Map<String, Integer>> scoreMap(Object o, int limit, String where, List<String> warnings) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!StorySafety.validId(key)) {
                    warnings.add(where + " 의 이름은 소문자·숫자·_·- 1~64자 (무시됨): \"" + clip(key) + "\"");
                    continue;
                }
                Map<String, Integer> s = scores(e.getValue(), limit, where + "." + key, warnings);
                if (!s.isEmpty()) out.put(key, s);
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** {npc: 값}. 값은 ±limit 로 자르고 (자르면 경고), 0 과 잘못된 npc id 는 뺀다. */
    private static Map<String, Integer> scores(Object o, int limit, String where, List<String> warnings) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String npc = String.valueOf(e.getKey());
                if (!StorySafety.NPC.matcher(npc).matches()) {
                    warnings.add(where + ": 잘못된 NPC id 무시: \"" + clip(npc) + "\"");
                    continue;
                }
                double raw;
                if (e.getValue() instanceof Number n) raw = n.doubleValue();
                else {
                    try {
                        raw = Double.parseDouble(String.valueOf(e.getValue()).trim());
                    } catch (NumberFormatException ex) {
                        raw = Double.NaN;
                    }
                }
                if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                    warnings.add(where + "." + npc + ": 숫자가 아님 (무시됨)");
                    continue;
                }
                long v = Math.round(raw);
                if (v > limit || v < -limit) {
                    warnings.add(where + "." + npc + ": " + v + " → ±" + limit + " 로 자름");
                    v = Math.max(-limit, Math.min(limit, v));
                }
                if (v != 0 && out.size() < 16) out.put(npc, (int) v);
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String clip(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
