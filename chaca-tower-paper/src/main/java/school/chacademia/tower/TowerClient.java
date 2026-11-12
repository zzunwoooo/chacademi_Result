package school.chacademia.tower;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * 차카데미 던전 클라 모드(chacademi-dungeon 0.6+)의 카드 화면. 모드가 있으면 카드 3장을 JSON 으로 보내고, 없으면 상자 창(CardMenu).
 * 채널: chacademi:tower (서버 → 클라) · chacademi:tower_act (클라 → 서버, "pick:<번호>:<카드>" 카드 0~2, 다시 뽑기 -2 · "quit" 포기).
 */
final class TowerClient implements PluginMessageListener {
    static final String S2C = "chacademi:tower", C2S = "chacademi:tower_act";

    private final TowerPlugin plugin;
    private int serial;
    /** 사람마다 지금 보여 준 카드 번호 (판이 여러 개라 하나로 두면 다른 사람 카드가 덮어씀). */
    private final java.util.Map<java.util.UUID, Integer> shown = new java.util.concurrent.ConcurrentHashMap<>();

    TowerClient(TowerPlugin plugin) {
        this.plugin = plugin;
        var m = plugin.getServer().getMessenger();
        m.registerOutgoingPluginChannel(plugin, S2C);
        m.registerIncomingPluginChannel(plugin, C2S, this);
    }

    static boolean modded(Player p) { return p.getListeningPluginChannels().contains(S2C); }

    /** 카드 3장을 보낸다. 모드가 없으면 false (상자 창을 쓴다). */
    boolean cards(Player p, Settings s, Run run) {
        if (!modded(p)) return false;
        var o = new JsonObject();
        o.addProperty("t", "cards");
        int id = ++serial;
        shown.put(p.getUniqueId(), id);
        o.addProperty("id", id);
        String title = run.firstPick ? "시작 마법 선택" + (s.startSpellPicks > 1 ? " " + (s.startSpellPicks - run.startPicksLeft + 1) + "/" + s.startSpellPicks : "") : (run.floor - 1) + "층 증강 선택";
        if (!run.picks.isEmpty()) title += " (" + (run.picks.size() + 1) + "번 남음)";
        o.addProperty("title", title);
        o.addProperty("sub", "");
        o.addProperty("left", Math.max(0, run.phaseEndsAt - System.currentTimeMillis()));
        o.addProperty("rerolls", run.rerolls);
        var cards = new JsonArray();
        for (var c : run.offer) {
            var g = s.grades.get(Math.clamp(c.grade(), 0, s.grades.size() - 1));
            var j = new JsonObject();
            j.addProperty("grade", Math.clamp(c.grade(), 0, 3));
            j.addProperty("gradeName", g.name());
            j.addProperty("color", String.format("#%06X", g.color() & 0xFFFFFF));
            j.addProperty("name", c.type() == Cards.Type.SPELL || c.type() == Cards.Type.ARTIFACT ? c.spellName() : c.type().title);
            var lines = new JsonArray();
            Cards.describe(c).forEach(lines::add);
            j.add("lines", lines);
            j.addProperty("icon", icon(c));
            j.addProperty("item", c.type().icon.getKey().toString());
            if (c.type() == Cards.Type.SPELL) j.addProperty("spell", c.spell());   // [기능:탑아이콘] 클라가 그 마법 아이콘을 그림
            cards.add(j);
        }
        o.add("cards", cards);
        try { o.add("build", buildJson(s, run)); }   // 카드 화면의 "내 증강" 버튼이 바로 열 수 있게 같이 보낸다
        catch (RuntimeException e) { plugin.getLogger().warning("내 증강 목록 만들기 실패 (카드는 그대로 보냄): " + e); }
        send(p, o);
        return true;
    }

    // [기능:탑내증강 시작]
    /** 내 증강 창을 연다. 모드가 없으면 false (상자 창을 쓴다). */
    boolean build(Player p, Settings s, Run run) {
        if (!modded(p)) return false;
        var o = buildJson(s, run);
        o.addProperty("t", "build");
        send(p, o);
        return true;
    }

    private static JsonObject buildJson(Settings s, Run run) {
        var o = new JsonObject();
        var list = Cards.owned(s, run);
        int total = 0;
        for (int i = 1; i < list.size(); i++) total += list.get(i).count();
        o.addProperty("sub", run.floor + "층 · 얻은 것 " + total + "개");
        var items = new JsonArray();
        for (var it : list) {
            var g = s.grades.get(Math.clamp(it.grade(), 0, s.grades.size() - 1));
            var j = new JsonObject();
            j.addProperty("grade", Math.clamp(it.grade(), 0, 3));
            j.addProperty("gradeName", g.name());
            j.addProperty("color", String.format("#%06X", g.color() & 0xFFFFFF));
            j.addProperty("name", it.name());
            j.addProperty("count", it.count());
            var lines = new JsonArray();
            it.lines().forEach(lines::add);
            j.add("lines", lines);
            j.addProperty("icon", it.icon());
            j.addProperty("item", it.item().getKey().toString());
            if (!it.spell().isEmpty()) j.addProperty("spell", it.spell());   // [기능:탑아이콘]
            items.add(j);
        }
        o.add("items", items);
        return o;
    }
    // [기능:탑내증강 끝]

// [기능:탑마법키 시작]
/** 탑에서 배운 마법을 모드에 알려 준다 → 마법 키(유저가 쓰던 키 그대로)에 왼쪽부터 채워 씀. spells = null 이면 원래 키로. */
void loadout(Player p, java.util.List<String> spells) {
    if (!modded(p)) return;
    var o = new JsonObject();
    o.addProperty("t", "loadout");
    o.addProperty("active", spells != null);
    var arr = new JsonArray();
    if (spells != null) spells.forEach(arr::add);
    o.add("spells", arr);
    send(p, o);
}
// [기능:탑마법키 끝]

void close(Player p) {
        if (shown.remove(p.getUniqueId()) == null || !modded(p)) return;
        var o = new JsonObject();
        o.addProperty("t", "close");
        send(p, o);
    }

    /** 탑 그림이 있는 카드 (클라 textures/gui/tower). 없으면 "" → 아이템 그림. */
    private static String icon(Cards.Card c) {
        return switch (c.type()) {
            case ARTIFACT -> "relic_" + c.spell().toLowerCase(Locale.ROOT);
            default -> Cards.iconOf(c.type());
        };
    }

    private void send(Player p, JsonObject o) { p.sendPluginMessage(plugin, S2C, o.toString().getBytes(StandardCharsets.UTF_8)); }

    @Override public void onPluginMessageReceived(String channel, Player p, byte[] data) {
        if (!C2S.equals(channel)) return;
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.equals("quit")) { plugin.service().quitButton(p); return; }   // [기능:탑포기] 모드 화면의 포기 버튼 (모드 쪽에서 두 번 눌러 확인함)
        String[] parts = text.split(":");
        if (parts.length != 3 || !parts[0].equals("pick")) return;
        try {
            int id = Integer.parseInt(parts[1]), choice = Integer.parseInt(parts[2]);
            Integer now = shown.get(p.getUniqueId());
            if (now == null || id != now) return; // 지난 카드
            plugin.service().menuClick(p, choice);
        } catch (NumberFormatException ignored) { }
    }
}
