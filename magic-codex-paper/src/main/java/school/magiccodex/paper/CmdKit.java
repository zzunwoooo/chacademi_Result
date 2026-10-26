package school.magiccodex.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.*;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 명령어 정리(2026-10-20) 공용 도우미: 이 모듈의 갈래 등록 코드가 같이 쓴다.
 * 허브({@link ChacaApi})가 없을 때(단위 테스트에서 브리지를 직접 만들 때 등)는 등록을 건너뛴다.
 */
final class CmdKit {
    private CmdKit() {}

    static final String CONFIRM = "확인";
    static final String CONFIRM_MESSAGE = "되돌릴 수 없어요. 끝에 '확인'을 붙여 다시 입력해 주세요";

    @FunctionalInterface interface Body { void run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException; }

    /** 등록 묶음 실행. 허브가 없으면 아무것도 하지 않고, 등록 중 오류는 콘솔에만 남긴다. */
    static void register(Plugin plugin, String what, Runnable registration) {
        if (!ChacaApi.ready()) return;
        try { registration.run(); }
        catch (RuntimeException | LinkageError error) { plugin.getLogger().warning("[명령어] " + what + " 등록 실패: " + error); }
    }

    static Command<CommandSourceStack> run(Body body) {
        return ctx -> { body.run(ctx); return Command.SINGLE_SUCCESS; };
    }

    static LiteralArgumentBuilder<CommandSourceStack> literal(String name) { return Commands.literal(name); }

    static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(String name, ArgumentType<T> type) { return Commands.argument(name, type); }

    static RequiredArgumentBuilder<CommandSourceStack, String> word(String name, Supplier<Collection<String>> suggestions) {
        return Commands.argument(name, ChacaApi.word(suggestions));
    }

    @FunctionalInterface interface Candidates { Collection<String> get(CommandContext<CommandSourceStack> ctx) throws Exception; }

    /** 앞 칸(대상 유저·상점 등)에 따라 후보가 달라지는 이름 칸. */
    static RequiredArgumentBuilder<CommandSourceStack, String> wordIn(String name, Candidates candidates) {
        return Commands.argument(name, ChacaApi.word(List::of)).suggests((ctx, builder) -> {
            Collection<String> all;
            try { all = candidates.get(ctx); } catch (Exception | LinkageError e) { all = List.of(); }
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            int count = 0;
            for (String s : all) {
                if (s == null || s.isEmpty() || s.indexOf(' ') >= 0 || !s.toLowerCase(Locale.ROOT).startsWith(typed)) continue;
                builder.suggest(s);
                if (++count >= 40) break;
            }
            return builder.buildFuture();
        });
    }

    /** 자동완성 단계에서 /유저관리 대상 (못 찾으면 null). */
    static UUID targetOf(CommandContext<CommandSourceStack> ctx) {
        try { return ChacaApi.find(ctx.getArgument(ChacaApi.TARGET, String.class)); }
        catch (IllegalArgumentException missing) { return null; }
    }

    static RequiredArgumentBuilder<CommandSourceStack, String> player(String name) { return Commands.argument(name, ChacaApi.player()); }

    static String text(CommandContext<CommandSourceStack> ctx, String name) { return ctx.getArgument(name, String.class); }

    static CommandSender sender(CommandContext<CommandSourceStack> ctx) { return ctx.getSource().getSender(); }

    /** 명령을 친 사람이 플레이어여야 할 때. */
    static Player self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player p) return p;
        throw ChacaApi.error("게임 안에서만 쓸 수 있어요");
    }

    /** [닉네임] 칸: 적었으면 그 유저(접속 중), 안 적었으면 명령을 친 사람. */
    static Player onlineOrSelf(CommandContext<CommandSourceStack> ctx, String argument) throws CommandSyntaxException {
        return has(ctx, argument) ? ChacaApi.resolveOnline(ctx, argument) : self(ctx);
    }

    static boolean has(CommandContext<CommandSourceStack> ctx, String argument) {
        try { ctx.getArgument(argument, Object.class); return true; }
        catch (IllegalArgumentException missing) { return false; }
    }

    /** 끝에 "확인"을 붙여야 실행되는 칸. 확인 없이 치면 안내 알림. */
    static <B extends ArgumentBuilder<CommandSourceStack, B>> B confirm(B node, Body action) {
        return node.executes(ctx -> { throw ChacaApi.error(CONFIRM_MESSAGE); })
                .then(Commands.literal(CONFIRM).executes(run(action)));
    }

    /** 유저가 고칠 수 있는 실패를 비동기 콜백에서 알릴 때 (콘솔 로그 없음). */
    static void userFail(CommandSender to, String message) {
        if (to instanceof Player p && ChacaApi.ready()) ChacaApi.notify(p, "error", "cmd-fail", message, "", 0);
        else to.sendMessage(message);
    }

    static void ok(CommandSender to, String message) {
        if (ChacaApi.ready()) ChacaApi.ok(to, message); else to.sendMessage(message);
    }

    static void lines(CommandSender to, List<String> lines) {
        if (ChacaApi.ready()) ChacaApi.lines(to, lines); else lines.forEach(to::sendMessage);
    }

    static void fail(CommandSender to, String message, Throwable cause) {
        if (ChacaApi.ready()) ChacaApi.fail(to, message, cause);
        else { Bukkit.getLogger().warning("[명령실패] " + message + " → " + cause); to.sendMessage(message); }
    }

    /** 액션바 대신 쓰는 알림 카드. 허브가 없으면 채팅 글씨. */
    static void notify(Player to, String kind, String key, String title, String body, long cooldownMillis) {
        if (ChacaApi.ready()) { ChacaApi.notify(to, kind, key, title, body, cooldownMillis); return; }
        to.sendMessage(Component.text(title + (body == null || body.isEmpty() ? "" : " — " + body),
                kind.equals("warning") || kind.equals("error") ? NamedTextColor.GOLD : NamedTextColor.WHITE));
    }

    @FunctionalInterface interface Check<T> { T get(); }

    /** 입력 검사(IllegalArgumentException, 한글 문구)를 유저가 고칠 수 있는 실패로 바꾼다. 이 모듈의 검사 코드에만 쓴다. */
    static <T> T user(Check<T> check) throws CommandSyntaxException {
        try { return check.get(); }
        catch (IllegalArgumentException e) { throw ChacaApi.error(e.getMessage() == null || e.getMessage().isBlank() ? "입력값을 확인해 주세요" : e.getMessage()); }
    }

    /** 이름(띄어쓰기 없앤 모양 포함)이나 ID로 목록에서 찾는다. 못 찾거나 겹치면 유저 실패. */
    static String pick(Map<String, String> catalog, String input, String what) throws CommandSyntaxException {
        String id = AdminCommandRules.resolve(catalog, input);
        if (id == null) throw ChacaApi.error(what + "을(를) 찾을 수 없어요: " + input);
        return id;
    }

    /** 표시용 한글 닉네임. 허브가 준비되기 전에도 닉네임 저장소에서 먼저 찾고, 그래도 없으면 계정 이름 (#94). */
    static String name(UUID id) {
        if (ChacaApi.ready()) return ChacaApi.name(id);
        var offline = Bukkit.getOfflinePlayer(id);
        try { var plugin = org.bukkit.plugin.java.JavaPlugin.getPlugin(MagicCodexBridge.class); if (plugin.names() != null) return plugin.nicknameOf(offline); }
        catch (RuntimeException ignored) { }
        return Optional.ofNullable(offline.getName()).orElse(id.toString());
    }

    static String compact(String s) { return AdminCommandRules.compact(s); }
}
