package school.chacademia.tower;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * HuskSync 연결 (2026-11-12 적용 전 점검). HuskSync 는 5대가 소지품·체력을 같이 쓰게 하는 플러그인이라 탑과 두 군데서 부딪힌다:
 * <ul>
 *   <li>저장: 월드 저장 때마다(save_on_world_save) 지금 상태를 공용 DB 에 올린다 → 판 도중이면 "탑 소지품(빈 가방)·탑 체력" 이 올라가
 *       서버가 갑자기 꺼졌을 때 그 상태가 다른 서버까지 퍼진다. 그래서 판 중인 사람의 저장(BukkitDataSaveEvent)은 취소한다.
 *       나갈 때는 탑이 먼저 원래 소지품을 돌려놓은 뒤라 그대로 저장된다.</li>
 *   <li>불러오기: 접속 직후 공용 DB 값을 덮어쓴다 → 남은 맡김을 돌려주는 일은 그 뒤(BukkitSyncCompleteEvent)에 해야 한다.</li>
 * </ul>
 * HuskSync 가 없거나 이벤트 모양이 다르면 조용히 건너뛰고 로그 한 줄 (그때는 예전처럼 시간으로 기다림).
 */
final class HuskHook {
    private HuskHook() {}

    /** @return 연결됐으면 true */
    static boolean install(Plugin plugin, Predicate<UUID> inRun, Consumer<UUID> synced) {
        Plugin husk = Bukkit.getPluginManager().getPlugin("HuskSync");
        if (husk == null || !husk.isEnabled()) { plugin.getLogger().info("HuskSync 없음 — 저장 막기·불러오기 뒤 복구 연결 안 함"); return false; }
        try {
            ClassLoader cl = husk.getClass().getClassLoader();
            Class<? extends Event> save = Class.forName("net.william278.husksync.event.BukkitDataSaveEvent", true, cl).asSubclass(Event.class);
            Class<? extends Event> done = Class.forName("net.william278.husksync.event.BukkitSyncCompleteEvent", true, cl).asSubclass(Event.class);
            var getUserSave = save.getMethod("getUser");
            var getUserDone = done.getMethod("getUser");
            var getUuid = Class.forName("net.william278.husksync.user.User", true, cl).getMethod("getUuid");
            Listener holder = new Listener() {};
            Bukkit.getPluginManager().registerEvent(save, holder, EventPriority.LOWEST, (l, e) -> {
                if (!save.isInstance(e)) return;
                try {
                    UUID id = (UUID) getUuid.invoke(getUserSave.invoke(e));
                    if (inRun.test(id)) ((Cancellable) e).setCancelled(true);
                } catch (ReflectiveOperationException | RuntimeException ex) { plugin.getLogger().warning("HuskSync 저장 검사 실패: " + ex); }
            }, plugin, true);
            Bukkit.getPluginManager().registerEvent(done, holder, EventPriority.MONITOR, (l, e) -> {
                if (!done.isInstance(e)) return;
                try {
                    UUID id = (UUID) getUuid.invoke(getUserDone.invoke(e));
                    if (Bukkit.isPrimaryThread()) synced.accept(id);
                    else Bukkit.getScheduler().runTask(plugin, () -> synced.accept(id));
                } catch (ReflectiveOperationException | RuntimeException ex) { plugin.getLogger().warning("HuskSync 불러오기 알림 실패: " + ex); }
            }, plugin, false);
            plugin.getLogger().info("HuskSync 연결: 판 중 저장 취소 + 불러오기 뒤 남은 맡김 복구");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("HuskSync 연결 실패 (판 중 저장이 막히지 않음 — 확인 필요): " + e);
            return false;
        }
    }
}
