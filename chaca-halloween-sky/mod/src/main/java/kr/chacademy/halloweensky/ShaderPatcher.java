package kr.chacademy.halloweensky;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Complementary Unbound r5.5.1 을 고쳐 배포하지 않고, Iris 가 셰이더 파일을 읽을 때 메모리에서만 우리 변경분(halloween.diff)을 끼운다.
 * 사용자 셰이더 파일은 그대로. 다른 셰이더·다른 버전이면 아무것도 안 한다. 한 파일이라도 맞지 않으면 그 파일은 원본 그대로 둔다.
 */
public final class ShaderPatcher {
    static final String PACK = "ComplementaryUnbound_r5.5.1";
    private static final Logger LOG = LoggerFactory.getLogger("chaca_sky");
    private static Map<String, List<Hunk>> patches;

    private record Hunk(List<String> before, List<String> after) {}

    private ShaderPatcher() {}

    /** 이 파일에 끼울 내용이 있으면 고친 전체 내용, 없으면 null. */
    public static String patched(Path file) {
        String key = keyFor(file);
        if (key == null) return null;
        List<Hunk> hunks = load().get(key);
        if (hunks == null) return null;
        String original = null;
        try {
            // Iris 는 .properties 를 ISO-8859-1, glsl 을 UTF-8 로 읽는다
            if (Files.exists(file)) original = new String(Files.readAllBytes(file),
                    key.endsWith(".properties") ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        String result = apply(original, hunks);
        if (result == null) LOG.warn("[할로윈 하늘] 셰이더 파일 {} 이 예상과 달라 원본 그대로 둡니다.", key);
        return result;
    }

    /** 팩 안 경로 "shaders/..." (우리 팩일 때만). zip 이면 파일시스템 이름에 팩 이름이 들어 있다. */
    static String keyFor(Path file) {
        String full = file.toString();
        if (file.getFileSystem() != FileSystems.getDefault()) full = file.getFileSystem() + "!" + full;
        full = full.replace('\\', '/');
        int pack = full.indexOf(PACK);
        if (pack < 0) return null;
        int at = full.indexOf("/shaders/", pack);
        return at < 0 ? null : full.substring(at + 1);
    }

    private static String apply(String original, List<Hunk> hunks) {
        if (original == null) {
            // 새 파일 (우리 코드만 있는 파일)
            if (hunks.size() != 1 || !hunks.get(0).before().isEmpty()) return null;
            return String.join("\n", hunks.get(0).after()) + "\n";
        }
        List<String> lines = new ArrayList<>(List.of(original.split("\n", -1)));
        int cursor = 0;
        for (Hunk h : hunks) {
            int at = find(lines, h.before(), cursor);
            if (at < 0) return null;
            for (int i = 0; i < h.before().size(); i++) lines.remove(at);
            lines.addAll(at, h.after());
            cursor = at + h.after().size();
        }
        return String.join("\n", lines);
    }

    private static int find(List<String> lines, List<String> seq, int from) {
        outer:
        for (int i = from; i + seq.size() <= lines.size(); i++) {
            for (int j = 0; j < seq.size(); j++) {
                if (!lines.get(i + j).stripTrailing().equals(seq.get(j).stripTrailing())) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static synchronized Map<String, List<Hunk>> load() {
        if (patches != null) return patches;
        patches = new HashMap<>();
        try (InputStream in = ShaderPatcher.class.getResourceAsStream("/chaca_sky_shader/halloween.diff")) {
            if (in == null) return patches;
            String key = null;
            List<String> before = null, after = null;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("diff ") || line.startsWith("--- ") || line.startsWith("\\")) continue;
                if (line.startsWith("+++ ")) {
                    String p = line.substring(4).split("\t")[0];
                    key = p.substring(p.indexOf('/') + 1); // "cuh2/shaders/..." → "shaders/..."
                    patches.put(key, new ArrayList<>());
                    before = after = null;
                } else if (line.startsWith("@@")) {
                    before = new ArrayList<>();
                    after = new ArrayList<>();
                    patches.get(key).add(new Hunk(before, after));
                } else if (before != null) {
                    String body = line.isEmpty() ? "" : line.substring(1);
                    char c = line.isEmpty() ? ' ' : line.charAt(0);
                    if (c == ' ' || c == '-') before.add(body);
                    if (c == ' ' || c == '+') after.add(body);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("[할로윈 하늘] 셰이더 변경분을 읽지 못했습니다.", e);
            patches = new HashMap<>();
        }
        return patches;
    }
}
