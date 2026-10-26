package school.magiccodex.paper;

import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import school.magiccodex.protocol.JournalProtocol;
import school.magiccodex.protocol.JournalProtocol.Entry;
import school.magiccodex.protocol.JournalProtocol.Message;

/**
 * 명령 결과·실패 알림.
 * <ul>
 *   <li>플레이어: MagicCodex 알림 카드(일지 채널 kind 4). 실패는 "error" 종류 → 모드가 삐빅 소리를 낸다.</li>
 *   <li>모드가 없는 플레이어: 채팅 글씨 (실패면 서버가 노트블록 소리).</li>
 *   <li>콘솔: 채팅 글씨. 기술적인 원인은 항상 콘솔 로그에만 남긴다.</li>
 * </ul>
 */
final class ChacaFeedback {
    private final MagicCodexBridge plugin;
    private final Map<String, Long> cooldowns = new HashMap<>();

    ChacaFeedback(MagicCodexBridge plugin) { this.plugin = plugin; }

    void ok(CommandSender to, String message) {
        if (to instanceof Player p) notify(p, "system", "cmd-ok:" + UUID.randomUUID(), message, "", 0);
        else to.sendMessage(message);
    }

    void lines(CommandSender to, List<String> lines) {
        main(() -> {
            boolean first = true;
            for (String line : lines) {
                to.sendMessage(Component.text(line, first ? NamedTextColor.GOLD : NamedTextColor.WHITE));
                first = false;
            }
        });
    }

    /** 유저가 고칠 수 있는 실패 (콘솔 로그 없음). */
    void userFail(CommandSender to, String message, String example) {
        if (to instanceof Player p) notify(p, "error", "cmd-fail", message, example == null ? "" : example, 0);
        else to.sendMessage(message + (example == null ? "" : " — " + example));
    }

    /** 원인은 콘솔에만, 유저에게는 userMessage만. */
    void fail(CommandSender to, String userMessage, Throwable cause) { fail(to, userMessage, cause, ""); }

    void fail(CommandSender to, String userMessage, Throwable cause, String input) {
        String who = to instanceof Player p ? plugin.nicknameOf(p) + "(" + p.getName() + ")" : to.getName();
        plugin.getLogger().log(Level.WARNING, "[명령실패] " + who + (input == null || input.isEmpty() ? "" : " /" + input) + " → " + (cause == null ? userMessage : cause), cause);
        userFail(to, userMessage, null);
    }

    void notify(Player to, String kind, String key, String title, String body, long cooldownMillis) {
        main(() -> {
            if (!to.isOnline()) return;
            if (cooldownMillis > 0) {
                String k = to.getUniqueId() + "|" + key;
                long now = System.currentTimeMillis();
                Long until = cooldowns.get(k);
                if (until != null && until > now) return;
                cooldowns.put(k, now + cooldownMillis);
                if (cooldowns.size() > 4096) cooldowns.values().removeIf(v -> v < now);
            }
            String id = key.length() > 80 ? key.substring(0, 80) : key;
            if (to.getListeningPluginChannels().contains(JournalProtocol.REPLY)) {
                var entry = new Entry("quest", "notice", "", JournalProtocol.cut(body == null ? "" : body, 500), "active", true, "", "");
                JournalBridge.send(plugin, to, new Message(4, "", kind, id, JournalProtocol.cut(title, 140), 0, 1, List.of(entry)));
                return;
            }
            NamedTextColor color = switch (kind) { case "error" -> NamedTextColor.RED; case "warning" -> NamedTextColor.GOLD; default -> NamedTextColor.WHITE; };
            to.sendMessage(Component.text(title + (body == null || body.isEmpty() ? "" : " — " + body), color));
            if (kind.equals("error")) {
                to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.6f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (to.isOnline()) to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.6f); }, 3L);
            }
        });
    }

    private void main(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }
}
