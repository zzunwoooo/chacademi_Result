package kr.kkaegul.core.minigame;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.casino.CasinoSupport;
import kr.kkaegul.core.casino.ModelEngineHook;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 러시안 룰렛: 테이블(의자 6개)마다 따로 진행.
 *  - 2시간마다 접속 인원 절반을 6의 배수로 내려서 강제 초대 (MinigameManager). 판돈은 들어올 때 자동으로 뗌.
 *  - 6칸 실린더에 1발. 라운드마다 다시 장전하고 돌림. 차례인 사람이 좌클릭(또는 시간 끝)으로 방아쇠.
 *  - 맞으면 탈락 → 남은 사람끼리 다음 라운드. 마지막 생존 1등, 마지막 탈락 2등, 그 전 3등만 배당.
 *  - 위치: rroulette.<테이블>.<1~6> (의자, 테이블 쪽을 보고 저장), rroulette-gun.<테이블> (총 자리, 없으면 의자들 가운데)
 *  - 총 그림: ModelEngine 모델 gun-model (revolver.bbmodel). 없으면 gun-fallback-item 아이템.
 */
public final class RussianRouletteGame extends Minigame {
    private static final String TAG = "kkaegul_rroulette";

    private final class Table {
        final int no;
        final List<Location> chairs;
        final List<UUID> order = new ArrayList<>();      // 앉은 순서 (의자 순)
        final Set<UUID> alive = new HashSet<>();
        final List<UUID> outOrder = new ArrayList<>();   // 탈락 순서
        final List<Entity> seats = new ArrayList<>();
        Entity gun;
        long pot;
        int bullet, cursor, round, starter, turn = -1, token;
        boolean waiting, done;
        Table(int no, List<Location> chairs) { this.no = no; this.chairs = chairs; }
    }

    private final List<Table> tables = new ArrayList<>();
    private final Set<UUID> seated = new HashSet<>();

    public RussianRouletteGame(KkaegulCore plugin) { super(plugin, "rroulette"); }

    @Override public String label() { return "러시안 룰렛"; }

    private int perTable() { return Math.max(2, cfgInt("seats-per-table", 6)); }
    private long stake() { return Math.max(0, cfgInt("stake", 10)); }

    /** 등록된 테이블들 (rroulette.N.1~6). 번호 사이가 비어도 됨 */
    private List<List<Location>> tableLocs() {
        List<List<Location>> out = new ArrayList<>();
        for (int t = 1; t <= 50; t++) {
            List<Location> l = plugin.places().list("rroulette." + t);
            if (l.size() >= 2) out.add(l.subList(0, Math.min(perTable(), l.size())));
        }
        return out;
    }
    private List<Integer> tableNos() {
        List<Integer> out = new ArrayList<>();
        for (int t = 1; t <= 50; t++) if (plugin.places().list("rroulette." + t).size() >= 2) out.add(t);
        return out;
    }

    @Override public int capacity() { int n = 0; for (List<Location> l : tableLocs()) n += l.size(); return n <= 0 ? Integer.MAX_VALUE : n; }

    @Override public String readiness() { return tableLocs().isEmpty() ? "위치 rroulette.1.1 ~ rroulette.1.6 필요" : null; }

    @Override public boolean eligible(Player p) { return CasinoSupport.available(plugin, p) >= stake(); }

    /** 보낼 인원: 접속 인원 절반을 6의 배수로 내림. 6명 안 되면 6명(있는 만큼) */
    public int wanted(int online) {
        int per = perTable();
        int n = (online / 2) / per * per;
        return Math.max(per, n);
    }

    public boolean seatedLock(Player p) { return seated.contains(p.getUniqueId()); }

    // ───────── 시작 ─────────
    @Override
    protected void start(List<Player> list) {
        tables.clear();
        seated.clear();
        List<List<Location>> locs = tableLocs();
        List<Integer> nos = tableNos();
        // 테이블 수 = 6명씩 나눈 수 (테이블이 모자라면 있는 만큼), 인원은 고르게
        int per = perTable();
        int k = Math.max(1, Math.min(locs.size(), (list.size() + per - 1) / per));
        for (int i = 0; i < k; i++) tables.add(new Table(nos.get(i), locs.get(i)));
        long stake = stake();
        int i = 0;
        for (Player p : list) {
            Table t = tables.get(i % k);
            if (t.order.size() >= t.chairs.size()) { drop(p, "&7자리가 없어 이번 판에서 빠졌어요."); continue; }
            long got = CasinoSupport.take(plugin, p, stake);
            if (got < stake) {
                CasinoSupport.give(plugin, p.getUniqueId(), got);
                drop(p, "&c판돈 " + stake + "다이아가 없어 이번 판에서 빠졌어요.");
                continue;
            }
            i++;
            t.pot += got;
            Location chair = t.chairs.get(t.order.size()).clone();
            t.order.add(p.getUniqueId());
            t.alive.add(p.getUniqueId());
            p.teleport(chair);
            Entity seat = chair.getWorld().spawnEntity(chair.clone().add(0, cfgDouble("seat-height", 0.5), 0), EntityType.ARMOR_STAND);
            if (seat instanceof ArmorStand as) { as.setInvisible(true); as.setGravity(false); as.setMarker(true); as.setInvulnerable(true); as.setPersistent(false); as.addScoreboardTag(TAG); }
            seat.addPassenger(p);
            t.seats.add(seat);
            seated.add(p.getUniqueId());
            state.put(p.getUniqueId(), "wait");
            p.sendMessage(c(Txt.notice("&f{e:system} &f판돈 &b" + stake + "다이아&f를 걸었어요. 끝까지 살아남으면 상금!")));
        }
        tables.removeIf(t -> {
            if (t.order.size() >= 2) return false;
            for (UUID u : t.order) {
                CasinoSupport.give(plugin, u, stake);
                seated.remove(u);
                Player p = Bukkit.getPlayer(u);
                if (p != null) drop(p, "&7같은 테이블 인원이 부족해 판돈을 돌려드렸어요.");
            }
            for (Entity e : t.seats) if (e.isValid()) e.remove();
            return true;
        });
        if (tables.isEmpty()) { end(); return; }
        for (Table t : tables) t.gun = spawnGun(t);
        for (Table t : tables) for (UUID u : t.order) { Player p = Bukkit.getPlayer(u); if (p != null) hud(t, p); }
        waitPhase(cfgInt("wait-seconds", 10), "러시안 룰렛 시작", () -> { for (Table t : tables) startRound(t); });
    }

    /** 이번 판에서 빼고 원래 자리로 */
    private void drop(Player p, String why) {
        seated.remove(p.getUniqueId());
        players.remove(p.getUniqueId());
        Location l = back.remove(p.getUniqueId());
        if (l != null) p.teleport(l);
        p.sendMessage(c(Txt.notice("&f{e:system} " + why)));
    }

    // ───────── 총 ─────────
    private Location gunLoc(Table t) {
        Location g = plugin.places().get("rroulette-gun." + t.no);
        if (g != null) return g;
        double x = 0, y = 0, z = 0;
        for (Location l : t.chairs) { x += l.getX(); y += l.getY(); z += l.getZ(); }
        int n = t.chairs.size();
        return new Location(t.chairs.get(0).getWorld(), x / n, y / n + cfgDouble("gun-height", 1.0), z / n);
    }

    private Entity spawnGun(Table t) {
        Location at = gunLoc(t);
        at.setPitch(0);
        String model = plugin.getConfig().getString("minigame.games.rroulette.gun-model", "revolver");
        if (ModelEngineHook.present() && model != null && !model.isBlank()) {
            Entity st = at.getWorld().spawnEntity(at, EntityType.ARMOR_STAND);
            if (st instanceof ArmorStand as) { as.setInvisible(true); as.setGravity(false); as.setMarker(true); as.setInvulnerable(true); as.setPersistent(false); as.addScoreboardTag(TAG); }
            if (ModelEngineHook.apply(st, model)) return st;
            st.remove();
        }
        ItemDisplay d = (ItemDisplay) at.getWorld().spawnEntity(at, EntityType.ITEM_DISPLAY);
        Material m = Material.matchMaterial(plugin.getConfig().getString("minigame.games.rroulette.gun-fallback-item", "CROSSBOW"));
        d.setItemStack(new ItemStack(m == null ? Material.CROSSBOW : m));
        d.setPersistent(false);
        d.addScoreboardTag(TAG);
        return d;
    }

    /** 총구를 그 사람 쪽으로 */
    private void aim(Table t, UUID u) {
        if (t.gun == null || !t.gun.isValid()) return;
        Player p = Bukkit.getPlayer(u);
        if (p == null) return;
        Location g = t.gun.getLocation();
        Vector dir = p.getLocation().toVector().subtract(g.toVector()).setY(0);
        if (dir.lengthSquared() < 1e-4) return;
        Location look = g.clone().setDirection(dir);
        float yaw = look.getYaw() + (float) cfgDouble("gun-yaw-offset", 0);
        t.gun.setRotation(yaw, 0);
        anim(t, "pass");
    }

    private void anim(Table t, String name) {
        if (t.gun == null || !t.gun.isValid()) return;
        ModelEngineHook.play(t.gun, plugin.getConfig().getString("minigame.games.rroulette.anim-prefix", "animation.revolver.") + name);
    }

    private void sound(Table t, String s, float pitch) {
        if (t.gun == null || !t.gun.isValid()) return;
        t.gun.getWorld().playSound(t.gun.getLocation(), s, 1.2f, pitch);
    }

    // ───────── 진행 ─────────
    private void startRound(Table t) {
        if (t.done) return;
        t.round++;
        t.bullet = Rng.r().nextInt(6);
        t.cursor = 0;
        if (t.round == 1) t.starter = Rng.r().nextInt(t.order.size());
        t.starter = nextAlive(t, t.starter - 1);
        tellTable(t, "&f" + t.round + "라운드", "&7총알 1발 장전 · 실린더를 돌립니다 (" + t.alive.size() + "명 남음)");
        anim(t, "spin");
        sound(t, "minecraft:block.chain.place", 0.8f);
        later(32, () -> turn(t, t.starter));
    }

    /** from 다음(시계 방향)으로 살아 있는 사람 자리 */
    private int nextAlive(Table t, int from) {
        int n = t.order.size();
        for (int s = 1; s <= n; s++) {
            int i = Math.floorMod(from + s, n);
            if (t.alive.contains(t.order.get(i))) return i;
        }
        return -1;
    }

    private void turn(Table t, int idx) {
        if (t.done || idx < 0) return;
        t.turn = idx;
        t.waiting = true;
        int tok = ++t.token;
        UUID u = t.order.get(idx);
        Player p = Bukkit.getPlayer(u);
        aim(t, u);
        later(14, () -> { if (t.token == tok) { anim(t, "cock"); sound(t, "minecraft:block.iron_trapdoor.open", 1.6f); } });
        int sec = Math.max(3, cfgInt("turn-seconds", 15));
        String name = p != null ? plugin.displayName(p) : "?";
        for (UUID o : t.order) {
            Player op = Bukkit.getPlayer(o);
            if (op == null || !players.contains(o)) continue;
            hud(t, op);
            if (o.equals(u)) {
                plugin.ui().center(op, "&f당신 차례", "&7좌클릭으로 방아쇠 · " + sec + "초 뒤 자동 발사");
                op.playSound(op.getLocation(), "minecraft:block.note_block.bell", 1f, 0.8f);
            } else op.sendActionBar(c("&f" + name + "&7님 차례…"));
        }
        later(sec * 20L, () -> { if (t.token == tok && t.waiting) pull(t); });
    }

    @Override
    public void onSwing(Player p) {
        for (Table t : tables) {
            if (t.done || !t.waiting || t.turn < 0) continue;
            if (t.order.get(t.turn).equals(p.getUniqueId())) { pull(t); return; }
        }
    }

    private void pull(Table t) {
        if (!t.waiting || t.done) return;
        t.waiting = false;
        int tok = ++t.token;
        UUID u = t.order.get(t.turn);
        Player p = Bukkit.getPlayer(u);
        String name = p != null ? plugin.displayName(p) : "?";
        boolean bang = t.cursor == t.bullet;
        if (!bang) {
            t.cursor++;
            anim(t, "click");
            sound(t, "minecraft:block.dispenser.fail", 1.4f);
            tellTable(t, "&7철컥…", "&f" + name + " &7생존 · 남은 칸 " + (6 - t.cursor));
            later(40, () -> { if (t.token == tok) turn(t, nextAlive(t, t.turn)); });
            return;
        }
        anim(t, "fire");
        sound(t, "minecraft:entity.generic.explode", 1.7f);
        sound(t, "minecraft:entity.firework_rocket.blast", 0.6f);
        tellTable(t, "&c탕!", "&f" + name + " &7탈락");
        int shooter = t.turn;
        out(t, u);
        if (t.alive.size() <= 1) { later(60, () -> finish(t)); return; }
        t.starter = shooter;   // 다음 라운드는 맞은 사람 다음 자리부터
        later(80, () -> { if (t.token == tok) startRound(t); });
    }

    /** 탈락 처리 (맞았거나 나감) */
    private void out(Table t, UUID u) {
        t.alive.remove(u);
        t.outOrder.add(u);
        state.put(u, "out");
        seated.remove(u);
        for (UUID o : t.order) { Player op = Bukkit.getPlayer(o); if (op != null && players.contains(o)) hud(t, op); }
        Player p = Bukkit.getPlayer(u);
        if (p == null) { players.remove(u); back.remove(u); return; }
        Location chair = t.chairs.get(t.order.indexOf(u));
        Entity v = p.getVehicle();
        if (v != null) v.eject();
        Location f = chair.clone();
        f.setPitch(0);
        Vector push = f.getDirection().multiply(-0.8).setY(0.35);
        for (int k = 1; k <= 3; k++) later(k, () -> { if (p.isOnline() && !p.isDead() && p.getVehicle() == null) p.setVelocity(push); });
        if (cfgBool("kill-on-hit", true)) {
            later(15, () -> {
                if (!p.isOnline() || p.isDead()) return;
                plugin.harass().keepInv.add(p.getUniqueId());
                p.setHealth(0);
            });
            // 죽으면 부활 지점으로 가니까 게임에서 바로 풀어 줌 (끝날 때 원래 자리로 다시 보내지 않음)
            later(16, () -> { players.remove(u); back.remove(u); });
        } else later(40, () -> {
            players.remove(u);
            Location l = back.remove(u);
            if (p.isOnline() && l != null) p.teleport(l);
        });
    }

    private void finish(Table t) {
        if (t.done) return;
        t.done = true;
        t.waiting = false;
        t.token++;
        List<UUID> rank = new ArrayList<>(t.alive);
        for (int i = t.outOrder.size() - 1; i >= 0; i--) rank.add(t.outOrder.get(i));
        List<Integer> ratio = plugin.getConfig().getIntegerList("minigame.games.rroulette." + (t.order.size() <= 2 ? "payout-2" : "payout"));
        if (ratio.isEmpty()) ratio = t.order.size() <= 2 ? List.of(60, 40) : List.of(50, 30, 20);
        int sum = 0;
        for (int r : ratio) sum += Math.max(0, r);
        long net = t.pot * (100 - Math.max(0, Math.min(100, cfgInt("rake-percent", 5)))) / 100;
        long[] prize = new long[rank.size()];
        long paid = 0;
        for (int i = 0; i < rank.size() && i < ratio.size() && sum > 0; i++) { prize[i] = net * Math.max(0, ratio.get(i)) / sum; paid += prize[i]; }
        if (prize.length > 0) prize[0] += Math.max(0, net - paid);   // 나머지는 1등
        StringBuilder sb = new StringBuilder("&f[러시안 룰렛] ");
        for (int i = 0; i < rank.size(); i++) {
            UUID u = rank.get(i);
            if (prize[i] > 0) CasinoSupport.give(plugin, u, prize[i]);
            String nm = name(u);
            if (i < 3 && prize[i] > 0) sb.append(i + 1).append("등 ").append(nm).append(" &b+").append(CasinoSupport.num(prize[i])).append("다이아&f  ");
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            String sub = (i + 1) + "등" + (prize[i] > 0 ? " · 상금 " + CasinoSupport.num(prize[i]) + "다이아" : " · 판돈 " + stake() + "다이아를 잃었어요");
            if (prize[i] > 0) resultFx(p, true, sub, CasinoSupport.num(prize[i]) + "다이아");
            else resultFx(p, false, sub);
            if (!players.contains(u)) plugin.ui().close(p, "minigame");
        }
        plugin.announce(sb.toString().trim(), true);
        plugin.getLogger().info("[러시안 룰렛] 테이블 " + t.no + " 판돈 " + t.pot + " → " + sb);
        // 이긴 사람도 의자에서 내려 줌
        for (UUID u : t.alive) seated.remove(u);
        for (Entity e : t.seats) if (e.isValid()) { e.eject(); e.remove(); }
        t.seats.clear();
        later(60, () -> { if (t.gun != null && t.gun.isValid()) t.gun.remove(); });
        boolean all = true;
        for (Table o : tables) all &= o.done;
        if (all) later(100, this::end);
    }

    private String name(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) return plugin.displayName(p);
        var d = plugin.data().get(u);
        return d != null && d.nickname != null && !d.nickname.isBlank() ? d.nickname : "누군가";
    }

    // ───────── 화면 ─────────
    private void hud(Table t, Player p) {
        List<Object> arr = new ArrayList<>();
        for (UUID u : t.order) {
            Player o = Bukkit.getPlayer(u);
            arr.add(Json.obj().put("name", name(u)).put("account", o != null ? o.getName() : "").put("state", t.alive.contains(u) ? "wait" : "out"));
        }
        Json data = Json.obj().put("game", id).put("title", label() + " · 테이블 " + t.no).put("participants", arr).put("pot", t.pot).put("round", t.round);
        if (t.waiting && t.turn >= 0) {
            UUID cur = t.order.get(t.turn);
            Player cp = Bukkit.getPlayer(cur);
            data.put("partner", Json.obj().put("name", name(cur) + " 차례").put("account", cp != null ? cp.getName() : ""));
            data.put("seconds", Math.max(3, cfgInt("turn-seconds", 15)));
        }
        plugin.ui().open(p, "minigame", data);
    }

    private void tellTable(Table t, String title, String sub) {
        for (UUID u : t.order) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && players.contains(u)) plugin.ui().center(p, title, sub);
        }
    }

    // ───────── 나감 / 정리 ─────────
    @Override
    public void quit(Player p) {
        UUID u = p.getUniqueId();
        for (Table t : tables) {
            if (t.done || !t.alive.contains(u)) continue;
            boolean wasTurn = t.waiting && t.turn >= 0 && t.order.get(t.turn).equals(u);
            seated.remove(u);
            t.alive.remove(u);
            t.outOrder.add(u);
            state.put(u, "out");
            if (t.alive.size() <= 1) { t.waiting = false; t.token++; later(20, () -> finish(t)); }
            else if (wasTurn) { t.waiting = false; int tok = ++t.token; int idx = t.turn; later(20, () -> { if (t.token == tok) turn(t, nextAlive(t, idx)); }); }
        }
        super.quit(p);
    }

    @Override
    protected void cleanup() {
        for (Table t : tables) {
            if (!t.done) {   // 중간에 멈추면 판돈 돌려줌
                for (UUID u : t.order) CasinoSupport.give(plugin, u, stake());
                t.done = true;
            }
            for (Entity e : t.seats) if (e != null && e.isValid()) { e.eject(); e.remove(); }
            if (t.gun != null && t.gun.isValid()) t.gun.remove();
        }
        tables.clear();
        seated.clear();
    }
}
