package kr.kkaegul.core.command;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.item.CustomItem;
import kr.kkaegul.core.item.ItemUseListener;
import kr.kkaegul.core.ui.UiBridge;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 플레이어 동작(버튼) 정의. 명령어와 UI 렌더러(kkaegul:action 채널)가 같은 동작을 공유한다.
 * UI 액션 이름 목록은 docs/UI_PROTOCOL.md 참고.
 */
public final class Actions {
    private final KkaegulCore plugin;

    public Actions(KkaegulCore plugin) { this.plugin = plugin; }

    public void register(UiBridge ui) {
        ui.onAction("hud.request", (p, a) -> plugin.hud().refresh(p));
        ui.onAction("land.open", (p, a) -> plugin.land().open(p));
        ui.onAction("land.release", (p, a) -> plugin.land().release(p));
        ui.onAction("land.trust", (p, a) -> { try { plugin.land().trust(p, java.util.UUID.fromString(a.getOrDefault("uuid", ""))); } catch (IllegalArgumentException ignored) { } });
        ui.onAction("land.untrust", (p, a) -> { try { plugin.land().untrust(p, java.util.UUID.fromString(a.getOrDefault("uuid", ""))); } catch (IllegalArgumentException ignored) { } });
        ui.onAction("casino", (p, a) -> {
            String game = a.getOrDefault("game", "");
            var g = plugin.minigames().game(game.equals("seotda") ? "seotda" : "poker");
            String json = a.getOrDefault("json", "{}");
            if (g instanceof kr.kkaegul.core.casino.holdem.PokerGame pg && game.equals("holdem")) pg.handle(p, json);
            else if (g instanceof kr.kkaegul.core.casino.seotda.SeotdaGame sg && game.equals("seotda")) sg.handle(p, json);
            else kr.kkaegul.core.casino.CasinoSupport.close(plugin, p, game.equals("seotda") ? "seotda" : "holdem");
        });
        ui.onAction("npc", (p, a) -> plugin.npcs().run(p, a.getOrDefault("id", "")));
        ui.onAction("gacha.info", (p, a) -> plugin.ui().open(p, "gacha", plugin.gacha().info(p, a.getOrDefault("machine", "1"))));
        ui.onAction("gacha.spin", (p, a) -> {
            String m = a.getOrDefault("machine", "1");
            plugin.gacha().spin(p, m);
            plugin.ui().update(p, "gacha", plugin.gacha().info(p, m));
        });
        ui.onAction("submit.normal", (p, a) -> plugin.submit().normal(p, parseAmount(a)));
        ui.onAction("submit.random", (p, a) -> plugin.submit().random(p, parseAmount(a)));
        ui.onAction("submit.open", (p, a) -> plugin.submit().open(p));
        ui.onAction("submit.board", (p, a) -> plugin.ui().open(p, "donation_board", plugin.submit().donorBoard()));
        ui.onAction("jail.exit", (p, a) -> plugin.jail().exit(p, a.getOrDefault("choice", "lobby")));
        ui.onAction("jail.game", (p, a) -> plugin.jail().playGame(p, false));
        ui.onAction("shop.sellAll", (p, a) -> { plugin.economy().sellAll(p); shopSync(p); });
        ui.onAction("shop.sellHand", (p, a) -> { plugin.economy().sellHand(p); shopSync(p); });
        ui.onAction("shop.open", (p, a) -> plugin.economy().openShop(p, a.getOrDefault("tab", "coin")));
        ui.onAction("shop.sell", (p, a) -> { plugin.economy().sellItem(p, a.get("id"), parseAmount(a)); shopSync(p); });
        ui.onAction("shop.buy", (p, a) -> { plugin.economy().buyCoins(p, (int) Math.max(1, Math.min(parseAmount(a), plugin.getConfig().getInt("economy.coin.max-per-buy", 100)))); shopSync(p); });
        ui.onAction("jump.open", (p, a) -> plugin.frogJump().open(p));
        ui.onAction("jump.start", (p, a) -> plugin.frogJump().start(p, parseAmount(a)));
        ui.onAction("jump.jump", (p, a) -> plugin.frogJump().jump(p));
        ui.onAction("jump.cashout", (p, a) -> plugin.frogJump().cashout(p));
        ui.onAction("race.open", (p, a) -> plugin.frogRace().open(p));
        ui.onAction("race.bet", (p, a) -> plugin.frogRace().bet(p, parse(a.get("no"), 0), parseAmount(a)));
        ui.onAction("shop.info", (p, a) -> plugin.ui().update(p, "shop", plugin.economy().shopInfo(p)));
        ui.onAction("shop.buyCoins", (p, a) -> { plugin.economy().buyCoins(p, parse(a.get("amount"), 1)); shopSync(p); });
        ui.onAction("shop.toDiamond", (p, a) -> plugin.economy().coinToDiamond(p, parseAmount(a)));
        ui.onAction("shop.exchangeDiamond",(p, a) -> { plugin.economy().exchangeDiamond(p, parse(a.get("amount"), 1)); shopSync(p); });
        ui.onAction("farm.info", (p, a) -> plugin.ui().open(p, "farm", plugin.economy().farmSnapshot()));
        ui.onAction("mail.open", (p, a) -> plugin.ui().open(p, "mail", plugin.mail().snapshot(p.getUniqueId())));
        ui.onAction("mail.claim", (p, a) -> { plugin.mail().claimAll(p); plugin.ui().update(p, "mail", plugin.mail().snapshot(p.getUniqueId())); });
        ui.onAction("playtime.open", (p, a) -> plugin.rewards().open(p));
        ui.onAction("playtime.claim", (p, a) -> plugin.rewards().claim(p));
        ui.onAction("playtime.exchange", (p, a) -> plugin.rewards().exchange(p));
        ui.onAction("title.open", (p, a) -> plugin.titles().open(p));
        ui.onAction("title.select", (p, a) -> plugin.titles().select(p, a.getOrDefault("id", "none")));
        ui.onAction("hall.open", (p, a) -> plugin.ranking().open(p));
        ui.onAction("nickname.save", (p, a) -> plugin.nickname().save(p, a.getOrDefault("name", "")));
        ui.onAction("nickname.check", (p, a) -> plugin.nickname().onCheck(p, a.getOrDefault("name", "")));
        ui.onAction("nickname.open", (p, a) -> plugin.nickname().open(p, !plugin.data().get(p).profileDone));
        ui.onAction("profile.save", (p, a) -> plugin.npcs().starter().saveProfile(p, a.get("name"), a.get("voice")));
        ui.onAction("profile.done", (p, a) -> {
            plugin.npcs().starter().saveProfile(p, a.get("name"), a.get("voice"));
            plugin.npcs().starter().finishProfile(p);
        });
        ui.onAction("ticket.userTp", (p, a) -> userTp(p, a.getOrDefault("target", "")));
        ui.onAction("ticket.gauge", (p, a) -> gaugeTicket(p, a.getOrDefault("dir", "up")));
        ui.onAction("fortune.use", (p, a) -> plugin.npcs().fortune().use(p));
        ui.onAction("wild.enter", (p, a) -> plugin.npcs().wild().enter(p));
        ui.onAction("wild.random", (p, a) -> plugin.npcs().wild().paidRandomTp(p));
        ui.onAction("card.pick", (p, a) -> plugin.minigames().cards().pick(p, parse(a.get("index"), 0)));
        ui.onAction("me", (p, a) -> plugin.menu().open(p, "me"));
        // ── HUD 드롭다운 ──
        ui.onAction("withdraw.open", (p, a) -> plugin.ui().open(p, "withdraw", withdrawInfo(p)));
        ui.onAction("withdraw", (p, a) -> {
            String amt = a.getOrDefault("amount", "1");
            long n = amt.equals("all") ? 0 : Math.max(1, parse(amt, 1));
            plugin.wallet().withdraw(p, n);
            plugin.ui().update(p, "withdraw", withdrawInfo(p));
        });
        ui.onAction("discord.link", (p, a) -> plugin.discord().link(p));
        ui.onAction("move", (p, a) -> move(p, a.getOrDefault("dest", "")));
        ui.onAction("menu.open", (p, a) -> plugin.menu().open(p, a.getOrDefault("tab", "me")));
        ui.onAction("trash.open", (p, a) -> plugin.npcs().run(p, "trash"));   // 이동 하위 목록의 쓰레기통
    }

    /** 처리 뒤(거절 포함) 상점 창을 항상 갱신 → 버튼이 "..." 로 멈춰 있지 않게 */
    private void shopSync(Player p) { if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "shop", plugin.economy().shopInfo(p)); }

    public void userTp(Player p, String target) {
        Player t = plugin.data().online(target);
        if (t == null || t.equals(p)) { plugin.ui().error(p, "&c접속 중인 다른 유저 이름을 입력하세요."); return; }
        if (plugin.data().get(p).inJail) { plugin.ui().error(p, "&c감옥에서는 쓸 수 없어요."); return; }
        if (!ItemUseListener.consumeFromInventory(plugin, p, CustomItem.TELEPORT_TICKET)) { plugin.ui().error(p, "&c유저 텔포권이 없어요."); return; }
        plugin.harass().saveBack(p);
        p.teleport(t.getLocation());
        plugin.ui().center(p, "&b" + plugin.displayName(t) + "님에게 이동", "");
    }

    public void gaugeTicket(Player p, String dir) {
        if (!ItemUseListener.consumeFromInventory(plugin, p, CustomItem.GAUGE_TICKET)) { plugin.ui().error(p, "&c게이지 변동권이 없어요."); return; }
        int step = plugin.getConfig().getInt("items.gauge_ticket.step", 10);
        boolean up = dir.equalsIgnoreCase("up") || dir.equals("위");
        PlayerData d = plugin.data().get(p);
        plugin.gauge().add(d, up ? step : -step);
        plugin.gauge().announce(p, up ? step : -step);
    }

    static int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    private static long parseAmount(java.util.Map<String, String> a) {
        try { return Long.parseLong(a.getOrDefault("amount", "0").trim()); } catch (NumberFormatException e) { return 0; }
    }

    private kr.kkaegul.core.util.Json withdrawInfo(org.bukkit.entity.Player p) {
        return kr.kkaegul.core.util.Json.obj().put("held", plugin.data().get(p).held);
    }

    private final java.util.Map<java.util.UUID, Long> moveCool = new java.util.HashMap<>();

    /** 드롭다운 이동: 깜빵·미니게임 중엔 막고, 5초 쿨타임 */
    private void move(org.bukkit.entity.Player p, String dest) {
        if (plugin.data().get(p).inJail) { plugin.ui().error(p, "&c깜빵에서는 이동할 수 없어요."); return; }
        if (plugin.minigames().gameOf(p) != null) { plugin.ui().error(p, "&c미니게임 중에는 이동할 수 없어요."); return; }
        long now = System.currentTimeMillis(), cd = plugin.getConfig().getLong("menu.move-cooldown-seconds", 5) * 1000;
        Long last = moveCool.get(p.getUniqueId());
        if (last != null && now - last < cd) { plugin.ui().error(p, "&c" + ((cd - (now - last)) / 1000 + 1) + "초 뒤에 다시 이동할 수 있어요."); return; }
        if (dest.equals("village")) {   // 마을 = 내 집 (처음 산 땅). 로비에서만, 땅이 없으면 알림만
            org.bukkit.Location lobby = plugin.places().get("lobby");
            if (lobby != null && lobby.getWorld() != null && !lobby.getWorld().equals(p.getWorld())) {
                plugin.ui().error(p, "&c내 집 이동은 로비에서만 할 수 있어요.");
                return;
            }
            if (plugin.land().home(p)) moveCool.put(p.getUniqueId(), now);
            return;
        }
        if (dest.equals("lobby") && !nearHomeOrWild(p)) {   // 로비 이동은 내 집·야생 스폰 30칸 안에서만
            int r = plugin.getConfig().getInt("menu.lobby-move-radius", 30);
            plugin.ui().error(p, "&c로비 이동은 내 집이나 야생 스폰 " + r + "칸 안에서만 할 수 있어요. (귀환권으로 언제든 로비로)");
            return;
        }
        if (dest.equals("wild") && plugin.npcs().wild().inWild(p)) {   // 야생에서 누르면 '야생' = 랜덤 장거리 이동 (다이아 50)
            moveCool.put(p.getUniqueId(), now);
            plugin.npcs().wild().paidRandomTp(p);
            return;
        }
        String npc = switch (dest) { case "lobby" -> "lobby"; case "gacha_shop" -> "gacha-shop"; case "wild" -> "wild"; default -> null; };
        if (npc == null) return;
        moveCool.put(p.getUniqueId(), now);
        plugin.npcs().run(p, npc);
    }

    /** 내 집(처음 산 땅 한가운데) 또는 야생 스폰 근처인지 */
    private boolean nearHomeOrWild(org.bukkit.entity.Player p) {
        double r = plugin.getConfig().getDouble("menu.lobby-move-radius", 30), r2 = r * r;
        org.bukkit.Location at = p.getLocation();
        org.bukkit.Location home = plugin.land().homeSpot(p.getUniqueId());
        if (home != null && home.getWorld() != null && home.getWorld().equals(at.getWorld()) && flat(home, at) <= r2) return true;
        org.bukkit.Location wild = plugin.places().get("wild");
        if (wild == null) {
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(plugin.getConfig().getString("wild.world", "world"));
            wild = w == null ? null : w.getSpawnLocation();
        }
        return wild != null && wild.getWorld() != null && wild.getWorld().equals(at.getWorld()) && flat(wild, at) <= r2;
    }

    private static double flat(org.bukkit.Location a, org.bukkit.Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
