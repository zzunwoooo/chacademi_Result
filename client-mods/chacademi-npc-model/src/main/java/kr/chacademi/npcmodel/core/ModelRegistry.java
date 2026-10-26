package kr.chacademi.npcmodel.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 클라이언트 모델 목록 (models.json). 서버는 모델 ID만 보내고, 실제 파일 위치·해시·클립 이름은 여기서만 정해진다.
 * 서버가 URL·경로를 지정하는 길은 없다.
 *
 * <pre>
 * {"formatVersion":1,"models":{"luka":{"file":"luka.glb","sha256":"…","clips":{"idle":"idle","idleVariants":["look_around"],
 *   "walk":"walk","talk":"wait","greet":null},"walkSpeed":1.23,"scale":1.0,"boneMap":{},"clipFiles":[],"lods":[]}}}
 * </pre>
 */
public final class ModelRegistry {
    public static final Pattern MODEL_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_.-]{1,64}\\.glb");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    public final Path dir;
    public final Map<String, Entry> models;
    public final List<String> diagnostics;

    private ModelRegistry(Path dir, Map<String, Entry> models, List<String> diagnostics) {
        this.dir = dir;
        this.models = models;
        this.diagnostics = diagnostics;
    }

    /** 항목 하나만 바꾼 복사본 (게임 안 조절 명령). */
    public ModelRegistry replace(Entry e) {
        Map<String, Entry> m = new LinkedHashMap<>(models);
        m.put(e.id(), e);
        return new ModelRegistry(dir, Map.copyOf(m), diagnostics);
    }

    /** 지금 값(비율·키)을 models.json 에 저장. 다른 항목·키는 그대로 둔다. */
    public void save(Entry e) throws java.io.IOException {
        Path f = dir.resolve("models.json");
        Map<String, Object> root = Json.obj(Json.parse(Files.readString(f, StandardCharsets.UTF_8)), "models.json");
        Map<String, Object> entry = Json.obj(Json.obj(root.get("models"), "models").get(e.id()), e.id());
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("legs", round(e.proportion().legs()));
        pr.put("torso", round(e.proportion().torso()));
        pr.put("arms", round(e.proportion().arms()));
        entry.put("proportion", pr);
        entry.put("height", round(e.height()));
        Path tmp = f.resolveSibling("models.json.tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static Double round(float v) {
        return Math.round(v * 1000) / 1000.0;
    }

    public static ModelRegistry empty(Path dir, String why) {
        return new ModelRegistry(dir, Map.of(), List.of(why));
    }

    /** 폴더의 models.json 을 읽는다. 항목 하나가 틀리면 그 항목만 빼고 진단을 남긴다. */
    public static ModelRegistry load(Path dir) {
        Path f = dir.resolve("models.json");
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            return empty(dir, "models.json 없음: " + f);
        }
        try {
            if (Files.size(f) > 1 << 20) {
                return empty(dir, "models.json 이 너무 큼");
            }
            return parse(dir, Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException | ModelFormatException e) {
            return empty(dir, "models.json 읽기 실패: " + e.getMessage());
        }
    }

    public static ModelRegistry parse(Path dir, String text) {
        List<String> diag = new ArrayList<>();
        Map<String, Object> root = Json.obj(Json.parse(text), "models.json");
        int fv = Json.integer(root, "formatVersion", 1);
        if (fv != 1) {
            throw new ModelFormatException("models.json formatVersion " + fv + " 미지원");
        }
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.obj(root.get("models"), "models").entrySet()) {
            String id = e.getKey();
            try {
                if (!MODEL_ID.matcher(id).matches()) {
                    throw new ModelFormatException("모델 ID 형식 (영소문자·숫자·_- 1~32자)");
                }
                out.put(id, entry(dir, id, Json.obj(e.getValue(), id)));
            } catch (ModelFormatException ex) {
                diag.add("모델 '" + id + "' 제외: " + ex.getMessage());
            }
        }
        return new ModelRegistry(dir, Map.copyOf(out), List.copyOf(diag));
    }

    private static Entry entry(Path dir, String id, Map<String, Object> m) {
        FileRef main = file(dir, m, "file", "sha256");
        Map<String, Object> clips = m.containsKey("clips") ? Json.obj(m.get("clips"), "clips") : Map.of();
        String idle = Json.str(clips, "idle", null);
        String walk = Json.str(clips, "walk", null);
        String talk = Json.str(clips, "talk", null);
        String greet = Json.str(clips, "greet", null);
        List<String> variants = new ArrayList<>();
        for (Object o : Json.arr(clips.get("idleVariants"), "idleVariants")) {
            if (!(o instanceof String s)) {
                throw new ModelFormatException("idleVariants 는 문자열 배열");
            }
            variants.add(s);
        }
        float walkSpeed = (float) Json.num(m, "walkSpeed", 0);
        if (walkSpeed < 0 || walkSpeed > 20) {
            throw new ModelFormatException("walkSpeed 범위 (0~20)");
        }
        float scale = (float) Json.num(m, "scale", 1);
        if (scale < 0.05f || scale > 20) {
            throw new ModelFormatException("scale 범위 (0.05~20)");
        }
        Map<String, String> boneMap = new LinkedHashMap<>();
        if (m.containsKey("boneMap")) {
            for (Map.Entry<String, Object> b : Json.obj(m.get("boneMap"), "boneMap").entrySet()) {
                if (!(b.getValue() instanceof String s)) {
                    throw new ModelFormatException("boneMap 값은 문자열");
                }
                boneMap.put(b.getKey(), s);
            }
        }
        List<FileRef> clipFiles = new ArrayList<>();
        for (Object o : Json.arr(m.get("clipFiles"), "clipFiles")) {
            clipFiles.add(file(dir, Json.obj(o, "clipFiles"), "file", "sha256"));
        }
        List<Lod> lods = new ArrayList<>();
        float lastFrom = 0;
        for (Object o : Json.arr(m.get("lods"), "lods")) {
            Map<String, Object> l = Json.obj(o, "lods");
            float from = (float) Json.num(l, "from", -1);
            if (from <= lastFrom || from > 256) {
                throw new ModelFormatException("lods.from 은 증가하는 양수(블록)여야 함");
            }
            lastFrom = from;
            lods.add(new Lod(file(dir, l, "file", "sha256"), from));
        }
        float plate = (float) Json.num(m, "nameplateOffset", 0);
        Proportion.Factors prop = Proportion.Factors.NONE;
        if (m.containsKey("proportion")) {
            Map<String, Object> pr = Json.obj(m.get("proportion"), "proportion");
            prop = new Proportion.Factors(factor(pr, "legs"), factor(pr, "torso"), factor(pr, "arms"));
        }
        float height = (float) Json.num(m, "height", 0);
        if (height != 0 && (height < 0.5f || height > 25f)) {
            throw new ModelFormatException("height 범위 (0.5~25 블록, 0 = scale 사용)");
        }
        return new Entry(id, main, idle, walk, talk, greet, List.copyOf(variants), walkSpeed, scale,
                Map.copyOf(boneMap), List.copyOf(clipFiles), List.copyOf(lods), Math.max(-2, Math.min(2, plate)), prop, height);
    }

    private static float factor(Map<String, Object> m, String key) {
        float v = (float) Json.num(m, key, 1);
        if (v < 0.5f || v > 1.5f) {
            throw new ModelFormatException("proportion." + key + " 범위 (0.5~1.5)");
        }
        return v;
    }

    private static FileRef file(Path dir, Map<String, Object> m, String fileKey, String shaKey) {
        String name = Json.str(m, fileKey, null);
        String sha = Json.str(m, shaKey, null);
        if (name == null || !FILE.matcher(name).matches() || name.contains("..")) {
            throw new ModelFormatException(fileKey + " 는 폴더 안의 파일 이름(.glb)만 허용: " + name);
        }
        if (sha == null || !SHA.matcher(sha).matches()) {
            throw new ModelFormatException(shaKey + " 는 소문자 64자리 sha256");
        }
        Path p = dir.resolve(name).normalize();
        if (!p.getParent().equals(dir.normalize())) {
            throw new ModelFormatException("폴더 밖 경로 거부: " + name);
        }
        return new FileRef(name, p, sha);
    }

    public record FileRef(String name, Path path, String sha256) {
    }

    public record Lod(FileRef file, float fromBlocks) {
    }

    public record Entry(String id, FileRef file, String idleClip, String walkClip, String talkClip, String greetClip,
                        List<String> idleVariants, float walkSpeed, float scale, Map<String, String> boneMap,
                        List<FileRef> clipFiles, List<Lod> lods, float nameplateOffset,
                        Proportion.Factors proportion, float height) {
        /** 게임 안 조절 명령용: 비율·키만 바꾼 복사본. */
        public Entry with(Proportion.Factors p, float h) {
            return new Entry(id, file, idleClip, walkClip, talkClip, greetClip, idleVariants, walkSpeed, scale, boneMap,
                    clipFiles, lods, nameplateOffset, p, h);
        }
    }

    /** 다른 GLB의 클립을 이 모델 노드 이름에 맞춰 옮긴다 (같은 골격 가정, 이름만 맞춤). */
    public static ModelData.Clip retarget(ModelData.Clip clip, ModelData source, ModelData target, Map<String, String> boneMap,
                                          List<String> diag) {
        List<ModelData.Channel> chs = new ArrayList<>();
        int dropped = 0, moves = 0;
        java.util.Set<Integer> targetJoints = new java.util.HashSet<>();
        for (ModelData.Skin s : target.skins) {
            for (int j : s.joints) {
                targetJoints.add(j);
            }
        }
        for (ModelData.Channel c : clip.channels) {
            String srcName = source.nodes[c.node].name;
            int dst = findNode(target, boneMap.getOrDefault(srcName, srcName));
            if (dst < 0) {
                dropped++;
                continue;
            }
            if (c.path != ModelData.PATH_R) {
                moves++; // 위치·크기 채널은 버린다: 모델마다 단위·뼈 길이가 달라서 그대로 쓰면 몸이 뜨거나 늘어남
                continue;
            }
            float[] values = c.values;
            boolean root = targetJoints.contains(dst) && (target.parent[dst] < 0 || !targetJoints.contains(target.parent[dst]));
            if (root) {
                // 뼈대 맨 위(Hips)는 파일마다 부모 좌표가 달라서(FBX 축 보정 등) 기본 자세 대비 회전량으로 옮긴다
                float[] rt = target.nodes[dst].r, rs = source.nodes[c.node].r;
                float[] rel = new float[4];
                MathUtil.quatMul(rt, 0, new float[]{-rs[0], -rs[1], -rs[2], rs[3]}, 0, rel, 0);
                values = new float[c.values.length];
                for (int k = 0; k + 3 < values.length; k += 4) {
                    MathUtil.quatMul(rel, 0, c.values, k, values, k);
                }
            }
            chs.add(new ModelData.Channel(dst, c.path, c.interpolation, c.times, values));
        }
        if (dropped > 0) {
            diag.add("클립 '" + clip.name + "': 맞는 뼈가 없는 채널 " + dropped + "개 제외");
        }
        if (moves > 0) {
            diag.add("클립 '" + clip.name + "': 다른 파일에서 가져와 위치·크기 채널 " + moves + "개 제외 (회전만 사용)");
        }
        return new ModelData.Clip(clip.name, clip.duration, chs.toArray(new ModelData.Channel[0]));
    }

    /** 정확히 같은 이름 → 접두어(":" 앞, 예 mixamorig:) 뗀 이름 순으로 찾는다. */
    static int findNode(ModelData m, String name) {
        int k = m.nodeByName(name);
        if (k >= 0) {
            return k;
        }
        String bare = name.substring(name.lastIndexOf(':') + 1);
        for (int i = 0; i < m.nodes.length; i++) {
            String n = m.nodes[i].name;
            if (n.substring(n.lastIndexOf(':') + 1).equals(bare)) {
                return i;
            }
        }
        return -1;
    }
}
