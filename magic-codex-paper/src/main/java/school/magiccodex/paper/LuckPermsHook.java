package school.magiccodex.paper;

import java.util.UUID;
import java.util.function.Consumer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Kept in a separate class so LuckPerms is genuinely optional at runtime. */
final class LuckPermsHook {
    static AutoCloseable connect(JavaPlugin plugin, Consumer<UUID> dirty) {
        var provider = plugin.getServer().getServicesManager().load(LuckPerms.class);
        if (provider == null) return null;
        var subscription = provider.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                event -> dirty.accept(event.getUser().getUniqueId()));
        plugin.getLogger().info("LuckPerms 권한 변경 알림 연결 완료.");
        return subscription::close;
    }
}
