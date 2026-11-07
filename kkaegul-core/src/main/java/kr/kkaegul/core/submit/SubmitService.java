package kr.kkaegul.core.submit;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 제출소.
 *  - 일반 제출: 인벤토리의 다이아(블록=9개)를 남은 다이아만큼 제출.
 *  - 랜덤 제출: 그대로 70 / 2배 10 / -30% 10 / -50% 10. 줄어든 만큼은 "기부"로 집계 → 기부 랭킹 TOP10.
 */
public final class SubmitService {
    private final KkaegulCore plugin;

    public SubmitService(KkaegulCore plugin) { this.plugin = plugin; }

    /** 제출에 쓸 수 있는 다이아 = 보유 다이아 + 인벤토리 다이아 */
    public long available(Player p) { return plugin.data().get(p).held + countDiamonds(p); }

    /** 상점 등에서 다이아 결제: 보유 다이아 → 인벤토리 순 */
    public long takeDiamonds(Player p, long need) { return takeAll(p, need); }

    /** 보유 다이아에서 먼저, 모자라면 인벤토리에서 need 개 사용 */
    private long takeAll(Player p, long need) {
        long fromWallet = plugin.wallet().spend(plugin.data().get(p), need);
        long rest = need - fromWallet;
        return fromWallet + (rest > 0 ? take(p, (int) Math.min(Integer.MAX_VALUE, rest)) : 0);
    }

    /** 인벤토리 다이아 개수 (블록 포함, 커스텀 아이템 제외) */
    public int countDiamonds(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || plugin.items().type(it) != null) continue;
            if (it.getType() == Material.DIAMOND) n += it.getAmount();
            else if (it.getType() == Material.DIAMOND_BLOCK) n += it.getAmount() * 9;
        }
        return n;
    }

    /** 다이아를 need 개만큼 인벤토리에서 제거 (블록은 쪼개서 거스름돈을 돌려줌). 실제 제거량 반환 */
    private int take(Player p, int need) {
        PlayerInventory inv = p.getInventory();
        int taken = 0;
        for (int pass = 0; pass < 2 && taken < need; pass++) {
            Material want = pass == 0 ? Material.DIAMOND : Material.DIAMOND_BLOCK;
            for (int i = 0; i < inv.getSize() && taken < need; i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || it.getType() != want || plugin.items().type(it) != null) continue;
                int per = want == Material.DIAMOND ? 1 : 9;
                int units = it.getAmount();
                int useUnits = Math.min(units, (int) Math.ceil((need - taken) / (double) per));
                taken += useUnits * per;
                if (useUnits >= units) inv.setItem(i, null);
                else { it.setAmount(units - useUnits); inv.setItem(i, it); }
            }
        }
        if (taken > need) plugin.mail().giveOrMail(p, new ItemStack(Material.DIAMOND, taken - need));
        return Math.min(taken, need);
    }

    /** 제출창 데이터 */
    public Json info(Player p) {
        PlayerData d = plugin.data().get(p);
        long inv = countDiamonds(p), have = d.held + inv;
        Json chances = Json.obj();
        ConfigurationSection oc = plugin.getConfig().getConfigurationSection("submit.random");
        if (oc != null) for (String k : oc.getKeys(false))
            chances.put(k, Json.obj().put("chance", oc.getDouble(k + ".chance")).put("factor", oc.getDouble(k + ".factor", 1.0)));
        return Json.obj().put("debt", d.debt).put("held", d.held).put("inventory", inv).put("available", have)
                .put("max", Math.max(0, Math.min(have, d.debt))).put("random", chances).put("donated", d.totalDonated);
    }

    /** 제출창 열기 (렌더러 없으면 바로 일반 제출) */
    public void open(Player p) {
        if (plugin.ui().send(p, Json.obj().put("type", "open").put("screen", "submit").put("data", info(p)))) return;
        normal(p, 0);
    }

    /** 거절했을 때도 창을 갱신해서 버튼이 '...' 로 멈춰 있지 않게 */
    private void refresh(Player p) { if (plugin.ui().hasRenderer(p)) plugin.ui().update(p, "submit", info(p)); }

    public void normal(Player p) { normal(p, 0); }

    /** @param want 낼 개수 (0 이하 = 낼 수 있는 만큼 전부) */
    public void normal(Player p, long want) {
        PlayerData d = plugin.data().get(p);
        long have = available(p);
        if (have <= 0) { plugin.msg(p, "submit.none"); refresh(p); return; }
        if (d.debt <= 0) { plugin.msg(p, "submit.no-debt"); refresh(p); return; }
        long amount = Math.min(have, d.debt);
        if (want > 0) amount = Math.min(amount, want);
        long taken = takeAll(p, amount);
        long applied = plugin.diamonds().submitReduce(d, taken);
        plugin.global().totalSubmittedAll += applied;
        plugin.global().dirty = true;
        plugin.ui().fx(p, "submit_normal", Json.obj().put("used", taken).put("credited", applied).put("debt", d.debt));
        if (!plugin.ui().hasRenderer(p))
            plugin.ui().center(p, plugin.getConfig().getString("submit.msg-normal", "&a제출 완료되었습니다."), "&f" + Txt.num(applied) + "개 제출 · 남은 다이아 " + Txt.num(d.debt));
        plugin.ui().update(p, "submit", info(p));
        plugin.hud().refresh(p);
    }

    public void random(Player p) { random(p, 0); }

    public void random(Player p, long want) {
        PlayerData d = plugin.data().get(p);
        long have = available(p);
        if (have <= 0) { plugin.msg(p, "submit.none"); refresh(p); return; }
        if (d.debt <= 0) { plugin.msg(p, "submit.no-debt"); refresh(p); return; }
        ConfigurationSection oc = plugin.getConfig().getConfigurationSection("submit.random");
        Map<String, Double> w = new LinkedHashMap<>();
        if (oc != null) for (String k : oc.getKeys(false)) w.put(k, oc.getDouble(k + ".chance"));
        String out = Rng.weighted(w);
        double factor = out == null || oc == null ? 1.0 : oc.getDouble(out + ".factor", 1.0);
        String msg = out == null || oc == null ? "&a제출 완료되었습니다." : oc.getString(out + ".message", "");

        // 손에 쥔 다이아 중 남은 다이아만큼(배수 고려) 사용
        long needItems = factor > 1.0 ? (long) Math.ceil(d.debt / factor) : d.debt;
        long use = Math.min(have, needItems);
        if (want > 0) use = Math.min(use, want);
        long taken = takeAll(p, use);
        long credited = Math.min(d.debt, (long) Math.floor(taken * factor));
        long donated = factor < 1.0 ? taken - credited : 0;
        long applied = plugin.diamonds().submitReduce(d, credited);
        if (donated > 0) {
            d.totalDonated += donated;
            d.markDirty();
            plugin.global().totalDonatedAll += donated;
        }
        plugin.global().totalSubmittedAll += applied;
        plugin.global().dirty = true;
        plugin.ui().fx(p, "random_submit", Json.obj().put("outcome", out).put("factor", factor).put("used", taken).put("credited", applied)
                .put("donated", donated).put("debt", d.debt).put("message", msg));
        if (!plugin.ui().hasRenderer(p))
            plugin.ui().center(p, msg, "&f" + Txt.num(taken) + "개 → " + Txt.num(applied) + "개 인정 · 남은 다이아 " + Txt.num(d.debt));
        plugin.ui().update(p, "submit", info(p));
        plugin.hud().refresh(p);
        if (donated > 0 && plugin.getConfig().getBoolean("submit.broadcast-donation", true))
            plugin.announce("&d[기부] &f" + plugin.displayName(p) + "님이 다이아 &e" + Txt.num(donated) + "개&f를 기부했습니다. 감사합니다.", false);
    }

    /** 랜덤 제출 기부 랭킹 */
    public List<PlayerData> donors(int limit) {
        List<PlayerData> list = new ArrayList<>();
        for (PlayerData d : plugin.data().all()) if (d.totalDonated > 0) list.add(d);
        list.sort(Comparator.comparingLong((PlayerData d) -> d.totalDonated).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public Json donorBoard() {
        List<Object> rows = new ArrayList<>();
        int rank = 1;
        for (PlayerData d : donors(plugin.getConfig().getInt("submit.board-size", 10)))
            rows.add(Json.obj().put("rank", rank++).put("name", plugin.nickname().display(d)).put("amount", d.totalDonated));
        return Json.obj().put("total", plugin.global().totalDonatedAll).put("rows", rows);
    }
}
