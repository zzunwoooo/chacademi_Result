package school.magiccodex.paper;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 명령 칸 종류.
 *
 * <p>Brigadier 기본 word() 칸은 영문·숫자만 받기 때문에, 한글 이름은 "공백 전까지 아무 글자" 칸으로 받는다.
 * 클라이언트에는 word()로 보이므로 MagicCodex 모드가 한글을 허용하도록 고쳐 둔다(클라이언트 StringReader 믹스인).
 * 유저 칸은 입력 글자를 그대로 넘기고 실행 단계에서 찾는다: 못 찾은 경우를 빨간 채팅 대신 알림으로 알리기 위해서다.</p>
 */
final class ChacaArguments {
    private static final int MAX_SUGGESTIONS = 40;
    private final MagicCodexBridge plugin;

    ChacaArguments(MagicCodexBridge plugin) { this.plugin = plugin; }

    ArgumentType<String> word(Supplier<Collection<String>> suggestions) { return new Word(suggestions); }

    ArgumentType<String> player() { return new Word(this::playerSuggestions); }

    private Collection<String> playerSuggestions() {
        var out = new LinkedHashSet<String>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(name(p.getUniqueId()));
        var names = plugin.names();
        if (names != null) out.addAll(names.nicknames().nicknames());
        return out;
    }

    String name(UUID id) {
        var offline = Bukkit.getOfflinePlayer(id);
        String fallback = offline.getName() == null ? id.toString().substring(0, 8) : offline.getName();
        return plugin.names() == null ? fallback : plugin.names().name(offline, fallback);
    }

    UUID find(String input) {
        if (input == null || input.isBlank()) return null;
        String text = input.strip();
        var names = plugin.names();
        if (names != null) {
            Player online = names.resolve(text);
            if (online != null) return online.getUniqueId();
            UUID owner = names.nicknames().ownerOf(text);
            if (owner != null) return owner;
        }
        try { return UUID.fromString(text); } catch (IllegalArgumentException ignored) { }
        var cached = Bukkit.getOfflinePlayerIfCached(text);
        return cached == null ? null : cached.getUniqueId();
    }

    UUID resolve(String input, boolean onlineOnly) throws CommandSyntaxException {
        UUID id = find(input);
        if (id == null) throw CommandHub.userError(input + " 님을 찾을 수 없어요");
        if (onlineOnly && Bukkit.getPlayer(id) == null) throw CommandHub.userError(name(id) + " 님이 접속 중이 아니에요");
        return id;
    }

    /** 공백 전까지 아무 글자. 클라이언트에는 word()로 보낸다. */
    static final class Word implements CustomArgumentType<String, String> {
        private final Supplier<Collection<String>> suggestions;

        Word(Supplier<Collection<String>> suggestions) { this.suggestions = suggestions; }

        @Override public String parse(StringReader reader) throws CommandSyntaxException {
            int start = reader.getCursor();
            while (reader.canRead() && reader.peek() != ' ') reader.skip();
            if (reader.getCursor() == start) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol().createWithContext(reader, "이름");
            return reader.getString().substring(start, reader.getCursor());
        }

        @Override public ArgumentType<String> getNativeType() { return StringArgumentType.word(); }

        @Override public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            int count = 0;
            Collection<String> all;
            try { all = suggestions.get(); } catch (RuntimeException e) { all = List.of(); }
            for (String s : all) {
                if (s == null || s.isEmpty() || s.indexOf(' ') >= 0) continue;
                if (!s.toLowerCase(Locale.ROOT).startsWith(typed)) continue;
                builder.suggest(s);
                if (++count >= MAX_SUGGESTIONS) break;
            }
            return builder.buildFuture();
        }
    }
}
