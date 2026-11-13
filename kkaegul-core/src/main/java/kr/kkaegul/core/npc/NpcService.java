package kr.kkaegul.core.npc;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * NPC 연결.
 *  방법 A) 아무 엔티티(주민, 아머스탠드, ModelEngine 베이스 등)를 바라보고 /kg npc bind <동작> → 우클릭 시 실행
 *  방법 B) Citizens/FancyNpcs 등의 명령 실행 기능에서 콘솔로 "kg npc run <동작> {player}" 실행
 *  config npc-actions.<동작>: [액션...] 로 새 동작을 추가할 수도 있다.
 */
public final class NpcService {
    private final KkaegulCore plugin;
    public final NamespacedKey KEY;
    private final Map<String, Consumer<Player>> builtins = new LinkedHashMap<>();
    private final FortuneService fortune;
    private final StarterService starter;
    private final WildService wild;

    public NpcService(KkaegulCore plugin) {
        this.plugin = plugin;
        this.KEY = new NamespacedKey(plugin, "npc");
        this.fortune = new FortuneService(plugin);
        this.starter = new StarterService(plugin);
        this.wild = new WildService(plugin);

        builtins.put("profile", starter::openProfile);
        builtins.put("armor", starter::rollArmor);
        builtins.put("realtor", starter::realtor);
        builtins.put("fortune", fortune::use);
        builtins.put("wild", wild::enter);
        builtins.put("wild-random", wild::randomTp);
        builtins.put("jail-exit", p -> plugin.jail().openExit(p));
        builtins.put("jail-exit-lobby", p -> plugin.jail().exit(p, "lobby"));     // 출구 NPC ① 로비로
        builtins.put("jail-exit-return", p -> plugin.jail().exit(p, "return"));   // 출구 NPC ② 원래 자리로(50%)
        builtins.put("jail-game", p -> plugin.jail().playGame(p, false));
        builtins.put("submit", p -> plugin.submit().open(p));        // 렌더러 있으면 제출창, 없으면 바로 일반 제출
        builtins.put("submit-now", p -> plugin.submit().normal(p));
        builtins.put("submit-random", p -> plugin.submit().random(p));
        builtins.put("submit-board", p -> plugin.boards().refresh());
        builtins.put("gacha-1", p -> openGacha(p, "1"));
        builtins.put("gacha-2", p -> openGacha(p, "2"));
        builtins.put("gacha-3", p -> openGacha(p, "3"));
        builtins.put("sell", p -> { if (!plugin.economy().openShop(p, "sell")) plugin.economy().sellAll(p); });   // 렌더러 있으면 상점 판매 탭
        builtins.put("frog-jump", p -> plugin.frogJump().open(p));   // 개구리 점프 기계/NPC
        builtins.put("frog-race", p -> plugin.frogRace().open(p));   // 개구리 경주 배팅 창구
        builtins.put("sell-now", p -> plugin.economy().sellAll(p));
        builtins.put("shop", p -> { if (!plugin.economy().openShop(p, "coin")) plugin.economy().sellAll(p); });
        // 코인 상점: 클릭 1개 / 웅크리고 클릭 10개 (창 없이)
        builtins.put("coin-shop", p -> { if (!plugin.economy().openShop(p, "coin")) plugin.economy().buyCoins(p, p.isSneaking()
                ? plugin.getConfig().getInt("economy.coin.sneak-amount", 10) : plugin.getConfig().getInt("economy.coin.click-amount", 1)); });
        builtins.put("farm", p -> plugin.getServer().dispatchCommand(p, "farmprice"));
        builtins.put("mail", p -> { if (plugin.ui().hasRenderer(p)) plugin.ui().open(p, "mail", plugin.mail().snapshot(p.getUniqueId())); else plugin.mail().claimAll(p); });
        builtins.put("playtime", p -> plugin.rewards().open(p));
        builtins.put("hall", p -> plugin.ranking().open(p));
        builtins.put("trash", this::openTrash);
        builtins.put("lobby", p -> plugin.places().tp(p, "lobby"));
        builtins.put("village", p -> plugin.places().tp(p, "village"));
        builtins.put("gacha-shop", p -> plugin.places().tp(p, "gacha"));
        builtins.put("hall-of-fame", p -> plugin.places().tp(p, "hall-of-fame"));   // 명예의 전당 (/깨굴 위치 저장 hall-of-fame)
        builtins.put("titles", p -> plugin.titles().open(p));
        builtins.put("card-draw", p -> plugin.minigames().cards().open(p));
        builtins.put("wardrobe", p -> plugin.cosmetics().open(p));   // 옷장 (치장품)
        builtins.put("omok", p -> plugin.minigames().duel().help(p));   // 오목 자유 대전 안내 (/오목 신청)
    }

    public FortuneService fortune() { return fortune; }
    public StarterService starter() { return starter; }
    public WildService wild() { return wild; }

    public java.util.Set<String> names() {
        java.util.Set<String> s = new java.util.TreeSet<>(builtins.keySet());
        var sec = plugin.getConfig().getConfigurationSection("npc-actions");
        if (sec != null) s.addAll(sec.getKeys(false));
        return s;
    }

    public boolean run(Player p, String action) {
        List<String> custom = plugin.getConfig().getStringList("npc-actions." + action);
        if (!custom.isEmpty()) { plugin.actions().runAll(p.getUniqueId(), custom, "NPC " + action); return true; }
        Consumer<Player> c = builtins.get(action);
        if (c == null) return false;
        c.accept(p);
        return true;
    }

    public void bind(Entity e, String action) {
        e.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, action);
    }

    public void unbind(Entity e) { e.getPersistentDataContainer().remove(KEY); }

    public String boundAction(Entity e) { return e.getPersistentDataContainer().get(KEY, PersistentDataType.STRING); }

    private void openGacha(Player p, String machine) {
        plugin.ui().open(p, "gacha", plugin.gacha().info(p, machine));
        if (!plugin.ui().hasRenderer(p)) p.sendMessage(Txt.color(Txt.notice("&e[가챠 " + machine + "] &f곡괭이를 손에 들고 &e/가챠 " + machine + " &f로 돌리기")));
    }

    private void openTrash(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, Txt.color("&8쓰레기통 (닫으면 삭제)"));
        p.openInventory(inv);
    }
}
