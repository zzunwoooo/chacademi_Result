package school.chacademia.tower;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * 탑 안에서 모드 화면으로 소지품·능력치가 바뀌는 것을 막는다 (2026-11-12 적용 전 점검).
 *
 * <p>명령은 허용 목록으로 막지만, 배낭 쪽 넘기기·우편·상점·강화·연금·아티팩트·장신구 같은 화면은 명령이 아니라 모드 채널(plugin message)로 온다.
 * 배낭 쪽 넘기기는 가방 27칸을 저장된 쪽과 통째로 바꾸므로, 탑 안에서 쓰면 진짜 물건이 탑으로 들어오고 탑 물건이 밖으로 나간다.
 *
 * <p>방법: 잠글 채널에 등록된 다른 플러그인의 처리기를 떼고, 우리 처리기를 대신 건다. 우리 처리기는 판 중인 사람의 요청만 버리고
 * 나머지는 원래 처리기로 그대로 넘긴다. 꺼질 때({@link #uninstall}) 반드시 원래대로 돌려놓는다.
 */
final class BridgeLock {
    private record Swap(Plugin owner, String channel, PluginMessageListener original, PluginMessageListener proxy) {}

    private final Plugin plugin;
    private final List<Swap> swaps = new ArrayList<>();
    /** 사람이 직접 연 화면만 알림을 띄운다. 나머지(지갑·의뢰·칭호처럼 화면이 알아서 주기적으로 보내는 요청)는 조용히 버린다 — 알림이 계속 뜨지 않게. */
    private static final java.util.Set<String> LOUD = java.util.Set.of("magiccodex:bag_request", "magiccodex:mailbox_request", "magiccodex:shop_request",
            "magiccodex:fish_shop_request", "magiccodex:enhance_request", "magiccodex:alchemy_request", "magiccodex:artifact_request",
            "magiccodex:equipment_request", "magiccodex:travel_request", "magiccodex:tp_pick_request", "magiccodex:loot_shop_request", "chacademy:broom_req");

    BridgeLock(Plugin plugin) { this.plugin = plugin; }

    void install(Collection<String> channels, Predicate<Player> locked) {
        var m = Bukkit.getMessenger();
        for (String ch : channels) {
            java.util.Set<org.bukkit.plugin.messaging.PluginMessageListenerRegistration> regs;
            try { regs = m.getIncomingChannelRegistrations(ch); }
            catch (RuntimeException e) { plugin.getLogger().warning("잠글 채널 이름이 잘못됨 (건너뜀): " + ch); continue; }   // 설정에 틀린 이름이 있어도 플러그인이 켜지게
            for (var reg : new ArrayList<>(regs)) {
                if (reg.getPlugin() == plugin) continue;
                Plugin owner = reg.getPlugin();
                PluginMessageListener original = reg.getListener();
                PluginMessageListener proxy = (channel, player, bytes) -> {
                    boolean block;
                    try { block = locked.test(player); } catch (RuntimeException e) { block = false; }
                    if (block) { if (LOUD.contains(channel)) ChacaLink.notify(player, "warning", "tower:ui", "탑 안에서는 쓸 수 없어요", "", 3000); return; }
                    original.onPluginMessageReceived(channel, player, bytes);
                };
                try {
                    m.unregisterIncomingPluginChannel(owner, ch, original);
                    m.registerIncomingPluginChannel(plugin, ch, proxy);
                    swaps.add(new Swap(owner, ch, original, proxy));
                } catch (RuntimeException e) {
                    // 바꿔 걸다 실패하면 원래 처리기를 바로 다시 건다 (채널이 죽은 채로 남으면 안 됨)
                    plugin.getLogger().severe("채널 잠금 실패 " + ch + ": " + e);
                    try { if (m.getIncomingChannelRegistrations(owner, ch).isEmpty()) m.registerIncomingPluginChannel(owner, ch, original); }
                    catch (RuntimeException again) { plugin.getLogger().severe("채널 되돌리기 실패 " + ch + ": " + again); }
                }
            }
        }
        plugin.getLogger().info("탑 안 모드 화면 잠금: 채널 " + swaps.size() + "개");
    }

    void uninstall() {
        var m = Bukkit.getMessenger();
        for (var s : swaps) {
            try { m.unregisterIncomingPluginChannel(plugin, s.channel(), s.proxy()); } catch (RuntimeException ignored) {}
            try { if (s.owner().isEnabled()) m.registerIncomingPluginChannel(s.owner(), s.channel(), s.original()); }
            catch (RuntimeException e) { plugin.getLogger().severe("채널 되돌리기 실패 " + s.channel() + ": " + e); }
        }
        swaps.clear();
    }
}
