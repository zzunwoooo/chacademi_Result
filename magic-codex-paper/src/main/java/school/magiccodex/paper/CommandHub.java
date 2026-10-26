package school.magiccodex.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 대단원(뿌리 명령)을 소유하고, 다른 플러그인이 붙인 갈래를 모아 Paper Brigadier로 등록한다.
 * Paper는 플러그인 활성화가 끝난 뒤(그리고 /minecraft:reload 때) COMMANDS 이벤트를 부르므로,
 * 갈래는 각 플러그인의 onEnable에서 붙이면 된다.
 */
final class CommandHub {
    /** 유저가 고칠 수 있는 실패. 메시지만 알림으로 보낸다. */
    static final class UserError extends LiteralMessage { UserError(String s) { super(s); } }

    static CommandSyntaxException userError(String message) {
        return new SimpleCommandExceptionType(new UserError(message)).create();
    }

    record Branch(Plugin owner, LiteralCommandNode<CommandSourceStack> node, String description, String example) {}
    /** 대단원 바로 뒤에 붙는 칸 (예: /후원관리 &lt;닉네임&gt; &lt;개수&gt;). 유저관리에는 못 붙인다. */
    record RootArgument(Plugin owner, ArgumentCommandNode<CommandSourceStack, ?> node, String description, String example) {}
    record PlayerRoot(Plugin owner, LiteralCommandNode<CommandSourceStack> node, String description, Collection<String> aliases) {}
    record Reload(Plugin owner, String key, String description, ChacaApi.ThrowingRunnable action) {}
    record Line(Plugin owner, String label, Function<UUID, String> line) {}
    record Status(Plugin owner, String label, Supplier<String> line) {}

    /** 대단원 순서와 설명 (/관리 메뉴에 이 순서로 보인다). */
    private static final LinkedHashMap<String, String> ROOTS = new LinkedHashMap<>();
    static {
        ROOTS.put(ChacaApi.유저관리, "특정 유저의 모든 것 (스탯·마법·칭호·아티팩트·호감도·큐디치·돈 …)");
        ROOTS.put(ChacaApi.아이템관리, "아이템 만들기·주기·능력 붙이기");
        ROOTS.put(ChacaApi.상점관리, "일반 상점·물고기 판매소·거래 처리");
        ROOTS.put(ChacaApi.상자관리, "뽑기 상자(API 상자) 만들기·보상·확률 관리");
        ROOTS.put(ChacaApi.후원관리, "별풍선 후원 보상 테스트·수동 지급 (실제 후원과 같은 처리)");
        ROOTS.put(ChacaApi.기숙사관리, "기숙사 점수·기증 마법");
        ROOTS.put(ChacaApi.퀘스트관리, "의뢰 게시판 퀘스트 만들기·수정");
        ROOTS.put(ChacaApi.대화관리, "스토리 대화·컷신 재생과 파일 반영");
        ROOTS.put(ChacaApi.NPC관리, "AI NPC 배치·설정");
        ROOTS.put(ChacaApi.큐디치관리, "큐디치·결투 경기장, 경기, 시즌");
        ROOTS.put(ChacaApi.하우징관리, "기숙사실 문·가구 목록");
        ROOTS.put(ChacaApi.월드관리, "계절·토네이도·야생 몹·변색종");
        ROOTS.put(ChacaApi.던전관리, "던전 기믹 모델·퍼즐");
        ROOTS.put(ChacaApi.컨텐츠관리, "등록된 칭호·아티팩트·연금술 레시피·책 보기·편집");
        ROOTS.put(ChacaApi.서버관리, "설정 새로고침·연결 상태·예산");
        ROOTS.put(ChacaApi.테스트, "미리보기·테스트 플레이");
        ROOTS.put(ChacaApi.개발, "개발자용 이펙트 디버그");
    }

    private final MagicCodexBridge plugin;
    private final ChacaArguments arguments;
    private final ChacaFeedback feedback;
    private final ChacaPermissions permissions;
    private final Map<String, List<Branch>> branches = new LinkedHashMap<>();
    private final List<PlayerRoot> playerRoots = new ArrayList<>();
    private final Map<String, List<RootArgument>> rootArguments = new LinkedHashMap<>();
    private final LinkedHashMap<String, Reload> reloads = new LinkedHashMap<>();
    private final List<Line> summaries = new ArrayList<>();
    private final List<Status> statuses = new ArrayList<>();
    private boolean built;
    private final OperatorHelp help;

    CommandHub(MagicCodexBridge plugin) {
        this(plugin, handler -> plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> handler.accept(event.registrar())));
    }

    /** Binder keeps Paper lifecycle wiring separate from the actual command-tree contract. */
    CommandHub(MagicCodexBridge plugin, java.util.function.Consumer<java.util.function.Consumer<Commands>> binder) {
        this.plugin = plugin;
        this.arguments = new ChacaArguments(plugin);
        this.feedback = new ChacaFeedback(plugin);
        this.permissions = new ChacaPermissions(plugin);
        this.help = new OperatorHelp(plugin, this);
        for (String root : ROOTS.keySet()) branches.put(root, new ArrayList<>());
        binder.accept(registrar -> { register(registrar); built = true; });
        ChacaApi.hub = this;
    }

    ChacaArguments arguments() { return arguments; }
    ChacaFeedback feedback() { return feedback; }
    ChacaPermissions permissions() { return permissions; }

    void close() { if (ChacaApi.hub == this) ChacaApi.hub = null; }

    // ---------------------------------------------------------------- 등록 받기

    void branch(Plugin owner, String root, LiteralCommandNode<CommandSourceStack> node, String description, String example) {
        var list = branches.get(root);
        if (list == null) throw new IllegalArgumentException("없는 대단원: " + root);
        // 같은 이름 갈래는 Brigadier가 자식들을 합친다 (같은 플러그인의 다른 파일이 같은 갈래에 붙여도 덮어쓰지 않음).
        list.add(new Branch(owner, node, description, example == null ? node.getLiteral() + " " : example));
        lateWarning(owner, "/" + root + " " + node.getLiteral());
    }

    void rootArgument(Plugin owner, String root, ArgumentCommandNode<CommandSourceStack, ?> node, String description, String example) {
        if (!ROOTS.containsKey(root) || root.equals(ChacaApi.유저관리)) throw new IllegalArgumentException("칸을 바로 붙일 수 없는 대단원: " + root);
        rootArguments.computeIfAbsent(root, k -> new ArrayList<>()).add(new RootArgument(owner, node, description, example == null ? "" : example));
        lateWarning(owner, "/" + root + " <" + node.getName() + ">");
    }

    void playerCommand(Plugin owner, LiteralCommandNode<CommandSourceStack> node, String description, Collection<String> aliases) {
        playerRoots.removeIf(r -> r.node().getLiteral().equals(node.getLiteral()));
        playerRoots.add(new PlayerRoot(owner, node, description, aliases == null ? List.of() : List.copyOf(aliases)));
        lateWarning(owner, "/" + node.getLiteral());
    }

    void reload(Plugin owner, String key, String description, ChacaApi.ThrowingRunnable action) {
        reloads.put(key, new Reload(owner, key, description, action));
        lateWarning(owner, "/서버관리 새로고침 " + key);
    }

    void summary(Plugin owner, String label, Function<UUID, String> line) {
        summaries.removeIf(l -> l.owner().equals(owner) && l.label().equals(label));
        summaries.add(new Line(owner, label, line));
    }

    void status(Plugin owner, String label, Supplier<String> line) {
        statuses.removeIf(l -> l.owner().equals(owner) && l.label().equals(label));
        statuses.add(new Status(owner, label, line));
    }

    private void lateWarning(Plugin owner, String what) {
        if (!built) return;
        plugin.getLogger().warning("[명령어] " + owner.getName() + "가 명령어 등록이 끝난 뒤에 " + what + "를 붙였습니다. "
                + "plugin.yml에 softdepend: [MagicCodexBridge]가 있는지 확인하세요. (/minecraft:reload 전까지 보이지 않음)");
    }

    // ---------------------------------------------------------------- Brigadier 등록

    private static boolean admin(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        return !(sender instanceof Player) || sender.isOp();
    }

    private void register(Commands registrar) {
        for (var entry : ROOTS.entrySet()) {
            String root = entry.getKey();
            LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(root).requires(CommandHub::admin)
                    .executes(ctx -> { menu(ctx.getSource().getSender(), root); return Command.SINGLE_SUCCESS; });
            ArgumentBuilder<CommandSourceStack, ?> parent = builder;
            RequiredArgumentBuilder<CommandSourceStack, String> target = null;
            if (root.equals(ChacaApi.유저관리)) {
                target = Commands.argument(ChacaApi.TARGET, arguments.player())
                        .executes(wrap(ctx -> { userSummary(ctx); return Command.SINGLE_SUCCESS; }, "/유저관리 <닉네임>"));
                target.then(Commands.literal("보기").executes(wrap(ctx -> { userSummary(ctx); return Command.SINGLE_SUCCESS; }, "/유저관리 <닉네임> 보기")));
                parent = target;
            }
            if (root.equals(ChacaApi.서버관리)) addServerBranches(parent);
            for (var branch : branches.get(root)) parent.then(graft(branch.node(), "/" + root + (target != null ? " <닉네임>" : "")));
            for (var arg : rootArguments.getOrDefault(root, List.of())) builder.then(copy(arg.node(), "/" + root + " <" + arg.node().getName() + ">"));
            if (target != null) builder.then(target);
            registrar.register(builder.build(), entry.getValue(), List.of());
        }
        registrar.register(Commands.literal(ChacaApi.관리).requires(CommandHub::admin)
                .executes(ctx -> { mainMenu(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                // #38 운영자 AI 도우미: 실제 명령 목록 + 운영 안내서를 주고 "어떤 명령을 쓰면 되는지" 묻는다 (실행은 하지 않음)
                .then(Commands.literal("질문").executes(ctx -> { feedback.userFail(ctx.getSource().getSender(), "궁금한 것을 적어 주세요", "/관리 질문 <궁금한 것>"); return 0; })
                        .then(Commands.argument("내용", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(wrap(ctx -> { help.ask(ctx.getSource().getSender(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "내용")); return Command.SINGLE_SUCCESS; }, "/관리 질문 <내용>"))))
                .build(),
                "차카데미 관리 명령 목록", List.of());
        for (var root : playerRoots) registrar.register(graft(root.node(), ""), root.description(), root.aliases());
        plugin.getLogger().info("[명령어] 대단원 " + ROOTS.size() + "개, 유저 명령 " + playerRoots.size() + "개 등록");
    }

    /** 갈래를 복사하면서 모든 실행을 감싸고, 실행이 없는 중간 칸에는 "형식이 맞지 않아요" 안내를 붙인다. */
    @SuppressWarnings("unchecked")
    private LiteralCommandNode<CommandSourceStack> graft(LiteralCommandNode<CommandSourceStack> node, String prefix) {
        return (LiteralCommandNode<CommandSourceStack>) copy(node, prefix.isEmpty() ? "/" + node.getLiteral() : prefix + " " + node.getLiteral());
    }

    private CommandNode<CommandSourceStack> copy(CommandNode<CommandSourceStack> node, String path) {
        ArgumentBuilder<CommandSourceStack, ?> builder;
        if (node instanceof LiteralCommandNode<CommandSourceStack> literal) {
            builder = Commands.literal(literal.getLiteral());
        } else if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
            var required = Commands.argument(argument.getName(), argument.getType());
            if (argument.getCustomSuggestions() != null) required.suggests(argument.getCustomSuggestions());
            builder = required;
        } else {
            throw new IllegalArgumentException("지원하지 않는 명령 칸: " + node);
        }
        Predicate<CommandSourceStack> requirement = node.getRequirement();
        if (requirement != null) builder.requires(requirement);
        if (node.getRedirect() != null) builder.forward(node.getRedirect(), node.getRedirectModifier(), node.isFork());
        if (node.getCommand() != null) builder.executes(wrap(node.getCommand(), path));
        else if (!node.getChildren().isEmpty()) builder.executes(ctx -> usage(ctx, node, path));
        for (var child : node.getChildren()) builder.then(copy(child, path + " " + token(child)));
        return builder.build();
    }

    private static String token(CommandNode<CommandSourceStack> node) {
        return node instanceof LiteralCommandNode<CommandSourceStack> literal ? literal.getLiteral() : "<" + node.getName() + ">";
    }

    Command<CommandSourceStack> wrap(Command<CommandSourceStack> command, String path) {
        return ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            try {
                int result = command.run(ctx);
                audit(ctx, path); // 운영 기록 (메모 #92): 바꾸는 관리 명령만, 대기열에 한 줄
                return result;
            } catch (CommandSyntaxException e) {
                if (e.getRawMessage() instanceof UserError) feedback.userFail(sender, e.getRawMessage().getString(), null);
                else feedback.userFail(sender, "명령어 형식이 맞지 않아요", path);
                return 0;
            } catch (Throwable t) {
                feedback.fail(sender, "명령어 사용에 실패했습니다", t, ctx.getInput());
                return 0;
            }
        };
    }

    /** 운영 기록: 관리 대단원(/관리 질문 제외) 명령이 예외 없이 실행되면 한 줄. 유저 명령은 남기지 않는다. */
    private void audit(CommandContext<CommandSourceStack> ctx, String path) {
        try {
            if (path == null || path.length() < 2) return;
            int space = path.indexOf(' ');
            String root = path.substring(1, space < 0 ? path.length() : space);
            if (!ROOTS.containsKey(root)) return;
            UUID target = null;
            if (root.equals(ChacaApi.유저관리)) {
                try { target = arguments.find(ctx.getArgument(ChacaApi.TARGET, String.class)); } catch (IllegalArgumentException ignored) { }
            }
            AuditLog.adminCommand(ctx.getSource().getSender(), path, ctx.getInput(), target);
        } catch (RuntimeException ignored) { /* 기록 실패가 명령을 막지 않는다 */ }
    }

    private int usage(CommandContext<CommandSourceStack> ctx, CommandNode<CommandSourceStack> node, String path) {
        var next = new ArrayList<String>();
        for (var child : node.getChildren()) if (child.canUse(ctx.getSource())) next.add(token(child));
        String example = path + (next.isEmpty() ? "" : " " + (next.size() <= 4 ? String.join("|", next) : String.join("|", next.subList(0, 4)) + "|…"));
        feedback.userFail(ctx.getSource().getSender(), "명령어 형식이 맞지 않아요", example);
        return 0;
    }

    // ---------------------------------------------------------------- 도움말 메뉴

    private void mainMenu(CommandSender sender) {
        var lines = new ArrayList<Component>();
        lines.add(Component.text("── 차카데미 관리 명령 ──", NamedTextColor.GOLD));
        for (var e : ROOTS.entrySet()) {
            String cmd = "/" + e.getKey() + (e.getKey().equals(ChacaApi.유저관리) ? " " : "");
            lines.add(Component.text(" /" + e.getKey(), NamedTextColor.YELLOW)
                    .append(Component.text("  " + e.getValue(), NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.suggestCommand(cmd))
                    .hoverEvent(HoverEvent.showText(Component.text("눌러서 " + cmd + " 입력"))));
        }
        lines.add(Component.text(" /관리 질문 <궁금한 것>", NamedTextColor.AQUA)
                .append(Component.text("  어떤 명령을 쓰면 되는지 AI에게 묻기 (예: 돈을 잃었다는 유저가 있어)", NamedTextColor.GRAY))
                .clickEvent(ClickEvent.suggestCommand("/관리 질문 "))
                .hoverEvent(HoverEvent.showText(Component.text("눌러서 /관리 질문 입력"))));
        lines.add(Component.text(" 줄을 누르면 채팅창에 명령이 들어갑니다.", NamedTextColor.DARK_GRAY));
        lines.forEach(sender::sendMessage);
    }

    // ---------------------------------------------------------------- 명령 목록 (운영자 AI 도우미용)

    /** 실행할 수 있는 관리 명령 한 줄: 모양(예: /유저관리 &lt;닉네임&gt; 돈 추가 &lt;금액&gt;)과 그 갈래 설명. */
    record CatalogLine(String path, String description) {}

    /** 지금 등록된 모든 관리·유저 명령 중 실행할 수 있는 끝 칸 전부 (갈래 등록 순서). */
    List<CatalogLine> catalog() {
        var out = new ArrayList<CatalogLine>();
        out.add(new CatalogLine("/관리", "차카데미 관리 명령 목록"));
        out.add(new CatalogLine("/관리 질문 <내용…>", "어떤 명령을 쓰면 되는지 AI에게 묻기"));
        for (var e : ROOTS.entrySet()) {
            String root = e.getKey();
            String head = "/" + root + (root.equals(ChacaApi.유저관리) ? " <닉네임>" : "");
            out.add(new CatalogLine("/" + root, e.getValue() + " (메뉴)"));
            if (root.equals(ChacaApi.유저관리)) out.add(new CatalogLine(head, "유저 요약 보기"));
            if (root.equals(ChacaApi.서버관리)) {
                out.add(new CatalogLine("/서버관리 새로고침 전체", "모든 설정 파일 다시 읽기"));
                for (var r : reloads.values()) out.add(new CatalogLine("/서버관리 새로고침 " + r.key(), r.description()));
                out.add(new CatalogLine("/서버관리 상태", "연결 상태 보기"));
            }
            for (var b : branches.get(root)) walk(b.node(), head + " " + b.node().getLiteral(), b.description(), out);
            for (var a : rootArguments.getOrDefault(root, List.of())) walk(a.node(), head + " " + catalogToken(a.node()), a.description(), out);
        }
        for (var r : playerRoots) walk(r.node(), "/" + r.node().getLiteral(), "(유저 명령) " + r.description(), out);
        var seen = new HashSet<String>();
        out.removeIf(l -> !seen.add(l.path()));
        return out;
    }

    private static void walk(CommandNode<CommandSourceStack> node, String path, String description, List<CatalogLine> out) {
        if (node.getCommand() != null) out.add(new CatalogLine(path, description));
        for (var child : node.getChildren()) walk(child, path + " " + catalogToken(child), description, out);
    }

    /** 목록용 칸 이름: 띄어쓰기까지 다 받는 글 칸은 &lt;이름…&gt; 로 표시 (도우미가 뒤에 붙은 말을 그 칸으로 본다). */
    private static String catalogToken(CommandNode<CommandSourceStack> node) {
        if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> a && a.getType() instanceof com.mojang.brigadier.arguments.StringArgumentType t
                && t.getType() == com.mojang.brigadier.arguments.StringArgumentType.StringType.GREEDY_PHRASE) return "<" + node.getName() + "…>";
        return token(node);
    }

    private void menu(CommandSender sender, String root) {
        var list = branches.get(root);
        var lines = new ArrayList<Component>();
        lines.add(Component.text("── /" + root + " ──", NamedTextColor.GOLD).append(Component.text("  " + ROOTS.get(root), NamedTextColor.GRAY)));
        String head = "/" + root + (root.equals(ChacaApi.유저관리) ? " <닉네임>" : "");
        if (root.equals(ChacaApi.유저관리)) {
            lines.add(line("/유저관리 <닉네임>", "유저 요약 보기", "/유저관리 "));
        }
        if (root.equals(ChacaApi.서버관리)) {
            lines.add(line("/서버관리 새로고침 <분야|전체>", "설정 파일 다시 읽기", "/서버관리 새로고침 "));
            lines.add(line("/서버관리 상태", "연결 상태 보기", "/서버관리 상태"));
        }
        var shownLiterals = new HashSet<String>();
        for (var b : list) {
            if (!shownLiterals.add(b.node().getLiteral())) continue;
            String typed = root.equals(ChacaApi.유저관리) ? "/유저관리 " : "/" + root + " ";
            String shown = head + " " + b.node().getLiteral();
            String example = root.equals(ChacaApi.유저관리) ? typed : typed + b.example();
            lines.add(line(shown, b.description(), example));
        }
        for (var a : rootArguments.getOrDefault(root, List.of())) {
            var shown = new StringBuilder(head + " <" + a.node().getName() + ">");
            for (CommandNode<CommandSourceStack> n = a.node(); n.getChildren().size() == 1; ) { n = n.getChildren().iterator().next(); shown.append(' ').append(token(n)); }
            lines.add(line(shown.toString(), a.description(), "/" + root + " " + a.example()));
        }
        if (list.isEmpty() && rootArguments.getOrDefault(root, List.of()).isEmpty() && !root.equals(ChacaApi.서버관리)) lines.add(Component.text(" (아직 등록된 명령이 없습니다)", NamedTextColor.DARK_GRAY));
        lines.add(Component.text(" 줄을 누르면 채팅창에 명령이 들어갑니다. 칸마다 다음에 넣을 것이 회색으로 보입니다.", NamedTextColor.DARK_GRAY));
        lines.forEach(sender::sendMessage);
    }

    private static Component line(String shown, String description, String insert) {
        return Component.text(" " + shown, NamedTextColor.YELLOW)
                .append(Component.text("  " + description, NamedTextColor.GRAY))
                .clickEvent(ClickEvent.suggestCommand(insert))
                .hoverEvent(HoverEvent.showText(Component.text("눌러서 입력: " + insert)));
    }

    private void userSummary(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = ChacaApi.target(ctx);
        var sender = ctx.getSource().getSender();
        var player = Bukkit.getPlayer(id);
        var out = new ArrayList<String>();
        out.add("── " + arguments.name(id) + " (" + Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse(id.toString()) + ") ──");
        out.add("접속: " + (player != null ? "접속 중 (이 서버)" : "이 서버에 없음"));
        for (var s : summaries) {
            try {
                String text = s.line().apply(id);
                if (text != null && !text.isBlank()) out.add(s.label() + ": " + text);
            } catch (Throwable t) {
                plugin.getLogger().warning("[명령어] 요약 줄 실패 (" + s.owner().getName() + " " + s.label() + "): " + t);
            }
        }
        feedback.lines(sender, out);
        var rows = new ArrayList<Component>();
        var seen = new HashSet<String>();
        for (var b : branches.get(ChacaApi.유저관리)) {
            if (!seen.add(b.node().getLiteral())) continue;
            String insert = "/유저관리 " + arguments.name(id) + " " + b.example();
            rows.add(line(b.node().getLiteral(), b.description(), insert));
        }
        rows.forEach(sender::sendMessage);
    }

    // ---------------------------------------------------------------- /서버관리 (허브 소유)

    private void addServerBranches(ArgumentBuilder<CommandSourceStack, ?> parent) {
        var refresh = Commands.literal("새로고침");
        var all = Commands.literal("전체").executes(wrap(ctx -> { reloadAll(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }, "/서버관리 새로고침 전체"));
        refresh.then(all);
        for (var r : reloads.values()) {
            refresh.then(Commands.literal(r.key()).executes(wrap(ctx -> {
                runReload(ctx.getSource().getSender(), r, true);
                return Command.SINGLE_SUCCESS;
            }, "/서버관리 새로고침 " + r.key())));
        }
        refresh.executes(wrap(ctx -> {
            var keys = new ArrayList<>(reloads.keySet());
            feedback.userFail(ctx.getSource().getSender(), "새로고침할 분야를 골라 주세요", "/서버관리 새로고침 <전체|" + String.join("|", keys.subList(0, Math.min(5, keys.size()))) + (keys.size() > 5 ? "|…" : "") + ">");
            return 0;
        }, "/서버관리 새로고침"));
        parent.then(refresh);
        parent.then(Commands.literal("상태").executes(wrap(ctx -> {
            var out = new ArrayList<String>();
            out.add("── 서버 상태 ──");
            for (var s : statuses) {
                try { out.add(s.label() + ": " + s.line().get()); }
                catch (Throwable t) { out.add(s.label() + ": 확인 실패"); plugin.getLogger().warning("[명령어] 상태 줄 실패 (" + s.label() + "): " + t); }
            }
            if (statuses.isEmpty()) out.add("(등록된 상태 항목이 없습니다)");
            feedback.lines(ctx.getSource().getSender(), out);
            return Command.SINGLE_SUCCESS;
        }, "/서버관리 상태")));
    }

    private boolean runReload(CommandSender sender, Reload r, boolean report) {
        try {
            r.action().run();
            if (report) feedback.ok(sender, r.key() + " 설정을 다시 읽었어요");
            return true;
        } catch (Throwable t) {
            feedback.fail(sender, "설정 파일에 문제가 있어 다시 읽지 못했어요 (" + r.key() + ", 자세한 내용은 콘솔)", t, "/서버관리 새로고침 " + r.key());
            return false;
        }
    }

    private void reloadAll(CommandSender sender) {
        int ok = 0;
        var failed = new ArrayList<String>();
        for (var r : reloads.values()) { if (runReload(sender, r, false)) ok++; else failed.add(r.key()); }
        if (failed.isEmpty()) feedback.ok(sender, "전체 " + ok + "개 설정을 다시 읽었어요");
        else feedback.userFail(sender, "일부 설정을 다시 읽지 못했어요: " + String.join(", ", failed) + " (콘솔 확인)", null);
    }
}
