package school.magiccodex.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 운영 기록 보기 명령 (메모 #92). DB 읽기는 기록 스레드에서, 결과만 게임 스레드에서 채팅으로.
 * <ul>
 *   <li>{@code /유저관리 <닉> 기록 [종류|전체] [일수] [쪽]} — 최근 것부터 한 쪽 15줄</li>
 *   <li>{@code /서버관리 기록 검색 <글자>} — 최근 search-days(기본 7일) 안에서 설명·상세·닉네임에 글자가 든 줄 최대 30개</li>
 *   <li>{@code /서버관리 기록 상태} — 켜짐·저장 방식·대기열</li>
 * </ul>
 */
final class AuditCommands {
    private AuditCommands() {}
    static final int PAGE = 15;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    static void register(MagicCodexBridge plugin, AuditLog log) {
        if (!CodexCommands.ready(plugin, "운영 기록")) return;
        var kinds = new ArrayList<String>(); kinds.add("전체"); kinds.addAll(AuditLog.KINDS);
        var node = Commands.literal("기록").executes(ctx -> player(ctx, log, "전체", 7, 1));
        node.then(Commands.argument("종류", ChacaApi.word(() -> kinds)).executes(ctx -> player(ctx, log, kind(ctx), 7, 1))
                .then(Commands.argument("일수", IntegerArgumentType.integer(1, 3650)).executes(ctx -> player(ctx, log, kind(ctx), IntegerArgumentType.getInteger(ctx, "일수"), 1))
                        .then(Commands.argument("쪽", IntegerArgumentType.integer(1, 200)).executes(ctx -> player(ctx, log, kind(ctx), IntegerArgumentType.getInteger(ctx, "일수"), IntegerArgumentType.getInteger(ctx, "쪽"))))));
        ChacaApi.branch(plugin, ChacaApi.유저관리, node, "운영 기록 보기 (마법·상점·퀘스트·호감도… 최근 것부터, 종류·일수·쪽)", "기록 전체 7 1");

        var server = Commands.literal("기록");
        server.then(Commands.literal("검색").then(Commands.argument("글자", StringArgumentType.greedyString()).executes(ctx -> {
            var sender = CodexCommands.sender(ctx);
            String text = StringArgumentType.getString(ctx, "글자").strip();
            if (text.length() < 2) throw ChacaApi.error("두 글자 이상 적어 주세요");
            log.searchRows(text, 30, rows -> CodexCommands.lines(sender, "── 운영 기록 검색 \"" + text + "\" · 최근 " + log.searchDays() + "일 · " + rows.size() + "줄 ──", lines(rows, true)),
                    m -> ChacaApi.fail(sender, m, new IllegalStateException(m)));
            return Command.SINGLE_SUCCESS;
        })));
        server.then(Commands.literal("상태").executes(ctx -> { ChacaApi.ok(CodexCommands.sender(ctx), "운영 기록: " + log.statusLine()); return Command.SINGLE_SUCCESS; }));
        ChacaApi.branch(plugin, ChacaApi.서버관리, server, "운영 기록 글자 검색·상태", "기록 검색 ");
    }

    private static String kind(CommandContext<CommandSourceStack> ctx) { return ctx.getArgument("종류", String.class); }

    private static int player(CommandContext<CommandSourceStack> ctx, AuditLog log, String kind, int days, int page) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        var sender = CodexCommands.sender(ctx);
        if (!kind.equals("전체") && !AuditLog.KINDS.contains(kind)) throw ChacaApi.error("종류는 전체·" + String.join("·", AuditLog.KINDS) + " 중 하나예요");
        log.playerRows(id, kind.equals("전체") ? null : kind, days, PAGE + 1, (page - 1) * PAGE, rows -> {
            boolean more = rows.size() > PAGE;
            var shown = more ? rows.subList(0, PAGE) : rows;
            var out = lines(shown, false);
            if (shown.isEmpty()) out.add(" (기록 없음)");
            if (more) out.add(" 다음 쪽: /유저관리 " + ChacaApi.name(id) + " 기록 " + kind + " " + days + " " + (page + 1));
            CodexCommands.lines(sender, "── " + ChacaApi.name(id) + " 님 운영 기록 · " + kind + " · 최근 " + days + "일 · " + page + "쪽 ──", out);
        }, m -> ChacaApi.fail(sender, m, new IllegalStateException(m)));
        return Command.SINGLE_SUCCESS;
    }

    private static List<String> lines(List<AuditStore.Row> rows, boolean withName) {
        var out = new ArrayList<String>(rows.size() + 1);
        for (var r : rows)
            out.add(" " + TIME.format(Instant.ofEpochMilli(r.at())) + " [" + r.server() + "] [" + r.kind() + "] "
                    + (withName && !r.name().isEmpty() ? r.name() + " · " : "") + r.summary());
        return out;
    }
}
