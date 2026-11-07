package kr.kkaegul.core.casino;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 개구리 경주 (경마). 개구리 4~6마리가 달리고, 사람들은 번호에 다이아를 건다.
 *  cycle-minutes(기본 3분)마다 자동: 배팅 → 경주 → 결과 → 대기 → 다음 회차. 아무도 안 걸어도 경주는 돈다.
 *
 * 경기장: 위치 race-start 하나만 저장하면 됨 (출발선 가운데, 달릴 방향을 바라보고). 레인은 옆으로 lane-spacing 칸씩 자동.
 *   race-finish 를 저장하면 거기까지가 결승선, 안 하면 track-length 칸. (예전 race-lane.1~N 도 그대로 동작)
 *   위치를 안 정하면 맵 개구리 없이 화면(창) 속 경주로만 진행.
 * 배당: 건 사람끼리 나눠 갖기(파리뮤추얼). 상금 = 이번 판 배팅 합계 - 수수료(fee-percent) + 이월금.
 *   맞힌 사람이 우승 번호에 건 비율대로 나눠 갖고, 아무도 못 맞히면 상금 전부 다음 회차로 이월. → 서버가 돈을 만들지 않음.
 * ★(컨디션)는 우승 확률만 정한다. 우승 개구리는 그 확률대로 미리 뽑고, 달리는 모습(역전 포함)은 결과에 맞춰 만든다.
 * 개구리: 바닐라 개구리 몹(AI 끔) + 번호 표지(TextDisplay). model 에 ModelEngine 모델 id 를 넣으면 그걸 씌운다(ModelEngine 있을 때).
 */
public final class FrogRaceService {
    private final KkaegulCore plugin;

    public enum State { IDLE, BETTING, RACING, RESULT }
    private State state = State.IDLE;
    private long stateEndsAt;
    private BukkitTask task;
    private int tick;

    private static final class Frog {
        int no; String name; int color; int form; double p, odds;
        Location start; LivingEntity body; TextDisplay label;
        int finishTick; double[] curve;   // curve[k] = 0..1 진행도 (k = 0..SEG)
        double pos;                       // 지금 진행도
    }
    private final List<Frog> frogs = new ArrayList<>();
    private final Map<UUID, Map<Integer, Long>> bets = new LinkedHashMap<>();
    /** 경주 창을 연 사람 (안 건 사람도 창 안에서는 경주를 볼 수 있게, 10분 지나면 뺌) */
    private final Map<UUID, Long> viewers = new java.util.HashMap<>();
    private int winner = -1;
    private static final int SEG = 8, HOP = 10;
    /** 그림 순서: 빨강 / 주황 / 노랑 / 초록 / 파랑 / 보라 (colorId 0~5) */
    private static final int[] COLORS = {0xFFE9584F, 0xFFF29B3A, 0xFFF2C230, 0xFF7CC24A, 0xFF3FB8E8, 0xFFA77BE0};
    private int round;
    private long nextOpenAt;
    private long lastPot;     // 이번 판 상금 (결과 표시용)
    private boolean warned30;

    public FrogRaceService(KkaegulCore plugin) {
        this.plugin = plugin;
        nextOpenAt = System.currentTimeMillis() + 20_000L;
        Bukkit.getScheduler().runTaskLater(plugin, this::sweep, 100L);   // 지난번에 남은 경주 개구리 정리
        // 회차 자동 진행: 1초마다 확인
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!enabled() || state != State.IDLE || Bukkit.getOnlinePlayers().isEmpty()) return;
            if (System.currentTimeMillis() >= nextOpenAt) openBetting(null);
        }, 40L, 20L);
    }

    private long cycleMs() { return Math.max(1, plugin.getConfig().getLong(cfg("cycle-minutes"), 3)) * 60_000L; }
    private double fee() { return Math.max(0, Math.min(50, plugin.getConfig().getDouble(cfg("fee-percent"), 10))) / 100.0; }
    private long carry() { return plugin.global().raceCarry; }

    /** 이번 판 배팅 합계 */
    private long poolTotal() { long t = 0; for (Map<Integer, Long> m : bets.values()) for (long v : m.values()) t += v; return t; }
    private long poolOn(int no) { long t = 0; for (Map<Integer, Long> m : bets.values()) t += m.getOrDefault(no, 0L); return t; }
    /** 수수료 떼고 이월금 더한 상금 */
    private long pot() { long t = poolTotal(); return t - (long) Math.floor(t * fee()) + carry() + plugin.getConfig().getLong(cfg("seed"), 0); }
    /** 지금 걸린 돈 기준 예상 배당 (그 번호에 아무도 안 걸었으면 0 = "-") */
    private double estOdds(int no) { long on = poolOn(no); return on <= 0 ? 0 : Math.floor(pot() / (double) on * 10) / 10.0; }

    private String cfg(String k) { return "casino.frog-race." + k; }
    public boolean enabled() { return plugin.getConfig().getBoolean(cfg("enabled"), true) && !plugin.global().racePaused; }
    public State state() { return state; }
    private long minBet() { return Math.max(1, plugin.getConfig().getLong(cfg("min-bet"), 1)); }
    private long maxBet() { return Math.max(minBet(), plugin.getConfig().getLong(cfg("max-bet"), 300)); }

    /** 맵에서 달릴 수 있으면 null. 아니면 이유 (그래도 경주는 화면 속에서 진행됨) */
    public String readiness() {
        return lanes(6).isEmpty() ? "맵 위치 없음 → 화면 속 경주로 진행 (맵에서 보려면 /깨굴 위치 저장 race-start)" : null;
    }

    /** 레인 출발점 n개. race-start 하나에서 옆으로 자동 배치, 없으면 예전 race-lane.N */
    private List<Location> lanes(int n) {
        Location st = plugin.places().get("race-start");
        if (st != null) {
            List<Location> out = new ArrayList<>();
            double sp = plugin.getConfig().getDouble(cfg("lane-spacing"), 2.0);
            Location base = st.clone(); base.setPitch(0);
            org.bukkit.util.Vector dir = base.getDirection().setY(0).normalize();
            org.bukkit.util.Vector side = new org.bukkit.util.Vector(-dir.getZ(), 0, dir.getX()); // 오른쪽
            for (int i = 0; i < n; i++) {
                double off = (i - (n - 1) / 2.0) * sp;
                Location l = base.clone().add(side.getX() * off, 0, side.getZ() * off);
                l.setYaw(base.getYaw());
                out.add(l);
            }
            return out;
        }
        List<Location> old = plugin.places().list("race-lane");
        return old.size() >= 2 ? old : List.of();
    }

    // ───────── 화면 / 데이터 ─────────
    public Json info(Player p) {
        List<Object> fl = new ArrayList<>();
        Map<Integer, Long> mine = p == null ? Map.of() : bets.getOrDefault(p.getUniqueId(), Map.of());
        Map<Integer, Long> pool = new HashMap<>();
        for (Map<Integer, Long> m : bets.values()) m.forEach((k, v) -> pool.merge(k, v, Long::sum));
        for (Frog f : frogs) {
            fl.add(Json.obj().put("no", f.no).put("name", f.name).put("color", String.format("#%06X", f.color & 0xFFFFFF)).put("colorId", (f.no - 1) % 6)
                    .put("form", f.form).put("odds", state == State.RESULT || state == State.RACING ? f.odds : estOdds(f.no)).put("myBet", mine.getOrDefault(f.no, 0L)).put("pool", pool.getOrDefault(f.no, 0L))
                    .put("progress", Math.round(f.pos * 1000) / 1000.0));
        }
        Json j = Json.obj().put("state", state.name().toLowerCase()).put("endsInMs", Math.max(0, stateEndsAt - System.currentTimeMillis()))
                .put("frogs", fl).put("round", round).put("minBet", minBet()).put("maxBet", maxBet()).put("winner", winner)
                .put("pot", state == State.RESULT ? lastPot : pot()).put("carry", carry()).put("feePercent", Math.round(fee() * 100))
                .put("nextInMs", state == State.IDLE ? Math.max(0, nextOpenAt - System.currentTimeMillis()) : 0).put("parimutuel", true);
        if (p != null) j.put("available", plugin.submit().available(p)).put("myTotal", mine.values().stream().mapToLong(Long::longValue).sum());
        return j;
    }

    /** NPC/명령어: 창 열기 (대기 중이면 배팅을 연다) */
    public void open(Player p) {
        if (!enabled()) { plugin.msg(p, "shop.disabled"); return; }
        if (plugin.ui().hasRenderer(p)) { plugin.ui().open(p, "race", info(p)); viewers.put(p.getUniqueId(), System.currentTimeMillis()); }
        else lineupChat(p);
    }

    private void lineupChat(Player p) {
        String st = switch (state) { case BETTING -> "배팅 중 (" + Math.max(0, (stateEndsAt - System.currentTimeMillis()) / 1000) + "초 남음)"; case RACING -> "경주 중"; case RESULT -> "결과 발표"; default -> "다음 경주까지 " + Math.max(0, (nextOpenAt - System.currentTimeMillis()) / 1000) + "초"; };
        p.sendMessage(Txt.color(Txt.notice("&a[개구리 경주] &f" + st + " &7| 상금 &b" + Txt.num(pot()) + " &7(이월 " + Txt.num(carry()) + ") | /경주 걸기 <번호> <다이아>")));
        for (Frog f : frogs) { double o = estOdds(f.no); p.sendMessage(Txt.color(" &f" + f.no + "번 " + f.name + " &7" + "★".repeat(f.form) + "☆".repeat(3 - f.form) + " &e예상 배당 " + (o <= 0 ? "-" : "x" + FrogJumpService.fmt(o)))); }
    }

    // ───────── 배팅 ─────────
    private void openBetting(Player by) {
        int n = Math.max(2, Math.min(6, plugin.getConfig().getInt(cfg("frogs"), 6)));
        List<Location> lanes = lanes(n);
        if (!lanes.isEmpty()) n = Math.min(n, lanes.size());
        clearFrogs();
        bets.clear();
        winner = -1;
        List<String> names = plugin.getConfig().getStringList(cfg("names"));
        if (names.isEmpty()) names = List.of("깨굴", "개굴", "뚱굴", "초록이", "폴짝", "연잎");
        List<String> pool = new ArrayList<>(names);
        java.util.Collections.shuffle(pool);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            Frog f = new Frog();
            f.no = i + 1;
            f.name = pool.get(i % pool.size());
            f.color = COLORS[i % COLORS.length];
            f.form = Rng.range(1, 3);
            f.start = lanes.isEmpty() ? null : lanes.get(i).clone();
            frogs.add(f);
            sum += weight(f.form);
        }
        for (Frog f : frogs) {
            f.p = weight(f.form) / sum;
            f.odds = 0;
            if (f.start != null) spawn(f);
        }
        state = State.BETTING;
        warned30 = false;
        int sec = plugin.getConfig().getInt(cfg("bet-seconds"), 120);
        long now = System.currentTimeMillis();
        stateEndsAt = now + sec * 1000L;
        nextOpenAt = now + cycleMs();
        tick = 0;
        startTask();
        pushAll();
    }

    private static double weight(int form) { return form == 3 ? 3.2 : form == 2 ? 2 : 1.1; }

    public void bet(Player p, int no, long amount) {
        if (state != State.BETTING) { plugin.ui().error(p, "&c[개구리 경주] 지금은 걸 수 없어요."); sync(p); return; }
        if (no < 1 || no > frogs.size()) { plugin.ui().error(p, "&c[개구리 경주] 1~" + frogs.size() + "번 중에 골라 주세요."); sync(p); return; }
        Map<Integer, Long> mine = bets.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
        long total = mine.values().stream().mapToLong(Long::longValue).sum();
        if (amount < minBet() || total + amount > maxBet()) { plugin.ui().error(p, "&c[개구리 경주] 한 경기에 " + minBet() + "~" + maxBet() + "개까지 걸 수 있어요. &7(지금 " + total + "개)"); sync(p); return; }
        long have = plugin.submit().available(p);
        if (have < amount) { plugin.msg(p, "shop.no-diamond", "need", Txt.num(amount), "have", Txt.num(have)); sync(p); return; }
        plugin.submit().takeDiamonds(p, amount);
        mine.merge(no, amount, Long::sum);
        plugin.hud().refresh(p);
        Frog f = frogs.get(no - 1);
        p.sendMessage(Txt.color(Txt.notice("&a[개구리 경주] &f" + no + "번 " + f.name + "에게 다이아 &b" + Txt.num(amount) + "개 &f걸었어요. &7(지금 예상 배당 x" + FrogJumpService.fmt(estOdds(no)) + ", 마감 때 확정)")));
        plugin.ui().fx(p, "race_bet", Json.obj().put("no", no).put("amount", amount));
        pushAll();
    }

    // ───────── 경주 ─────────
    private void startRace() {
        state = State.RACING;
        tick = 0;
        round++;
        // 우승 개구리 뽑기 → 나머지 순위도 확률대로
        List<Frog> left = new ArrayList<>(frogs), order = new ArrayList<>();
        while (!left.isEmpty()) {
            Map<Frog, Double> w = new LinkedHashMap<>();
            for (Frog f : left) w.put(f, f.p);
            Frog pick = Rng.weighted(w);
            order.add(pick);
            left.remove(pick);
        }
        winner = order.get(0).no;
        int base = (int) (plugin.getConfig().getInt(cfg("race-seconds"), 18) * 20 * (0.92 + Rng.r().nextDouble() * 0.16));
        int t = base;
        for (int i = 0; i < order.size(); i++) {
            Frog f = order.get(i);
            if (i > 0) t += Rng.range(4, 24);
            f.finishTick = t;
            f.curve = curve(i == 0);
        }
        stateEndsAt = System.currentTimeMillis() + t * 50L;
        long pt = pot();
        for (Frog f : frogs) f.odds = estOdds(f.no);   // 마감 시점 배당 확정
        plugin.announce("&a[개구리 경주] &f" + round + "회차 출발! &7상금 &b" + Txt.num(pt) + (carry() > 0 ? " &7(이월 " + Txt.num(carry()) + " 포함)" : ""), false);
        for (Player p : watchers()) p.playSound(p.getLocation(), "minecraft:entity.firework_rocket.blast", 1f, 1.2f);
        pushAll();
    }

    /** 0..1 진행도 곡선. 우승 개구리는 반쯤 '뒷심' 곡선 (뒤에서 역전) */
    private static double[] curve(boolean win) {
        double[] w = new double[SEG];
        boolean comeback = win && Rng.chance(55);
        double s = 0;
        for (int j = 0; j < SEG; j++) {
            w[j] = 0.55 + Rng.r().nextDouble();
            if (comeback) w[j] *= 0.55 + 0.9 * j / (SEG - 1.0);
            s += w[j];
        }
        double[] c = new double[SEG + 1];
        for (int j = 0; j < SEG; j++) c[j + 1] = c[j] + w[j] / s;
        c[SEG] = 1;
        return c;
    }

    private static double at(Frog f, int t) {
        if (t >= f.finishTick) return 1;
        double x = Math.max(0, t) / (double) f.finishTick * SEG;
        int k = (int) Math.floor(x);
        return f.curve[k] + (f.curve[k + 1] - f.curve[k]) * (x - k);
    }

    private void finish() {
        state = State.RESULT;
        Frog w = frogs.get(winner - 1);
        int sec = plugin.getConfig().getInt(cfg("result-seconds"), 10);
        stateEndsAt = System.currentTimeMillis() + sec * 1000L;
        long total = poolTotal(), feeAmt = (long) Math.floor(total * fee());
        long pot = total - feeAmt + carry() + plugin.getConfig().getLong(cfg("seed"), 0);
        lastPot = pot;
        long onWinner = poolOn(winner);
        long totalPaid = 0;
        List<String> big = new ArrayList<>();
        double odds = onWinner > 0 ? pot / (double) onWinner : 0;
        w.odds = Math.floor(odds * 10) / 10.0;
        for (var e : bets.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            long on = e.getValue().getOrDefault(winner, 0L), spent = e.getValue().values().stream().mapToLong(Long::longValue).sum();
            long got = onWinner > 0 ? (long) Math.floor(pot * (on / (double) onWinner)) : 0;
            if (got > 0) {
                totalPaid += got;
                if (p != null) plugin.wallet().deposit(plugin.data().get(p), got, p);
                else { var d = plugin.data().get(e.getKey()); if (d != null) { d.held += got; d.markDirty(); } }
                if (got >= plugin.getConfig().getLong(cfg("announce-win"), 100)) big.add((p != null ? plugin.displayName(p) : "?") + " +" + Txt.num(got));
            }
            if (p != null) plugin.ui().fx(p, "race_result", Json.obj().put("winner", winner).put("colorId", (winner - 1) % 6).put("name", w.name).put("odds", w.odds).put("bet", on).put("spent", spent).put("got", got).put("pot", pot));
        }
        // 아무도 못 맞히면 전부 이월, 맞히면 나누고 남은 자투리만 이월
        plugin.global().raceCarry = onWinner > 0 ? Math.max(0, pot - totalPaid) : pot;
        plugin.global().dirty = true;
        String tail = onWinner > 0 ? " &7상금 &b" + Txt.num(pot) + " &7→ 맞힌 사람끼리 나눔 (x" + FrogJumpService.fmt(w.odds) + ")" + (big.isEmpty() ? "" : " &b" + String.join(", ", big))
                : total > 0 || pot > 0 ? " &7맞힌 사람이 없어 상금 &b" + Txt.num(pot) + " &7다음 회차로 이월!" : "";
        plugin.announce("&a[개구리 경주] &e" + winner + "번 " + w.name + " &f우승!" + tail, true);
        // 우승 화면·효과음은 이번 판에 건 사람에게만 (구경꾼은 채팅 한 줄만)
        for (Player p : Bukkit.getOnlinePlayers()) if (bets.containsKey(p.getUniqueId())) p.playSound(p.getLocation(), "minecraft:ui.toast.challenge_complete", 1f, 1f);
        plugin.getLogger().info("개구리 경주 " + round + "회 " + winner + "번 우승, 배팅 " + total + " 수수료 " + feeAmt + " 지급 " + totalPaid + " 이월 " + plugin.global().raceCarry);
        // 결과가 나오면 오른쪽 위 작은 레인은 바로 끔
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "race_hud", Json.obj().put("state", "idle"));
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "race", info(p));
    }

    private void toIdle() {
        state = State.IDLE;
        clearFrogs();
        bets.clear();
        winner = -1;
        if (task != null) { task.cancel(); task = null; }
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "race", info(p));
    }

    private void startTask() {
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        tick++;
        long now = System.currentTimeMillis();
        switch (state) {
            case BETTING -> {
                idleHop();
                if (!warned30 && stateEndsAt - now <= 30_000) {
                    warned30 = true;
                    plugin.announce("&a[개구리 경주] &f배팅 마감 &e30초 &f전! &7상금 &b" + Txt.num(pot()) + " &7(경주 NPC / /경주)", false);
                }
                if (now >= stateEndsAt) startRace();
            }
            case RACING -> {
                boolean all = true;
                for (Frog f : frogs) {
                    // 폴짝폴짝: HOP 틱마다 한 번 뛰기
                    int h = tick / HOP, ph = tick % HOP;
                    double p0 = at(f, h * HOP), p1 = at(f, (h + 1) * HOP);
                    double fr = ph / (double) HOP, e = fr * fr * (3 - 2 * fr);
                    f.pos = p0 + (p1 - p0) * e;
                    double y = p1 - p0 > 1e-4 ? Math.sin(Math.PI * fr) * 0.45 : 0;
                    place(f, f.pos, y);
                    if (f.pos < 1) all = false;
                }
                if (tick % 4 == 0) pushProgress();
                if (all || tick > 20 * 60) finish();
            }
            case RESULT -> {
                if (tick % 6 == 0 && winner > 0) { Frog w = frogs.get(winner - 1); place(w, 1, tick % 12 == 0 ? 0.5 : 0); }
                if (now >= stateEndsAt) toIdle();
            }
            default -> { }
        }
    }

    private void idleHop() {
        for (Frog f : frogs) {
            int ph = (tick + f.no * 7) % 40;
            place(f, 0, ph < 8 ? Math.sin(Math.PI * ph / 8.0) * 0.3 : 0);
        }
    }

    private double length() {
        Location st = plugin.places().get("race-start"), fin = plugin.places().get("race-finish");
        if (st != null && fin != null && st.getWorld() == fin.getWorld()) {
            Location b = st.clone(); b.setPitch(0);
            org.bukkit.util.Vector dir = b.getDirection().setY(0).normalize();
            double d = (fin.getX() - st.getX()) * dir.getX() + (fin.getZ() - st.getZ()) * dir.getZ();
            if (d > 3) return d;
        }
        return plugin.getConfig().getDouble(cfg("track-length"), 19);
    }

    private void place(Frog f, double prog, double y) {
        if (f.start == null) return;
        Location s = f.start.clone();
        s.setPitch(0);
        Location l = s.clone().add(s.getDirection().multiply(prog * length()).getX(), y, s.getDirection().multiply(prog * length()).getZ());
        l.setYaw(f.start.getYaw());
        l.setPitch(0);
        if (f.body != null && f.body.isValid()) f.body.teleport(l);
        if (f.label != null && f.label.isValid()) f.label.teleport(l.clone().add(0, 1.0, 0));
    }

    // ───────── 개구리 몹 ─────────
    private void spawn(Frog f) {
        Location l = f.start.clone();
        l.setPitch(0);
        Entity e = l.getWorld().spawnEntity(l, EntityType.FROG);
        if (e instanceof LivingEntity le) {
            le.setAI(false);
            le.setSilent(true);
            le.setInvulnerable(true);
            le.setPersistent(false);
            le.setGravity(false);
            le.addScoreboardTag("kkaegul_race");
            f.body = le;
            String model = plugin.getConfig().getString(cfg("model"), "");
            if (!model.isBlank()) ModelEngineHook.apply(le, model);
        }
        Entity t = l.getWorld().spawnEntity(l.clone().add(0, 1.0, 0), EntityType.TEXT_DISPLAY);
        if (t instanceof TextDisplay td) {
            td.setText(Txt.color("&f" + f.no + " &r&f" + f.name));
            td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            td.setBackgroundColor(org.bukkit.Color.fromARGB(0xCC000000 | (f.color & 0xFFFFFF)));
            td.setTeleportDuration(2);
            td.setPersistent(false);
            td.addScoreboardTag("kkaegul_race");
            f.label = td;
        }
    }

    private void clearFrogs() {
        for (Frog f : frogs) {
            if (f.body != null && f.body.isValid()) f.body.remove();
            if (f.label != null && f.label.isValid()) f.label.remove();
        }
        frogs.clear();
        sweep();
    }

    /** 남아 있는 경주 개구리·이름표 (재시작·리로드로 놓친 것) 모두 지우기 */
    private void sweep() {
        java.util.Set<Entity> keep = new java.util.HashSet<>();
        for (Frog f : frogs) { if (f.body != null) keep.add(f.body); if (f.label != null) keep.add(f.label); }
        for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds())
            for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains("kkaegul_race") && !keep.contains(e)) e.remove();
    }

    // ───────── 화면 갱신 ─────────
    private List<Player> watchers() {
        List<Player> out = new ArrayList<>();
        Location c = frogs.isEmpty() ? null : frogs.get(0).start;  // 맵 경주가 없으면 건 사람만
        double r = plugin.getConfig().getDouble(cfg("watch-radius"), 48);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean near = c != null && p.getWorld() == c.getWorld() && p.getLocation().distanceSquared(c) < r * r;
            if (near || bets.containsKey(p.getUniqueId())) out.add(p);
        }
        return out;
    }

    private void pushAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.ui().hasRenderer(p)) continue;
            plugin.ui().update(p, "race", info(p));
            if ((state == State.RACING || state == State.RESULT) && bets.containsKey(p.getUniqueId())) plugin.ui().update(p, "race_hud", hud(p));
        }
    }

    /** 오른쪽 위 작은 경주 화면은 돈 건 사람에게만 */
    private void pushProgress() {
        long now = System.currentTimeMillis();
        viewers.values().removeIf(t -> now - t > 600_000);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.ui().hasRenderer(p)) continue;
            if (bets.containsKey(p.getUniqueId())) plugin.ui().update(p, "race_hud", hud(p));
            else if (viewers.containsKey(p.getUniqueId())) plugin.ui().update(p, "race", hud(p));   // 창 안 경주만 (오른쪽 위 레인은 안 띄움)
        }
    }

    /** 경주 중 화면 위 진행 막대 */
    private Json hud(Player p) {
        List<Object> fl = new ArrayList<>();
        Map<Integer, Long> mine = bets.getOrDefault(p.getUniqueId(), Map.of());
        for (Frog f : frogs) fl.add(Json.obj().put("no", f.no).put("name", f.name).put("color", String.format("#%06X", f.color & 0xFFFFFF)).put("colorId", (f.no - 1) % 6)
                .put("progress", Math.round(f.pos * 1000) / 1000.0).put("myBet", mine.getOrDefault(f.no, 0L)));
        return Json.obj().put("state", state.name().toLowerCase()).put("round", round).put("endsInMs", Math.max(0, stateEndsAt - System.currentTimeMillis())).put("frogs", fl).put("winner", state == State.RESULT ? winner : -1);
    }

    private void sync(Player p) { if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "race", info(p)); }

    /** 관리자: 바로 열기 / 바로 출발 / 취소(환불) */
    public String admin(String what) {
        switch (what) {
            case "open" -> { if (state != State.IDLE) return "이미 진행 중"; openBetting(null); String r = readiness(); return "배팅 시작" + (r == null ? "" : " (" + r + ")"); }
            case "go" -> { if (state != State.BETTING) return "배팅 중이 아님"; startRace(); return "출발"; }
            case "cancel" -> { refundAll(); toIdle(); return "취소하고 환불"; }
            case "carry" -> { return "이월금 " + Txt.num(carry()); }
            case "pause" -> {   // 자동 진행 멈춤 (진행 중이면 환불하고 멈춤)
                refundAll(); toIdle();
                plugin.global().racePaused = true; plugin.global().dirty = true;
                return "자동 진행 멈춤 (다시 하려면 /깨굴 경주 재개)";
            }
            case "resume" -> {
                plugin.global().racePaused = false; plugin.global().dirty = true;
                nextOpenAt = System.currentTimeMillis() + 10_000L;
                return "자동 진행 재개 (10초 뒤 배팅 시작, 이후 " + plugin.getConfig().getLong(cfg("cycle-minutes"), 10) + "분마다)";
            }
            default -> { return "열기 | 출발 | 취소 | 이월 | 멈춤 | 재개" + (plugin.global().racePaused ? " (지금 멈춤)" : ""); }
        }
    }

    private void refundAll() {
        if (state != State.BETTING && state != State.RACING) return;
        for (var e : bets.entrySet()) {
            long sum = e.getValue().values().stream().mapToLong(Long::longValue).sum();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) plugin.wallet().deposit(plugin.data().get(p), sum, p);
            else { var d = plugin.data().get(e.getKey()); if (d != null) { d.held += sum; d.markDirty(); } }
        }
    }

    public void shutdown() { refundAll(); toIdle(); }
}
