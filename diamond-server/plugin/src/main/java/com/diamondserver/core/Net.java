package com.diamondserver.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 클라 모드(diamond-ui)와 주고받는 통신. 채널 diamond:main, 내용은 JSON 글자 그대로.
 * 서버 → 클라: state(남은 목표·보유) · shop(상품 목록) · open(화면 열기) · toast(알림)
 * 클라 → 서버: hello · buy · sell · submit
 */
final class Net implements PluginMessageListener {
    static final String CH = "diamond:main";

    private final DiamondPlugin pl;
    private final Set<UUID> modded = new HashSet<>();

    Net(DiamondPlugin pl) { this.pl = pl; }

    boolean hasMod(Player p) { return modded.contains(p.getUniqueId()); }

    void forget(UUID id) { modded.remove(id); }

    private void send(Player p, JsonObject o) {
        p.sendPluginMessage(pl, CH, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    void state(Player p) {
        Store.PData d = pl.store.get(p);
        JsonObject o = new JsonObject();
        o.addProperty("t", "state");
        o.addProperty("goal", d.goal);
        o.addProperty("held", d.held);
        o.addProperty("done", d.done);
        send(p, o);
    }

    void shop(Player p) { send(p, pl.shop.catalog()); }

    void open(Player p, String screen) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "open");
        o.addProperty("screen", screen);
        send(p, o);
    }

    /** 알림. 모드가 있으면 화면 알림으로, 없으면 채팅으로 */
    void toast(Player p, String text, boolean ok) {
        if (hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "toast");
            o.addProperty("text", text);
            o.addProperty("ok", ok);
            send(p, o);
        } else {
            p.sendMessage(Component.text(text, ok ? NamedTextColor.WHITE : NamedTextColor.RED));
        }
    }

    /** 후원 이벤트 알림 (누가 몇 개 → 어떤 이벤트, 대상). 모드가 없으면 채팅으로 */
    void event(Player p, String donor, int count, String name, String kind, String target) {
        if (hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "event");
            o.addProperty("donor", donor);
            o.addProperty("count", count);
            o.addProperty("name", name);
            o.addProperty("kind", kind);
            o.addProperty("target", target);
            send(p, o);
        } else {
            p.sendMessage(Component.text(donor + "님 별풍선 " + count + "개 → " + name + " (" + target + ")", NamedTextColor.AQUA));
        }
    }

    /** 줄 서 있는 이벤트 목록 */
    void queue(Player p, java.util.List<String> lines) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "queue");
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String l : lines) a.add(l);
        o.add("lines", a);
        send(p, o);
    }

    /** 모드가 직접 그리는 화면 효과 (reverse = 조작 반전, blackout = 정전). ms 가 0 이면 끝 */
    void fx(Player p, String name, long ms) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "fx");
        o.addProperty("name", name);
        o.addProperty("ms", ms);
        send(p, o);
    }

    /** 지금 걸려 있는 효과 목록 (id · 이름 · 남은 시간 ms) */
    void effects(Player p, java.util.List<String[]> rows) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "effects");
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String[] r : rows) {
            JsonObject e = new JsonObject();
            e.addProperty("id", r[0]);
            e.addProperty("name", r[1]);
            e.addProperty("ms", Long.parseLong(r[2]));
            a.add(e);
        }
        o.add("list", a);
        send(p, o);
    }

    /** 대상 룰렛: 이름들 사이를 돌다가 winner 에서 멈춘다. 모드가 없으면 채팅으로 결과만 */
    void pick(Player p, String title, java.util.List<String> names, String winner, long ms) {
        if (hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "pick");
            o.addProperty("title", title);
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (String n : names) a.add(n);
            o.add("names", a);
            o.addProperty("winner", winner);
            o.addProperty("ms", ms);
            send(p, o);
        } else {
            p.sendMessage(Component.text(title + " 대상: " + winner, NamedTextColor.AQUA));
        }
    }

    /** 모드가 준비됐다고 알려 오면 지금 상태와 상점 목록을 보낸다 */
    void greet(Player p) {
        modded.add(p.getUniqueId());
        state(p);
        shop(p);
        pl.events.sendQueueTo(p);
        pl.effects.sendListTo(p);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player p, byte[] message) {
        if (!CH.equals(channel)) return;
        try {
            JsonObject o = JsonParser.parseString(new String(message, StandardCharsets.UTF_8)).getAsJsonObject();
            String t = o.has("t") ? o.get("t").getAsString() : "";
            switch (t) {
                case "hello" -> greet(p);
                case "buy" -> pl.shop.buy(p, o.get("id").getAsString(), o.has("qty") ? o.get("qty").getAsInt() : 1);
                case "sell" -> {
                    String mat = o.has("mat") ? o.get("mat").getAsString() : "all";
                    if (mat.equals("all")) pl.shop.sell(p, null);
                    else {
                        Material m = pl.shop.sellMaterial(mat);
                        if (m == null) toast(p, "판매할 수 없는 품목입니다.", false);
                        else pl.shop.sell(p, m);
                    }
                }
                case "submit" -> pl.cmd.submit(p, o.has("qty") ? o.get("qty").getAsLong() : -1);
                default -> { }
            }
        } catch (RuntimeException e) {
            pl.getLogger().warning("모드 신호 처리 실패 (" + p.getName() + "): " + e);
        }
    }
}
