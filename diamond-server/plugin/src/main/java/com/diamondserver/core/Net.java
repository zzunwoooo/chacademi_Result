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

    /** 모드가 준비됐다고 알려 오면 지금 상태와 상점 목록을 보낸다 */
    void greet(Player p) {
        modded.add(p.getUniqueId());
        state(p);
        shop(p);
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
