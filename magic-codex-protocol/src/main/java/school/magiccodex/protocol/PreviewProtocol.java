package school.magiccodex.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 서버 → 클라이언트 "미리보기 열기" (2026-10-20 명령어 정리: 클라이언트 전용 미리보기 명령을 /테스트 아래로 옮김).
 * 채널 {@value #CHANNEL}, 내용은 UTF-8 한 줄: "&lt;종류&gt; [값...]" (공백으로 나눔).
 *
 * <pre>
 * kyudichi  &lt;lobby|duel_lobby|apply|duel|prepare|match|result|accept|matching|dashboard|off&gt;
 * targethud &lt;normal|shiny|player|boss|off&gt;
 * season    &lt;spring|summer|autumn|winter|id&gt;
 * temperature &lt;섭씨 -100..100|auto&gt;
 * notice    &lt;system|warning|error|shop&gt;
 * sound     &lt;cueId|stop&gt;
 * discovery [spellId]
 * ascension [circle 2..9]
 * </pre>
 */
public final class PreviewProtocol {
    private PreviewProtocol() {}
    public static final String CHANNEL = "magiccodex:preview";
    public static final int MAX_BYTES = 512;

    public static byte[] encode(String kind, String... values) {
        var sb = new StringBuilder(kind);
        for (String v : values) if (v != null && !v.isBlank()) sb.append(' ').append(v.strip());
        byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (out.length > MAX_BYTES) throw new IllegalArgumentException("preview too long");
        return out;
    }

    /** [종류, 값...]. 잘못된 내용이면 빈 목록. */
    public static List<String> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return List.of();
        String s = new String(bytes, StandardCharsets.UTF_8).strip();
        if (s.isEmpty()) return List.of();
        return Arrays.asList(s.split(" +"));
    }
}
