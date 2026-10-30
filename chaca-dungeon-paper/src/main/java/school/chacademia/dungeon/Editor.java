package school.chacademia.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import school.chacademia.dungeon.protocol.DungeonProtocol;

/**
 * 던전 설정 도구 (2026-10-30 오너: 명령어 없이 비전공자도). 도구를 들고
 * <ul>
 *   <li>블록 좌클릭 = 첫 모서리, 블록 우클릭 = 반대 모서리 (월드에딧처럼)</li>
 *   <li>허공 우클릭 = 설정 창 (클라이언트 모드 화면): 칸 고르기, 구역을 칸 구역·방·몹 구역·문으로, 시작점·보스·지점, 단계, 칸 복사, 보상, 기믹 설치</li>
 *   <li>기믹을 우클릭 = 그 기믹 조절 창 (회전·이동·높이·크기·정답·삭제·이름 붙이기)</li>
 * </ul>
 * 도구를 든 동안 고른 칸의 구역·방·몹 구역·문·보스·지점·기믹 이름이 월드에 보인다 (K_VIEW). 모든 동작은 서버가 권한을 보고 처리한다.
 */
final class Editor implements Listener {
    private final DungeonPlugin plugin;
    private final NamespacedKey toolKey;
    final GimmickLink gimmicks = new GimmickLink();

    private static final class State {
        String dungeon; int slot = 1;
        int[] a, b;
        String gimmick;
        String lastView = "";
        long lastViewAt;
        String msg = "";
    }
    private final Map<UUID, State> states = new HashMap<>();

    Editor(DungeonPlugin plugin) {
        this.plugin = plugin;
        this.toolKey = new NamespacedKey(plugin, "setup_tool");
    }

    private DungeonService svc() { return plugin.service(); }

    private State state(Player p) {
        var s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        if (s.dungeon == null || !svc().dungeons.containsKey(s.dungeon))
            s.dungeon = svc().dungeons.isEmpty() ? null : svc().dungeons.keySet().iterator().next();
        return s;
    }

    // ------------------------------------------------------------ 도구

    ItemStack tool() {
        var it = new ItemStack(Material.BREEZE_ROD);
        var meta = it.getItemMeta();
        meta.displayName(Component.text("던전 설정 도구", NamedTextColor.LIGHT_PURPLE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("블록 좌클릭: 첫 모서리 · 우클릭: 반대 모서리", NamedTextColor.GRAY),
                Component.text("허공 우클릭: 설정 창 · 기믹 우클릭: 기믹 조절", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    boolean isTool(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }

    private boolean holding(Player p) { return isTool(p.getInventory().getItemInMainHand()); }

    @EventHandler(priority = EventPriority.LOWEST)
    void interact(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isTool(e.getItem())) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (!p.hasPermission("chacademi.dungeon.admin")) return;
        var s = state(p);
        switch (e.getAction()) {
            case LEFT_CLICK_BLOCK -> { var b = e.getClickedBlock(); s.a = new int[]{b.getX(), b.getY(), b.getZ()}; selected(p, s, "첫 모서리"); }
            case RIGHT_CLICK_BLOCK -> { var b = e.getClickedBlock(); s.b = new int[]{b.getX(), b.getY(), b.getZ()}; selected(p, s, "반대 모서리"); }
            case RIGHT_CLICK_AIR -> { s.gimmick = null; sendEditor(p, true, ""); }
            case LEFT_CLICK_AIR -> { if (p.isSneaking()) { s.a = s.b = null; p.sendActionBar(Component.text("고른 구역을 지웠어요", NamedTextColor.GRAY)); sendView(p, true); } }
            default -> { }
        }
    }

    private void selected(Player p, State s, String which) {
        String size = s.a != null && s.b != null ? "  →  " + (Math.abs(s.a[0] - s.b[0]) + 1) + "×" + (Math.abs(s.a[1] - s.b[1]) + 1) + "×" + (Math.abs(s.a[2] - s.b[2]) + 1) : "";
        p.sendActionBar(Component.text(which + " 지정" + size, NamedTextColor.AQUA));
        sendView(p, true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    void interactEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isTool(e.getPlayer().getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("chacademi.dungeon.admin")) return;
        String id = gimmicks.byEntity(e.getRightClicked());
        if (id == null) { p.sendActionBar(Component.text("기믹이 아니에요", NamedTextColor.GRAY)); return; }
        state(p).gimmick = id;
        sendEditor(p, true, "");
    }

    @EventHandler void quit(PlayerQuitEvent e) { states.remove(e.getPlayer().getUniqueId()); }

    /** 10틱마다: 도구를 든 사람에게 구역 표시 (바뀌었거나 3초마다). */
    void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPermission("chacademi.dungeon.admin")) continue;
            var s = states.get(p.getUniqueId());
            if (holding(p)) sendView(p, false);
            else if (s != null && !s.lastView.isEmpty()) {
                s.lastView = "";
                svc().send(p, new DungeonProtocol.JsonMsg(DungeonProtocol.K_VIEW, "{}"));
            }
        }
    }

    // ------------------------------------------------------------ 보여 주기

    static JsonObject box(Defs.Box b, String label) {
        var o = new JsonObject();
        o.addProperty("x1", b.minX()); o.addProperty("y1", b.minY()); o.addProperty("z1", b.minZ());
        o.addProperty("x2", b.maxX()); o.addProperty("y2", b.maxY()); o.addProperty("z2", b.maxZ());
        o.addProperty("label", label);
        return o;
    }

    private static JsonObject point(double x, double y, double z, String label) {
        var o = new JsonObject();
        o.addProperty("x", x); o.addProperty("y", y); o.addProperty("z", z); o.addProperty("label", label);
        return o;
    }

    private static JsonObject point(Defs.Loc l, String label) { return point(l.x(), l.y(), l.z(), label); }

    void sendView(Player p, boolean force) {
        var s = state(p);
        var d = s.dungeon == null ? null : svc().dungeons.get(s.dungeon);
        var v = new JsonObject();
        World w = d == null ? null : Bukkit.getWorld(d.world);
        v.addProperty("dim", w == null ? "" : w.getKey().toString());
        v.addProperty("title", d == null ? "던전 없음" : d.name + " · " + s.slot + "번 칸");
        if (s.a != null) v.add("a", point(s.a[0], s.a[1], s.a[2], "1"));
        if (s.b != null) v.add("b", point(s.b[0], s.b[1], s.b[2], "2"));
        if (s.a != null && s.b != null) {
            var sel = Defs.Box.of(s.a, s.b);
            v.add("sel", box(sel, (sel.maxX() - sel.minX() + 1) + "×" + (sel.maxY() - sel.minY() + 1) + "×" + (sel.maxZ() - sel.minZ() + 1)));
        }
        if (d != null) {
            var slot = d.slot(s.slot);
            if (slot.region() != null) v.add("region", box(slot.region(), s.slot + "번 칸 구역"));
            var rooms = new JsonArray(); var zones = new JsonArray();
            for (var r : slot.rooms.values()) {
                if (r.box() != null) rooms.add(box(r.box(), "방 " + r.name));
                for (var z : r.zones) zones.add(box(z.box(), z.mob + " ×" + z.count));
            }
            v.add("rooms", rooms); v.add("zones", zones);
            var doors = new JsonArray();
            for (var dr : slot.doors.values()) doors.add(box(dr.box(), "문 " + dr.name + (dr.gimmick.isBlank() ? "" : " (기믹 " + dr.gimmick + ")")));
            v.add("doors", doors);
            var marks = new JsonArray();
            if (slot.start != null) marks.add(point(slot.start, "시작점"));
            for (var e : slot.bosses.entrySet()) marks.add(point(e.getValue().at, "보스 " + e.getKey() + " (" + e.getValue().mob + ")"));
            for (var e : slot.points.entrySet()) marks.add(point(e.getValue(), "지점 " + e.getKey()));
            for (var e : slot.gimmicks.entrySet()) marks.add(point(e.getValue()[0] + .5, e.getValue()[1] + 1.2, e.getValue()[2] + .5, "기믹 " + e.getKey()));
            if (d.portal != null) marks.add(point(d.portal, "입장 포탈"));
            if (d.exit != null) marks.add(point(d.exit, "나가는 곳"));
            v.add("marks", marks);
        }
        String json = v.toString();
        long now = System.currentTimeMillis();
        if (!force && json.equals(s.lastView) && now - s.lastViewAt < 3000) return;
        s.lastView = json; s.lastViewAt = now;
        svc().send(p, new DungeonProtocol.JsonMsg(DungeonProtocol.K_VIEW, json));
    }

    /** 설정 창 내용 (open=true 면 창을 연다, false 면 열린 창만 갱신). */
    void sendEditor(Player p, boolean open, String msg) {
        var s = state(p);
        if (msg != null && !msg.isEmpty()) s.msg = msg;
        var o = new JsonObject();
        o.addProperty("open", open);
        o.addProperty("msg", s.msg);
        var ds = new JsonArray();
        for (var d : svc().dungeons.values()) { var x = new JsonObject(); x.addProperty("id", d.id); x.addProperty("name", d.name); x.addProperty("slots", d.instances); ds.add(x); }
        o.add("dungeons", ds);
        o.addProperty("dungeon", s.dungeon == null ? "" : s.dungeon);
        o.addProperty("slot", s.slot);
        o.addProperty("hasSel", s.a != null && s.b != null);
        if (s.a != null && s.b != null) {
            var sel = Defs.Box.of(s.a, s.b);
            o.addProperty("selSize", (sel.maxX() - sel.minX() + 1) + "×" + (sel.maxY() - sel.minY() + 1) + "×" + (sel.maxZ() - sel.minZ() + 1));
        }
        var d = s.dungeon == null ? null : svc().dungeons.get(s.dungeon);
        if (d != null) {
            var slot = d.slot(s.slot);
            o.addProperty("start", slot.start == null ? "" : slot.start.shortText());
            o.addProperty("region", slot.region() == null ? "" : slot.region().text());
            var rooms = new JsonArray();
            for (var r : slot.rooms.values()) {
                var x = new JsonObject(); x.addProperty("name", r.name);
                var zs = new JsonArray();
                for (var z : r.zones) { var zz = new JsonObject(); zz.addProperty("mob", z.mob); zz.addProperty("count", z.count); zs.add(zz); }
                x.add("zones", zs); rooms.add(x);
            }
            o.add("rooms", rooms);
            var doors = new JsonArray();
            for (var dr : slot.doors.values()) { var x = new JsonObject(); x.addProperty("name", dr.name); x.addProperty("gimmick", dr.gimmick); doors.add(x); }
            o.add("doors", doors);
            o.add("bosses", names(slot.bosses.keySet()));
            o.add("points", names(slot.points.keySet()));
            o.add("gimmicks", names(slot.gimmicks.keySet()));
            var stages = new JsonArray();
            for (var st : d.stages) {
                var x = new JsonObject();
                x.addProperty("name", st.name); x.addProperty("goal", st.goal); x.addProperty("type", st.type); x.addProperty("target", st.target);
                x.addProperty("count", st.count); x.addProperty("radius", st.radius); x.add("doors", names(st.openDoors));
                stages.add(x);
            }
            o.add("stages", stages);
            o.addProperty("portal", d.portal == null ? "" : d.portal.shortText() + " (반경 " + d.portalRadius + ")");
            o.addProperty("exit", d.exit == null ? "" : d.exit.shortText());
            o.addProperty("regen", d.naturalRegen);
            o.addProperty("rewardBase", d.baseRewards.size()); o.addProperty("rewardFirst", d.firstRewards.size()); o.addProperty("rewardRandom", d.randomRewards.size());
            o.add("check", names(check(d)));
        }
        o.add("mobs", names(svc().hooks.mobNames()));
        var models = new JsonArray();
        for (var m : gimmicks.models()) { var a = new JsonArray(); for (String x : m) a.add(x); models.add(a); }
        o.add("models", models);
        o.addProperty("gimmickReady", gimmicks.ready());
        if (s.gimmick != null) {
            var info = gimmicks.info(s.gimmick);
            if (info == null) s.gimmick = null;
            else {
                var g = new JsonObject();
                info.forEach(g::addProperty);
                String linked = "";
                if (d != null) for (var e : d.slot(s.slot).gimmicks.entrySet())
                    if (e.getValue()[0] == Integer.parseInt(info.get("x")) && e.getValue()[1] == Integer.parseInt(info.get("y")) && e.getValue()[2] == Integer.parseInt(info.get("z"))) linked = e.getKey();
                g.addProperty("linked", linked);
                o.add("gimmick", g);
            }
        }
        svc().send(p, new DungeonProtocol.JsonMsg(DungeonProtocol.K_EDITOR, o.toString()));
    }

    private static JsonArray names(Iterable<String> list) { var a = new JsonArray(); for (String n : list) a.add(n); return a; }

    /** 칸별로 빠진 설정 (설정 창 "점검"). */
    List<String> check(Defs.Dungeon d) {
        var out = new ArrayList<String>();
        if (d.portal == null) out.add("입장 포탈이 없어요");
        if (d.stages.isEmpty()) out.add("단계가 없어요");
        for (int no = 1; no <= d.instances; no++) {
            var s = d.slot(no);
            var miss = new ArrayList<String>();
            if (s.start == null) miss.add("시작점");
            if (s.region() == null) miss.add("칸 구역");
            for (var st : d.stages) {
                switch (st.type) {
                    case "room" -> { var r = s.rooms.get(st.target); if (r == null) miss.add("방 " + st.target); else if (r.zones.isEmpty()) miss.add("방 " + st.target + " 몹 구역"); }
                    case "boss" -> { if (!s.bosses.containsKey(st.target)) miss.add("보스 " + st.target); }
                    case "gimmick" -> { if (!s.gimmicks.containsKey(st.target)) miss.add("기믹 " + st.target); }
                    case "reach" -> { if (!s.points.containsKey(st.target)) miss.add("지점 " + st.target); }
                    default -> { }
                }
                for (String door : st.openDoors) if (!s.doors.containsKey(door)) miss.add("문 " + door);
            }
            for (var door : s.doors.values()) if (!door.gimmick.isBlank() && !s.gimmicks.containsKey(door.gimmick)) miss.add("문 " + door.name + "의 기믹 " + door.gimmick);
            out.add(no + "번 칸: " + (miss.isEmpty() ? "준비됨" : String.join(", ", miss) + " 없음"));
        }
        return out;
    }

    // ------------------------------------------------------------ 동작 (C_EDIT)

    void action(Player p, String raw) {
        if (!p.hasPermission("chacademi.dungeon.admin")) return;
        JsonObject a;
        try { a = JsonParser.parseString(raw).getAsJsonObject(); } catch (RuntimeException e) { return; }
        var s = state(p);
        String result;
        try { result = run(p, s, a); }
        catch (Refused r) { result = "!" + r.getMessage(); }
        catch (RuntimeException e) { plugin.getLogger().warning("설정 동작 실패 " + raw + ": " + e); result = "!처리하지 못했어요 (콘솔 확인)"; }
        if (result == null) return;
        boolean bad = result.startsWith("!");
        p.sendActionBar(Component.text(bad ? result.substring(1) : result, bad ? NamedTextColor.RED : NamedTextColor.GREEN));
        sendEditor(p, false, result);
        sendView(p, true);
    }

    private static final class Refused extends RuntimeException { Refused(String m) { super(m, null, false, false); } }

    private static String str(JsonObject a, String k) { return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsString().trim() : ""; }
    private static int num(JsonObject a, String k, int def) { try { return a.has(k) ? a.get(k).getAsInt() : def; } catch (RuntimeException e) { return def; } }
    private static double dnum(JsonObject a, String k, double def) { try { return a.has(k) ? a.get(k).getAsDouble() : def; } catch (RuntimeException e) { return def; } }

    private Defs.Dungeon dungeon(State s) {
        var d = s.dungeon == null ? null : svc().dungeons.get(s.dungeon);
        if (d == null) throw new Refused("던전을 먼저 골라 주세요");
        return d;
    }

    private Defs.Box selection(State s, Player p, Defs.Dungeon d) {
        if (s.a == null || s.b == null) throw new Refused("도구로 구역의 두 모서리를 먼저 찍어 주세요 (좌클릭·우클릭)");
        if (!p.getWorld().getName().equals(d.world)) throw new Refused(d.world + " 월드에서 해 주세요");
        return Defs.Box.of(s.a, s.b);
    }

    private static String name(JsonObject a) {
        String n = str(a, "name");
        if (!Defs.validName(n)) throw new Refused("이름은 한글·영문·숫자·-·_ 32자까지 (점은 안 돼요)");
        return n;
    }

    private void inWorld(Player p, Defs.Dungeon d) { if (!p.getWorld().getName().equals(d.world)) throw new Refused(d.world + " 월드에서 해 주세요"); }

    /** null = 결과 알림 없음. "!…" = 실패. */
    private String run(Player p, State s, JsonObject a) {
        String act = str(a, "a");
        switch (act) {
            case "refresh" -> { sendEditor(p, false, ""); return null; }
            case "tool" -> { p.getInventory().addItem(tool()); return "설정 도구를 받았어요"; }
            case "slot" -> {
                String id = str(a, "dungeon");
                var d = svc().dungeons.get(id);
                if (d == null) throw new Refused("그런 던전이 없어요");
                int no = num(a, "slot", 1);
                if (no < 1 || no > d.instances) throw new Refused("이 던전은 " + d.instances + "칸이에요");
                s.dungeon = id; s.slot = no;
                return d.name + " " + no + "번 칸을 설정해요";
            }
            case "sel.clear" -> { s.a = s.b = null; return "고른 구역을 지웠어요"; }
            case "gimmick.close" -> { s.gimmick = null; sendEditor(p, false, ""); return null; }
        }
        var d = dungeon(s);
        var slot = d.slot(s.slot);
        String done = switch (act) {
            case "region.set" -> { var b = selection(s, p, d); slot.corner1 = new int[]{b.minX(), b.minY(), b.minZ()}; slot.corner2 = new int[]{b.maxX(), b.maxY(), b.maxZ()}; yield "칸 구역 = " + b.text(); }
            case "start.set" -> { inWorld(p, d); slot.start = Defs.Loc.of(p.getLocation()); yield "시작점 = 지금 자리 (보는 방향 포함)"; }
            case "room.add" -> {
                var b = selection(s, p, d); String n = name(a);
                var r = slot.rooms.computeIfAbsent(n, Defs.Room::new);
                r.corner1 = new int[]{b.minX(), b.minY(), b.minZ()}; r.corner2 = new int[]{b.maxX(), b.maxY(), b.maxZ()};
                yield "방 " + n + " = " + b.text();
            }
            case "room.del" -> { String n = str(a, "name"); if (slot.rooms.remove(n) == null) throw new Refused("그런 방이 없어요"); yield "방 " + n + " 지움"; }
            case "zone.add" -> {
                var b = selection(s, p, d);
                var r = slot.rooms.get(str(a, "room"));
                if (r == null) throw new Refused("몹 구역을 넣을 방을 골라 주세요");
                String mob = str(a, "mob");
                if (mob.isBlank()) throw new Refused("몹을 골라 주세요");
                int count = Math.clamp(num(a, "count", 1), 1, 50);
                long safe = DungeonService.safeSpots(p.getWorld(), b, count).size();
                r.zones.add(new Defs.Zone(new int[]{b.minX(), b.minY(), b.minZ()}, new int[]{b.maxX(), b.maxY(), b.maxZ()}, mob, count));
                yield "방 " + r.name + " 몹 구역: " + mob + " ×" + count + (safe < count ? " (안전한 땅이 " + safe + "곳뿐 — 구역을 땅 한 칸 위까지 잡아 주세요)" : "");
            }
            case "zone.del" -> {
                var r = slot.rooms.get(str(a, "room"));
                int i = num(a, "index", -1);
                if (r == null || i < 0 || i >= r.zones.size()) throw new Refused("그런 몹 구역이 없어요");
                var z = r.zones.remove(i);
                yield "방 " + r.name + " 몹 구역 " + z.mob + " 지움";
            }
            case "door.add" -> {
                var b = selection(s, p, d); String n = name(a);
                long vol = (long) (b.maxX() - b.minX() + 1) * (b.maxY() - b.minY() + 1) * (b.maxZ() - b.minZ() + 1);
                if (vol > 4096) throw new Refused("문이 너무 커요 (" + vol + "칸, 4096칸까지)");
                var old = slot.doors.get(n);
                var door = new Defs.Door(n, new int[]{b.minX(), b.minY(), b.minZ()}, new int[]{b.maxX(), b.maxY(), b.maxZ()});
                if (old != null) door.gimmick = old.gimmick;
                slot.doors.put(n, door);
                yield "문 " + n + " = " + b.text() + " (판이 시작되면 닫힘)";
            }
            case "door.del" -> {
                String n = str(a, "name");
                if (slot.doors.remove(n) == null) throw new Refused("그런 문이 없어요");
                for (var st : d.stages) st.openDoors.remove(n);
                yield "문 " + n + " 지움";
            }
            case "door.link" -> {
                var door = slot.doors.get(str(a, "name"));
                if (door == null) throw new Refused("그런 문이 없어요");
                String g = str(a, "gimmick");
                if (!g.isBlank() && !slot.gimmicks.containsKey(g)) throw new Refused("이름 붙인 기믹이 아니에요: " + g);
                door.gimmick = g;
                yield g.isBlank() ? "문 " + door.name + ": 단계로만 열림" : "문 " + door.name + ": 기믹 " + g + " 이 열리면 열림";
            }
            case "boss.set" -> {
                inWorld(p, d); String n = name(a); String mob = str(a, "mob");
                if (mob.isBlank()) throw new Refused("보스 몹을 골라 주세요");
                slot.bosses.put(n, new Defs.Boss(mob, Defs.Loc.of(p.getLocation())));
                yield "보스 " + n + " = " + mob + " (지금 자리)";
            }
            case "boss.del" -> { if (slot.bosses.remove(str(a, "name")) == null) throw new Refused("그런 보스 자리가 없어요"); yield "보스 자리 지움"; }
            case "point.set" -> { inWorld(p, d); String n = name(a); slot.points.put(n, Defs.Loc.of(p.getLocation())); yield "지점 " + n + " = 지금 자리"; }
            case "point.del" -> { if (slot.points.remove(str(a, "name")) == null) throw new Refused("그런 지점이 없어요"); yield "지점 지움"; }
            case "gimmick.name" -> {
                String n = name(a);
                String id = s.gimmick != null ? s.gimmick : gimmicks.nearest(p.getLocation(), 6);
                var info = id == null ? null : gimmicks.info(id);
                if (info == null) throw new Refused("이름 붙일 기믹을 도구로 우클릭해 주세요");
                slot.gimmicks.put(n, new int[]{Integer.parseInt(info.get("x")), Integer.parseInt(info.get("y")), Integer.parseInt(info.get("z"))});
                yield "기믹 " + info.get("name") + " → 이름 " + n;
            }
            case "gimmick.unname" -> {
                String n = str(a, "name");
                if (slot.gimmicks.remove(n) == null) throw new Refused("그런 기믹 이름이 없어요");
                for (var door : slot.doors.values()) if (door.gimmick.equals(n)) door.gimmick = "";
                yield "기믹 이름 " + n + " 지움";
            }
            case "stage.save" -> {
                int i = num(a, "index", -1);
                var st = i >= 0 && i < d.stages.size() ? d.stages.get(i) : new Defs.Stage();
                String type = str(a, "type");
                if (!List.of("room", "kill", "boss", "gimmick", "reach").contains(type)) throw new Refused("단계 종류를 골라 주세요");
                st.name = str(a, "name").isBlank() ? (d.stages.size() + 1) + "단계" : str(a, "name");
                st.goal = str(a, "goal");
                st.type = type;
                st.target = str(a, "target");
                if (st.target.isBlank()) throw new Refused("단계 대상을 골라 주세요");
                st.count = Math.clamp(num(a, "count", 1), 1, 999);
                st.radius = Math.clamp(dnum(a, "radius", 3), 0.5, 32);
                st.openDoors = new ArrayList<>();
                if (a.has("doors")) for (var e : a.getAsJsonArray("doors")) st.openDoors.add(e.getAsString());
                if (i < 0 || i >= d.stages.size()) d.stages.add(st);
                yield "단계 저장: " + st.name;
            }
            case "stage.del" -> {
                int i = num(a, "index", -1);
                if (i < 0 || i >= d.stages.size()) throw new Refused("그런 단계가 없어요");
                yield "단계 지움: " + d.stages.remove(i).name;
            }
            case "stage.move" -> {
                int i = num(a, "index", -1), j = i + num(a, "dir", 0);
                if (i < 0 || j < 0 || i >= d.stages.size() || j >= d.stages.size()) throw new Refused("더 옮길 수 없어요");
                java.util.Collections.swap(d.stages, i, j);
                yield "단계 순서를 바꿨어요";
            }
            case "copy" -> {
                int to = num(a, "to", 0);
                if (to == s.slot || to < 1 || to > d.instances) throw new Refused("복사할 칸을 골라 주세요");
                var target = d.slot(to);
                if (slot.start == null) throw new Refused(s.slot + "번 칸 시작점이 없어요");
                if (target.start == null) throw new Refused(to + "번 칸 시작점을 먼저 찍어 주세요 (그 칸으로 바꾸고 '시작점')");
                int dx = (int) Math.round(target.start.x() - slot.start.x()), dy = (int) Math.round(target.start.y() - slot.start.y()), dz = (int) Math.round(target.start.z() - slot.start.z());
                Defs.copySlot(slot, target, dx, dy, dz);
                int copied = 0;
                World w = Bukkit.getWorld(d.world);
                if (a.has("gimmicks") && a.get("gimmicks").getAsBoolean() && slot.region() != null && w != null) {
                    var r = slot.region();
                    copied = gimmicks.copyArea(w, new int[]{r.minX(), r.minY(), r.minZ()}, new int[]{r.maxX(), r.maxY(), r.maxZ()}, w, dx, dy, dz);
                }
                yield s.slot + "번 칸 설정을 " + to + "번 칸으로 복사 (" + dx + ", " + dy + ", " + dz + " 만큼 이동" + (copied > 0 ? ", 기믹 " + copied + "개 설치" : "") + ")";
            }
            case "slot.clear" -> { Defs.clearSlot(slot); yield s.slot + "번 칸 설정을 비웠어요"; }
            case "portal.set" -> {
                double r = Math.clamp(dnum(a, "radius", d.portalRadius), 0.5, 16);
                d.portal = Defs.Loc.of(p.getLocation().getBlock().getLocation().add(.5, 0, .5));
                d.portalRadius = r;
                svc().apply(svc().dungeons);
                yield "입장 포탈 = 지금 자리 (반경 " + r + ")";
            }
            case "exit.set" -> { d.exit = Defs.Loc.of(p.getLocation()); yield "나가는 곳 = 지금 자리"; }
            case "regen" -> { d.naturalRegen = a.has("on") && a.get("on").getAsBoolean(); yield "던전 안 체력 자동 회복 " + (d.naturalRegen ? "켬" : "끔"); }
            case "reward.add" -> {
                // 손에 설정 도구를 들고 있으면 보조손(F) 아이템
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir() || isTool(hand)) hand = p.getInventory().getItemInOffHand();
                if (hand.getType().isAir() || isTool(hand)) throw new Refused("보상으로 넣을 아이템을 보조손(F키)에 들고 눌러 주세요");
                String kind = str(a, "kind");
                double chance = kind.equals("random") ? Math.clamp(dnum(a, "chance", 0), 0, 100) : 0;
                if (kind.equals("random") && chance <= 0) throw new Refused("확률(%)을 적어 주세요");
                String ia = svc().items.idOf(hand);
                Defs.Reward r;
                if (ia != null) r = new Defs.Reward(ia, hand.getAmount(), "", chance);
                else if (!hand.hasItemMeta() || hand.isSimilar(new ItemStack(hand.getType()))) r = new Defs.Reward(hand.getType().getKey().toString(), hand.getAmount(), "", chance);
                else { var one = hand.clone(); one.setAmount(1); r = new Defs.Reward("", hand.getAmount(), Base64.getEncoder().encodeToString(one.serializeAsBytes()), chance); }
                (kind.equals("first") ? d.firstRewards : kind.equals("random") ? d.randomRewards : d.baseRewards).add(r);
                yield "보상 추가: " + (r.item().isBlank() ? hand.getType().getKey().toString() : r.item()) + " ×" + r.amount() + (chance > 0 ? " · " + chance + "%" : "");
            }
            case "reward.clear" -> {
                String kind = str(a, "kind");
                (kind.equals("first") ? d.firstRewards : kind.equals("random") ? d.randomRewards : d.baseRewards).clear();
                yield "보상을 비웠어요";
            }
            case "gimmick.place" -> {
                inWorld(p, d);
                String model = str(a, "model");
                int x = num(a, "x", 0), y = num(a, "y", 0), z = num(a, "z", 0), facing = num(a, "facing", 0);
                var feet = p.getWorld().getBlockAt(x, y, z);
                if (feet.getLocation().add(.5, 0, .5).distance(p.getLocation()) > 16) throw new Refused("너무 멀어요 (16칸 안에서)");
                String r = gimmicks.place(p, model, feet, facing);
                if (r.startsWith("!")) throw new Refused(r.substring(1));
                s.gimmick = r;
                yield "기믹 설치: " + model;
            }
            case "gimmick.edit" -> {
                if (s.gimmick == null) throw new Refused("조절할 기믹을 도구로 우클릭해 주세요");
                String op = str(a, "op");
                int sign = num(a, "sign", 1), dx = 0, dz = 0;
                if (op.equals("move")) {
                    // 보는 방향 기준 앞·뒤·왼쪽·오른쪽 한 칸
                    var look = p.getLocation().getDirection().setY(0);
                    int[] f = Math.abs(look.getX()) > Math.abs(look.getZ()) ? new int[]{(int) Math.signum(look.getX()), 0} : new int[]{0, (int) Math.signum(look.getZ())};
                    switch (str(a, "dir")) {
                        case "b" -> { dx = -f[0]; dz = -f[1]; }
                        case "l" -> { dx = f[1]; dz = -f[0]; }
                        case "r" -> { dx = -f[1]; dz = f[0]; }
                        default -> { dx = f[0]; dz = f[1]; }
                    }
                }
                String r = gimmicks.edit(s.gimmick, op, sign, dx, dz);
                if (r.startsWith("!")) throw new Refused(r.substring(1));
                if (op.equals("delete")) {
                    var info = s.gimmick;
                    s.gimmick = null;
                    yield "기믹 " + info + " 삭제";
                }
                yield null;
            }
            default -> throw new Refused("모르는 동작: " + act);
        };
        // 기믹 설치·조절은 기믹 플러그인이 저장한다. 나머지는 dungeons.yml 에.
        if (!act.equals("gimmick.place") && !act.equals("gimmick.edit")) plugin.saveDungeons();
        return done == null ? "" : done;
    }
}
