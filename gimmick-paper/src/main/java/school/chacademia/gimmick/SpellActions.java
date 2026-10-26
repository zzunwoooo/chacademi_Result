package school.chacademia.gimmick;

import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.util.Vector;
import school.chacademia.magicmythic.MagicGimmickEvent;

/**
 * Dungeon gimmick spells 239~250 (+ 35 다시 타오르는 불 / 48 심지 줄이기 on the element brazier). Registered only with
 * ChAcademiaMagicMythic. Each action finds the gimmick the caster looks at; a wrong / missing target leaves the event
 * unhandled with a message, and the mechanic refunds mana + cooldown.
 * Effects: claude:<pack>/<system> at the gimmick (packs in portable-vfx-runtime/assets, see docs/handoff/CLAUDE_GIMMICK_SPELLS_20261020.md).
 */
final class SpellActions implements Listener {
    private final GimmickPlugin plugin;
    private final Map<UUID, Thaw> thaws = new HashMap<>();
    private final Map<UUID, Beam> beams = new HashMap<>();
    private final Map<UUID, Object[]> marks = new HashMap<>(); // player -> {gate id, until tick}
    private final NamespacedKey freezeKey;
    private long tick;
    private Set<String> litBefore = Set.of();

    SpellActions(GimmickPlugin plugin) {
        this.plugin = plugin;
        this.freezeKey = new NamespacedKey(plugin, "light_beam_root");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    private static String fx(String pack, String system) { return "claude:" + pack + "/" + system; }
    private Location floor(Gimmick g) { return new Location(Bukkit.getWorld(g.world), g.x, g.by, g.z); }
    private static float yawTo(Location from, Location to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        return dx * dx + dz * dz < 1e-6 ? 0 : (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
    /** 기믹 마법 결과 안내 → 정보 알림 (같은 문구는 1.5초 안에 다시 안 보냄). */
    private void bar(Player p, String text) { ChacaLink.notify(p, "system", "gimmick-spell:" + text, text, "", 1500); }

    // ================================================================== dispatch
    @EventHandler(priority = EventPriority.NORMAL)
    public void cast(MagicGimmickEvent e) {
        Player p = e.getCaster();
        if (p == null || !p.isOnline()) return;
        String action = e.getAction();
        if (action.equals("beam")) { e.setHandled(beam(p, e.getRange())); return; }
        Gimmick g = plugin.world().looking(p, e.getRange());
        if (g != null && (g.attached() || !plugin.protection().pair(p, g.location()))) g = null;
        var info = g == null ? null : plugin.catalog().get(g.model);
        String need = switch (action) {
            case "thaw" -> "seal"; case "steam" -> "valve"; case "level" -> "pillar"; case "weight" -> "plate"; case "grow" -> "pot";
            case "tune" -> "mirror|dial"; case "tint" -> "prism"; case "wake" -> "statue"; case "conduit" -> "conduit"; case "mark" -> "gate";
            case "ignite", "extinguish" -> "brazier"; case "rewind" -> "*";
            default -> null;
        };
        if (need == null) return;
        if (g == null || !need.equals("*") && !Set.of(need.split("\\|")).contains(info.kind())) {
            if (!action.equals("ignite") && !action.equals("extinguish")) e.setMessage(missing(action));
            return; // ignite / extinguish: the mechanic falls back to campfires, candles and fire blocks
        }
        boolean ok = switch (action) {
            case "thaw" -> thaw(p, g);
            case "steam" -> steam(p, g);
            case "level" -> level(p, g, e.isSneaking(), e);
            case "weight" -> weight(p, g);
            case "grow" -> grow(p, g);
            case "tune" -> { plugin.devices().position(g, e.isSneaking() ? -1 : 1); plugin.vfx().play(fx("mirrortune", "turn"), floor(g), g.yaw, 16); yield true; }
            case "tint" -> tint(p, g, e.isSneaking());
            case "wake" -> wake(p, g);
            case "rewind" -> rewind(p, g, e);
            case "conduit" -> { plugin.devices().turn(g, 1); plugin.vfx().play(fx("conduitturn", "turn"), floor(g), g.yaw - 90, 14); yield true; }
            case "mark" -> mark(p, g);
            case "ignite" -> device(p, g, "ignite", true);
            case "extinguish" -> device(p, g, "extinguish", false);
            default -> false;
        };
        e.setHandled(ok);
    }

    private static String missing(String action) {
        return switch (action) {
            case "thaw" -> "녹일 얼음 봉인을 바라보고 써 주세요.";
            case "steam" -> "증기 밸브를 바라보고 써 주세요.";
            case "level" -> "수위 기둥을 바라보고 써 주세요.";
            case "weight" -> "압력판을 바라보고 써 주세요.";
            case "grow" -> "씨앗 화분을 바라보고 써 주세요.";
            case "tune" -> "반사 거울이나 회전 다이얼을 바라보고 써 주세요.";
            case "tint" -> "프리즘을 바라보고 써 주세요.";
            case "wake" -> "잠든 석상을 바라보고 써 주세요.";
            case "rewind" -> "되돌릴 장치를 바라보고 써 주세요.";
            case "conduit" -> "마력 도관을 바라보고 써 주세요.";
            case "mark" -> "텔레포트 게이트를 바라보고 써 주세요.";
            default -> "바라보는 곳에 이 마법이 통하는 장치가 없습니다.";
        };
    }

    /** The device's own reaction (Signals.action); a brazier already in that state still counts as a valid target. */
    private boolean device(Player p, Gimmick g, String trigger, boolean freeOn) {
        plugin.signals().action(g, trigger);
        return true;
    }

    // ================================================================== 239 해빙의 열기 (3 s channel)
    private final class Thaw { final Gimmick g; int age; UUID melt; Thaw(Gimmick g) { this.g = g; } }
    private boolean thaw(Player p, Gimmick g) {
        if (plugin.world().on(g)) { bar(p, "이미 녹은 봉인입니다."); return true; }
        var old = thaws.remove(p.getUniqueId());
        if (old != null) plugin.vfx().stop(old.melt);
        var t = new Thaw(g);
        t.melt = plugin.vfx().start(fx("thawingheat", "melt"), floor(g), yawTo(floor(g), p.getLocation()), 60, 0xFFFFFF);
        thaws.put(p.getUniqueId(), t);
        bar(p, "얼음 봉인에 열기를 불어넣는 중... (피해를 받으면 다시 얼어붙습니다)");
        return true;
    }
    private void thawTick() {
        for (var it = thaws.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next(); Player p = Bukkit.getPlayer(e.getKey()); var t = e.getValue();
            Location at = t.g.location();
            if (p == null || !p.isValid() || at == null || p.getWorld() != at.getWorld() || p.getLocation().distance(at) > 16 || plugin.world().get(t.g.id) == null) {
                it.remove(); plugin.vfx().stop(t.melt); if (at != null) plugin.vfx().play(fx("thawingheat", "fizzle"), floor(t.g), 0, 12); continue;
            }
            if (++t.age >= 60) {
                it.remove();
                plugin.vfx().play(fx("thawingheat", "thaw"), floor(t.g), yawTo(floor(t.g), p.getLocation()), 24);
                plugin.signals().action(t.g, "thaw");
                bar(p, "얼음 봉인이 녹았습니다.");
            }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getFinalDamage() <= 0) return;
        var t = thaws.remove(p.getUniqueId());
        if (t == null) return;
        plugin.vfx().stop(t.melt);
        plugin.vfx().play(fx("thawingheat", "fizzle"), floor(t.g), 0, 12);
        bar(p, "피해를 받아 봉인이 다시 얼어붙었습니다.");
    }

    // ================================================================== 240 증기 점화
    private boolean steam(Player p, Gimmick g) {
        plugin.vfx().play(fx("steamignition", "ignite"), floor(g), yawTo(floor(g), p.getLocation()), 32);
        if (g.live()) plugin.world().overlay(g, "fire_in");
        plugin.signals().action(g, "steam");
        return true;
    }

    // ================================================================== 241 수위 조절
    private boolean level(Player p, Gimmick g, boolean down, MagicGimmickEvent e) {
        if (!plugin.devices().level(g, down ? -1 : 1)) { e.setMessage(down ? "수위가 이미 가장 낮습니다." : "수위가 이미 가득 찼습니다."); return false; }
        plugin.vfx().play(fx("waterlevel", down ? "drain" : "rise"), floor(g), yawTo(floor(g), p.getLocation()), 24);
        bar(p, "수위 " + g.level + " / 3");
        return true;
    }

    // ================================================================== 242 무게추
    private boolean weight(Player p, Gimmick g) {
        plugin.vfx().play(fx("stoneweight", "drop"), floor(g), yawTo(floor(g), p.getLocation()), 32);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (plugin.world().get(g.id) == null) return;
            plugin.signals().action(g, "weight");
        }, 13);
        return true;
    }

    // ================================================================== 243 덩굴 깨우기
    private boolean grow(Player p, Gimmick g) {
        plugin.vfx().play(fx("vineawaken", "sprout"), floor(g), yawTo(floor(g), p.getLocation()), 40);
        return device(p, g, "grow", true);
    }

    // ================================================================== 246 프리즘 물들이기
    private boolean tint(Player p, Gimmick g, boolean back) {
        plugin.devices().color(g, Colors.next(g.color, back ? -1 : 1));
        plugin.vfx().start(fx("prismtint", "tint"), floor(g), yawTo(floor(g), p.getLocation()), 20, Colors.rgb(g.color));
        bar(p, "프리즘 색: " + Colors.ko(g.color));
        return true;
    }

    // ================================================================== 247 석상 깨우기
    private boolean wake(Player p, Gimmick g) {
        plugin.vfx().play(fx("statuewake", "wake"), floor(g), g.yaw, 30);
        plugin.devices().turn(g, 1);
        var info = plugin.catalog().get(g.model);
        if (g.live() && info.has("activate")) {
            plugin.world().play(g, "activate", info.has("active") ? "active" : null);
            long back = info.anims().get("activate").ticks() + 40;
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (g.live()) plugin.world().playState(g, false); }, back);
        }
        return true;
    }

    // ================================================================== 248 되감기
    private boolean rewind(Player p, Gimmick g, MagicGimmickEvent e) {
        if (!plugin.devices().rewind(g, plugin.getConfig().getDouble("rewind-seconds", 60))) { e.setMessage("되돌릴 변화가 없습니다. (1분 안에 바뀐 장치만)"); return false; }
        plugin.vfx().play(fx("rewind", "rewind"), floor(g), yawTo(floor(g), p.getLocation()), 24);
        return true;
    }

    // ================================================================== 250 차원 표식
    private boolean mark(Player p, Gimmick g) {
        Object[] m = marks.get(p.getUniqueId());
        Gimmick first = m == null || (long) m[1] < tick ? null : plugin.world().get((String) m[0]);
        if (first == null || first == g) {
            marks.put(p.getUniqueId(), new Object[]{g.id, tick + 600L});
            plugin.vfx().play(fx("dimensionmark", "mark"), floor(g), g.yaw, 30);
            bar(p, "게이트에 표식을 새겼습니다. 30초 안에 다른 게이트에 쓰면 두 게이트가 이어집니다.");
            return true;
        }
        marks.remove(p.getUniqueId());
        int seconds = plugin.getConfig().getInt("dimension-link-seconds", 120);
        plugin.devices().link(first, g, seconds);
        plugin.vfx().play(fx("dimensionmark", "linked"), floor(first), first.yaw, 20);
        plugin.vfx().play(fx("dimensionmark", "linked"), floor(g), g.yaw, 20);
        bar(p, "두 게이트가 " + seconds + "초 동안 이어졌습니다.");
        return true;
    }

    // ================================================================== 244 빛줄기
    private record Seg(Location from, Vector dir, double len, String color) {}
    private final class Beam {
        final int ticks; int age; String signature = ""; final List<UUID> shown = new ArrayList<>();
        final Set<String> lit = new HashSet<>(); // receiver id + "|" + colour, mirrors, prisms
        Beam(int ticks) { this.ticks = ticks; }
    }
    private boolean beam(Player p, double range) {
        var old = beams.remove(p.getUniqueId());
        if (old != null) old.shown.forEach(plugin.vfx()::stop);
        beams.put(p.getUniqueId(), new Beam(plugin.getConfig().getInt("light-beam-ticks", 60)));
        root(p, true);
        return true;
    }
    private void root(Player p, boolean on) {
        for (Attribute a : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH)) {
            var inst = p.getAttribute(a);
            if (inst == null) continue;
            inst.removeModifier(freezeKey);
            // transient: never saved with the player, so a crash mid-beam cannot leave anyone frozen
            if (on) inst.addTransientModifier(new AttributeModifier(freezeKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        }
    }
    private void beamTick(Set<String> lit) {
        for (var it = beams.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next(); Player p = Bukkit.getPlayer(e.getKey()); var b = e.getValue();
            if (p == null || !p.isValid() || ++b.age > b.ticks) {
                it.remove(); b.shown.forEach(plugin.vfx()::stop); if (p != null) root(p, false); continue;
            }
            if (b.age % 2 == 1) trace(p, b);
            lit.addAll(b.lit);
        }
    }
    /** Follow the beam: straight, turned 90° by mirrors, coloured by prisms, stopped by blocks and other gimmicks. */
    private void trace(Player p, Beam b) {
        b.lit.clear();
        var segs = new ArrayList<Seg>();
        double max = plugin.getConfig().getDouble("light-beam-range", 32);
        double yaw = Math.toRadians(p.getLocation().getYaw());
        // grid puzzles: the beam leaves along the nearest axis (also keeps the drawn path stable while the caster turns slightly)
        Vector dir = axis(new Vector(-Math.sin(yaw), 0, Math.cos(yaw)));
        Location from = p.getLocation().add(0, 1.2, 0).add(dir.clone().multiply(0.4));
        Location pos = from.clone(); String color = "white"; double total = 0, segLen = 0; Gimmick last = null; boolean hit = false;
        World w = p.getWorld(); int turns = 0;
        while (total < max) {
            pos.add(dir.clone().multiply(0.2)); total += 0.2; segLen += 0.2;
            int cx = pos.getBlockX(), cy = pos.getBlockY(), cz = pos.getBlockZ();
            Gimmick g = plugin.world().at(w.getName(), cx, cy, cz);
            if (g != null && g != last) {
                if (g.attached() || !plugin.protection().pair(p, g.location())) { hit = true; break; }
                var info = plugin.catalog().get(g.model);
                Location centre = new Location(w, cx + 0.5, pos.getY(), cz + 0.5);
                if (info.is("mirror") && turns < 8) {
                    Vector in = axis(dir); int m = Math.floorMod(g.facing + g.pos, 4);
                    int[] f = Gimmick.forward(m), r = Gimmick.right(m);
                    Vector out = null;
                    if (in.getBlockX() == -f[0] && in.getBlockZ() == -f[1]) out = new Vector(r[0], 0, r[1]);
                    else if (in.getBlockX() == -r[0] && in.getBlockZ() == -r[1]) out = new Vector(f[0], 0, f[1]);
                    segs.add(new Seg(from, dir, from.distance(centre), color));
                    b.lit.add("m|" + g.id);
                    if (out == null) { hit = true; pos = centre; break; } // the back of the mirror blocks the light
                    dir = out; from = centre.clone(); pos = centre.clone(); segLen = 0; last = g; turns++;
                    continue;
                }
                if (info.is("prism")) {
                    segs.add(new Seg(from, dir, from.distance(centre), color));
                    color = Colors.norm(g.color); b.lit.add("p|" + g.id);
                    from = centre.clone(); pos = centre.clone(); segLen = 0; last = g;
                    continue;
                }
                if (info.is("receiver")) { b.lit.add("r|" + g.id + "|" + color); pos = centre; hit = true; break; }
                if (!info.collision().equals("floor")) { hit = true; break; }
            }
            var block = pos.getBlock();
            if (g == null && !block.isPassable() && !plugin.collision().ours(block)) { hit = true; break; }
        }
        segs.add(new Seg(from, dir, Math.max(0.2, from.distance(pos)), color));
        // signature: redraw only when the path or colour changed
        var sig = new StringBuilder();
        for (Seg s : segs) sig.append(Math.round(s.from().getX() * 4)).append(',').append(Math.round(s.from().getZ() * 4)).append(',')
            .append(Math.round(s.dir().getX() * 100)).append(',').append(Math.round(s.dir().getZ() * 100)).append(',').append(Math.round(s.len() * 4)).append(s.color()).append(';');
        if (hit) sig.append("hit");
        if (sig.toString().equals(b.signature)) return;
        b.signature = sig.toString();
        b.shown.forEach(plugin.vfx()::stop); b.shown.clear();
        int left = Math.max(2, b.ticks - b.age);
        for (Seg s : segs) {
            float y = (float) Math.toDegrees(Math.atan2(-s.dir().getX(), s.dir().getZ()));
            int n = Math.max(1, (int) Math.ceil(s.len() / 2 - 0.05));
            for (int k = 0; k < n; k++) {
                double off = s.len() >= 2 ? Math.min(2.0 * k, s.len() - 2) : 0;
                var id = plugin.vfx().start(fx("lightbeam", "beam"), s.from().clone().add(s.dir().clone().multiply(off)), y, left, Colors.rgb(s.color()));
                if (id != null) b.shown.add(id);
            }
        }
        if (hit) { var end = segs.getLast(); var id = plugin.vfx().start(fx("lightbeam", "hit"), pos, (float) Math.toDegrees(Math.atan2(-end.dir().getX(), end.dir().getZ())), left, Colors.rgb(end.color())); if (id != null) b.shown.add(id); }
    }
    private static Vector axis(Vector v) { return Math.abs(v.getX()) > Math.abs(v.getZ()) ? new Vector(Math.signum(v.getX()), 0, 0) : new Vector(0, 0, Math.signum(v.getZ())); }

    /** Receivers (with the colours reaching them) and mirrors / prisms a beam passes, this tick -> Signals. */
    private void applyLight(Set<String> lit) {
        if (lit.equals(litBefore)) return;
        var receivers = new HashMap<String, Set<String>>();
        var passed = new HashSet<String>();
        for (String k : lit) {
            String[] f = k.split("\\|");
            if (f[0].equals("r")) receivers.computeIfAbsent(f[1], x -> new HashSet<>()).add(Colors.norm(f[2]));
            else passed.add(f[1]);
        }
        plugin.signals().light(receivers, passed);
        litBefore = Set.copyOf(lit);
    }

    // ================================================================== tick / cleanup
    private void tick() {
        tick++;
        thawTick();
        var lit = new HashSet<String>();
        beamTick(lit);
        applyLight(lit);
        if (tick % 200 == 0) marks.values().removeIf(m -> (long) m[1] < tick);
    }
    @EventHandler public void join(org.bukkit.event.player.PlayerJoinEvent e) { root(e.getPlayer(), false); } // older saves

    @EventHandler public void quit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        var b = beams.remove(id); if (b != null) { b.shown.forEach(plugin.vfx()::stop); root(e.getPlayer(), false); }
        var t = thaws.remove(id); if (t != null) plugin.vfx().stop(t.melt);
        marks.remove(id);
    }
    void shutdown() {
        for (var e : beams.entrySet()) { e.getValue().shown.forEach(plugin.vfx()::stop); Player p = Bukkit.getPlayer(e.getKey()); if (p != null) root(p, false); }
        beams.clear();
        thaws.values().forEach(t -> plugin.vfx().stop(t.melt)); thaws.clear();
    }
}
