package kr.chacademy.storyplugin;

import java.util.*;
/** Untrusted suggestion metadata only. Never authorizes playback, commands or rewards. */
public record StoryCatalog(List<String> cutscenes,List<String> dialogues) {
    public static final int MAX_IDS=128, MAX_TEXT=8320, MAX_PACKET=16646;
    public StoryCatalog {cutscenes=List.copyOf(cutscenes);dialogues=List.copyOf(dialogues);}
    public static List<String> parseIds(String text) {
        if(text.isEmpty())return List.of();
        var ids=Arrays.asList(text.split("\n",-1));
        if(text.length()>MAX_TEXT||ids.size()>MAX_IDS||ids.stream().anyMatch(id->!StorySafety.validId(id))||new HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("잘못된 스토리 목록");
        return ids.stream().sorted().toList();
    }
}
