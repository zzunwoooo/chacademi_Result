package com.diamondserver.core;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
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
 * 이벤트 동작(action):
 *   food <개수>                         밥 지급
 *   goal <최소> <최대>                   남은 목표를 그 사이 랜덤만큼 바꿈 (음수 = 감소)
 *   goalpm <개수>                       남은 목표를 +개수 또는 -개수 (반반)
 *   repeat <횟수> goal <최소> <최대>      goal 을 여러 번 (한 번씩 숫자가 바뀜)
 *   diamonds <개수>                     보유 다이아 지급
 *   half                                보유 다이아 반토막
 *   death                               즉사 (사신 연출)
 *   pool <이름>                          랜덤 풀에서 하나 (config 의 pools)
 *   poolx <이름> <횟수>                  랜덤 풀을 여러 번 연속
 *   wheel                               룰렛 (config 의 wheel)
 *   miner                               후원자 AI 플레이어
 *   pending                             아직 안 만든 이벤트 (알림만)
 *
 * 랜덤 풀 한 칸의 동작(pools 의 action):
 *   ant·curse·creeper·reverse·blackout·lava·bunny·dance·midas·giant <초>
 *   hotbar | swap | crow | rocket | anvil | rain <개수> | fountain <개수> | pinata <개수> | meteor <개수>
 */
final class Events {
    record Def(String name, String kind, String icon, List<String> actions) {
        /** 랜덤 풀 이벤트는 대상이 룰렛으로 정해질 수 있으므로 알림에 "→ 누구" 를 붙이지 않는다 */
        boolean pool() { return !actions.isEmpty() && actions.get(0).startsWith("pool prank"); }
    }
    /** rest = 연출 도중 대상이 나가서 남은 동작 (비어 있으면 그 개수의 이벤트를 처음부터) */
    record Job(UUID target, String targetName, String donor, int count, String rest) {
        Job(UUID target, String targetName, String donor, int count) { this(target, targetName, donor, count, ""); }
        Job withRest(String action) { return new Job(target, targetName, donor, count, action); }
    }
    /** 랜덤 풀 한 칸. scope = one(룰렛으로 뽑힌 1명) / all(접속한 전원) / self(후원 받은 사람) */
    record Prank(String id, String name, String scope, String icon, String action) { }
    /** 룰렛 한 칸 */
    record Prize(String name, int weight, String icon, String action) { }

    private final DiamondPlugin pl;
    private final Map<Integer, Def> defs = new HashMap<>();
    private final Map<String, List<Prank>> pools = new HashMap<>();
    private final Map<String, String> lastPick = new HashMap<>();      // 풀마다 직전에 나온 것 (다음 한 번 제외)
    private final List<Prize> wheel = new ArrayList<>();
    private final Deque<Job> queue = new ArrayDeque<>();
    private final Map<UUID, List<Job>> waiting = new HashMap<>();      // 대상이 접속해 있지 않아 미뤄 둔 것 (pending.yml 에 저장)
    private Job current;
    static final long PICK_TICKS = 70;                                 // 룰렛이 도는 시간

    Events(DiamondPlugin pl) {
        this.pl = pl;
        loadWaiting();
    }

    void load() {
        defs.clear();
        ConfigurationSection cs = pl.getConfig().getConfigurationSection("events");
        if (cs != null) for (String k : cs.getKeys(false)) {
            try {
                int count = Integer.parseInt(k);
                defs.put(count, new Def(cs.getString(k + ".name", k + "개 이벤트"), cs.getString(k + ".kind", "neutral"), cs.getString(k + ".icon", "icons/diamond"), cs.getStringList(k + ".actions")));
            } catch (NumberFormatException e) {
                pl.getLogger().warning("후원 이벤트 설정 오류로 건너뜀: " + k);
            }
        }
        pools.clear();
        ConfigurationSection ps = pl.getConfig().getConfigurationSection("pools");
        if (ps != null) for (String pool : ps.getKeys(false)) {
            List<Prank> list = new ArrayList<>();
            for (Map<?, ?> raw : ps.getMapList(pool)) {
                Object id = raw.get("id"), action = raw.get("action");
                if (id == null || action == null) { pl.getLogger().warning("랜덤 풀 설정 오류로 건너뜀: " + raw); continue; }
                Object name = raw.get("name"), scope = raw.get("scope"), icon = raw.get("icon");
                list.add(new Prank(String.valueOf(id), name == null ? String.valueOf(id) : String.valueOf(name),
                        scope == null ? "one" : String.valueOf(scope), icon == null ? "icons/diamond" : String.valueOf(icon), String.valueOf(action)));
            }
            pools.put(pool, list);
        }
        wheel.clear();
        for (Map<?, ?> raw : pl.getConfig().getMapList("wheel")) {
            Object name = raw.get("name"), action = raw.get("action");
            int weight = raw.get("weight") instanceof Number n ? n.intValue() : 0;
            if (name == null || action == null || weight <= 0) { pl.getLogger().warning("룰렛 설정 오류로 건너뜀: " + raw); continue; }
            wheel.add(new Prize(String.valueOf(name), weight, raw.get("icon") == null ? "icons/diamond" : String.valueOf(raw.get("icon")), String.valueOf(action)));
        }
        StringBuilder sb = new StringBuilder();
        pools.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v.size()));
        pl.getLogger().info("후원 이벤트: " + defs.size() + "개 등록, 랜덤 풀:" + (sb.length() == 0 ? " 없음" : sb) + ", 룰렛 " + wheel.size() + "칸");
    }

    boolean has(int count) { return defs.containsKey(count); }

    /** 등록된 후원 개수 (자동완성용, 작은 것부터) */
    List<String> counts() {
        List<Integer> keys = new ArrayList<>(defs.keySet());
        java.util.Collections.sort(keys);
        List<String> out = new ArrayList<>();
        for (int k : keys) out.add(String.valueOf(k));
        return out;
    }

    /** 바로 실행해 볼 수 있는 연출 이름 (빈칸은 _ 로): 랜덤 풀의 모든 칸 + 다이아 폭탄 + 즉사 */
    List<String> testNames() {
        List<String> out = new ArrayList<>();
        for (List<Prank> list : pools.values()) for (Prank pr : list) out.add(pr.name.replace(' ', '_'));
        out.add("다이아_폭탄");
        out.add("즉사");
        return out;
    }

    /** 시험용: 이름으로 연출 하나를 대상에게 바로 건다 (전원 대상인 것은 전원에게). 그런 이름이 없으면 false */
    boolean test(String name, Player target) {
        if (name.equals("다이아 폭탄")) { pl.rewards.bomb(target, Math.max(1, pl.getConfig().getInt("effects.bomb-diamonds", 500))); return true; }
        if (name.equals("즉사")) { pl.pranks.reaper(target); return true; }
        for (List<Prank> list : pools.values()) for (Prank pr : list) {
            if (!pr.name.equals(name) && !pr.id.equalsIgnoreCase(name)) continue;
            boolean all = pr.scope.equals("all");
            announceAll(pr, all ? "전원" : pl.nick(target) + "님");
            applyPrank(pr, all ? new ArrayList<>(Bukkit.getOnlinePlayers()) : List.of(target));
            return true;
        }
        return false;
    }

    // ── 대기열 ─────────────────────────────────────────────
    /** 후원 1건을 줄에 세운다. 등록 안 된 개수면 false */
    boolean enqueue(UUID target, String targetName, String donor, int count) {
        if (!defs.containsKey(count)) return false;
        Job job = new Job(target, targetName, donor == null || donor.isEmpty() ? "익명" : donor, count);
        if (Bukkit.getPlayer(target) == null) hold(job);
        else queue.add(job);
        sendQueue();
        if (current == null) next();
        return true;
    }

    private void hold(Job job) {
        waiting.computeIfAbsent(job.target, k -> new ArrayList<>()).add(job);
        saveWaiting();
    }

    /** 접속하면 미뤄 둔 후원을 줄에 넣는다 */
    void onJoin(Player p) {
        List<Job> held = waiting.remove(p.getUniqueId());
        if (held != null) { queue.addAll(held); saveWaiting(); }
        sendQueue();
        if (current == null) next();
    }

    private void next() {
        current = null;
        Job job = queue.poll();
        if (job == null) { sendQueue(); return; }
        Player target = Bukkit.getPlayer(job.target);
        Def def = defs.get(job.count);
        if (target == null || def == null) {                    // 그사이 나갔으면 다시 미뤄 둔다
            if (def != null) hold(job);
            next();
            return;
        }
        current = job;
        sendQueue();
        for (Player p : Bukkit.getOnlinePlayers()) {
            pl.net.event(p, job.donor, job.count, def.name, def.kind, def.icon, def.pool() ? "" : pl.nick(target));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, def.kind.equals("bad") ? 0.7f : 1.4f);
        }
        long announce = Math.max(1, pl.getConfig().getLong("donation.announce-ticks", 40));
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            long busy = 0;
            Player t = Bukkit.getPlayer(job.target);
            if (t == null) hold(job);
            else for (String action : (job.rest.isEmpty() ? def.actions : List.of(job.rest))) busy = Math.max(busy, run(t, def, action, job));
            long gap = Math.max(1, pl.getConfig().getLong("donation.queue-gap-ticks", 20));
            Bukkit.getScheduler().runTaskLater(pl, this::next, busy + gap);
        }, announce);
    }

    // ── 미뤄 둔 후원 저장 (서버를 껐다 켜도 남게) ─────────────────────
    private File waitingFile() { return new File(pl.getDataFolder(), "pending.yml"); }

    private void saveWaiting() {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Job> list : waiting.values()) for (Job j : list) {
            Map<String, Object> m = new HashMap<>();
            m.put("target", j.target.toString());
            m.put("name", j.targetName);
            m.put("donor", j.donor);
            m.put("count", j.count);
            m.put("rest", j.rest);
            rows.add(m);
        }
        y.set("pending", rows);
        try { y.save(waitingFile()); } catch (IOException e) { pl.getLogger().warning("미뤄 둔 후원 저장 실패: " + e.getMessage()); }
    }

    private void loadWaiting() {
        File f = waitingFile();
        if (!f.isFile()) return;
        int n = 0;
        for (Map<?, ?> m : YamlConfiguration.loadConfiguration(f).getMapList("pending")) {
            try {
                UUID id = UUID.fromString(String.valueOf(m.get("target")));
                int count = m.get("count") instanceof Number c ? c.intValue() : 0;
                String rest = m.get("rest") == null ? "" : String.valueOf(m.get("rest"));
                waiting.computeIfAbsent(id, k -> new ArrayList<>()).add(new Job(id, String.valueOf(m.get("name")), String.valueOf(m.get("donor")), count, rest));
                n++;
            } catch (RuntimeException ignored) { }
        }
        if (n > 0) pl.getLogger().info("미뤄 둔 후원 " + n + "건을 불러옴");
    }

    // ── 동작 실행 ───────────────────────────────────────────
    private static void toastAll(DiamondPlugin pl, String text, boolean ok) {
        for (Player p : Bukkit.getOnlinePlayers()) pl.net.toast(p, text, ok);
    }

    private static String signed(long v) { return (v >= 0 ? "+" : "-") + Math.abs(v); }

    private static long roll(long a, long b) {
        long lo = Math.min(a, b), hi = Math.max(a, b);
        return ThreadLocalRandom.current().nextLong(lo, hi + 1);
    }

    /** 동작 하나를 실행하고, 줄이 기다려야 하는 시간(틱)을 돌려준다 */
    private long run(Player t, Def def, String action, Job job) {
        final String donor = job.donor;
        String[] a = action.trim().split("\\s+");
        boolean good = def.kind.equals("good");
        try {
            switch (a[0]) {
                case "food" -> {
                    int n = Integer.parseInt(a[1]);
                    pl.mail.giveOrMail(t, pl.items.make(Items.FOOD, n), "후원 선물");
                    toastAll(pl, pl.nick(t) + "님이 " + pl.items.nameOf(Items.FOOD) + " " + n + "개를 받았습니다.", true);
                    return 0;
                }
                case "goal" -> {
                    long d = roll(Long.parseLong(a[1]), Long.parseLong(a[2]));
                    pl.ledger.addGoal(t, d);
                    toastAll(pl, pl.nick(t) + "님의 남은 목표 " + signed(d), d <= 0);
                    return 0;
                }
                case "goalpm" -> {
                    long d = Long.parseLong(a[1]) * (ThreadLocalRandom.current().nextBoolean() ? 1 : -1);
                    pl.ledger.addGoal(t, d);
                    toastAll(pl, pl.nick(t) + "님의 남은 목표 " + signed(d), d <= 0);
                    return 0;
                }
                case "repeat" -> {
                    int times = Math.max(1, Math.min(50, Integer.parseInt(a[1])));
                    if (!a[2].equals("goal")) throw new IllegalArgumentException("repeat 는 goal 만 됩니다");
                    long lo = Long.parseLong(a[3]), hi = Long.parseLong(a[4]);
                    final long step = 8;
                    final long[] sum = {0};
                    final UUID id = t.getUniqueId();
                    final String name = pl.nick(t);
                    final boolean[] gone = {false};
                    for (int i = 0; i < times; i++) {
                        final boolean last = i == times - 1;
                        final int roundsLeft = times - i;
                        Bukkit.getScheduler().runTaskLater(pl, () -> {
                            if (gone[0]) return;
                            Player p = Bukkit.getPlayer(id);
                            if (p == null) {                    // 도중에 나갔다 → 남은 횟수는 다시 접속하면 이어서 한다
                                gone[0] = true;
                                hold(job.withRest("repeat " + roundsLeft + " goal " + lo + " " + hi));
                                return;
                            }
                            long d = roll(lo, hi);
                            sum[0] += d;
                            pl.ledger.addGoal(p, d);
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1f);
                            if (last) toastAll(pl, name + "님의 남은 목표 " + signed(sum[0]) + " (" + times + "회)", sum[0] <= 0);
                        }, i * step);
                    }
                    return times * step;
                }
                case "diamonds" -> {
                    long n = Long.parseLong(a[1]);
                    pl.ledger.addHeld(t, n);
                    toastAll(pl, pl.nick(t) + "님이 다이아 " + n + "개를 받았습니다.", true);
                    return 0;
                }
                case "bomb" -> {
                    int n = Integer.parseInt(a[1]);
                    pl.rewards.bomb(t, n);
                    toastAll(pl, pl.nick(t) + "님 앞에 다이아 폭탄이 떨어졌습니다! (다이아 " + n + "개)", true);
                    return 80;
                }
                case "half" -> {
                    long lost = pl.store.get(t).held / 2;
                    pl.ledger.takeHeld(t, lost);
                    toastAll(pl, pl.nick(t) + "님의 보유 다이아가 반토막 났습니다. (-" + lost + ")", false);
                    return 0;
                }
                case "death" -> {
                    return pl.pranks.reaper(t, () -> hold(job.withRest("death")));     // 낫이 닿기 전에 나가면 다시 접속했을 때 이어서
                }
                case "pool" -> {
                    return runPool(a[1], t);
                }
                case "poolx" -> {
                    int times = Math.max(1, Math.min(10, Integer.parseInt(a[2])));
                    final long each = 180;                      // 연출 하나가 끝날 만한 시간(9초)을 두고 다음 것
                    final UUID id = t.getUniqueId();
                    final String pool = a[1];
                    final boolean[] gone = {false};
                    for (int i = 0; i < times; i++) {
                        final int roundsLeft = times - i;
                        Bukkit.getScheduler().runTaskLater(pl, () -> {
                            if (gone[0]) return;
                            Player p = Bukkit.getPlayer(id);
                            if (p == null) {                    // 도중에 나갔다 → 남은 횟수는 다시 접속하면 이어서 한다
                                gone[0] = true;
                                hold(job.withRest("poolx " + pool + " " + roundsLeft));
                                return;
                            }
                            runPool(pool, p);
                        }, i * each);
                    }
                    return times * each;
                }
                case "wheel" -> {
                    return runWheel(t, def, job);
                }
                case "miner" -> {
                    pl.miner.summon(t, donor);
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

    // ── 룰렛 (1000개) ────────────────────────────────────────
    /** 동작이 끝날 때까지 걸릴 시간(틱)을 미리 어림한다 (룰렛 상품처럼 나중에 실행되는 동작도 줄이 기다려 주게) */
    private long estimate(String action) {
        String[] a = action.trim().split("\\s+");
        try {
            return switch (a[0]) {
                case "repeat" -> Math.max(1, Math.min(50, Integer.parseInt(a[1]))) * 8L;
                case "poolx" -> Math.max(1, Math.min(10, Integer.parseInt(a[2]))) * 180L;
                case "pool" -> PICK_TICKS + 30;
                case "death" -> 90L;
                case "miner" -> 20L;
                default -> 0L;
            };
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private long runWheel(Player t, Def def, Job job) {
        if (wheel.isEmpty()) { toastAll(pl, "룰렛 상품이 설정되어 있지 않습니다.", false); return 0; }
        int total = 0;
        for (Prize z : wheel) total += z.weight;
        int r = ThreadLocalRandom.current().nextInt(total);
        Prize won = wheel.get(wheel.size() - 1);
        for (Prize z : wheel) { if (r < z.weight) { won = z; break; } r -= z.weight; }
        List<String> names = new ArrayList<>(), icons = new ArrayList<>();
        for (Prize z : wheel) { names.add(z.name); icons.add(z.icon); }
        for (Player p : Bukkit.getOnlinePlayers()) pl.net.wheel(p, "룰렛 → " + pl.nick(t), names, icons, wheel.indexOf(won), PICK_TICKS * 50);
        final Prize prize = won;
        final UUID id = t.getUniqueId();
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null) { hold(job.withRest("wheel")); return; }      // 도는 동안 나갔다 → 다시 접속하면 룰렛을 다시 돌린다
            for (Player o : Bukkit.getOnlinePlayers()) pl.net.announce(o, prize.icon, prize.name, "룰렛 결과 · " + pl.nick(p) + "님");
            run(p, def, prize.action, job);
        }, PICK_TICKS + 20);
        return PICK_TICKS + 60 + estimate(prize.action);                 // 당첨 상품이 끝날 때까지 다음 후원이 기다린다
    }

    // ── 랜덤 풀 (100개 장난 · 300개 다이아 연출) ───────────────────────
    /** 풀에서 하나 뽑는다 (직전에 나온 것은 한 번 제외) */
    private Prank pickFrom(String pool) {
        List<Prank> all = pools.getOrDefault(pool, List.of());
        if (all.isEmpty()) return null;
        List<Prank> ok = new ArrayList<>(all);
        String last = lastPick.get(pool);
        if (ok.size() > 1 && last != null) ok.removeIf(x -> x.id.equals(last));
        Prank p = ok.get(ThreadLocalRandom.current().nextInt(ok.size()));
        lastPick.put(pool, p.id);
        return p;
    }

    /** 랜덤 풀 이벤트 실행: 하나 뽑고, 1명 대상이면 룰렛을 돌린 뒤 건다. 줄이 기다릴 시간(틱)을 돌려준다 */
    private long runPool(String pool, Player recipient) {
        Prank pr = pickFrom(pool);
        if (pr == null) { toastAll(pl, "이 이벤트는 아직 준비 중입니다.", true); return 0; }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) return 0;
        if (pr.scope.equals("self")) {
            announceAll(pr, pl.nick(recipient) + "님");
            applyPrank(pr, List.of(recipient));
            return 40;
        }
        if (pr.scope.equals("all")) {
            announceAll(pr, "전원");
            for (Player p : online) p.playSound(p.getLocation(), Sound.ENTITY_EVOKER_PREPARE_WOLOLO, 0.6f, 1f);
            applyPrank(pr, online);
            return 20;
        }
        Player winner = online.get(ThreadLocalRandom.current().nextInt(online.size()));
        if (online.size() == 1) {                       // 혼자면 룰렛 없이 바로
            announceAll(pr, pl.nick(winner) + "님");
            applyPrank(pr, List.of(winner));
            return 20;
        }
        List<String> names = new ArrayList<>(), ids = new ArrayList<>();
        for (Player p : online) { names.add(pl.nick(p)); ids.add(p.getUniqueId().toString()); }
        for (Player p : online) pl.net.pick(p, pr.name, pr.icon, names, ids, online.indexOf(winner), PICK_TICKS * 50);
        final UUID id = winner.getUniqueId();
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            Player w = Bukkit.getPlayer(id);
            if (w == null) {                                    // 뽑힌 사람이 그사이 나갔다 → 남은 사람 중에서 바로 다시 뽑는다
                List<Player> remain = new ArrayList<>(Bukkit.getOnlinePlayers());
                if (remain.isEmpty()) return;
                w = remain.get(ThreadLocalRandom.current().nextInt(remain.size()));
            }
            announceAll(pr, pl.nick(w) + "님");
            pl.models.followHead(w, "target_marker", 3, "pick", "leave");       // 뽑힌 사람 머리 위 표시
            applyPrank(pr, List.of(w));
        }, PICK_TICKS);
        return PICK_TICKS + 30;
    }

    private void announceAll(Prank pr, String who) {
        for (Player o : Bukkit.getOnlinePlayers()) pl.net.announce(o, pr.icon, pr.name, who);
    }

    /** 풀 한 칸의 동작을 대상들에게 건다 */
    private void applyPrank(Prank pr, List<Player> targets) {
        String[] a = pr.action.trim().split("\\s+");
        try {
            int n = a.length > 1 ? Integer.parseInt(a[1]) : 0;
            switch (a[0]) {
                case "ant" -> targets.forEach(p -> pl.effects.apply(p, Effects.ANT, pr.name, pr.icon, n));
                case "curse" -> targets.forEach(p -> pl.effects.apply(p, Effects.CURSE, pr.name, pr.icon, n));
                case "creeper" -> targets.forEach(p -> pl.effects.apply(p, Effects.CREEPER, pr.name, pr.icon, n));
                case "reverse" -> targets.forEach(p -> pl.effects.apply(p, Effects.REVERSE, pr.name, pr.icon, n));
                case "blackout" -> targets.forEach(p -> pl.effects.apply(p, Effects.BLACKOUT, pr.name, pr.icon, n));
                case "lava" -> targets.forEach(p -> pl.effects.apply(p, Effects.LAVA, pr.name, pr.icon, n));
                case "bunny" -> targets.forEach(p -> pl.effects.apply(p, Effects.BUNNY, pr.name, pr.icon, n));
                case "dance" -> targets.forEach(p -> pl.effects.apply(p, Effects.DANCE, pr.name, pr.icon, n));
                case "midas" -> targets.forEach(p -> pl.effects.apply(p, Effects.MIDAS, pr.name, pr.icon, n));
                case "giant" -> targets.forEach(p -> pl.effects.apply(p, Effects.GIANT, pr.name, pr.icon, n));
                case "seal" -> targets.forEach(p -> pl.effects.apply(p, Effects.SEAL, pr.name, pr.icon, n));
                case "hotbar" -> targets.forEach(Effects::shuffleHotbar);
                case "swap" -> {
                    if (!Effects.swapPlaces(targets)) toastAll(pl, "자리를 바꿀 사람이 2명 이상 있어야 합니다.", false);
                }
                case "crow" -> targets.forEach(pl.pranks::crow);
                case "rocket" -> targets.forEach(pl.pranks::rocket);
                case "anvil" -> targets.forEach(pl.pranks::anvil);
                case "ufo" -> targets.forEach(pl.pranks::ufo);
                case "rain" -> targets.forEach(p -> pl.rewards.rain(p, n));
                case "fountain" -> targets.forEach(p -> pl.rewards.fountain(p, n));
                case "pinata" -> targets.forEach(p -> pl.rewards.pinata(p, n));
                case "meteor" -> targets.forEach(p -> pl.rewards.meteor(p, n));
                default -> pl.getLogger().warning("모르는 풀 동작: " + pr.action);
            }
        } catch (RuntimeException e) {
            pl.getLogger().warning("풀 동작 실행 실패 '" + pr.action + "': " + e);
        }
    }

    // ── 대기열 화면 ──────────────────────────────────────────
    private List<String[]> queueLines() {
        List<String[]> lines = new ArrayList<>();
        if (current != null && defs.containsKey(current.count)) lines.add(line(current));
        for (Job j : queue) if (defs.containsKey(j.count)) lines.add(line(j));
        return lines;
    }

    private String[] line(Job j) {
        Def d = defs.get(j.count);
        return new String[]{d.icon, d.pool() ? d.name : d.name + " → " + j.targetName};
    }

    /** 지금 줄 서 있는 이벤트 목록을 모드에 보낸다 (진행 중인 것이 맨 위) */
    private void sendQueue() {
        List<String[]> lines = queueLines();
        for (Player p : Bukkit.getOnlinePlayers()) if (pl.net.hasMod(p)) pl.net.queue(p, lines);
    }

    /** 방금 접속한 사람에게 지금 줄을 보여 준다 */
    void sendQueueTo(Player p) { pl.net.queue(p, queueLines()); }

    int size() { return queue.size() + (current == null ? 0 : 1); }
}
