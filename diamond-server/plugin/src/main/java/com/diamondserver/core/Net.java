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

    void forget(UUID id) { modded.remove(id); greeted.remove(id); }

    private void send(Player p, JsonObject o) {
        if (!pl.isEnabled() || !p.isOnline()) return;      // 서버가 꺼지는 중에는 보낼 수 없다 (보내면 오류가 나서 뒤의 저장이 끊긴다)
        p.sendPluginMessage(pl, CH, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final java.util.regex.Pattern ITEM_TOKEN = java.util.regex.Pattern.compile("\\{item:(?:[a-z0-9_.-]+:)?([a-z0-9_./-]+)\\}");

    /** 글 안의 아이템 이름 표시 {item:minecraft:xxx} 는 모드가 한글 이름으로 바꾼다. 모드가 없는 사람에게는 영어 이름으로 풀어 준다 */
    static String plain(String s) {
        java.util.regex.Matcher m = ITEM_TOKEN.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group(1).replace('_', ' ')));
        return m.appendTail(out).toString();
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
            p.sendMessage(Component.text(plain(text), ok ? NamedTextColor.WHITE : NamedTextColor.RED));
        }
    }

    /** 후원 이벤트 알림 (누가 몇 개 → 어떤 이벤트, 대상). 모드가 없으면 채팅으로 */
    void event(Player p, String donor, int count, String name, String kind, String icon, String target) {
        if (hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "event");
            o.addProperty("donor", donor);
            o.addProperty("count", count);
            o.addProperty("name", name);
            o.addProperty("kind", kind);
            o.addProperty("icon", icon);
            o.addProperty("target", target);
            send(p, o);
        } else {
            p.sendMessage(Component.text(donor + "님 별풍선 " + count + "개 → " + name + " (" + target + ")", NamedTextColor.AQUA));
        }
    }

    /** 줄 서 있는 이벤트 목록 */
    void queue(Player p, java.util.List<String[]> lines) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "queue");
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String[] l : lines) { JsonObject r = new JsonObject(); r.addProperty("icon", l[0]); r.addProperty("text", l[1]); a.add(r); }
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
            e.addProperty("icon", r[3]);
            e.addProperty("total", Long.parseLong(r[4]));
            a.add(e);
        }
        o.add("list", a);
        send(p, o);
    }

    /** 접속한 사람들의 닉네임 (머리 위 이름표용). 계정 UUID → 닉네임 */
    void names(Player viewer) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "names");
        JsonObject map = new JsonObject();
        for (Player x : org.bukkit.Bukkit.getOnlinePlayers()) if (pl.nicks.has(x)) map.addProperty(x.getUniqueId().toString(), pl.nicks.of(x));
        o.add("map", map);
        send(viewer, o);
    }

    /** 닉네임 창 열기. first = 처음 정하는 것(닫을 수 없음) */
    void openNick(Player p, boolean first, String current, String rule, int max) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "open");
        o.addProperty("screen", "nick");
        o.addProperty("first", first);
        o.addProperty("current", current == null ? "" : current);
        o.addProperty("rule", rule);
        o.addProperty("max", max);
        send(p, o);
    }

    /** 닉네임 창에 거절 이유를 띄운다 */
    void nickError(Player p, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "nickerr");
        o.addProperty("text", reason);
        send(p, o);
    }

    /** 3000 채팅 투표 화면·집계 신호 (state = start · tally · end). 모드가 없으면 채팅으로 */
    void vote(Player p, JsonObject o) {
        if (hasMod(p)) { o.addProperty("t", "vote"); send(p, o); return; }
        String st = o.has("state") ? o.get("state").getAsString() : "";
        if (st.equals("start")) p.sendMessage(Component.text("채팅 투표 시작! 방송 채팅에 1(다이아 폭탄) 또는 2(즉사)를 입력하세요.", NamedTextColor.AQUA));
        if (st.equals("end")) p.sendMessage(Component.text("채팅 투표 결과: " + (o.has("result") ? o.get("result").getAsString() : ""), NamedTextColor.AQUA));
    }

    void closeNick(Player p) {
        if (!hasMod(p)) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "close");
        o.addProperty("screen", "nick");
        send(p, o);
    }

    /** 1000개 룰렛: 상품 이름·아이콘 8칸과 당첨 번호. 모드가 없으면 채팅으로 결과만 */
    void wheel(Player p, String title, java.util.List<String> labels, java.util.List<String> icons, int winner, long ms) {
        if (hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "wheel");
            o.addProperty("title", title);
            com.google.gson.JsonArray a = new com.google.gson.JsonArray(), b = new com.google.gson.JsonArray();
            for (String s : labels) a.add(s);
            for (String s : icons) b.add(s);
            o.add("labels", a);
            o.add("icons", b);
            o.addProperty("winner", winner);
            o.addProperty("ms", ms);
            send(p, o);
        } else {
            p.sendMessage(Component.text(title + ": " + labels.get(Math.max(0, Math.min(labels.size() - 1, winner))), NamedTextColor.AQUA));
        }
    }

    /** 화면 가운데 발표 창 (아이콘 · 제목 · 설명). 모드가 없으면 채팅으로 */
    void announce(Player p, String icon, String title, String desc) {
        if (hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "announce");
            o.addProperty("icon", icon);
            o.addProperty("title", title);
            o.addProperty("desc", desc);
            send(p, o);
        } else {
            p.sendMessage(Component.text(title + " → " + desc, NamedTextColor.AQUA));
        }
    }

    /** 즉사 연출 (화면 가장자리 붉은 효과 + 제목 띠) */
    void death(Player p, String name) {
        if (!hasMod(p)) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "death");
        o.addProperty("name", name);
        send(p, o);
    }

    /** 우편함 목록 (받지 않은 우편). 모드가 없으면 보내지 않는다 */
    void mail(Player p) {
        if (!hasMod(p)) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "mail");
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (Mail.Letter l : pl.mail.list(p.getUniqueId())) {
            String[] icon = pl.mail.icon(l);
            JsonObject e = new JsonObject();
            e.addProperty("id", l.id);
            e.addProperty("title", l.title);
            e.addProperty("from", l.from);
            e.addProperty("text", pl.mail.contentName(l));
            e.addProperty("art", icon[0]);
            e.addProperty("icon", icon[1]);
            a.add(e);
        }
        o.add("list", a);
        send(p, o);
    }

    /** 플레이어 모션 재생 (who 가 name 모션을 한다. name 이 "stop" 이면 멈춤). 보는 사람 모두에게 보낸다 */
    void motion(Player viewer, UUID who, String name) {
        if (!hasMod(viewer)) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "motion");
        o.addProperty("player", who.toString());
        o.addProperty("name", name);
        send(viewer, o);
    }

    /** 대상 룰렛: 이름들 사이를 돌다가 winner 에서 멈춘다. 모드가 없으면 채팅으로 결과만 */
    void pick(Player p, String title, String icon, java.util.List<String> names, java.util.List<String> ids, int winner, long ms) {
        if (hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "pick");
            o.addProperty("title", title);
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (String n : names) a.add(n);
            o.add("names", a);
            com.google.gson.JsonArray b = new com.google.gson.JsonArray();
            for (String n : ids) b.add(n);
            o.add("ids", b);
            o.addProperty("icon", icon);
            o.addProperty("winner", winner);
            o.addProperty("ms", ms);
            send(p, o);
        } else {
            p.sendMessage(Component.text(title + " 대상: " + names.get(Math.max(0, Math.min(names.size() - 1, winner))), NamedTextColor.AQUA));
        }
    }

    /** 모드가 준비됐다고 알려 오면 지금 상태와 상점 목록을 보낸다 */
    void greet(Player p) {
        modded.add(p.getUniqueId());
        state(p);
        shop(p);
        pl.events.sendQueueTo(p);
        pl.effects.sendListTo(p);
        mail(p);
        pl.nicks.sendNames();                // 새로 온 사람에게도, 이미 있던 사람들에게도 닉네임 목록을 다시
        if (!greeted.contains(p.getUniqueId())) {     // 닉네임이 없으면 창을 한 번 띄운다
            greeted.add(p.getUniqueId());
            pl.nicks.promptIfNeeded(p);
        }
    }

    private final Set<UUID> greeted = new HashSet<>();

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
                case "nick" -> pl.nicks.save(p, o.has("name") ? o.get("name").getAsString() : "");
                case "mail.claim" -> pl.mail.claim(p, o.has("id") ? o.get("id").getAsString() : "");
                case "mail.claimall" -> pl.mail.claimAll(p);
                case "vote.tally" -> pl.events.voteTally(p, o.has("a") ? o.get("a").getAsInt() : 0, o.has("b") ? o.get("b").getAsInt() : 0);
                default -> { }
            }
        } catch (RuntimeException e) {
            pl.getLogger().warning("모드 신호 처리 실패 (" + p.getName() + "): " + e);
        }
    }
}
