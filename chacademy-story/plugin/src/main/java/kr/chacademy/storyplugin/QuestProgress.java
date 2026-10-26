package kr.chacademy.storyplugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 유저별 스토리 단계 진행 (plugins/ChacademyStory/quest-progress.yml). Bukkit 에 의존하지 않는다.
 * 진행 기준 서버는 학교 서버 하나 (이 파일은 그 서버의 로컬 파일). 다른 서버와 공유하지 않는다.
 */
final class QuestProgress {
    static final int FORMAT = 1;
    /** 퀘스트창 완료 목록에도 쓰므로 넉넉히 (2026-10-25, 64 → 600). */
    static final int MAX_HISTORY = 600;

    enum Phase { ACTIVE, PLAYING }

    static final class P {
        /** 지금 단계. 빈 값 = 진행할 단계 없음 (끝냈거나 아직 시작 전). */
        String step = "";
        /** 단계에 들어갈 때마다 1씩 오른다. 패킷·중복 실행 구분용. */
        int run;
        final Map<Integer, Integer> counts = new HashMap<>();
        final Map<Integer, Set<String>> hits = new HashMap<>();
        /** 제출 조건의 아이템을 이미 걷었는지 (걷은 뒤에는 버려도 충족으로 남는다). */
        boolean submitted;
        Phase phase = Phase.ACTIVE;
        /** PLAYING 일 때 기다리는 것: "d:<대화>" 또는 "c:<컷신>". */
        String waiting = "";
        /** 끝낸 단계 (최근 순). */
        final List<String> history = new ArrayList<>();
        boolean started;
        /** 시작명령-한번 단계에서 시작명령을 이미 실행한(실행하려고 저장한) 회차. */
        int startedRun;
        /** 컷신을 여러 번 불러오지 못해 건너뛴 기록 "단계:컷신" (관리자 확인용, 마지막 것). */
        String skippedCutscene = "";

        void enter(String id) {
            step = id == null ? "" : id;
            run++;
            counts.clear();
            hits.clear();
            submitted = false;
            phase = Phase.ACTIVE;
            waiting = "";
            started = true;
        }

        void finished(String id) {
            if (id == null || id.isEmpty()) return;
            history.remove(id);
            history.add(0, id);
            while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        }
    }

    final Map<UUID, P> players = new HashMap<>();

    P of(UUID id) { return players.computeIfAbsent(id, k -> new P()); }

    // ------------------------------------------------------------ 저장 형식

    Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        Map<String, Object> all = new LinkedHashMap<>();
        for (var e : players.entrySet()) {
            P p = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", p.step);
            m.put("run", p.run);
            m.put("started", p.started);
            if (p.submitted) m.put("submitted", true);
            if (p.startedRun != 0) m.put("started-run", p.startedRun);
            if (!p.skippedCutscene.isEmpty()) m.put("skipped-cutscene", p.skippedCutscene);
            if (p.phase == Phase.PLAYING) { m.put("phase", "playing"); m.put("waiting", p.waiting); }
            if (!p.counts.isEmpty()) {
                Map<String, Object> c = new LinkedHashMap<>();
                p.counts.forEach((k, v) -> c.put(String.valueOf(k), v));
                m.put("counts", c);
            }
            if (!p.hits.isEmpty()) {
                Map<String, Object> h = new LinkedHashMap<>();
                p.hits.forEach((k, v) -> h.put(String.valueOf(k), new ArrayList<>(v)));
                m.put("hits", h);
            }
            if (!p.history.isEmpty()) m.put("history", new ArrayList<>(p.history));
            all.put(e.getKey().toString(), m);
        }
        root.put("players", all);
        return root;
    }

    static QuestProgress fromMap(Map<?, ?> root, List<String> notes) {
        QuestProgress q = new QuestProgress();
        if (root == null || !(root.get("players") instanceof Map<?, ?> all)) return q;
        for (var e : all.entrySet()) {
            UUID id;
            try { id = UUID.fromString(String.valueOf(e.getKey())); } catch (IllegalArgumentException ex) { notes.add("잘못된 UUID 무시: " + e.getKey()); continue; }
            if (!(e.getValue() instanceof Map<?, ?> m)) continue;
            P p = new P();
            Object step = m.get("step");
            p.step = step != null && StorySafety.validId(String.valueOf(step)) ? String.valueOf(step) : "";
            p.run = m.get("run") instanceof Number n ? n.intValue() : 0;
            p.started = Boolean.TRUE.equals(m.get("started")) || !p.step.isEmpty();
            p.submitted = Boolean.TRUE.equals(m.get("submitted"));
            p.startedRun = m.get("started-run") instanceof Number sr ? sr.intValue() : 0;
            p.skippedCutscene = m.get("skipped-cutscene") == null ? "" : String.valueOf(m.get("skipped-cutscene"));
            if ("playing".equals(m.get("phase"))) {
                p.phase = Phase.PLAYING;
                p.waiting = m.get("waiting") == null ? "" : String.valueOf(m.get("waiting"));
            }
            if (m.get("counts") instanceof Map<?, ?> c) {
                for (var ce : c.entrySet()) {
                    try { p.counts.put(Integer.parseInt(String.valueOf(ce.getKey())), ((Number) ce.getValue()).intValue()); }
                    catch (RuntimeException ignored) { }
                }
            }
            if (m.get("hits") instanceof Map<?, ?> h) {
                for (var he : h.entrySet()) {
                    if (!(he.getValue() instanceof List<?> l)) continue;
                    try {
                        Set<String> set = new LinkedHashSet<>();
                        for (Object o : l) if (o != null) set.add(String.valueOf(o));
                        p.hits.put(Integer.parseInt(String.valueOf(he.getKey())), set);
                    } catch (NumberFormatException ignored) { }
                }
            }
            if (m.get("history") instanceof List<?> l) for (Object o : l) if (o != null && p.history.size() < MAX_HISTORY) p.history.add(String.valueOf(o));
            q.players.put(id, p);
        }
        return q;
    }
}
