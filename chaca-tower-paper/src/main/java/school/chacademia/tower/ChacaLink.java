package school.chacademia.tower;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * MagicCodexBridge 허브(school.magiccodex.paper.ChacaApi)를 리플렉션으로 부르는 링크 (ChacaDungeon ChacaLink 에서 필요한 것만).
 * 허브가 없으면 알림은 채팅 글씨로 대신한다.
 */
final class ChacaLink {
    private ChacaLink() {}

    private static Class<?> api;

    private static Class<?> api() {
        if (api != null) return api;
        Plugin bridge = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (bridge == null || !bridge.isEnabled()) throw new IllegalStateException("MagicCodexBridge가 켜져 있지 않습니다");
        try { api = Class.forName("school.magiccodex.paper.ChacaApi", true, bridge.getClass().getClassLoader()); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("MagicCodexBridge 버전이 낮습니다 (ChacaApi 없음)", e); }
        return api;
    }

    private static Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method m = api().getMethod(name, types);
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("ChacaApi." + name + ": " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ChacaApi." + name + " 호출 실패", e);
        }
    }

    static String name(UUID id) {
        try { return (String) call("name", new Class<?>[]{UUID.class}, id); }
        catch (RuntimeException e) { var n = Bukkit.getOfflinePlayer(id).getName(); return n == null ? id.toString().substring(0, 8) : n; }
    }

    static void ok(CommandSender to, String message) {
        try { call("ok", new Class<?>[]{CommandSender.class, String.class}, to, message); } catch (RuntimeException e) { to.sendMessage("§f" + message); }
    }

    static void fail(CommandSender to, String message) {
        try { call("fail", new Class<?>[]{CommandSender.class, String.class, Throwable.class}, to, message, null); } catch (RuntimeException e) { to.sendMessage("§c" + message); }
    }

    /** 알림 카드. kind: system(정보) · warning(경고) · error(실패) · shop(보상). */
    static void notify(Player to, String kind, String key, String title, String body, long cooldownMillis) {
        try { call("notify", new Class<?>[]{Player.class, String.class, String.class, String.class, String.class, long.class}, to, kind, key, title, body, cooldownMillis); }
        catch (RuntimeException e) { to.sendMessage("§f" + title + (body == null || body.isEmpty() ? "" : " §7— " + body)); }
    }

    @SuppressWarnings("unchecked")
    static CompletableFuture<Void> permission(UUID id, String node, boolean grant) {
        return (CompletableFuture<Void>) call("permission", new Class<?>[]{UUID.class, String.class, boolean.class}, id, node, grant);
    }
}
