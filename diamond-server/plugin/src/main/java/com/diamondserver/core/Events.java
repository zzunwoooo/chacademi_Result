package com.diamondserver.core;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 후원 이벤트 공통 틀: 후원이 들어오면 줄(대기열)에 세우고, 한 번에 하나씩 "알림 → 실행" 한다.
 * 어떤 개수에 어떤 이벤트가 나가는지는 config.yml 의 events 에 적는다.
 *
 * 지금 되는 동작(action):
 *   food <개수>                밥 지급
 *   goal <최소> <최대>          남은 목표를 그 사이 랜덤만큼 바꿈 (음수 = 감소)
 *   repeat <횟수> goal <최소> <최대>   위를 여러 번 (한 번씩 숫자가 바뀜)
 *   death                      즉사
 *   pending                    아직 안 만든 이벤트 (알림만)
 */
final class Events {
    record Def(String name, String kind, List<String> actions) { }
    record Job(UUID target, String targetName, String donor, int count) { }

    private final DiamondPlugin pl;
    private final Map<Integer, Def> defs = new HashMap<>();
    private final Deque<Job> queue = new ArrayDeque<>();
    private final Map<UUID, List<Job>> waiting = new HashMap<>();     // 대상이 접속해 있지 않아 미뤄 둔 것
    private Job current;

    Events(DiamondPlugin pl) { this.pl = pl; }

    void load() {
        defs.clear();
        ConfigurationSection cs = pl.getConfig().getConfigurationSection("events");
        if (cs != null) for (String k : cs.getKeys(false)) {
            try {
                int count = Integer.parseInt(k);
                defs.put(count, new Def(cs.getString(k + ".name", k + "개 이벤트"), cs.getString(k + ".kind", "neutral"), cs.getStringList(k + ".actions")));
            } catch (NumberFormatException e) {
                pl.getLogger().warning("후원 이벤트 설정 오류로 건너뜀: " + k);
            }
        }
        pl.getLogger().info("후원 이벤트: " + defs.size() + "개 등록");
    }

    boolean has(int count) { return defs.containsKey(count); }

    /** 후원 1건을 줄에 세운다. 등록 안 된 개수면 false */
    boolean enqueue(UUID target, String targetName, String donor, int count) {
        if (!defs.containsKey(count)) return false;
        Job job = new Job(target, targetName, donor == null || donor.isEmpty() ? "익명" : donor, count);
        if (Bukkit.getPlayer(target) == null) waiting.computeIfAbsent(target, k -> new ArrayList<>()).add(job);
        else queue.add(job);
        sendQueue();
        if (current == null) next();
        return true;
    }

    /** 접속하면 미뤄 둔 후원을 줄에 넣는다 */
    void onJoin(Player p) {
        List<Job> held = waiting.remove(p.getUniqueId());
        if (held != null) queue.addAll(held);
        sendQueue();
        if (current == null) next();
    }

    private void next() {
        current = null;
        Job job = queue.poll();
        if (job == null) { sendQueue(); return; }
        Player target = Bukkit.getPlayer(job.target);
        if (target == null) {                                   // 그사이 나갔으면 다시 미뤄 둔다
            waiting.computeIfAbsent(job.target, k -> new ArrayList<>()).add(job);
            next();
            return;
        }
        current = job;
        Def def = defs.get(job.count);
        sendQueue();
        for (Player p : Bukkit.getOnlinePlayers()) {
            pl.net.event(p, job.donor, job.count, def.name, def.kind, target.getName());
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, def.kind.equals("bad") ? 0.7f : 1.4f);
        }
        long announce = Math.max(1, pl.getConfig().getLong("donation.announce-ticks", 40));
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            long busy = 0;
            Player t = Bukkit.getPlayer(job.target);
            if (t == null) waiting.computeIfAbsent(job.target, k -> new ArrayList<>()).add(job);
            else for (String action : def.actions) busy = Math.max(busy, run(t, def, action));
            long gap = Math.max(1, pl.getConfig().getLong("donation.queue-gap-ticks", 20));
            Bukkit.getScheduler().runTaskLater(pl, this::next, busy + gap);
        }, announce);
    }

    private static void toastAll(DiamondPlugin pl, String text, boolean ok) {
        for (Player p : Bukkit.getOnlinePlayers()) pl.net.toast(p, text, ok);
    }

    private static String signed(long v) { return (v >= 0 ? "+" : "-") + Math.abs(v); }

    private static long roll(long a, long b) {
        long lo = Math.min(a, b), hi = Math.max(a, b);
        return ThreadLocalRandom.current().nextLong(lo, hi + 1);
    }

    /** 동작 하나를 실행하고, 끝날 때까지 걸리는 시간(틱)을 돌려준다 */
    private long run(Player t, Def def, String action) {
        String[] a = action.trim().split("\\s+");
        boolean good = def.kind.equals("good");
        try {
            switch (a[0]) {
                case "food" -> {
                    int n = Integer.parseInt(a[1]);
                    Items.give(t, pl.items.make(Items.FOOD, n));
                    toastAll(pl, t.getName() + "님이 " + pl.items.nameOf(Items.FOOD) + " " + n + "개를 받았습니다.", true);
                    return 0;
                }
                case "goal" -> {
                    long d = roll(Long.parseLong(a[1]), Long.parseLong(a[2]));
                    pl.ledger.addGoal(t, d);
                    toastAll(pl, t.getName() + "님의 남은 목표 " + signed(d), d <= 0);
                    return 0;
                }
                case "repeat" -> {
                    int times = Math.max(1, Math.min(50, Integer.parseInt(a[1])));
                    if (!a[2].equals("goal")) throw new IllegalArgumentException("repeat 는 goal 만 됩니다");
                    long lo = Long.parseLong(a[3]), hi = Long.parseLong(a[4]);
                    final long step = 8;
                    final long[] sum = {0};
                    final UUID id = t.getUniqueId();
                    final String name = t.getName();
                    for (int i = 0; i < times; i++) {
                        final boolean last = i == times - 1;
                        Bukkit.getScheduler().runTaskLater(pl, () -> {
                            Player p = Bukkit.getPlayer(id);
                            if (p != null) {
                                long d = roll(lo, hi);
                                sum[0] += d;
                                pl.ledger.addGoal(p, d);
                                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1f);
                            }
                            if (last) toastAll(pl, name + "님의 남은 목표 " + signed(sum[0]) + " (" + times + "회)", sum[0] <= 0);
                        }, i * step);
                    }
                    return times * step;
                }
                case "death" -> {
                    t.getWorld().strikeLightningEffect(t.getLocation());
                    t.setHealth(0);
                    toastAll(pl, t.getName() + "님이 즉사했습니다.", false);
                    return 20;
                }
                case "pending" -> {
                    toastAll(pl, def.name + " 이벤트는 아직 준비 중입니다.", good);
                    return 0;
                }
                default -> pl.getLogger().warning("모르는 이벤트 동작: " + action);
            }
        } catch (RuntimeException e) {
            pl.getLogger().warning("이벤트 동작 실행 실패 '" + action + "': " + e);
        }
        return 0;
    }

    /** 지금 줄 서 있는 이벤트 목록을 모드에 보낸다 (진행 중인 것이 맨 위) */
    private void sendQueue() {
        List<String> lines = new ArrayList<>();
        if (current != null) lines.add(defs.get(current.count).name + " → " + current.targetName);
        for (Job j : queue) lines.add(defs.get(j.count).name + " → " + j.targetName);
        for (Player p : Bukkit.getOnlinePlayers()) if (pl.net.hasMod(p)) pl.net.queue(p, lines);
    }

    /** 방금 접속한 사람에게 지금 줄을 보여 준다 */
    void sendQueueTo(Player p) {
        List<String> lines = new ArrayList<>();
        if (current != null) lines.add(defs.get(current.count).name + " → " + current.targetName);
        for (Job j : queue) lines.add(defs.get(j.count).name + " → " + j.targetName);
        pl.net.queue(p, lines);
    }

    int size() { return queue.size() + (current == null ? 0 : 1); }
}
