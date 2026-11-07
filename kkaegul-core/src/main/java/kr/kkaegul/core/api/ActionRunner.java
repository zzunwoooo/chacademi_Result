package kr.kkaegul.core.api;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.item.CustomItem;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * config 에 적힌 한 줄짜리 액션을 실행한다. API·NPC·보상 등이 모두 이걸 쓴다.
 *
 * 대상 1명 액션:
 *   roulette <종류>            증감 룰렛 (config roulette.<종류>)
 *   box <normal|premium|lucky|random> [개수]
 *   set <normal|premium>       상자 세트(1111/5555)
 *   item <아이템id> [최소-최대]
 *   food                       음식(콜라/깨깨로) 3~11개
 *   mending [개수]             수선 마법책
 *   jail | harass | tour | death | invite | mine
 *   coins <a-b> | money <a-b> | gauge <+n|-n|a~b>
 *   msg <텍스트> | center <텍스트>|<서브> | sound <사운드id>
 *   cmd <콘솔 명령, {player} 치환>
 *   chance <퍼센트> <액션...>
 * 대상 없음 / 전체:
 *   all <액션...>              접속자 전원에게
 *   random-player <액션...>    접속자 1명 랜덤
 *   harass-all                 섭주 전체 괴롭히기
 *   fever <diamond|box> [초]
 *   broadcast <텍스트>
 */
public final class ActionRunner {
    private final KkaegulCore plugin;
    /** all / random-player 로 들어온 액션 = 섭주(전체) 쪽. 0 이면 개인 API */
    private int serverScope;

    public ActionRunner(KkaegulCore plugin) { this.plugin = plugin; }

    public void runAll(UUID target, List<String> actions, String reason) {
        for (String a : actions) run(target, a, reason);
    }

    public void run(UUID target, String line, String reason) {
        if (line == null || line.isBlank()) return;
        String[] parts = line.trim().split("\\s+");
        String op = parts[0].toLowerCase();
        String[] a = Arrays.copyOfRange(parts, 1, parts.length);
        String rest = String.join(" ", a);
        try {
            switch (op) {
                // ── 전체/무대상 ──
                case "all" -> {
                    serverScope++;
                    try { for (Player p : List.copyOf(Bukkit.getOnlinePlayers())) run(p.getUniqueId(), rest, reason); } finally { serverScope--; }
                }
                case "random-player" -> {
                    List<Player> list = new ArrayList<>(Bukkit.getOnlinePlayers());
                    Player p = Rng.pick(list);
                    serverScope++;
                    try { if (p != null) run(p.getUniqueId(), rest, reason); } finally { serverScope--; }
                }
                case "harass-all" -> plugin.harass().serverHarass();
                case "fever" -> {
                    int sec = a.length > 1 ? parseInt(a[1], 0) : 0;
                    if (a.length > 0 && a[0].equalsIgnoreCase("box")) plugin.fever().startBox(sec);
                    else plugin.fever().startDiamond(sec);
                }
                case "broadcast" -> plugin.announce(rest, true);
                case "chance" -> {
                    if (a.length >= 2 && Rng.chance(parseDouble(a[0], 0)))
                        run(target, String.join(" ", Arrays.copyOfRange(a, 1, a.length)), reason);
                }
                case "cmd" -> {
                    String name = target == null ? "" : plugin.data().get(target).name;
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rest.replace("{player}", name));
                }
                default -> runTargeted(target, op, a, rest, reason);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("액션 실행 실패 '" + line + "': " + e);
        }
    }

    private void runTargeted(UUID target, String op, String[] a, String rest, String reason) {
        if (target == null) {
            plugin.getLogger().warning("대상이 필요한 액션: " + op);
            return;
        }
        PlayerData d = plugin.data().get(target);
        Player p = Bukkit.getPlayer(target);
        switch (op) {
            case "roulette" -> plugin.roulette().spin(target, a.length > 0 ? a[0] : "increase", reason);
            case "box" -> {
                String kind = a.length > 0 ? a[0] : "normal";
                int n = a.length > 1 ? parseInt(a[1], 1) : 1;
                if (kind.equalsIgnoreCase("random")) {
                    for (int i = 0; i < n; i++) plugin.boxes().giveBox(target, Rng.chance(50) ? "premium" : "normal", 1);
                } else plugin.boxes().giveBox(target, kind, n);
            }
            case "set" -> plugin.boxes().giveSet(target, a.length > 0 ? a[0] : "normal");
            case "item" -> {
                CustomItem ci = a.length > 0 ? CustomItem.byId(a[0]) : null;
                int n = a.length > 1 ? parseRange(a[1]) : 1;
                ItemStack it;
                if (ci != null) it = plugin.items().create(ci, n);
                else {
                    Material m = a.length > 0 ? Material.matchMaterial(a[0]) : null;
                    if (m == null) { plugin.getLogger().warning("알 수 없는 아이템: " + rest); return; }
                    it = new ItemStack(m, n);
                }
                long dia = plugin.wallet().enabled() ? plugin.wallet().valueOf(new ItemStack(it.getType(), n)) : 0;
                if (dia > 0) { plugin.wallet().deposit(d, dia, p); return; } // 다이아는 보유 다이아로
                giveSplit(target, it, n);
            }
            case "mending" -> giveSplit(target, plugin.items().mendingBook(), a.length > 0 ? parseRange(a[0]) : 1);
            case "food" -> {
                int n = Rng.range(plugin.getConfig().getInt("food.min", 3), plugin.getConfig().getInt("food.max", 11));
                CustomItem type = plugin.clock().isPeperoSeason() ? CustomItem.KKAEKKAERO : CustomItem.COLA;
                giveSplit(target, plugin.items().create(type, 1), n);
                if (p != null) plugin.ui().center(p, (type == CustomItem.COLA ? "&c콜라 " : "&6깨깨로 ") + n + "개", "");
            }
            // 보물광산 안: 개인 API 감옥·투어(이동)는 안 먹힘 (섭주 쪽은 그대로)
            case "jail" -> { if (p != null && minedOut(p, "감옥")) return; if (p != null) plugin.jail().send(p, reason); else queue(d, "jail"); }
            case "harass" -> { if (p != null) plugin.harass().personal(p); else queue(d, "harass"); }
            case "tour" -> { if (p != null && !minedOut(p, "투어")) plugin.harass().tour(p); }
            case "death" -> { if (p != null) plugin.harass().instantDeath(p); else queue(d, "death"); }
            case "invite" -> { } // 초대권 기능 제거 (아이템·보상은 유지)
            case "mine" -> { if (p != null) plugin.mine().sendTreasure(p, true); }
            case "mine-return" -> { if (p != null) plugin.mine().returnAfterRelog(p); }
            case "coins" -> {
                long v = parseRange(a.length > 0 ? a[0] : "1");
                d.coins += v; d.markDirty();
                if (p != null) plugin.msg(p, "coin.got", "amount", Txt.num(v));
            }
            case "money" -> {
                long v = parseRange(a.length > 0 ? a[0] : "1");
                d.money += v; d.markDirty();
                if (p != null) plugin.msg(p, "money.got", "amount", Txt.num(v));
            }
            case "gauge" -> {
                String s = a.length > 0 ? a[0] : "0";
                int delta;
                if (s.contains("~")) {
                    String[] mm = s.split("~", 2);
                    delta = Rng.range(parseInt(mm[0].replace("+", ""), 0), parseInt(mm[1].replace("+", ""), 0));
                } else delta = parseInt(s.replace("+", ""), 0);
                plugin.gauge().add(d, delta);
                if (p != null) plugin.msg(p, "gauge.changed", "delta", (delta >= 0 ? "+" : "") + delta, "value", plugin.gauge().value());
            }
            case "msg" -> { if (p != null) p.sendMessage(Txt.color(rest)); }
            case "center" -> {
                if (p != null) {
                    String[] ts = rest.split("\\|", 2);
                    plugin.ui().center(p, ts[0], ts.length > 1 ? ts[1] : "");
                }
            }
            case "sound" -> { if (p != null) p.playSound(p.getLocation(), rest, 1f, 1f); }
            default -> plugin.getLogger().warning("알 수 없는 액션: " + op);
        }
    }

    private boolean minedOut(Player p, String what) {
        if (serverScope > 0 || !plugin.mine().isInMine(p)) return false;
        plugin.ui().actionbar(p, "&7광산 안이라 " + what + "은(는) 적용되지 않았어요");
        plugin.getLogger().info("[광산] " + plugin.displayName(p) + " 개인 " + what + " 막음 (광산 안)");
        return true;
    }

    private void queue(PlayerData d, String action) {
        d.pendingApi.add("@" + action);
        d.markDirty();
    }

    /** 최대 스택 단위로 나눠서 인벤/우편함에 */
    private void giveSplit(UUID target, ItemStack base, int total) {
        int max = Math.max(1, base.getMaxStackSize());
        while (total > 0) {
            int n = Math.min(max, total);
            ItemStack it = base.clone();
            it.setAmount(n);
            Player p = Bukkit.getPlayer(target);
            if (p != null) plugin.mail().giveOrMail(p, it);
            else plugin.mail().send(target, it, "오프라인 보상");
            total -= n;
        }
    }

    public static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    public static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim().replace("%", "")); } catch (RuntimeException e) { return def; }
    }

    /** "3-11" → 랜덤, "5" → 5 */
    public static int parseRange(String s) {
        if (s == null) return 1;
        s = s.trim();
        int dash = s.indexOf('-', 1);
        if (dash > 0) return Rng.range(parseInt(s.substring(0, dash), 1), parseInt(s.substring(dash + 1), 1));
        return parseInt(s, 1);
    }
}
