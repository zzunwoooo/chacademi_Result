package kr.chacademy.storyplugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 플레이어별 스토리 상태 전부 (progress.yml format 2). 메인 스레드에서만 고치고,
 * 저장할 때는 {@link #snapshot()} 으로 복사본을 떠서 쓰기 스레드에 넘긴다. Bukkit 없이 테스트한다.
 * <ul>
 *   <li>active / queue: 지금 보여 주는 컷신·대화와 차례를 기다리는 요청 (한 번에 하나만 재생)</li>
 *   <li>done / fired / gained: 완료 기록 — 끝낸 대화, 대화별로 이미 실행한 이벤트, 대화별로 준 호감도 합</li>
 *   <li>effects: 저장은 됐지만 아직 실행하지 못한 효과 (명령어·호감도). 접속하면 실행한다</li>
 * </ul>
 */
final class StoryState {
    static final int FORMAT = 2;
    static final int MAX_QUEUE = 16;

    enum Kind { CUTSCENE, DIALOGUE }

    /** 대화 열기 요청의 결과: 처음 / 다시 보기 / 이미 끝냄 (열지 않음) / 이미 진행·대기 중 / 대기열 가득. */
    enum Open { FRESH, REPLAY, ALREADY_DONE, DUPLICATE, QUEUE_FULL }

    /** 재생 요청 하나 (/대화관리 재생 한 번). */
    static final class Request {
        final Kind kind;
        final String id;
        final long seq;
        /** 컷신 건너뛰기 규칙 (StoryCodec.MODE_*). */
        int mode;
        /** 다시 보기: 아무것도 주지 않고 기록도 바꾸지 않는다. */
        boolean replay;
        /** 대화에서 보고 있던 곳 (이어서 보기). */
        String scene = "";
        int line = 0;

        // ---- 아래는 저장하지 않는다 (접속할 때마다 새로)
        /** 모드로 재생 신호를 보냈고 끝나기를 기다리는 중. */
        boolean sent;
        /** 지금은 다시 보내지 않는다 (모드 없음, 파일 없음, 재시도 한도 등). 다음 접속이나 reload 때 풀린다. */
        boolean parked;
        boolean toldMissing;
        long sentAtMs;
        long retryAtMs;
        int resends;
        int illegal;
        /** 비동기 호감도 조회가 끝났을 때 아직 같은 전송인지 확인용. */
        Object token;
        StoryGraph.Walk walk;

        Request(Kind kind, String id, long seq) {
            this.kind = kind;
            this.id = id;
            this.seq = seq;
        }

        void resetTransient() {
            sent = false;
            parked = false;
            toldMissing = false;
            sentAtMs = 0;
            retryAtMs = 0;
            resends = 0;
            illegal = 0;
            token = null;
            walk = null;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind == Kind.CUTSCENE ? "cutscene" : "dialogue");
            m.put("id", id);
            m.put("seq", seq);
            if (kind == Kind.CUTSCENE) m.put("mode", mode);
            if (replay) m.put("replay", true);
            if (kind == Kind.DIALOGUE) {
                m.put("scene", scene);
                m.put("line", line);
            }
            return m;
        }

        static Request fromMap(Object o) {
            if (!(o instanceof Map<?, ?> m)) return null;
            String id = str(m.get("id"));
            if (!StorySafety.validId(id)) return null;
            Kind kind = "cutscene".equals(str(m.get("kind"))) ? Kind.CUTSCENE : Kind.DIALOGUE;
            Request r = new Request(kind, id, num(m.get("seq")));
            r.mode = (int) Math.max(0, Math.min(2, num(m.get("mode"))));
            r.replay = Boolean.TRUE.equals(m.get("replay"));
            String scene = str(m.get("scene"));
            r.scene = StorySafety.validId(scene) ? scene : "";
            r.line = (int) Math.max(0, Math.min(10000, num(m.get("line"))));
            return r;
        }
    }

    /** 저장된 뒤 실행할 효과. type: event (name = 이벤트) | dialogue_end (name = 마지막 장면) | cutscene_end. */
    static final class Effect {
        static final String EVENT = "event", DIALOGUE_END = "dialogue_end", CUTSCENE_END = "cutscene_end";
        final long seq;
        final String type, id, name;
        final boolean skipped, missing;
        /** 이 효과가 들어간 저장이 디스크에 끝났는지. 파일에서 읽은 것은 처음부터 true. */
        boolean durable;

        Effect(long seq, String type, String id, String name, boolean skipped, boolean missing) {
            this.seq = seq;
            this.type = type;
            this.id = id;
            this.name = name == null ? "" : name;
            this.skipped = skipped;
            this.missing = missing;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("type", type);
            m.put("id", id);
            if (!name.isEmpty()) m.put("name", name);
            if (skipped) m.put("skipped", true);
            if (missing) m.put("missing", true);
            return m;
        }

        static Effect fromMap(Object o) {
            if (!(o instanceof Map<?, ?> m)) return null;
            String type = str(m.get("type")), id = str(m.get("id")), name = str(m.get("name"));
            if (!StorySafety.validId(id) || !(type.equals(EVENT) || type.equals(DIALOGUE_END) || type.equals(CUTSCENE_END))) return null;
            if (!name.isEmpty() && !StorySafety.validId(name)) return null;
            Effect e = new Effect(num(m.get("seq")), type, id, name, Boolean.TRUE.equals(m.get("skipped")), Boolean.TRUE.equals(m.get("missing")));
            e.durable = true;
            return e;
        }
    }

    /** 끝낸 대화 기록. scene = 끝난 장면 ("" = 모드 없이 끝난 것으로 침), at = 처음 끝낸 시각 (ms). */
    static final class Done {
        String scene = "";
        long at;
        int times;
    }

    static final class PlayerStory {
        /** 마지막으로 본 마인크래프트 닉네임 (관리 명령어에서 접속하지 않은 사람을 찾을 때). */
        String name = "";
        long seq;
        Request active;
        final ArrayDeque<Request> queue = new ArrayDeque<>();
        final Map<String, Done> done = new TreeMap<>();
        final Map<String, Set<String>> fired = new TreeMap<>();
        final Map<String, Integer> gained = new TreeMap<>();
        final List<Effect> effects = new ArrayList<>();
        /** 클라 고정 보상(LocalStoryRewards) 영수증: 키 → pending|applied|failed. 한 번 생기면 다시 주지 않는다 (관리자 초기화로만 지움). */
        final Map<String, String> rewards = new TreeMap<>();
        /** 끝까지 본 컷신 (서버 기록, 2026-10-25). 다음부터 이 컷신은 건너뛰기를 허용한다. 런처를 바꿔도 유지. */
        final Set<String> seen = new TreeSet<>();
        /** 효과 실행 중 (명령어가 다시 스토리 명령을 불러도 겹쳐 돌지 않게). 저장하지 않는다. */
        boolean running;

        long nextSeq() {
            return ++seq;
        }

        boolean empty() {
            return active == null && queue.isEmpty() && done.isEmpty() && fired.isEmpty() && gained.isEmpty() && effects.isEmpty() && rewards.isEmpty() && seen.isEmpty();
        }

        boolean has(Kind kind, String id) {
            if (active != null && active.kind == kind && active.id.equals(id)) return true;
            for (Request r : queue) if (r.kind == kind && r.id.equals(id)) return true;
            return false;
        }

        /** A failed client cutscene is cancelled, never completed or rewarded. */
        boolean cancelFailedCutscene(String id) {
            if (active == null || active.kind != Kind.CUTSCENE || !active.sent || !active.id.equals(id)) return false;
            return cancelCutscene(active);
        }

        boolean cancelCutscene(Request expected) {
            if (expected == null || active != expected || expected.kind != Kind.CUTSCENE) return false;
            active = null;
            return true;
        }

        /** Same queue transition used by the live pump and completion regression tests. */
        Request activateNext() {
            if (active == null) {
                active = queue.pollFirst();
                if (active != null) active.resetTransient();
            }
            return active;
        }

        /**
         * 대화를 열라는 요청을 어떻게 할지. 이미 끝낸 대화는 replay 인자나 allow-replay 가 있을 때만 "다시 보기" 로 열린다.
         * dialogueAllowReplay = 그 대화 server_commands.yml 의 allow-replay (없으면 null → config 값).
         */
        Open decideOpen(String id, boolean replayArg, Boolean dialogueAllowReplay, boolean configAllowReplay) {
            if (has(Kind.DIALOGUE, id)) return Open.DUPLICATE;
            if (done.containsKey(id)) {
                boolean allowed = replayArg || (dialogueAllowReplay != null ? dialogueAllowReplay : configAllowReplay);
                if (!allowed) return Open.ALREADY_DONE;
                return queue.size() >= MAX_QUEUE ? Open.QUEUE_FULL : Open.REPLAY;
            }
            return queue.size() >= MAX_QUEUE ? Open.QUEUE_FULL : Open.FRESH;
        }

        /**
         * 이벤트 효과를 줘도 되는지 확인하고, 되면 "실행함" 으로 기록한다 (플레이어마다 대화당 한 번).
         * 다시 보기 (replay) 는 아무것도 주지 않는다.
         */
        boolean claimEvent(Request a, String event) {
            if (a.replay) return false;
            return fired.computeIfAbsent(a.id, k -> new TreeSet<>()).add(event);
        }

        /** 대화를 끝냄으로 기록한다. 끝 효과를 줘야 하면 true (다시 보기는 기록도 효과도 없다). */
        boolean recordDone(Request a, String lastScene, long now) {
            if (a.replay) return false;
            Done d = done.computeIfAbsent(a.id, k -> new Done());
            if (d.times == 0) {
                d.at = now;
                d.scene = lastScene == null ? "" : lastScene;
            }
            d.times++;
            return true;
        }

        /** 완료 기록 지우기 (관리 명령어). 지운 것이 있으면 true. */
        boolean resetLedger(String id) {
            boolean had = done.remove(id) != null;
            had |= fired.remove(id) != null;
            had |= gained.remove(id) != null;
            had |= rewards.keySet().removeIf(k -> LocalStoryRewards.dialogue(k).equals(id));
            return had;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!name.isEmpty()) m.put("name", name);
            m.put("seq", seq);
            if (active != null) m.put("active", active.toMap());
            if (!queue.isEmpty()) {
                List<Object> q = new ArrayList<>();
                for (Request r : queue) q.add(r.toMap());
                m.put("queue", q);
            }
            if (!done.isEmpty()) {
                Map<String, Object> d = new LinkedHashMap<>();
                for (var e : done.entrySet()) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("scene", e.getValue().scene);
                    one.put("at", e.getValue().at);
                    one.put("times", e.getValue().times);
                    d.put(e.getKey(), one);
                }
                m.put("done", d);
            }
            if (!fired.isEmpty()) {
                Map<String, Object> f = new LinkedHashMap<>();
                for (var e : fired.entrySet()) if (!e.getValue().isEmpty()) f.put(e.getKey(), new ArrayList<>(new TreeSet<>(e.getValue())));
                if (!f.isEmpty()) m.put("fired", f);
            }
            if (!gained.isEmpty()) m.put("gained", new LinkedHashMap<>(gained));
            if (!rewards.isEmpty()) m.put("rewards", new LinkedHashMap<>(rewards));
            if (!seen.isEmpty()) m.put("seen", new ArrayList<>(seen));
            if (!effects.isEmpty()) {
                List<Object> l = new ArrayList<>();
                for (Effect e : effects) l.add(e.toMap());
                m.put("effects", l);
            }
            return m;
        }

        static PlayerStory fromMap(Map<?, ?> m) {
            PlayerStory ps = new PlayerStory();
            ps.name = str(m.get("name"));
            ps.seq = Math.max(0, num(m.get("seq")));
            ps.active = Request.fromMap(m.get("active"));
            if (m.get("queue") instanceof List<?> q) {
                for (Object o : q) {
                    Request r = Request.fromMap(o);
                    if (r != null && ps.queue.size() < MAX_QUEUE) ps.queue.add(r);
                }
            }
            if (m.get("done") instanceof Map<?, ?> d) {
                for (var e : d.entrySet()) {
                    String id = String.valueOf(e.getKey());
                    if (!StorySafety.validId(id) || !(e.getValue() instanceof Map<?, ?> one)) continue;
                    Done done = new Done();
                    String scene = str(one.get("scene"));
                    done.scene = StorySafety.validId(scene) ? scene : "";
                    done.at = num(one.get("at"));
                    done.times = (int) Math.max(1, Math.min(Integer.MAX_VALUE, num(one.get("times"))));
                    ps.done.put(id, done);
                }
            }
            if (m.get("fired") instanceof Map<?, ?> f) {
                for (var e : f.entrySet()) {
                    String id = String.valueOf(e.getKey());
                    if (!StorySafety.validId(id) || !(e.getValue() instanceof List<?> l)) continue;
                    Set<String> set = new TreeSet<>();
                    for (Object o : l) if (o != null && StorySafety.validId(o.toString())) set.add(o.toString());
                    if (!set.isEmpty()) ps.fired.put(id, set);
                }
            }
            if (m.get("gained") instanceof Map<?, ?> g) {
                for (var e : g.entrySet()) {
                    String id = String.valueOf(e.getKey());
                    if (StorySafety.validId(id)) ps.gained.put(id, (int) Math.max(0, Math.min(1_000_000, num(e.getValue()))));
                }
            }
            if (m.get("seen") instanceof List<?> sl) {
                for (Object o : sl) if (o != null && StorySafety.validId(o.toString())) ps.seen.add(o.toString());
            }
            if (m.get("rewards") instanceof Map<?, ?> rw) {
                for (var e : rw.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    if (LocalStoryRewards.resolve(key) != null && LocalStoryRewards.validState(e.getValue())) ps.rewards.put(key, String.valueOf(e.getValue()));
                }
            }
            if (m.get("effects") instanceof List<?> l) {
                for (Object o : l) {
                    Effect e = Effect.fromMap(o);
                    if (e != null) {
                        ps.effects.add(e);
                        ps.seq = Math.max(ps.seq, e.seq);
                    }
                }
            }
            if (ps.active != null) ps.seq = Math.max(ps.seq, ps.active.seq);
            for (Request r : ps.queue) ps.seq = Math.max(ps.seq, r.seq);
            return ps;
        }
    }

    final Map<UUID, PlayerStory> players = new HashMap<>();

    PlayerStory of(UUID id) {
        return players.computeIfAbsent(id, k -> new PlayerStory());
    }

    /** 저장용 복사본 (이후에 상태를 고쳐도 바뀌지 않는다). 아무 기록도 없는 플레이어는 뺀다. */
    Map<String, Object> snapshot() {
        Map<String, Object> ps = new TreeMap<>();
        for (var e : players.entrySet()) if (!e.getValue().empty()) ps.put(e.getKey().toString(), e.getValue().toMap());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("players", ps);
        return root;
    }

    /**
     * progress.yml 을 읽은 Map 에서 복원한다. format 이 없으면 예전 형식
     * (맨 위가 플레이어 UUID, 값이 진행 중인 대화 하나: id / scene / line / fired) 으로 보고 옮긴다.
     */
    static StoryState fromMap(Map<?, ?> root, List<String> notes) {
        StoryState st = new StoryState();
        if (root == null) return st;
        if (root.get("format") == null) {
            int moved = 0;
            for (var e : root.entrySet()) {
                UUID uuid = uuid(e.getKey());
                if (uuid == null || !(e.getValue() instanceof Map<?, ?> m)) continue;
                String id = str(m.get("id"));
                if (!StorySafety.validId(id)) continue;
                PlayerStory ps = st.of(uuid);
                Request r = new Request(Kind.DIALOGUE, id, ps.nextSeq());
                String scene = str(m.get("scene"));
                r.scene = StorySafety.validId(scene) ? scene : "";
                r.line = (int) Math.max(0, Math.min(10000, num(m.get("line"))));
                ps.active = r;
                if (m.get("fired") instanceof List<?> l) {
                    Set<String> set = new TreeSet<>();
                    for (Object o : l) if (o != null && StorySafety.validId(o.toString())) set.add(o.toString());
                    if (!set.isEmpty()) ps.fired.put(id, set);
                }
                moved++;
            }
            if (moved > 0) notes.add("예전 형식 progress.yml 에서 진행 중인 대화 " + moved + "개를 옮겼습니다");
            return st;
        }
        long format = num(root.get("format"));
        if (format > FORMAT) throw new IllegalArgumentException("progress.yml format " + format + " 은 이 플러그인보다 새 버전입니다");
        if (root.get("players") instanceof Map<?, ?> players) {
            for (var e : players.entrySet()) {
                UUID uuid = uuid(e.getKey());
                if (uuid == null || !(e.getValue() instanceof Map<?, ?> m)) continue;
                PlayerStory ps = PlayerStory.fromMap(m);
                if (!ps.empty()) st.players.put(uuid, ps);
            }
        }
        return st;
    }

    private static UUID uuid(Object key) {
        try {
            return UUID.fromString(String.valueOf(key));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static long num(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o != null) {
            try {
                return Long.parseLong(o.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
