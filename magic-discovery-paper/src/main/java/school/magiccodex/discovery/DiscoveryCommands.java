package school.magiccodex.discovery;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 마법 발견 명령 (명령어 허브 갈래). 예전 /마법발견관리, /칭호수령.
 * <pre>
 * /유저관리 &lt;닉네임&gt; 마법조건 진행 &lt;보기 [키]|설정 &lt;키&gt; &lt;값&gt;&gt;      (마법조건 보기·주기·회수·초기화는 MagicCodexBridge 쪽)
 * /유저관리 &lt;닉네임&gt; 마법발견보상 &lt;보기|다시주기 &lt;번호&gt;|완료처리 &lt;번호&gt;&gt;
 * /아이템관리 칭호보상 등록 &lt;마법&gt;
 * /테스트 마법발견 &lt;이벤트 &lt;키&gt; [수량]|상태 &lt;키&gt; &lt;값&gt;&gt; [닉네임]
 * /서버관리 새로고침 마법발견
 * </pre>
 * 칭호 수령(유저)은 MagicCodexBridge의 /칭호 수령 → {@link MagicDiscovery#claimTitles}.
 */
final class DiscoveryCommands {
    private static final List<String> STATES = List.of("state.circle", "state.temperature", "state.flight_prohibited", "state.mana_maximum");
    private final MagicDiscovery plugin;

    DiscoveryCommands(MagicDiscovery plugin) { this.plugin = plugin; }

    void register() {
        ChacaLink.branch(plugin, ChacaLink.유저관리, Commands.literal("마법조건").then(progress()),
            "마법 획득 조건 (진행 = 조건 진행도 보기·설정)", "마법조건 진행 보기");
        ChacaLink.branch(plugin, ChacaLink.유저관리, rewards(), "지급 확인 대기로 멈춘 마법 발견 칭호 보상 보기·복구", "마법발견보상 보기");
        ChacaLink.branch(plugin, ChacaLink.아이템관리, Commands.literal("칭호보상").then(Commands.literal("등록")
            .then(Commands.argument("마법", ChacaLink.word(plugin::spellIds)).executes(ctx -> {
                if (!(ctx.getSource().getSender() instanceof Player p)) throw ChacaLink.error("게임 안에서 칭호 아이템을 손에 들고 써 주세요");
                try { plugin.configureTitle(p, ctx.getArgument("마법", String.class)); }
                catch (CommandSyntaxException e) { throw e; }
                catch (Exception e) { throw new IllegalStateException("titles.yml 저장 실패", e); }
                return 1;
            }))), "손에 든 아이템을 마법 발견 칭호 보상으로 등록", "칭호보상 등록 ");
        ChacaLink.branch(plugin, ChacaLink.테스트, test(), "마법 발견 조건 이벤트·상태 강제 전달", "마법발견 이벤트 ");
        ChacaLink.reload(plugin, "마법발견", "discoveries.yml·manual-conditions.yml·titles.yml·config.yml", plugin::reloadDefinitions);
    }

    // ------------------------------------------------------------------ /유저관리 <닉네임> 마법조건 진행

    private LiteralArgumentBuilder<CommandSourceStack> progress() {
        var keyWord = ChacaLink.word(plugin::progressKeys);
        return Commands.literal("진행")
            .then(Commands.literal("보기")
                .executes(ctx -> showProgress(ctx, null))
                .then(Commands.argument("키", keyWord).executes(ctx -> showProgress(ctx, ctx.getArgument("키", String.class)))))
            .then(Commands.literal("설정").then(Commands.argument("키", ChacaLink.word(plugin::progressKeys))
                .then(Commands.argument("값", DoubleArgumentType.doubleArg(-1e9, 1e9)).executes(ctx -> {
                    UUID id = ChacaLink.target(ctx);
                    CommandSender sender = ctx.getSource().getSender();
                    String key = ctx.getArgument("키", String.class);
                    double value = DoubleArgumentType.getDouble(ctx, "값");
                    String name = ChacaLink.name(id);
                    plugin.adminSetProgress(id, key, value, problem -> {
                        if (problem == null) ChacaLink.ok(sender, name + " 님 " + key + " = " + format(value));
                        else userFail(sender, problem);
                    }, error -> ChacaLink.fail(sender, "진행도를 저장하지 못했어요", error));
                    return 1;
                }))));
    }

    private int showProgress(CommandContext<CommandSourceStack> ctx, String filter) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        CommandSender sender = ctx.getSource().getSender();
        String name = ChacaLink.name(id);
        plugin.adminProgress(id, values -> {
            var out = new ArrayList<String>();
            var keys = new TreeSet<>(values.keySet());
            if (filter != null) keys.removeIf(k -> !k.startsWith(filter));
            out.add("── " + name + " 님 마법 조건 진행도" + (filter == null ? "" : " (" + filter + ")") + " " + keys.size() + "개 ──");
            int shown = 0;
            for (String k : keys) {
                if (shown++ >= 60) { out.add(" … 외 " + (keys.size() - 60) + "개 (키 앞부분으로 좁혀 보세요)"); break; }
                out.add(" " + k + " = " + format(values.get(k)));
            }
            if (keys.isEmpty()) out.add(" (기록 없음)");
            ChacaLink.lines(sender, out);
        }, error -> ChacaLink.fail(sender, "진행도를 읽지 못했어요", error));
        return 1;
    }

    // ------------------------------------------------------------------ /유저관리 <닉네임> 마법발견보상

    private LiteralArgumentBuilder<CommandSourceStack> rewards() {
        return Commands.literal("마법발견보상")
            .then(Commands.literal("보기").executes(ctx -> {
                UUID id = ChacaLink.target(ctx);
                plugin.adminReserved(ctx.getSource().getSender(), id, ChacaLink.name(id));
                return 1;
            }))
            .then(Commands.literal("다시주기").then(Commands.argument("번호", LongArgumentType.longArg(1)).executes(ctx -> resolve(ctx, true))))
            .then(Commands.literal("완료처리").then(Commands.argument("번호", LongArgumentType.longArg(1)).executes(ctx -> resolve(ctx, false))));
    }

    private int resolve(CommandContext<CommandSourceStack> ctx, boolean claimable) throws CommandSyntaxException {
        UUID id = ChacaLink.target(ctx);
        plugin.adminResolve(ctx.getSource().getSender(), id, ChacaLink.name(id), LongArgumentType.getLong(ctx, "번호"), claimable);
        return 1;
    }

    // ------------------------------------------------------------------ /테스트 마법발견

    private LiteralArgumentBuilder<CommandSourceStack> test() {
        var eventKey = Commands.argument("키", ChacaLink.word(plugin::eventKeys)).executes(ctx -> event(ctx));
        eventKey.then(Commands.argument("수량", DoubleArgumentType.doubleArg(0.0001, 1e9)).executes(this::event)
            .then(Commands.argument(ChacaLink.TARGET, ChacaLink.player()).executes(this::event)));
        eventKey.then(Commands.argument(ChacaLink.TARGET, ChacaLink.player()).executes(this::event));
        var stateValue = Commands.argument("값", DoubleArgumentType.doubleArg(-1e6, 1e6)).executes(this::state);
        stateValue.then(Commands.argument(ChacaLink.TARGET, ChacaLink.player()).executes(this::state));
        return Commands.literal("마법발견")
            .then(Commands.literal("이벤트").then(eventKey))
            .then(Commands.literal("상태").then(Commands.argument("키", ChacaLink.word(() -> STATES)).then(stateValue)));
    }

    private Player who(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        try { ctx.getArgument(ChacaLink.TARGET, String.class); }
        catch (IllegalArgumentException missing) {
            if (ctx.getSource().getSender() instanceof Player p) return p;
            throw ChacaLink.error("콘솔에서는 닉네임을 적어 주세요");
        }
        return ChacaLink.resolveOnline(ctx, ChacaLink.TARGET);
    }

    private int event(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = who(ctx);
        String key = ctx.getArgument("키", String.class);
        double amount = opt(ctx, "수량", Double.class, 1d);
        if (!plugin.signal(p.getUniqueId(), key, amount))
            throw ChacaLink.error("전달하지 못했어요 (이벤트 키, 생존·모험 모드, 데이터 로딩 상태를 확인해 주세요)");
        ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(p.getUniqueId()) + " 님에게 " + key + " +" + format(amount) + " 전달");
        return 1;
    }

    private int state(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player p = who(ctx);
        String key = ctx.getArgument("키", String.class);
        double value = DoubleArgumentType.getDouble(ctx, "값");
        if (!plugin.state(p.getUniqueId(), key, value, true))
            throw ChacaLink.error("전달하지 못했어요 (상태 키·값, 데이터 로딩 상태를 확인해 주세요. state.circle은 1~9)");
        ChacaLink.ok(ctx.getSource().getSender(), ChacaLink.name(p.getUniqueId()) + " 님 " + key + " = " + format(value) + " 전달");
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static String format(double v) { return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v); }

    private static void userFail(CommandSender to, String message) {
        if (to instanceof Player p) ChacaLink.notify(p, "error", "cmd-fail", message, "", 0); else to.sendMessage(message);
    }

    private static <T> T opt(CommandContext<CommandSourceStack> ctx, String name, Class<T> type, T fallback) {
        try { return ctx.getArgument(name, type); } catch (IllegalArgumentException missing) { return fallback; }
    }

}
