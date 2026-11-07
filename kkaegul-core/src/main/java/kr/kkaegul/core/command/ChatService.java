package kr.kkaegul.core.command;

import kr.kkaegul.core.KkaegulCore;
import kr.kkaegul.core.util.Json;
import kr.kkaegul.core.util.Txt;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 채팅 명령: /공지 /귓 /답 /도움, 눌러서 수락하는 초대 문구, /깨굴 전체지급.
 * 이름은 전부 한글 닉네임.
 */
public final class ChatService {
    private final KkaegulCore plugin;
    private final Map<UUID, UUID> lastPartner = new HashMap<>();
    private final Map<UUID, Long> helpCd = new HashMap<>();

    public ChatService(KkaegulCore plugin) { this.plugin = plugin; }

    private static BaseComponent[] legacy(String s) {
        BaseComponent[] c = TextComponent.fromLegacyText(Txt.color(s));
        return c == null ? new BaseComponent[0] : c;
    }

    /** 문장 + 눌러서 실행하는 버튼 */
    public void invite(Player p, String text, String button, String command, String hover) {
        List<BaseComponent> out = new ArrayList<>(List.of(legacy(text)));
        TextComponent btn = new TextComponent(legacy(button));
        btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(legacy(hover))));
        out.add(btn);
        p.spigot().sendMessage(out.toArray(new BaseComponent[0]));
    }

    // ───────── /공지 ─────────
    public void notice(String msg) {
        plugin.announce("&f" + msg, true);
        for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), "minecraft:block.note_block.bell", 1f, 1.2f);
    }

    // ───────── /귓 /답 ─────────
    public void whisper(Player from, String toName, String msg) {
        Player to = plugin.data().online(toName);
        if (to == null) { plugin.ui().error(from, "&c접속 중인 사람이 아니에요: " + toName); return; }
        if (to.equals(from)) { plugin.ui().error(from, "&c자신에게는 귓속말을 보낼 수 없어요."); return; }
        send(from, to, msg);
    }

    public void reply(Player from, String msg) {
        UUID u = lastPartner.get(from.getUniqueId());
        Player to = u == null ? null : Bukkit.getPlayer(u);
        if (to == null) { plugin.ui().error(from, "&c답장할 상대가 없어요."); return; }
        send(from, to, msg);
    }

    private void send(Player from, Player to, String msg) {
        String clean = Txt.strip(Txt.color(msg));
        String fn = plugin.displayName(from), tn = plugin.displayName(to);
        from.sendMessage(Txt.color("&f{e:whisper} &7[나 → " + tn + "] &f" + clean));
        to.sendMessage(Txt.color("&f{e:whisper} &7[" + fn + " → 나] &f" + clean));
        to.playSound(to.getLocation(), "minecraft:entity.experience_orb.pickup", 0.6f, 1.6f);
        lastPartner.put(from.getUniqueId(), to.getUniqueId());
        lastPartner.put(to.getUniqueId(), from.getUniqueId());
        plugin.getLogger().info("[귓] " + from.getName() + " -> " + to.getName() + ": " + clean);
    }

    // ───────── /도움 ─────────
    public void help(Player from, String msg) {
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("chat.help-cooldown-seconds", 30) * 1000L;
        Long last = helpCd.get(from.getUniqueId());
        if (last != null && now - last < cd) { plugin.ui().error(from, "&c" + ((cd - (now - last)) / 1000 + 1) + "초 뒤에 다시 보낼 수 있어요."); return; }
        helpCd.put(from.getUniqueId(), now);
        String clean = Txt.strip(Txt.color(msg));
        int n = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.hasPermission("kkaegul.admin")) continue;
            n++;
            o.sendMessage(Txt.color("&f{e:help} &e[도움] &f" + plugin.displayName(from) + "&7: &f" + clean));
            o.playSound(o.getLocation(), "minecraft:block.note_block.pling", 1f, 1.4f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (o.isOnline()) o.playSound(o.getLocation(), "minecraft:block.note_block.pling", 1f, 1.8f); }, 3L);
        }
        plugin.getLogger().info("[도움] " + from.getName() + ": " + clean);
        from.sendMessage(Txt.color("&f{e:help} &7운영자에게 도움 요청을 보냈어요." + (n == 0 ? " (지금 접속한 운영자가 없어요)" : "")));
    }

    /** 초대권 사용: 운영자 호출 (운영자 전원에게 알림 + 소리, 전체에 공지) */
    public void callAdmin(Player from) {
        String name = plugin.displayName(from);
        int n = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.hasPermission("kkaegul.admin")) continue;
            n++;
            o.sendMessage(Txt.color("&f{e:help} &e[초대권] &f" + name + "님이 운영자를 호출했어요!"));
            o.playSound(o.getLocation(), "minecraft:block.note_block.pling", 1f, 1.0f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (o.isOnline()) o.playSound(o.getLocation(), "minecraft:block.note_block.pling", 1f, 1.5f); }, 3L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (o.isOnline()) o.playSound(o.getLocation(), "minecraft:block.note_block.pling", 1f, 2.0f); }, 6L);
        }
        plugin.announce("&f" + name + "님이 초대권으로 운영자를 호출했어요!", true);
        plugin.ui().successSound(from);
        if (n == 0) from.sendMessage(Txt.color(Txt.notice("&7지금 접속한 운영자가 없어요. 운영자가 들어오면 확인할게요.")));
        plugin.getLogger().info("[초대권] " + from.getName() + " 운영자 호출");
    }

    // ───────── /깨굴 전체지급 ─────────
    /** 손에 든 아이템을 접속자 모두에게. 인벤토리가 차면 우편함 */
    public String giveAll(Player admin, int count) {
        ItemStack hand = admin.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) return "&c손에 아이템을 들어 주세요.";
        int amount = count > 0 ? count : hand.getAmount();
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack it = hand.clone();
            int left = amount;
            int max = Math.max(1, hand.getType().getMaxStackSize());
            while (left > 0) {
                int k = Math.min(left, max);
                left -= k;
                ItemStack part = it.clone();
                part.setAmount(k);
                Map<Integer, ItemStack> over = p.getInventory().addItem(part);
                for (ItemStack rest : over.values()) plugin.mail().send(p.getUniqueId(), rest, "전체 지급");
            }
            p.playSound(p.getLocation(), "minecraft:entity.item.pickup", 0.8f, 1.2f);
            n++;
        }
        announceItem(hand, amount);
        return "&a" + n + "명에게 지급했어요.";
    }

    /** "모든 유저에게 [아이템] N개가 지급됐어요!" — 아이템 이름은 각자 게임 언어(한글)로 */
    private void announceItem(ItemStack it, int amount) {
        ItemMeta m = it.getItemMeta();
        String custom = m != null && m.hasDisplayName() ? m.getDisplayName() : null;
        String key = translationKey(it);
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<BaseComponent> out = new ArrayList<>(List.of(legacy("&f{e:system} &f모든 유저에게 ")));
            if (custom != null) out.addAll(List.of(legacy("&e" + Txt.strip(custom))));
            else {
                TranslatableComponent t = new TranslatableComponent(key);
                t.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
                out.add(t);
            }
            out.addAll(List.of(legacy("&f " + amount + "개가 지급됐어요!")));
            p.spigot().sendMessage(out.toArray(new BaseComponent[0]));
            if (plugin.ui().hasRenderer(p)) {
                String name = custom != null ? Txt.strip(custom) : "{tr:" + key + "}";
                plugin.ui().send(p, Json.obj().put("type", "banner").put("text", "모든 유저에게 " + name + " " + amount + "개가 지급됐어요!").put("color", "gold"));
            }
        }
        plugin.getLogger().info("[전체지급] " + it.getType() + " x" + amount);
    }

    /** 아이템 번역 키 (item.minecraft.x / block.minecraft.x). 서버 API 가 주면 그걸 씀 */
    public static String translationKey(ItemStack it) {
        for (String mn : new String[]{"getTranslationKey", "translationKey"}) {
            try {
                Object r = it.getClass().getMethod(mn).invoke(it);
                if (r instanceof String s && !s.isBlank()) return s;
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return translationKey(it.getType());
    }

    public static String translationKey(Material m) {
        return (m.isBlock() ? "block.minecraft." : "item.minecraft.") + m.name().toLowerCase(java.util.Locale.ROOT);
    }
}
