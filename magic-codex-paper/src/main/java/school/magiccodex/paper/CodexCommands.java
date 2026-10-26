package school.magiccodex.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** 이 모듈 브리지들이 같이 쓰는 작은 명령 도우미 (2026-10-20 명령어 정리). 허브가 없으면(테스트 등) 콘솔 경고만 남긴다. */
final class CodexCommands {
    private CodexCommands() {}
    static final String CONFIRM = "되돌릴 수 없어요. 끝에 '확인'을 붙여 다시 입력해 주세요";

    static boolean ready(Plugin owner, String what) {
        if (ChacaApi.ready()) return true;
        owner.getLogger().warning("[명령어] 허브가 준비되지 않아 " + what + " 명령을 등록하지 못했습니다.");
        return false;
    }

    static LiteralArgumentBuilder<CommandSourceStack> literal(String name) { return Commands.literal(name); }

    static CommandSender sender(CommandContext<CommandSourceStack> ctx) { return ctx.getSource().getSender(); }

    /** 명령을 친 사람이 플레이어여야 할 때. */
    static Player self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player p) return p;
        throw ChacaApi.error("게임 안에서 사용해 주세요");
    }

    /** 되돌릴 수 없는 동작: 끝에 리터럴 '확인'이 있어야 실행. */
    static <T extends ArgumentBuilder<CommandSourceStack, T>> T confirmed(T node, Command<CommandSourceStack> action) {
        return node.executes(ctx -> { throw ChacaApi.error(CONFIRM); }).then(Commands.literal("확인").executes(action));
    }

    /** 액션바 대신 알림 카드. 허브가 없으면 채팅. */
    static void notify(Player p, String kind, String key, String title, long cooldownMillis) {
        if (p == null || title == null || title.isBlank()) return;
        if (ChacaApi.ready()) ChacaApi.notify(p, kind, key, title, "", cooldownMillis);
        else p.sendMessage(title);
    }

    static void warn(Player p, String key, String title) { notify(p, "warning", key, title, 1500); }

    static void lines(CommandSender to, String title, List<String> rows) {
        var out = new java.util.ArrayList<String>(rows.size() + 1);
        out.add(title);
        out.addAll(rows);
        ChacaApi.lines(to, out);
    }

    static String seconds(long millis) {
        long s = Math.max(0, (millis + 999) / 1000);
        return s >= 60 ? (s / 60) + "분 " + (s % 60) + "초" : s + "초";
    }
}
