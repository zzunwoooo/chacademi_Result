package kr.chacademy.story.dialogue;

import kr.chacademy.story.compat.MagicCodexClientLink;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 대사 속 바꿔 넣을 글자들.
 * <ul>
 *   <li>{player} = MagicCodex 에서 정한 한글 닉네임 (없으면 마인크래프트 닉네임)</li>
 *   <li>{account} = 마인크래프트 닉네임</li>
 *   <li>%...% = 서버가 PlaceholderAPI 로 계산해서 보내 준 값 (서버 config 의 dialogue-placeholders)</li>
 * </ul>
 */
public final class TextVars {
    private TextVars() {}

    private static final char ITEM = '\u001E', KEY = '\u001F';

    public static Map<String, String> decode(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return out;
        for (String item : s.split(String.valueOf(ITEM))) {
            int i = item.indexOf(KEY);
            if (i <= 0 || out.size() >= 64) continue;
            String k = item.substring(0, i), v = item.substring(i + 1);
            if (k.length() <= 64 && v.length() <= 128) out.put(k, v.replace("\n", " "));
        }
        return out;
    }

    /** 서버 값이 없으면 이 PC 에서 알 수 있는 값으로 채운다 (/story dialogue 로 혼자 열 때). */
    public static Map<String, String> withLocal(Map<String, String> fromServer) {
        Map<String, String> out = new LinkedHashMap<>(fromServer);
        var p = Minecraft.getInstance().player;
        String account = p == null ? "나" : p.getGameProfile().getName();
        out.putIfAbsent("{account}", account);
        String kor = out.get("{player}");
        if (kor == null || kor.isBlank()) out.put("{player}", MagicCodexClientLink.nickname(account));
        return out;
    }

    public static String fill(String s, Map<String, String> vars) {
        if (s == null || s.isEmpty() || (s.indexOf('{') < 0 && s.indexOf('%') < 0)) return s;
        for (var e : vars.entrySet()) s = s.replace(e.getKey(), e.getValue());
        return s;
    }
}
