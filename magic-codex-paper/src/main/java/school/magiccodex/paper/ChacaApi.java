package school.magiccodex.paper;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
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
 * 차카데미 공용 명령어 허브 (2026-10-20 명령어 정리).
 *
 * <p>모든 관리 명령은 대단원(뿌리) 아래 갈래로 붙는다. 대단원은 이 플러그인(MagicCodexBridge)만 등록하고,
 * 다른 플러그인은 {@link #branch}로 갈래를 붙인다. 같은 빌드에 있는 플러그인은 이 클래스를 직접 부르고
 * (plugin.yml softdepend: MagicCodexBridge), 따로 빌드되는 플러그인은 리플렉션 링크(ChacaLink)로 같은 메서드를 부른다.
 * 그래서 매개변수·반환값에는 JDK·Paper API·Brigadier 타입만 쓴다.</p>
 *
 * <ul>
 *   <li>관리 대단원은 OP(또는 콘솔)만 실행하고, OP가 아니면 자동완성에도 보이지 않는다.</li>
 *   <li>갈래 안의 모든 실행은 허브가 감싼다: 잡히지 않은 예외는 콘솔에 원인을 남기고 유저에게는
 *       "명령어 사용에 실패했습니다" 알림 + 삐빅 소리만 보낸다.</li>
 *   <li>{@link #error}로 만든 예외는 유저가 고칠 수 있는 실패다. 그 문구만 알림으로 보낸다.</li>
 * </ul>
 */
public final class ChacaApi {
    private ChacaApi() {}

    /** 대단원 이름. {@link #branch}의 root 인자로 쓴다. */
    public static final String 관리 = "관리", 유저관리 = "유저관리", 아이템관리 = "아이템관리", 상점관리 = "상점관리",
            기숙사관리 = "기숙사관리", 퀘스트관리 = "퀘스트관리", 대화관리 = "대화관리", NPC관리 = "NPC관리",
            큐디치관리 = "큐디치관리", 하우징관리 = "하우징관리", 월드관리 = "월드관리", 던전관리 = "던전관리",
            컨텐츠관리 = "컨텐츠관리", 서버관리 = "서버관리", 테스트 = "테스트", 개발 = "개발", 상자관리 = "상자관리", 후원관리 = "후원관리";

    /** /유저관리 아래 갈래에서 대상 유저를 꺼낼 때 쓰는 인자 이름. */
    public static final String TARGET = "닉네임";

    static CommandHub hub;

    private static CommandHub hub() {
        if (hub == null) throw new IllegalStateException("MagicCodexBridge 명령어 허브가 아직 준비되지 않았습니다.");
        return hub;
    }

    /** 허브가 준비됐는지 (다른 플러그인이 갈래를 붙이기 전에 확인). */
    public static boolean ready() { return hub != null; }

    /**
     * 대단원 아래에 갈래 하나를 붙인다. {@code node}의 리터럴이 갈래 이름이 된다.
     * root가 {@link #유저관리}이면 갈래는 {@code /유저관리 <닉네임>} 아래에 붙고, {@link #target}으로 대상 UUID를 읽는다.
     * 같은 이름의 갈래를 두 플러그인이 붙이면 자식들이 합쳐진다(먼저 붙인 쪽의 실행이 남는다).
     *
     * @param description 도움말 메뉴에 한 줄로 보이는 설명
     * @param example     도움말 줄을 누르면 채팅창에 들어갈 예시 (대단원 뒤 부분, 예: "스탯 최대마나 설정 500")
     */
    public static void branch(Plugin owner, String root, LiteralArgumentBuilder<CommandSourceStack> node, String description, String example) {
        hub().branch(owner, root, node.build(), description, example);
    }

    /**
     * 대단원 바로 뒤에 칸을 붙인다 (갈래 이름 없이, 예: /후원관리 &lt;닉네임&gt; &lt;개수&gt;). 실행·OP 제한·운영 기록은 갈래와 같다.
     * 유저관리에는 못 붙인다 (그 자리는 &lt;닉네임&gt; 칸).
     */
    public static void rootArgument(Plugin owner, String root, com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?> node, String description, String example) {
        hub().rootArgument(owner, root, node.build(), description, example);
    }

    /** 일반 유저 명령(누구나). 실행은 허브가 감싸고, OP 제한은 없다. */
    public static void playerCommand(Plugin owner, LiteralArgumentBuilder<CommandSourceStack> node, String description, Collection<String> aliases) {
        hub().playerCommand(owner, node.build(), description, aliases);
    }

    /** /서버관리 새로고침 &lt;key&gt; 와 "전체"에 묶이는 새로고침. 실패하면 예외를 던진다(허브가 콘솔에 남기고 운영자에게 알림). */
    public static void reload(Plugin owner, String key, String description, ThrowingRunnable action) {
        hub().reload(owner, key, description, action);
    }

    /** /유저관리 &lt;닉네임&gt; 보기 요약에 들어갈 줄. 메인 스레드에서 불리므로 캐시된 값만 쓴다. 빈 문자열이면 줄을 넣지 않는다. */
    public static void summary(Plugin owner, String label, Function<UUID, String> line) {
        hub().summary(owner, label, line);
    }

    /** /서버관리 상태 에 들어갈 줄. */
    public static void status(Plugin owner, String label, Supplier<String> line) {
        hub().status(owner, label, line);
    }

    /**
     * 유저 칸 (한글 닉네임 자동완성). 값은 입력한 글자 그대로이고, 실행할 때 {@link #resolve}로 UUID를 얻는다.
     * (칸 단계에서 실패시키면 빨간 채팅 글씨가 되므로, 못 찾은 경우도 실행 단계에서 알림으로 알린다.)
     */
    public static ArgumentType<String> player() { return hub().arguments().player(); }

    /** 공백 전까지 아무 글자나 받는 칸(한글 이름 등). 후보는 suggestions가 준다. */
    public static ArgumentType<String> word(Supplier<Collection<String>> suggestions) { return hub().arguments().word(suggestions); }

    /** {@link #player()} 칸을 UUID로. 접속하지 않은 유저도 닉네임 기록이 있으면 찾는다. 못 찾으면 유저 실패 알림. */
    public static UUID resolve(CommandContext<CommandSourceStack> ctx, String argument) throws CommandSyntaxException {
        return hub().arguments().resolve(ctx.getArgument(argument, String.class), false);
    }

    /** {@link #resolve}와 같지만 접속 중인 유저만. 접속 중이 아니면 유저 실패 알림. */
    public static Player resolveOnline(CommandContext<CommandSourceStack> ctx, String argument) throws CommandSyntaxException {
        var id = hub().arguments().resolve(ctx.getArgument(argument, String.class), true);
        return Bukkit.getPlayer(id);
    }

    /** /유저관리 &lt;닉네임&gt; 의 대상. */
    public static UUID target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException { return resolve(ctx, TARGET); }

    /** /유저관리 &lt;닉네임&gt; 의 대상이 접속 중이어야 할 때. */
    public static Player targetOnline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException { return resolveOnline(ctx, TARGET); }

    /** 접속 중이면 그 플레이어, 아니면 null. */
    public static Player online(UUID id) { return Bukkit.getPlayer(id); }

    /** 화면에 보이는 이름(한글 닉네임, 없으면 계정명). */
    public static String name(UUID id) { return hub().arguments().name(id); }

    /** 이름(닉네임·계정명·UUID)으로 유저 찾기. 없으면 null. */
    public static UUID find(String input) { return hub().arguments().find(input); }

    /** 유저가 고칠 수 있는 실패. 실행 중 던지면 이 문구만 알림 + 삐빅으로 간다. */
    public static CommandSyntaxException error(String userMessage) { return CommandHub.userError(userMessage); }

    /** 짧은 성공 결과 → 알림 카드(정보). 콘솔이면 채팅 글씨. */
    public static void ok(CommandSender to, String message) { hub().feedback().ok(to, message); }

    /** 긴 결과(목록·보기)는 채팅. */
    public static void lines(CommandSender to, List<String> lines) { hub().feedback().lines(to, lines); }

    /** 유저 화면에는 userMessage만, 콘솔에는 cause까지. */
    public static void fail(CommandSender to, String userMessage, Throwable cause) { hub().feedback().fail(to, userMessage, cause); }

    /**
     * 알림 카드 (액션바 대신 쓴다). kind: "system"(정보), "warning"(경고), "error"(실패·삐빅), "shop"(보상).
     * key가 같은 알림은 화면에 떠 있는 동안 다시 쌓이지 않고, 같은 key는 cooldownMillis 안에 다시 보내지 않는다.
     */
    public static void notify(Player to, String kind, String key, String title, String body, long cooldownMillis) {
        hub().feedback().notify(to, kind, key, title, body, cooldownMillis);
    }

    /** LuckPerms 권한 노드 주기/회수 (보유 판정용 권한: 빗자루·가구·펫·레시피 등). LuckPerms가 없으면 실패. */
    public static CompletableFuture<Void> permission(UUID id, String node, boolean grant) { return hub().permissions().set(id, node, grant); }

    /** LuckPerms에 저장된 노드 중 prefix로 시작하는 것 (오프라인 유저도). */
    public static CompletableFuture<List<String>> permissions(UUID id, String prefix) { return hub().permissions().list(id, prefix); }

    /**
     * 운영 기록 한 줄 (메모 #92). 아무 스레드에서 불러도 된다: 메모리 대기열에 넣기만 하고 DB·파일 쓰기는 기록 스레드가 묶어서 한다.
     * kind 는 AuditLog 종류 이름(마법·상점·퀘스트·스토리·호감도…), summary 는 한 줄 한국어 설명, detail 은 key=value (null 가능).
     * player 가 null 이면 시스템 기록. 기록이 꺼져 있거나 아직 준비 전이면 조용히 건너뛴다.
     */
    public static void audit(UUID player, String kind, String summary, java.util.Map<String, String> detail) {
        AuditLog.record(player, kind, summary, detail);
    }

    /**
     * 서버 통계 카운터 더하기 (메모 #96, codex_player_stats). 아무 스레드, 메모리에 더하기만 하고 몇 분마다 묶어서 DB 에 쓴다.
     * key 예: "결투참가", "큐디치승리", "발견시도:fireball". 96자까지.
     */
    public static void stat(UUID player, String key, long amount) {
        AuditStats.add(player, key, amount);
        // 스토리 1-30 (세드릭: 1:1 결투 한 판 해 보고 와라, 2026-10-28): 아레나 큐디치가 결투가 끝나면 "결투참가" 를 보낸다
        // (승패·나감 상관없이, 경기장에 온 사람만). 공유 LuckPerms 도장 → 학교 스토리가 권한 조건으로 본다.
        if ("결투참가".equals(key)) stampStoryDuel(player);
    }

    static final String STORY_DUEL = "chacademi.story.duel";

    private static void stampStoryDuel(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null && online.hasPermission(STORY_DUEL)) return;
        try {
            permission(player, STORY_DUEL, true).exceptionally(ex -> {
                Bukkit.getLogger().warning("[MagicCodexBridge] 스토리 결투 도장을 찍지 못했습니다 " + player + ": " + ex);
                return null;
            });
        } catch (RuntimeException ex) { Bukkit.getLogger().warning("[MagicCodexBridge] 스토리 결투 도장을 찍지 못했습니다 " + player + ": " + ex); }
    }

    @FunctionalInterface
    public interface ThrowingRunnable { void run() throws Exception; }
}
