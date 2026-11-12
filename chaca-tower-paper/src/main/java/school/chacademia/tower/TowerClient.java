package school.chacademia.tower;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * 차카데미 던전 클라 모드(chacademi-dungeon 0.6+)의 카드 화면. 모드가 있으면 카드 3장을 JSON 으로 보내고, 없으면 상자 창(CardMenu).
 * 채널: chacademi:tower (서버 → 클라) · chacademi:tower_act (클라 → 서버, "pick:<번호>:<카드>" 카드 0~2, 다시 뽑기 -2).
 */
final class TowerClient implements PluginMessageListener {
    static final String S2C = "chacademi:tower", C2S = "chacademi:tower_act";

    private final TowerPlugin plugin;
    private int serial;
    int shown = -1;

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
        o.addProperty("id", shown = ++serial);
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
            items.add(j);
        }
        o.add("items", items);
        return o;
    }
    // [기능:탑내증강 끝]

    void close(Player p) {
        if (shown < 0 || !modded(p)) return;
        shown = -1;
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
        String[] parts = new String(data, StandardCharsets.UTF_8).split(":");
        if (parts.length != 3 || !parts[0].equals("pick")) return;
        try {
            int id = Integer.parseInt(parts[1]), choice = Integer.parseInt(parts[2]);
            if (id != shown) return; // 지난 카드
            plugin.service().menuClick(p, choice);
        } catch (NumberFormatException ignored) { }
    }
}
