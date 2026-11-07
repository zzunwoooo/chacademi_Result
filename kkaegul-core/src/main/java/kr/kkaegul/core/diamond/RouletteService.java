package kr.kkaegul.core.diamond;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 다이아 증감 룰렛.
 * config roulette.<kind>: { type: increase|decrease, min, max, spin-ms, label, allow-extra }
 * 추가 회전(1+1, 1+2): 운빨 게이지(증가 룰렛만) / 다이아 피버 / 3배 피버.
 * 같은 플레이어의 룰렛은 순서대로(큐) 돌아가고, 연출 시간이 끝난 뒤 장부에 반영된다.
 * 서버 종료 시 대기 중·연출 중인 룰렛은 연출 없이 전부 반영한다 (후원 손실 방지).
 */
public final class RouletteService {
    private final KkaegulCore plugin;
    private final Map<UUID, Deque<Job>> queues = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> running = new ConcurrentHashMap<>();
    /** 연출 중이라 아직 장부에 반영 안 된 결과 */
    private final Map<UUID, Inflight> inflight = new ConcurrentHashMap<>();

    public RouletteService(KkaegulCore plugin) { this.plugin = plugin; }

    public record Result(String kind, boolean increase, List<Long> rolls, long total, int extra, long spinMs) {}

    private record Job(String kind, String reason) {}

    private static final class Inflight {
        final Result r; final String reason; boolean applied = false;
        Inflight(Result r, String reason) { this.r = r; this.reason = reason; }
    }

    public boolean exists(String kind) { return plugin.getConfig().isConfigurationSection("roulette." + kind); }

    public List<String> kinds() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("roulette");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    private ConfigurationSection cfg(String kind) { return plugin.getConfig().getConfigurationSection("roulette." + kind); }

    /** 룰렛 큐에 넣는다. 오프라인이면 장부에만 즉시 반영. */
    public void spin(UUID id, String kind, String reason) {
        if (cfg(kind) == null) {
            plugin.getLogger().warning("알 수 없는 룰렛: " + kind);
            return;
        }
        if (Bukkit.getPlayer(id) == null) {
            applySilently(id, new Job(kind, reason));
            return;
        }
        queues.computeIfAbsent(id, k -> new ArrayDeque<>()).add(new Job(kind, reason));
        if (!running.getOrDefault(id, false)) next(id);
    }

    private Result roll(PlayerData d, String kind) {
        ConfigurationSection c = cfg(kind);
        if (c == null) return new Result(kind, true, List.of(0L), 0, 0, 0);
        boolean inc = "increase".equalsIgnoreCase(c.getString("type", "increase"));
        long min = c.getLong("min", 1), max = c.getLong("max", 10);
        int extra = extraSpins(d, inc, c);
        List<Long> rolls = new ArrayList<>();
        long total = 0;
        for (int i = 0; i <= extra; i++) {
            long v = Rng.range(min, max);
            rolls.add(v);
            total += v;
        }
        long spin = c.getLong("spin-ms", plugin.getConfig().getLong("roulette-defaults.spin-ms", 3000));
        spin = (long) (spin * plugin.fever().spinSpeedFactor());
        return new Result(kind, inc, rolls, total, extra, spin);
    }

    private int extraSpins(PlayerData d, boolean increase, ConfigurationSection c) {
        if (!c.getBoolean("allow-extra", true)) return 0;
        int gaugeExtra = increase ? plugin.gauge().rouletteExtra(d) : 0;
        int feverExtra = plugin.fever().diamondExtra();
        boolean stack = plugin.getConfig().getBoolean("roulette-defaults.stack-extras", false);
        return stack ? gaugeExtra + feverExtra : Math.max(gaugeExtra, feverExtra);
    }

    private void applySilently(UUID id, Job job) {
        PlayerData d = plugin.data().get(id);
        apply(d, roll(d, job.kind()), job.reason());
    }

    private long apply(PlayerData d, Result r, String reason) {
        String why = reason == null ? r.kind() : reason;
        return r.increase() ? plugin.diamonds().increase(d, r.total(), why) : plugin.diamonds().decrease(d, r.total(), why);
    }

    private void next(UUID id) {
        Deque<Job> q = queues.get(id);
        Job job = q == null ? null : q.poll();
        if (job == null) { running.put(id, false); return; }
        running.put(id, true);
        boolean scheduled = false;
        try {
            scheduled = run(id, job);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("룰렛 오류 (" + job.kind() + "): " + e);
        } finally {
            if (!scheduled) finish(id);
        }
    }

    /** @return 연출 후 finish 가 예약됐으면 true */
    private boolean run(UUID id, Job job) {
        Player p = Bukkit.getPlayer(id);
        PlayerData d = plugin.data().get(id);
        if (p == null) { applySilently(id, job); return false; }
        ConfigurationSection c = cfg(job.kind());
        Result r = roll(d, job.kind());
        String label = c == null ? job.kind() : c.getString("label", job.kind());
        Json payload = Json.obj().put("type", "roulette").put("kind", job.kind()).put("label", label)
                .put("increase", r.increase()).put("min", c == null ? 0 : c.getLong("min", 1)).put("max", c == null ? 0 : c.getLong("max", 10))
                .put("rolls", r.rolls()).put("total", r.total()).put("extra", r.extra())
                .put("spinMs", r.spinMs()).put("fever", plugin.fever().diamondFeverActive());
        boolean rendered = plugin.ui().send(p, payload);
        if (!rendered) {
            String extraTxt = r.extra() > 0 ? " &d(1+" + r.extra() + ")" : "";
            p.sendMessage(Txt.color(Txt.notice("&e[룰렛] &f" + label + extraTxt + " &7돌아가는 중...")));
        }
        Inflight inf = new Inflight(r, job.reason());
        inflight.put(id, inf);
        long ticks = Math.max(1, r.spinMs() / 50);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                if (inf.applied) return;
                inf.applied = true;
                inflight.remove(id, inf);
                long applied = apply(d, r, job.reason());
                Player now = Bukkit.getPlayer(id);
                if (now != null) {
                    String sign = r.increase() ? "&c+" : "&a-";
                    String rollsTxt = r.rolls().size() > 1 ? " &7" + r.rolls() : "";
                    if (!rendered) now.sendMessage(Txt.color("&e[룰렛] &f" + label + " 결과: " + sign + Txt.num(r.total()) + rollsTxt
                            + (applied != r.total() ? " &7(적용 " + Txt.num(applied) + ")" : "")));
                    plugin.ui().center(now, sign + Txt.num(r.total()), "&f" + label);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("룰렛 반영 오류: " + e);
            } finally {
                finish(id);
            }
        }, ticks);
        return true;
    }

    private void finish(UUID id) {
        long gap = plugin.getConfig().getLong("roulette-defaults.gap-ticks", 10);
        if (!plugin.isEnabled()) { running.put(id, false); return; }
        Bukkit.getScheduler().runTaskLater(plugin, () -> next(id), Math.max(1, gap));
    }

    public int pending(UUID id) {
        Deque<Job> q = queues.get(id);
        return q == null ? 0 : q.size();
    }

    /** 서버 종료: 연출 중 + 대기 중 룰렛을 전부 바로 반영 */
    public void flushAll() {
        for (Map.Entry<UUID, Inflight> e : inflight.entrySet()) {
            Inflight inf = e.getValue();
            if (inf.applied) continue;
            inf.applied = true;
            apply(plugin.data().get(e.getKey()), inf.r, inf.reason);
        }
        inflight.clear();
        for (Map.Entry<UUID, Deque<Job>> e : queues.entrySet()) {
            Job j;
            while ((j = e.getValue().poll()) != null) applySilently(e.getKey(), j);
        }
        queues.clear();
        running.clear();
    }
}
