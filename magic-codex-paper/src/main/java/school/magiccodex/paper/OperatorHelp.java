package school.magiccodex.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 운영자 AI 도우미 (오픈 전 메모 #38, 오너 결정 2026-10-25): {@code /관리 질문 <궁금한 것>}.
 * <ul>
 *   <li>AI 에게 "지금 서버에 실제로 등록된 명령 목록"({@link CommandHub#catalog}, 켜질 때 등록된 명령에서 바로 뽑음)과
 *       운영 안내서(operator-guide.md)를 같이 주고, 어떤 명령을 어떤 순서로 쓰면 되는지 묻는다. 학습(파인튜닝) 없음.</li>
 *   <li>답에 나온 명령은 서버가 실제 명령 모양과 맞춰 본다. 목록에 없는 명령은 보여 주지 않고 "확인 필요"로 센다.</li>
 *   <li>명령을 실행하지 않는다. 맞는 명령은 눌러서 채팅창에 넣을 수 있게만 보여 준다.</li>
 *   <li>AI 연결은 ChacaNPC 의 OpenAI 연결을 그대로 쓴다 (kr.chacademy.npc.api.ChacaNpcApi.ask, 리플렉션). 예산 한도와 상관없이 보낸다.</li>
 * </ul>
 */
final class OperatorHelp {
    static final int MAX_QUESTION = 300, MAX_COMMANDS = 6, MAX_OUTPUT_TOKENS = 1200;

    private final MagicCodexBridge plugin;
    private final CommandHub hub;
    private final Set<String> busy = new HashSet<>();
    private String guide;

    OperatorHelp(MagicCodexBridge plugin, CommandHub hub) {
        this.plugin = plugin;
        this.hub = hub;
    }

    /** 명령 칸 하나: 정해진 글자(literal) 또는 아무 값이나 받는 칸(argument). greedy = 띄어쓰기까지 끝까지 받는 글 칸 (&lt;이름…&gt;). */
    record Token(String text, boolean argument, boolean greedy) {}

    void ask(CommandSender sender, String question) {
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) { hub.feedback().userFail(sender, "궁금한 것을 적어 주세요", "/관리 질문 <궁금한 것>"); return; }
        if (q.length() > MAX_QUESTION) q = q.substring(0, MAX_QUESTION);
        String key = sender instanceof Player p ? p.getUniqueId().toString() : "console";
        if (!busy.add(key)) { hub.feedback().userFail(sender, "앞의 질문에 답하는 중이에요. 잠시만요", null); return; }
        var catalog = hub.catalog();
        String instructions = instructions(catalog);
        sender.sendMessage(Component.text("[도우미] 찾아보는 중… ", NamedTextColor.DARK_AQUA).append(Component.text(q, NamedTextColor.GRAY)));
        CompletableFuture<String> call;
        try { call = callAi(instructions, q); }
        catch (Exception e) { busy.remove(key); hub.feedback().userFail(sender, "AI 도우미를 쓸 수 없어요: " + e.getMessage(), null); return; }
        final String asked = q;
        call.whenComplete((text, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            busy.remove(key);
            if (sender instanceof Player p && !p.isOnline()) return;
            if (error != null || text == null || text.isBlank()) {
                Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
                hub.feedback().userFail(sender, "AI 도우미가 답하지 못했어요" + (cause == null ? "" : ": " + cause.getMessage()), "/관리 질문 " + asked);
                return;
            }
            show(sender, text, catalog);
        }));
    }

    // ------------------------------------------------------------------ AI 호출 (ChacaNPC)

    @SuppressWarnings("unchecked")
    private CompletableFuture<String> callAi(String instructions, String question) throws Exception {
        var chaca = Bukkit.getPluginManager().getPlugin("ChacaNPC");
        if (chaca == null || !chaca.isEnabled()) throw new IllegalStateException("ChacaNPC 가 꺼져 있어요");
        Class<?> api = Class.forName("kr.chacademy.npc.api.ChacaNpcApi", true, chaca.getClass().getClassLoader());
        var ask = api.getMethod("ask", String.class, String.class, int.class);
        try { return (CompletableFuture<String>) ask.invoke(null, instructions, question, MAX_OUTPUT_TOKENS); }
        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause() instanceof Exception x ? x : e; }
    }

    private String guide() {
        if (guide != null) return guide;
        try (InputStream in = plugin.getResource("operator-guide.md")) {
            guide = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { guide = ""; }
        return guide;
    }

    String instructions(List<CommandHub.CatalogLine> catalog) {
        var sb = new StringBuilder();
        sb.append("""
            너는 마인크래프트 서버 "차카데미"의 운영자 도우미다. 운영자는 개발을 모르는 사람이다.
            운영자의 질문(유저가 겪는 문제나 하고 싶은 일)에, 어떤 관리 명령을 어떤 순서로 쓰면 되는지 쉬운 한국어로 짧게 알려 준다.
            규칙:
            - 아래 [명령 목록]에 있는 명령만 안내한다. 목록에 없는 명령이나 칸을 절대 지어내지 않는다.
            - <닉네임>, <금액> 같은 칸은 질문에 값이 나왔으면 그 값으로 채우고, 아니면 칸 이름 그대로 둔다.
            - 순서가 있으면 확인 명령부터. 되돌릴 수 없는 명령(지우기·초기화)은 끝에 확인을 붙여야 한다는 점과 위험을 같이 알려 준다.
            - 명령으로 할 수 없는 일(서버 파일 수정, 개발자 작업)이면 그렇다고 말하고 [운영 안내서]의 해당 내용을 알려 준다.
            - 모르겠으면 모른다고 하고 가까운 메뉴(/관리, /유저관리 <닉네임>)를 알려 준다.
            - answer 는 400자 이내, commands 는 실행 순서대로 최대 6개. commands 의 command 는 / 로 시작하는 명령 한 줄.
            """);
        sb.append("\n[명령 목록] (모양 — 설명)\n");
        for (var l : catalog) sb.append(l.path()).append(" — ").append(l.description()).append('\n');
        String g = guide();
        if (!g.isBlank()) sb.append("\n[운영 안내서]\n").append(g);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 답 보여 주기

    private void show(CommandSender sender, String json, List<CommandHub.CatalogLine> catalog) {
        Reply reply = parse(json);
        var patterns = patterns(catalog);
        sender.sendMessage(Component.text("── 도우미 답 ──", NamedTextColor.DARK_AQUA));
        for (String line : reply.answer().split("\n")) if (!line.isBlank()) sender.sendMessage(Component.text(" " + line.strip(), NamedTextColor.WHITE));
        int dropped = 0, n = 0;
        for (var c : reply.commands()) {
            if (!valid(c.command(), patterns)) { dropped++; continue; }
            n++;
            String cmd = c.command().strip();
            sender.sendMessage(Component.text(" " + n + ". " + cmd, NamedTextColor.YELLOW)
                .append(Component.text(c.note().isBlank() ? "" : "  " + c.note(), NamedTextColor.GRAY))
                .clickEvent(ClickEvent.suggestCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text("눌러서 채팅창에 넣기 (실행은 직접)"))));
        }
        if (dropped > 0) sender.sendMessage(Component.text(" (실제 명령과 맞지 않는 안내 " + dropped + "개는 뺐어요 — /관리 메뉴에서 확인해 주세요)", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text(" 명령은 눌러서 채팅창에 넣은 뒤 칸(<…>)을 채워 직접 실행하세요.", NamedTextColor.DARK_GRAY));
    }

    record Suggestion(String command, String note) {}
    record Reply(String answer, List<Suggestion> commands) {}

    /** AI 답(JSON: answer, commands[{command, note}]). JSON 이 아니면 글 전체를 answer 로. */
    static Reply parse(String text) {
        try {
            JsonObject o = JsonParser.parseString(text.strip()).getAsJsonObject();
            String answer = o.has("answer") && o.get("answer").isJsonPrimitive() ? o.get("answer").getAsString() : "";
            var list = new ArrayList<Suggestion>();
            if (o.has("commands") && o.get("commands").isJsonArray()) {
                JsonArray a = o.getAsJsonArray("commands");
                for (var e : a) {
                    if (!e.isJsonObject() || list.size() >= MAX_COMMANDS) continue;
                    var c = e.getAsJsonObject();
                    String cmd = c.has("command") && c.get("command").isJsonPrimitive() ? c.get("command").getAsString() : "";
                    String note = c.has("note") && c.get("note").isJsonPrimitive() ? c.get("note").getAsString() : "";
                    if (!cmd.isBlank()) list.add(new Suggestion(cmd.strip(), note.strip()));
                }
            }
            return new Reply(answer, list);
        } catch (RuntimeException notJson) {
            return new Reply(text, List.of());
        }
    }

    /** 명령 모양을 칸 목록으로: "/유저관리 &lt;닉네임&gt; 돈 추가 &lt;금액&gt;" → [유저관리, (칸), 돈, 추가, (칸)]. */
    static List<List<Token>> patterns(List<CommandHub.CatalogLine> catalog) {
        var out = new ArrayList<List<Token>>();
        for (var l : catalog) out.add(tokens(l.path()));
        return out;
    }

    static List<Token> tokens(String path) {
        var out = new ArrayList<Token>();
        for (String t : path.strip().replaceFirst("^/", "").split("\\s+")) {
            if (t.isEmpty()) continue;
            boolean arg = t.startsWith("<") && t.endsWith(">");
            out.add(new Token(t, arg, arg && t.endsWith("…>")));
        }
        return out;
    }

    /**
     * 실제 명령 모양과 맞는지: 정해진 글자는 같아야 하고, 칸에는 아무 값(또는 &lt;칸 이름&gt;)이 와도 된다.
     * 마지막 칸이 끝까지 받는 글 칸(&lt;이름…&gt;)일 때만 뒤에 더 붙은 말도 그 칸으로 본다.
     */
    static boolean valid(String command, List<List<Token>> patterns) {
        if (command == null || !command.strip().startsWith("/")) return false;
        var words = tokens(command);
        if (words.isEmpty()) return false;
        for (var p : patterns) {
            if (p.isEmpty() || words.size() < p.size()) continue;
            if (words.size() > p.size() && !p.get(p.size() - 1).greedy()) continue;
            boolean ok = true;
            for (int i = 0; i < p.size() && ok; i++) {
                var want = p.get(i);
                if (!want.argument() && !want.text().equals(words.get(i).text())) ok = false;
            }
            if (ok) return true;
        }
        return false;
    }
}
