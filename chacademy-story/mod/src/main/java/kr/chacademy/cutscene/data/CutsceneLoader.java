package kr.chacademy.cutscene.data;

import net.fabricmc.loader.api.FabricLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** config/chaca_cutscene/&lt;id&gt;/cutscene.yml 을 읽는다. */
public final class CutsceneLoader {
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_\\-]{1,64}");

    private CutsceneLoader() {}

    public static Path rootDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("chaca_cutscene");
    }

    public static Path folder(String id) {
        return rootDir().resolve(id);
    }

    /** 폴더 안에 cutscene.yml 이 있는 컷신 id 목록. */
    public static List<String> listIds() {
        List<String> ids = new ArrayList<>();
        Path root = rootDir();
        if (!Files.isDirectory(root)) return ids;
        try (Stream<Path> s = Files.list(root)) {
            s.filter(p -> Files.isRegularFile(p.resolve("cutscene.yml")))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> ID_PATTERN.matcher(n).matches())
                    .sorted()
                    .forEach(ids::add);
        } catch (IOException ignored) {
        }
        return ids;
    }

    public static Cutscene load(String id) throws IOException {
        if (!ID_PATTERN.matcher(id).matches()) throw new IOException("잘못된 컷신 id: " + id);
        Path file = folder(id).resolve("cutscene.yml");
        // 파일이 아예 없는 것은 따로 구분한다 (서버에 "없음" 과 "깨짐" 을 다르게 알림)
        if (!Files.isRegularFile(file)) throw new java.nio.file.NoSuchFileException(file.toString(), null, "파일 없음");
        if (Files.size(file) > 2L * 1024 * 1024) throw new IOException("cutscene.yml 이 너무 큼");

        Object root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
        } catch (RuntimeException e) {
            throw new IOException("yml 형식 오류: " + e.getMessage(), e);
        }
        return parse(id, root);
    }

    /** 장면 수 / 장면당 그림 수 / 장면당 자막 수 한도 (실수로 만든 거대한 파일이 게임을 멈추지 않게). */
    static final int MAX_SCENES = 200, MAX_IMAGES = 120, MAX_LINES = 200;

    /**
     * 읽어 들인 cutscene.yml (Map) 을 검사해서 {@link Cutscene} 으로 만든다. 파일을 건드리지 않아 단위 테스트가 된다.
     * 숫자 자리에 NaN·무한대가 오면 오류 (끝나지 않는 컷신이 되지 않게).
     */
    public static Cutscene parse(String id, Object root) throws IOException {
        if (id == null || !ID_PATTERN.matcher(id).matches()) throw new IOException("잘못된 컷신 id: " + id);
        if (!(root instanceof Map<?, ?> m)) throw new IOException("yml 최상위가 비어 있음");

        List<Cutscene.Scene> scenes = new ArrayList<>();
        int si = 0;
        for (Object o : list(m.get("scenes"))) {
            if (!(o instanceof Map<?, ?> sm)) continue;
            String at = "scenes[" + (si++) + "]";
            if (scenes.size() >= MAX_SCENES) throw new IOException("장면이 너무 많음 (최대 " + MAX_SCENES + "개)");
            List<String> images = new ArrayList<>();
            for (Object img : list(sm.get("images"))) images.add(imageName(String.valueOf(img)));
            // 예전 형식의 image: 한 장 (같은 이름 검사를 거친다)
            if (images.isEmpty() && sm.get("image") != null) images.add(imageName(String.valueOf(sm.get("image"))));
            if (images.isEmpty()) throw new IOException("이미지가 없는 장면이 있음: " + at);
            if (images.size() > MAX_IMAGES) throw new IOException("한 장면의 이미지가 너무 많음 (최대 " + MAX_IMAGES + "장): " + at);

            Map<?, ?> zoom = sm.get("zoom") instanceof Map<?, ?> z ? z : Map.of();
            List<Cutscene.Line> lines = new ArrayList<>();
            for (Object lo : list(sm.get("lines"))) {
                if (!(lo instanceof Map<?, ?> lm)) continue;
                if (lines.size() >= MAX_LINES) throw new IOException("한 장면의 자막이 너무 많음 (최대 " + MAX_LINES + "개): " + at);
                double start = clamp(num(lm.get("start"), 0, at + ".lines.start"), 0, 600);
                double end = clamp(num(lm.get("end"), start + 3, at + ".lines.end"), 0, 600);
                lines.add(new Cutscene.Line(str(lm.get("text"), ""), start, Math.max(end, start + 0.1)));
            }
            scenes.add(new Cutscene.Scene(
                    List.copyOf(images),
                    clamp(num(sm.get("fps"), 6, at + ".fps"), 0.5, 30),
                    clamp(num(sm.get("duration"), 5, at + ".duration"), 0.2, 600),
                    clamp(num(zoom.get("x"), 0.5, at + ".zoom.x"), 0, 1),
                    clamp(num(zoom.get("y"), 0.5, at + ".zoom.y"), 0, 1),
                    clamp(num(zoom.get("from"), 1.0, at + ".zoom.from"), 1, 4),
                    clamp(num(zoom.get("to"), 1.0, at + ".zoom.to"), 1, 4),
                    str(sm.get("transition"), "ink"),
                    clamp(num(sm.get("transition_time"), 1.2, at + ".transition_time"), 0, 10),
                    List.copyOf(lines)));
        }
        if (scenes.isEmpty()) throw new IOException("장면이 하나도 없음");

        return new Cutscene(
                id,
                str(m.get("title"), id),
                str(m.get("font"), "medium"),
                clamp(num(m.get("font_size"), 1.0, "font_size"), 0.4, 2.5),
                color(m.get("text_color"), 0xEFE3C8),
                clamp(num(m.get("text_x"), 0.5, "text_x"), 0, 1),
                textY(m.get("text_y")),
                bool(m.get("text_shadow"), false),
                bool(m.get("italic"), true),
                str(m.get("prefix"), "- "),
                clamp(num(m.get("type_speed"), 0.06, "type_speed"), 0, 1),
                clamp(num(m.get("letterbox"), 0.14, "letterbox"), 0, 0.3),
                clamp(num(m.get("letterbox_time"), 1.6, "letterbox_time"), 0, 10),
                clamp(num(m.get("end_fade"), 1.2, "end_fade"), 0, 10),
                str(m.get("type_sound"), "default").trim(),
                clamp(num(m.get("type_sound_volume"), 0.5, "type_sound_volume"), 0, 1),
                bgmName(m.get("bgm")),
                clamp(num(m.get("bgm_volume"), 0.6, "bgm_volume"), 0, 1),
                clamp(num(m.get("bgm_start"), 0, "bgm_start"), 0, 3600),
                clamp(num(m.get("bgm_fade_in"), 3.0, "bgm_fade_in"), 0, 30),
                clamp(num(m.get("bgm_fade_out"), 4.0, "bgm_fade_out"), 0, 30),
                scenes);
    }

    /** 컷신 폴더 안의 ogg 파일 이름. 비우면 배경음 없음. */
    private static String bgmName(Object o) throws IOException {
        if (o == null) return "";
        String name = o.toString().trim();
        if (name.isEmpty() || name.equalsIgnoreCase("none")) return "";
        if (name.contains("/") || name.contains("\\") || name.contains("..")) throw new IOException("bgm 이름에 경로를 넣을 수 없음: " + name);
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")) throw new IOException("bgm은 ogg 파일만 쓸 수 있어요: " + name);
        return name;
    }

    /** "#RRGGBB" 또는 "RRGGBB". 틀리면 기본값. */
    private static int color(Object o, int def) {
        if (o == null) return def;
        String t = o.toString().trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (!t.matches("[0-9a-fA-F]{6}")) return def;
        return Integer.parseInt(t, 16);
    }

    /** "auto" 또는 비우면 아래 띠 가운데, 숫자면 화면 높이 비율 (0 위 ~ 1 아래). */
    private static double textY(Object o) throws IOException {
        if (o == null || o.toString().isBlank() || "auto".equalsIgnoreCase(o.toString().trim())) return Cutscene.AUTO;
        return clamp(num(o, Cutscene.AUTO, "text_y"), 0, 1);
    }

    /** 컷신 폴더 안의 PNG 파일 이름 (경로 불가). */
    private static String imageName(String name) throws IOException {
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IOException("이미지 이름에 경로를 넣을 수 없음: " + name);
        }
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            throw new IOException("PNG만 쓸 수 있어요 (편집기에서 내보내면 자동 변환): " + name);
        }
        return name;
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    /** 숫자가 아니면 기본값. NaN·무한대는 오류 (clamp 를 그대로 통과해서 끝나지 않는 컷신이 되기 때문). */
    private static double num(Object o, double def, String at) throws IOException {
        double v = def;
        if (o instanceof Number n) v = n.doubleValue();
        else if (o != null) {
            try {
                v = Double.parseDouble(o.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new IOException("숫자가 아님 (NaN/무한대): " + at);
        return v;
    }

    private static String str(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(o.toString());
        return def;
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
