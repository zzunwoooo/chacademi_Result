package school.chacademia.gimmick;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * MagicCodexBridge 명령어 허브(school.magiccodex.paper.ChacaApi)를 리플렉션으로 부르는 링크.
 * 이 플러그인은 MagicCodexBridge를 컴파일 의존성으로 두지 않으므로, 같은 이름·같은 매개변수 타입의 정적 메서드를 찾아 부른다.
 * plugin.yml 에 softdepend: [MagicCodexBridge] 가 있어야 허브가 먼저 켜진다.
 * 허브가 없으면 명령은 등록되지 않고(콘솔 경고), 알림은 채팅 글씨로 대신한다.
 */
final class ChacaLink {
    private ChacaLink() {}

    static final String 유저관리 = "유저관리", 아이템관리 = "아이템관리", 상점관리 = "상점관리", 기숙사관리 = "기숙사관리",
            퀘스트관리 = "퀘스트관리", 대화관리 = "대화관리", NPC관리 = "NPC관리", 큐디치관리 = "큐디치관리", 하우징관리 = "하우징관리",
            월드관리 = "월드관리", 던전관리 = "던전관리", 컨텐츠관리 = "컨텐츠관리", 서버관리 = "서버관리", 테스트 = "테스트", 개발 = "개발";
    static final String TARGET = "닉네임";

    @FunctionalInterface interface ThrowingRunnable { void run() throws Exception; }

    private static Class<?> api;

    private static Class<?> api() {
        if (api != null) return api;
        Plugin bridge = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (bridge == null || !bridge.isEnabled()) throw new IllegalStateException("MagicCodexBridge가 켜져 있지 않습니다");
        try { api = Class.forName("school.magiccodex.paper.ChacaApi", true, bridge.getClass().getClassLoader()); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("MagicCodexBridge 버전이 낮습니다 (ChacaApi 없음)", e); }
        return api;
    }

    static boolean ready() {
        try { return (boolean) call("ready", new Class<?>[0]); } catch (RuntimeException e) { return false; }
    }

    private static Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method m = api().getMethod(name, types);
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ChacaApi." + name + " 호출 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T { throw (T) t; }

    static void branch(Plugin owner, String root, LiteralArgumentBuilder<CommandSourceStack> node, String description, String example) {
        call("branch", new Class<?>[]{Plugin.class, String.class, LiteralArgumentBuilder.class, String.class, String.class}, owner, root, node, description, example);
    }

    static void playerCommand(Plugin owner, LiteralArgumentBuilder<CommandSourceStack> node, String description, Collection<String> aliases) {
        call("playerCommand", new Class<?>[]{Plugin.class, LiteralArgumentBuilder.class, String.class, Collection.class}, owner, node, description, aliases);
    }

    static void reload(Plugin owner, String key, String description, ThrowingRunnable action) {
        Class<?> runnable;
        try { runnable = Class.forName("school.magiccodex.paper.ChacaApi$ThrowingRunnable", true, api().getClassLoader()); }
        catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(runnable.getClassLoader(), new Class<?>[]{runnable}, (p, m, a) -> {
            if (m.getName().equals("run")) { action.run(); return null; }
            return switch (m.getName()) { case "hashCode" -> System.identityHashCode(p); case "equals" -> p == a[0]; default -> "ChacaLink.reload(" + key + ")"; };
        });
        call("reload", new Class<?>[]{Plugin.class, String.class, String.class, runnable}, owner, key, description, proxy);
    }

    static void summary(Plugin owner, String label, Function<UUID, String> line) {
        call("summary", new Class<?>[]{Plugin.class, String.class, Function.class}, owner, label, line);
    }

    static void status(Plugin owner, String label, Supplier<String> line) {
        call("status", new Class<?>[]{Plugin.class, String.class, Supplier.class}, owner, label, line);
    }

    @SuppressWarnings("unchecked")
    static ArgumentType<String> player() { return (ArgumentType<String>) call("player", new Class<?>[0]); }

    @SuppressWarnings("unchecked")
    static ArgumentType<String> word(Supplier<Collection<String>> suggestions) {
        return (ArgumentType<String>) call("word", new Class<?>[]{Supplier.class}, suggestions);
    }

    static UUID resolve(CommandContext<CommandSourceStack> ctx, String argument) throws CommandSyntaxException {
        return (UUID) call("resolve", new Class<?>[]{CommandContext.class, String.class}, ctx, argument);
    }

    static Player resolveOnline(CommandContext<CommandSourceStack> ctx, String argument) throws CommandSyntaxException {
        return (Player) call("resolveOnline", new Class<?>[]{CommandContext.class, String.class}, ctx, argument);
    }

    static UUID target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException { return resolve(ctx, TARGET); }

    static Player targetOnline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException { return resolveOnline(ctx, TARGET); }

    static String name(UUID id) {
        try { return (String) call("name", new Class<?>[]{UUID.class}, id); }
        catch (RuntimeException e) { return nicknameFallback(id); }
    }

    /** 허브가 아직 없을 때(켜지는 중 등): 브리지의 한글 닉네임(nicknameOf), 그것도 없으면 계정 이름 (#94). */
    private static String nicknameFallback(UUID id) {
        var p = Bukkit.getOfflinePlayer(id);
        String account = p.getName() == null ? id.toString() : p.getName();
        Plugin bridge = Bukkit.getPluginManager().getPlugin("MagicCodexBridge");
        if (bridge == null || !bridge.isEnabled()) return account;
        try { return bridge.getClass().getMethod("nicknameOf", org.bukkit.OfflinePlayer.class).invoke(bridge, p) instanceof String n && !n.isBlank() ? n : account; }
        catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { return account; }
    }

    static UUID find(String input) { return (UUID) call("find", new Class<?>[]{String.class}, input); }

    static CommandSyntaxException error(String userMessage) {
        return (CommandSyntaxException) call("error", new Class<?>[]{String.class}, userMessage);
    }

    static void ok(CommandSender to, String message) {
        try { call("ok", new Class<?>[]{CommandSender.class, String.class}, to, message); } catch (RuntimeException e) { to.sendMessage(message); }
    }

    static void lines(CommandSender to, List<String> lines) {
        try { call("lines", new Class<?>[]{CommandSender.class, List.class}, to, lines); } catch (RuntimeException e) { lines.forEach(to::sendMessage); }
    }

    static void fail(CommandSender to, String userMessage, Throwable cause) {
        try { call("fail", new Class<?>[]{CommandSender.class, String.class, Throwable.class}, to, userMessage, cause); }
        catch (RuntimeException e) { to.sendMessage(userMessage); if (cause != null) Bukkit.getLogger().log(java.util.logging.Level.WARNING, "[명령실패] " + userMessage, cause); }
    }

    /** 알림 카드. kind: system(정보) · warning(경고) · error(실패, 삐빅) · shop(보상). 같은 key는 cooldownMillis 안에 다시 안 보냄. */
    static void notify(Player to, String kind, String key, String title, String body, long cooldownMillis) {
        try { call("notify", new Class<?>[]{Player.class, String.class, String.class, String.class, String.class, long.class}, to, kind, key, title, body, cooldownMillis); }
        catch (RuntimeException e) { to.sendMessage(title + (body == null || body.isEmpty() ? "" : " — " + body)); }
    }

    @SuppressWarnings("unchecked")
    static CompletableFuture<Void> permission(UUID id, String node, boolean grant) {
        return (CompletableFuture<Void>) call("permission", new Class<?>[]{UUID.class, String.class, boolean.class}, id, node, grant);
    }

    @SuppressWarnings("unchecked")
    static CompletableFuture<List<String>> permissions(UUID id, String prefix) {
        return (CompletableFuture<List<String>>) call("permissions", new Class<?>[]{UUID.class, String.class}, id, prefix);
    }
}
