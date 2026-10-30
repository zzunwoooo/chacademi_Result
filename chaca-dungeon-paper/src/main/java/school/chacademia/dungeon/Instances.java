package school.chacademia.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;

/**
 * 칸(인스턴스) 상태와 대기열 — 순수 상태 기계 (Bukkit 호출 없음, 시험 가능).
 * <pre>
 * FREE ─배정→ RESERVED(입장 준비 안내 또는 입장 처리 중) ─도착→ RUNNING ─끝→ RESETTING ─정리 완료→ FREE
 *                    └ 응답 없음·취소·이동 실패 ────────────────────────→ RESETTING
 *                                                         정리 실패 → BROKEN (배정 안 함, /던전관리 칸초기화)
 * </pre>
 * 대기열은 등록 순서(FIFO). 빈 칸이 생기면 맨 앞 사람에게 그 칸을 잡아 두고 안내(offer)한다.
 * 대기열이 비어 있을 때만 포탈에서 바로 입장할 수 있다 (먼저 기다린 사람이 우선).
 */
final class Instances {
    enum State { FREE, RESERVED, RUNNING, RESETTING, BROKEN }

    static final class Slot {
        final String dungeon;
        final int no;
        State state = State.FREE;
        UUID holder;
        Run run;
        String broken = "";
        Slot(String dungeon, int no) { this.dungeon = dungeon; this.no = no; }
    }

    /** 자리가 나서 잡아 둔 칸. session = 이 안내에 응답할 때 쓸 번호. */
    record Offer(UUID player, Slot slot, long expiresAt, int session) {}

    /** 한 판. 서버 시각(ms) 기준. startedAt=0 = 아직 도전 시작 전. */
    static final class Run {
        final UUID id = UUID.randomUUID();
        final UUID player;
        final Slot slot;
        final int ticketsTaken;
        final String ticketItem;
        long arrivedAt, startedAt, endedAt;
        Location arrival;
        boolean ended, arrived, cleared;
        int stage = -1, progress;
        /** 이 판에서 소환한 몹 → 방 이름 ("" = 방 아님, "#boss" = 보스). */
        final Map<UUID, String> mobs = new LinkedHashMap<>();
        final Set<String> roomsTriggered = new HashSet<>(), roomsCleared = new HashSet<>();
        /** 방마다 실제로 소환한 수 (진행도 분모). */
        final Map<String, Integer> roomTotals = new HashMap<>();
        String lockedRoom;
        UUID boss;
        long lastSync;

        Run(UUID player, Slot slot, int ticketsTaken, String ticketItem) {
            this.player = player; this.slot = slot; this.ticketsTaken = ticketsTaken; this.ticketItem = ticketItem;
        }

        long elapsed(long now) {
            if (startedAt == 0) return 0;
            return Math.max(0, (endedAt > 0 ? endedAt : now) - startedAt);
        }

        int roomAlive(String room) {
            int n = 0;
            for (String r : mobs.values()) if (r.equals(room)) n++;
            return n;
        }
    }

    private final Map<String, List<Slot>> slots = new HashMap<>();
    private final Map<String, LinkedHashSet<UUID>> queues = new HashMap<>();
    private final Map<UUID, Offer> offers = new HashMap<>();
    private final Map<UUID, String> queuedIn = new HashMap<>();

    /** 던전 설정이 바뀌면 칸 수를 맞춘다. 쓰는 중인 칸은 그대로 둔다 (줄어든 칸은 끝나면 사라짐). */
    void ensure(String dungeon, int count) {
        var list = slots.computeIfAbsent(dungeon, k -> new ArrayList<>());
        while (list.size() < count) list.add(new Slot(dungeon, list.size() + 1));
        list.removeIf(s -> s.no > count && s.state == State.FREE);
    }

    List<Slot> slots(String dungeon) { return slots.getOrDefault(dungeon, List.of()); }

    Slot slot(String dungeon, int no) {
        for (var s : slots(dungeon)) if (s.no == no) return s;
        return null;
    }

    int capacity(String dungeon) { return slots(dungeon).size(); }

    /** 쓰는 중인 칸 수 (잡아 둔·진행 중·정리 중·고장). */
    int busy(String dungeon) {
        int n = 0;
        for (var s : slots(dungeon)) if (s.state != State.FREE) n++;
        return n;
    }

    Slot firstFree(String dungeon) {
        for (var s : slots(dungeon)) if (s.state == State.FREE) return s;
        return null;
    }

    // ------------------------------------------------------------ 대기열

    /** 등록. 이미 이 던전/다른 던전에 등록됐으면 false. */
    boolean join(String dungeon, UUID player) {
        if (queuedIn.containsKey(player) || offers.containsKey(player)) return false;
        queues.computeIfAbsent(dungeon, k -> new LinkedHashSet<>()).add(player);
        queuedIn.put(player, dungeon);
        return true;
    }

    /** 대기 취소 (잡아 둔 칸이 있으면 풀어서 돌려준다 → 부르는 쪽이 정리). */
    Slot leave(UUID player) {
        String d = queuedIn.remove(player);
        if (d != null) queues.getOrDefault(d, new LinkedHashSet<>()).remove(player);
        Offer o = offers.remove(player);
        return o == null ? null : o.slot();
    }

    String queuedDungeon(UUID player) {
        String d = queuedIn.get(player);
        if (d != null) return d;
        Offer o = offers.get(player);
        return o == null ? null : o.slot().dungeon;
    }

    /** 1부터. 대기 중이 아니면 0. */
    int position(String dungeon, UUID player) {
        int i = 0;
        for (UUID id : queues.getOrDefault(dungeon, new LinkedHashSet<>())) { i++; if (id.equals(player)) return i; }
        return 0;
    }

    int queued(String dungeon) { return queues.getOrDefault(dungeon, new LinkedHashSet<>()).size(); }

    boolean hasWaiting(String dungeon) {
        if (queued(dungeon) > 0) return true;
        for (var o : offers.values()) if (o.slot().dungeon.equals(dungeon)) return true;
        return false;
    }

    Offer offer(UUID player) { return offers.get(player); }

    Iterable<Offer> offers() { return List.copyOf(offers.values()); }

    /**
     * 빈 칸마다 맨 앞 대기자에게 칸을 잡아 준다. online 이 false 인 사람은 건너뛰고 대기에서 뺀다.
     * 돌려준 목록 = 새로 만든 안내 (부르는 쪽이 알림을 보낸다).
     */
    List<Offer> assign(String dungeon, long now, long offerMillis, java.util.function.Predicate<UUID> online, java.util.function.IntSupplier session) {
        var made = new ArrayList<Offer>();
        var queue = queues.getOrDefault(dungeon, new LinkedHashSet<>());
        Slot free;
        while (!queue.isEmpty() && (free = firstFree(dungeon)) != null) {
            UUID next = queue.iterator().next();
            queue.remove(next);
            queuedIn.remove(next);
            if (!online.test(next)) continue;
            free.state = State.RESERVED;
            free.holder = next;
            var o = new Offer(next, free, now + offerMillis, session.getAsInt());
            offers.put(next, o);
            made.add(o);
        }
        return made;
    }

    /** 안내가 지난 사람 목록 (안내는 지운다, 칸 정리는 부르는 쪽). */
    List<Offer> expired(long now) {
        var out = new ArrayList<Offer>();
        for (var it = offers.values().iterator(); it.hasNext(); ) {
            var o = it.next();
            if (o.expiresAt() <= now) { out.add(o); it.remove(); }
        }
        return out;
    }

    /** 안내를 받아 들어가는 중: 안내만 지운다 (칸은 그대로 RESERVED). */
    void consumeOffer(UUID player) { offers.remove(player); }

    /** 포탈에서 바로 입장: 대기자가 없고 빈 칸이 있으면 그 칸을 잡는다. */
    Slot reserveDirect(String dungeon, UUID player) {
        if (hasWaiting(dungeon)) return null;
        Slot free = firstFree(dungeon);
        if (free == null) return null;
        free.state = State.RESERVED;
        free.holder = player;
        return free;
    }

    /** 칸을 정리 단계로 (판 연결도 끊는다). */
    static void toResetting(Slot slot) {
        slot.state = State.RESETTING;
        slot.run = null;
        slot.holder = null;
    }
}
