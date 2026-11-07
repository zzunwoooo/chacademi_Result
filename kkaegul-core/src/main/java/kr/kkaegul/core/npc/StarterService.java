package kr.kkaegul.core.npc;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.data.PlayerData;
import kr.kkaegul.core.item.CustomItem;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Rng;
import kr.kkaegul.core.util.Txt;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스타트 지점 NPC 3종.
 *  1) 프로필 NPC: 이름·보이스 설정 (UI 에서 profile.save 액션으로 받음)
 *  2) 갑옷 랜덤 NPC (스타트팩 = 보호용 갑옷만, 1회): 없음/가죽/구리/사슬/철/금/다이아 + 멘트
 *     ※ 1.21.4 에는 구리 갑옷이 없어서, 구리색으로 염색한 가죽 갑옷을 "구리 갑옷"으로 지급
 *  3) 부동산업자 (1회): 70% 땅 구매권 / 30% "와 개똥손ㅋ" + 작은 증가 룰렛 후 땅 구매권
 *     land.mode: ticket(구매권 아이템만) | plot(구역 목록에서 랜덤 배정 + 명령 실행)
 */
public final class StarterService {
    private final KkaegulCore plugin;

    /** 채팅으로 이름 입력을 기다리는 사람 */
    private final java.util.Set<java.util.UUID> awaitingName = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public StarterService(KkaegulCore plugin) { this.plugin = plugin; }

    public boolean awaitingName(Player p) { return awaitingName.contains(p.getUniqueId()); }

    /** 채팅 스레드에서 호출 → 메인 스레드로 넘겨 처리 */
    public void onNameChat(Player p, String msg) {
        awaitingName.remove(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (msg.trim().equals("취소")) { p.sendMessage(Txt.color(Txt.notice("&7이름 입력을 취소했어요."))); return; }
            if (!plugin.nickname().save(p, msg.trim())) openChatName(p); // 규칙 위반 → 다시 입력
        });
    }

    // ───────── 프로필 ─────────
    public void openProfile(Player p) {
        plugin.nickname().open(p, !plugin.data().get(p).profileDone);
    }

    /** 렌더러 없는 클라: 채팅으로 이름 입력 */
    public void openChatName(Player p) {
        awaitingName.add(p.getUniqueId());
        plugin.ui().center(p, "&e이름을 채팅으로 입력하세요", "&7(취소하려면 '취소')");
    }

    public void saveProfile(Player p, String nickname, String voice) {
        PlayerData d = plugin.data().get(p);
        if (nickname != null && !nickname.isBlank()) {
            // 닉네임 창과 같은 규칙으로 검사 (예전엔 검사 없이 저장돼서 "ㅈㅜㄴㅇㅜ" 같은 이름이 들어갔다)
            String n = Txt.jamo(Txt.strip(Txt.color(nickname)).trim());
            String err = plugin.nickname().check(p, n);
            if (err != null) plugin.ui().error(p, "&f{e:error} &c" + err);
            else { d.nickname = n; plugin.nickname().pushAll(); }
        }
        if (voice != null) d.voiceSetting = voice;
        d.markDirty();
        plugin.titles().applyName(p);
    }

    public void finishProfile(Player p) {
        PlayerData d = plugin.data().get(p);
        d.profileDone = true;
        d.markDirty();
        plugin.ui().center(p, "&a프로필 설정 완료!", "&f갑옷 NPC와 부동산업자를 찾아가 보세요");
        for (String c : plugin.getConfig().getStringList("profile.on-complete-commands"))
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()).replace("{voice}", d.voiceSetting));
    }

    // ───────── 갑옷 뽑기 ─────────
    public void rollArmor(Player p) {
        PlayerData d = plugin.data().get(p);
        if (plugin.getConfig().getBoolean("starter.require-profile", true) && !d.profileDone) { plugin.msg(p, "starter.profile-first"); return; }
        if (d.armorRolled && !p.hasPermission("kkaegul.admin.bypass")) { plugin.msg(p, "starter.already"); return; }
        ConfigurationSection tiers = plugin.getConfig().getConfigurationSection("starter.armor");
        if (tiers == null) return;
        Map<String, Double> w = new LinkedHashMap<>();
        for (String k : tiers.getKeys(false)) w.put(k, tiers.getDouble(k + ".chance", 1));
        String tier = Rng.weighted(w);
        if (tier == null) return;
        d.armorRolled = true;
        d.markDirty();
        String msg = tiers.getString(tier + ".message", "");
        String prefix = tiers.getString(tier + ".prefix", "");
        if (!prefix.isEmpty()) {
            String[] slots = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
            for (String s : slots) plugin.mail().giveOrMail(p, armorPiece(prefix, s, tiers.getString(tier + ".fallback-color", ""), tiers.getString(tier + ".name", "")));
        }
        Material pick = plugin.getConfig().getBoolean("starter.give-pickaxe", false) ? Material.matchMaterial(tiers.getString(tier + ".pickaxe", "")) : null;
        if (pick != null) plugin.mail().giveOrMail(p, new ItemStack(pick));
        plugin.ui().fx(p, "armor_roll", Json.obj().put("tier", tier).put("message", msg).put("name", tiers.getString(tier + ".name", tier)).put("prefix", prefix));
        if (!plugin.ui().hasRenderer(p)) plugin.ui().center(p, msg, tiers.getString(tier + ".name", tier));
        else if (!msg.isBlank()) {   // 카드가 지나간 뒤 한마디를 가운데에 크게
            java.util.UUID id = p.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> { Player now = Bukkit.getPlayer(id); if (now != null) plugin.ui().taunt(now, msg, ""); },
                    plugin.getConfig().getLong("starter.taunt-delay-ticks", 50));
        }
    }

    private ItemStack armorPiece(String prefix, String slot, String fallbackColor, String name) {
        Material m = Material.matchMaterial(prefix + "_" + slot);
        boolean fake = false;
        if (m == null) { m = Material.matchMaterial("LEATHER_" + slot); fake = true; }
        if (m == null) m = Material.LEATHER_CHESTPLATE;
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (fake && meta instanceof LeatherArmorMeta lam && !fallbackColor.isEmpty()) {
            try { lam.setColor(Color.fromRGB(Integer.parseInt(fallbackColor.replace("#", ""), 16))); } catch (RuntimeException ignored) { }
        }
        if (meta != null && fake && !name.isEmpty()) meta.setDisplayName(Txt.color("&6" + name));
        if (meta != null) it.setItemMeta(meta);
        return it;
    }

    // ───────── 부동산업자 ─────────
    public void realtor(Player p) {
        PlayerData d = plugin.data().get(p);
        if (plugin.getConfig().getBoolean("starter.require-profile", true) && !d.profileDone) { plugin.msg(p, "starter.profile-first"); return; }
        if (d.realtorClaimed && !p.hasPermission("kkaegul.admin.bypass")) { plugin.msg(p, "starter.already"); return; }
        d.realtorClaimed = true;
        d.markDirty();
        double ok = plugin.getConfig().getDouble("land.success-chance", 70);
        if (Rng.chance(ok)) {
            plugin.ui().center(p, "&a땅 구매권 획득!", "");
            grantLand(p);
        } else {
            plugin.ui().taunt(p, plugin.getConfig().getString("land.fail-message", "&c와 개똥손ㅋ"), "&f작은 증가 룰렛 후 땅 구매권 지급");
            plugin.roulette().spin(p.getUniqueId(), plugin.getConfig().getString("land.fail-roulette", "tiny_increase"), "부동산 개똥손");
            java.util.UUID id = p.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player now = Bukkit.getPlayer(id);
                if (now != null) grantLand(now);
                else plugin.mail().send(id, plugin.items().create(CustomItem.LAND_TICKET, 1), "부동산 땅 구매권");
            }, 80L);
        }
    }

    private void grantLand(Player p) {
        String mode = plugin.getConfig().getString("land.mode", "ticket");
        if (mode.equalsIgnoreCase("plot")) {
            String plot = assignPlot(p);
            if (plot != null) return;
        }
        plugin.mail().giveOrMail(p, plugin.items().create(CustomItem.LAND_TICKET, 1));
    }

    /** 땅 구매권 아이템 사용 */
    public boolean useLandTicket(Player p) {
        if (!plugin.getConfig().getString("land.mode", "ticket").equalsIgnoreCase("plot")) {
            for (String c : plugin.getConfig().getStringList("land.ticket-use-commands"))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
            return !plugin.getConfig().getStringList("land.ticket-use-commands").isEmpty();
        }
        return assignPlot(p) != null;
    }

    /** 다메다메식: 구역 목록에서 아직 안 쓴 칸 랜덤 배정 */
    private String assignPlot(Player p) {
        List<String> plots = new ArrayList<>(plugin.getConfig().getStringList("land.plots"));
        plots.removeIf(pl -> plugin.global().firsts.containsKey("plot:" + pl));
        String plot = Rng.pick(plots);
        if (plot == null) { plugin.ui().error(p, "&c남은 땅이 없어요. 관리자에게 문의하세요."); return null; }
        plugin.global().firsts.put("plot:" + plot, p.getUniqueId().toString());
        plugin.global().dirty = true;
        for (String c : plugin.getConfig().getStringList("land.assign-commands"))
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()).replace("{plot}", plot));
        plugin.ui().center(p, "&a" + plot + " 구역 당첨!", "&f내 땅이 생겼어요");
        return plot;
    }
}
