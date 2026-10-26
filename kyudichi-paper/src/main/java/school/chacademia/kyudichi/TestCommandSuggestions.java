package school.chacademia.kyudichi;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** /테스트 큐디치 시작 &lt;경기장&gt; [옵션…] 의 옵션 칸 자동완성 (실행 규칙은 KyudichiCommands). */
final class TestCommandSuggestions {
    static final List<String> OPTIONS = List.of("준비:0", "시간:3", "스니치:0", "무적", "블러저끔");
    private TestCommandSuggestions() {}

    /** Options still worth offering after {@code typed} (the whole option text so far; the last word is being typed). */
    static List<String> complete(String typed) {
        String[] words = typed.split(" ", -1);
        String prefix = words[words.length - 1];
        return OPTIONS.stream().filter(option -> {
            String key = key(option);
            for (int i = 0; i < words.length - 1; i++) if (key(words[i]).equals(key)) return false;
            return option.startsWith(prefix);
        }).toList();
    }

    static <S> CompletableFuture<Suggestions> suggest(CommandContext<S> ctx, SuggestionsBuilder b) {
        String typed = b.getRemaining();
        var sub = b.createOffset(b.getStart() + typed.lastIndexOf(' ') + 1);
        for (String s : complete(typed)) sub.suggest(s);
        return sub.buildFuture();
    }

    private static String key(String value) { int colon = value.indexOf(':'); return colon < 0 ? value : value.substring(0, colon); }
}
