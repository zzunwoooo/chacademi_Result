import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 할로윈 펫 모델 묶음(NW Pets Halloween, Blockbench 5 형식) → 다이아서버 몬스터용 blueprints 로 바꾼다.
 * 사용: java -cp gson.jar NwMobs.java <nw_pets_vol2 폴더> <출력 폴더>
 *
 * 하는 일:
 *  - Blockbench 5 형식(뼈 정보가 groups 에 따로 있음) → 4.10 형식(outliner 안에 뼈 정보)으로 펼침. ModelEngine 4.0.8 이 확실히 읽는 형식
 *  - 이름 nw_xxx → dmob_xxx (나중에 원래 펫 묶음을 같이 깔아도 겹치지 않게)
 *  - 걷기 애니메이션 이름 move → walk (ModelEngine 이 걸을 때 저절로 트는 이름)
 *  - hitbox 뼈 추가 (모델 크기에 맞춘 상자. 게임에서 때리는 범위)
 *  - 공격용 애니메이션 6개 추가: attack(근접 휘두르기) cast(원거리 쏘기) ready(돌진 준비, 끝 자세 유지)
 *    slam(뛰어올라 내려찍기) vanish(사라지기, 끝 자세 유지) appear(나타나기)
 * 모델 파일 자체는 산 묶음이라 저장소에 넣지 않는다 (호스팅과 PC 자료 폴더에만).
 */
public class NwMobs {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static void main(String[] a) throws Exception {
        Path in = Path.of(a[0]), out = Path.of(a[1]);
        Files.createDirectories(out);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(in, "nw_*.bbmodel")) { ds.forEach(files::add); }
        Collections.sort(files);
        for (Path f : files) {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = "dmob_" + f.getFileName().toString().replace("nw_", "").replace(".bbmodel", "");
            convert(o, id);
            Files.writeString(out.resolve(id + ".bbmodel"), GSON.toJson(o), StandardCharsets.UTF_8);
            System.out.println(id + " 완료");
        }
    }

    static void convert(JsonObject o, String id) {
        o.getAsJsonObject("meta").addProperty("format_version", "4.10");
        o.addProperty("name", id);
        o.addProperty("model_identifier", id);

        // ── 뼈 정보 펼치기 ──
        Map<String, JsonObject> groups = new HashMap<>();
        if (o.has("groups")) for (JsonElement e : o.getAsJsonArray("groups")) groups.put(e.getAsJsonObject().get("uuid").getAsString(), e.getAsJsonObject());
        JsonArray outliner = inline(o.getAsJsonArray("outliner"), groups);
        o.remove("groups");
        o.add("outliner", outliner);

        // 뼈 이름 → uuid, 맨 위 뼈
        Map<String, String> bone = new LinkedHashMap<>();
        collect(outliner, bone);
        JsonObject root = outliner.get(0).getAsJsonObject();

        // ── 크기 재기 (hitbox 상자 만들기용) ──
        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (JsonElement e : o.getAsJsonArray("elements")) {
            JsonObject el = e.getAsJsonObject();
            if (!el.has("from") || el.get("name").getAsString().contains("outline")) continue;
            for (int i = 0; i < 3; i++) {
                mn[i] = Math.min(mn[i], el.getAsJsonArray("from").get(i).getAsDouble());
                mx[i] = Math.max(mx[i], el.getAsJsonArray("to").get(i).getAsDouble());
            }
        }
        double h = Math.max(6, mx[1] - Math.max(0, mn[1]));
        double w = Math.max(6, Math.min(14, Math.min(mx[0] - mn[0], mx[2] - mn[2])));
        addHitbox(o, outliner, w, h);

        // ── 애니메이션 ──
        JsonArray anims = o.getAsJsonArray("animations");
        for (JsonElement e : anims) {
            JsonObject an = e.getAsJsonObject();
            if (an.get("name").getAsString().equals("move")) an.addProperty("name", "walk");
        }
        Rig r = new Rig(bone, root.get("name").getAsString());
        anims.add(attack(r));
        anims.add(cast(r));
        anims.add(ready(r));
        anims.add(slam(r));
        anims.add(vanish(r));
        anims.add(appear(r));
    }

    /** Blockbench 5 의 outliner 항목 {uuid, children} 에 groups 의 뼈 정보를 붙여 4.10 모양으로 */
    static JsonArray inline(JsonArray arr, Map<String, JsonObject> groups) {
        JsonArray res = new JsonArray();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) { res.add(e); continue; }
            JsonObject item = e.getAsJsonObject();
            JsonObject g = groups.get(item.get("uuid").getAsString());
            JsonObject full = g == null ? item.deepCopy() : g.deepCopy();
            full.remove("_static");
            full.remove("primary_selected");
            full.addProperty("isOpen", false);
            full.add("children", item.has("children") ? inline(item.getAsJsonArray("children"), groups) : new JsonArray());
            res.add(full);
        }
        return res;
    }

    static void collect(JsonArray arr, Map<String, String> bone) {
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject g = e.getAsJsonObject();
            bone.putIfAbsent(g.get("name").getAsString(), g.get("uuid").getAsString());
            collect(g.getAsJsonArray("children"), bone);
        }
    }

    static void addHitbox(JsonObject o, JsonArray outliner, double w, double h) {
        String cube = UUID.randomUUID().toString(), grp = UUID.randomUUID().toString();
        JsonObject el = new JsonObject();
        el.addProperty("name", "hitbox");
        el.addProperty("box_uv", false);
        el.addProperty("rescale", false);
        el.addProperty("locked", false);
        el.addProperty("render_order", "default");
        el.addProperty("allow_mirror_modeling", true);
        el.add("from", arr(-w / 2, 0, -w / 2));
        el.add("to", arr(w / 2, h, w / 2));
        el.addProperty("autouv", 0);
        el.addProperty("color", 0);
        el.add("origin", arr(0, 0, 0));
        JsonObject faces = new JsonObject();
        for (String side : List.of("north", "east", "south", "west", "up", "down")) {
            JsonObject fc = new JsonObject();
            fc.add("uv", arr(0, 0, 1, 1));
            fc.add("texture", JsonNull.INSTANCE);
            faces.add(side, fc);
        }
        el.add("faces", faces);
        el.addProperty("type", "cube");
        el.addProperty("uuid", cube);
        o.getAsJsonArray("elements").add(el);

        JsonObject g = new JsonObject();
        g.addProperty("name", "hitbox");
        g.add("origin", arr(0, h * 0.85, 0));
        g.addProperty("color", 0);
        g.addProperty("uuid", grp);
        g.addProperty("export", true);
        g.addProperty("mirror_uv", false);
        g.addProperty("isOpen", false);
        g.addProperty("locked", false);
        g.addProperty("visibility", true);
        g.addProperty("autouv", 0);
        JsonArray ch = new JsonArray();
        ch.add(cube);
        g.add("children", ch);
        outliner.add(g);
    }

    static JsonArray arr(double... v) { JsonArray r = new JsonArray(); for (double d : v) r.add(Math.round(d * 1000) / 1000.0); return r; }

    // ── 애니메이션 만들기 ─────────────────────────────────
    /** 뼈 고르기: 이름으로 몸통·머리·팔·다리를 찾는다 */
    static final class Rig {
        final Map<String, String> bone;
        final String root;
        final List<String> armsL = new ArrayList<>(), armsR = new ArrayList<>(), legs = new ArrayList<>(), tails = new ArrayList<>();
        String body, head;

        Rig(Map<String, String> bone, String root) {
            this.bone = bone;
            this.root = root;
            for (String n : bone.keySet()) {
                if (n.contains("outline") || n.equals("hitbox")) continue;
                boolean arm = n.contains("hand") || n.contains("arm") || n.contains("wing");
                if (arm && n.contains("small")) continue;
                if (arm) (n.startsWith("left") ? armsL : armsR).add(n);
                else if (n.contains("leg")) legs.add(n);
                else if (n.startsWith("tail")) tails.add(n);
                else if (n.equals("body") && body == null) body = n;
                else if (n.equals("head") && head == null) head = n;
            }
        }
    }

    static final class Anim {
        final JsonObject o = new JsonObject();
        final JsonObject animators = new JsonObject();
        final Rig r;

        Anim(Rig r, String name, String loop, double length) {
            this.r = r;
            o.addProperty("uuid", UUID.randomUUID().toString());
            o.addProperty("name", name);
            o.addProperty("loop", loop);
            o.addProperty("override", false);
            o.addProperty("length", length);
            o.addProperty("snapping", 24);
            o.addProperty("selected", false);
            o.addProperty("anim_time_update", "");
            o.addProperty("blend_weight", "");
            o.addProperty("start_delay", "");
            o.addProperty("loop_delay", "");
            o.add("animators", animators);
        }

        /** 뼈 하나의 한 통로(rotation/position/scale)에 [시간, x, y, z] 키들을 넣는다 */
        Anim key(String boneName, String channel, double[]... keys) {
            if (boneName == null || !r.bone.containsKey(boneName)) return this;
            String uuid = r.bone.get(boneName);
            JsonObject an = animators.has(uuid) ? animators.getAsJsonObject(uuid) : null;
            if (an == null) {
                an = new JsonObject();
                an.addProperty("name", boneName);
                an.addProperty("type", "bone");
                an.add("keyframes", new JsonArray());
                animators.add(uuid, an);
            }
            for (double[] k : keys) {
                JsonObject kf = new JsonObject();
                kf.addProperty("channel", channel);
                JsonObject dp = new JsonObject();
                dp.addProperty("x", num(k[1]));
                dp.addProperty("y", num(k[2]));
                dp.addProperty("z", num(k[3]));
                JsonArray dps = new JsonArray();
                dps.add(dp);
                kf.add("data_points", dps);
                kf.addProperty("uuid", UUID.randomUUID().toString());
                kf.addProperty("time", k[0]);
                kf.addProperty("color", -1);
                kf.addProperty("interpolation", "linear");
                an.getAsJsonArray("keyframes").add(kf);
            }
            return this;
        }

        /** 왼팔·오른팔 양쪽에 같은 키 (z 회전은 좌우 반대로) */
        Anim arms(String channel, double[]... keys) {
            for (String n : r.armsL) key(n, channel, keys);
            double[][] mir = new double[keys.length][];
            for (int i = 0; i < keys.length; i++) mir[i] = new double[]{keys[i][0], keys[i][1], -keys[i][2], -keys[i][3]};
            for (String n : r.armsR) key(n, channel, mir);
            return this;
        }

        static String num(double v) {
            double x = Math.round(v * 100) / 100.0;
            return x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x);
        }
    }

    static double[] k(double t, double x, double y, double z) { return new double[]{t, x, y, z}; }

    /** 근접 공격 0.5초: 뒤로 젖혔다가 앞으로 확 덤빈다. 팔은 위로 들었다 내리친다 */
    static JsonObject attack(Rig r) {
        Anim a = new Anim(r, "attack", "once", 0.5);
        a.key(r.root, "rotation", k(0, 0, 0, 0), k(0.12, -14, 0, 0), k(0.25, 22, 0, 0), k(0.5, 0, 0, 0));
        a.key(r.root, "position", k(0, 0, 0, 0), k(0.12, 0, 0.5, 1.5), k(0.25, 0, 0, -3), k(0.5, 0, 0, 0));
        a.key(r.head, "rotation", k(0, 0, 0, 0), k(0.12, -12, 0, 0), k(0.25, 14, 0, 0), k(0.5, 0, 0, 0));
        a.arms("rotation", k(0, 0, 0, 0), k(0.12, -120, 0, 10), k(0.25, 25, 0, -5), k(0.5, 0, 0, 0));
        for (String t : r.tails) a.key(t, "rotation", k(0, 0, 0, 0), k(0.12, 25, 0, 0), k(0.25, -20, 0, 0), k(0.5, 0, 0, 0));
        return a.o;
    }

    /** 원거리 0.8초: 몸을 띄우며 힘을 모았다가(0.3초) 앞으로 내쏜다(0.45초) */
    static JsonObject cast(Rig r) {
        Anim a = new Anim(r, "cast", "once", 0.8);
        a.key(r.root, "position", k(0, 0, 0, 0), k(0.3, 0, 1.5, 0.5), k(0.45, 0, 1, -1.5), k(0.8, 0, 0, 0));
        a.key(r.root, "rotation", k(0, 0, 0, 0), k(0.3, -16, 0, 0), k(0.45, 14, 0, 0), k(0.8, 0, 0, 0));
        a.key(r.root, "scale", k(0, 1, 1, 1), k(0.3, 1.08, 1.12, 1.08), k(0.45, 0.94, 0.92, 1.06), k(0.8, 1, 1, 1));
        a.key(r.head, "rotation", k(0, 0, 0, 0), k(0.3, -20, 0, 0), k(0.45, 12, 0, 0), k(0.8, 0, 0, 0));
        a.arms("rotation", k(0, 0, 0, 0), k(0.3, -150, 0, 25), k(0.45, -85, 0, 5), k(0.8, 0, 0, 0));
        return a.o;
    }

    /** 돌진 준비 0.7초 (끝 자세 유지): 앞으로 숙이고 몸을 떤다 */
    static JsonObject ready(Rig r) {
        Anim a = new Anim(r, "ready", "hold", 0.7);
        a.key(r.root, "rotation", k(0, 0, 0, 0), k(0.25, 20, 0, 0), k(0.35, 20, 5, 0), k(0.45, 20, -5, 0), k(0.55, 20, 5, 0), k(0.7, 22, 0, 0));
        a.key(r.root, "position", k(0, 0, 0, 0), k(0.25, 0, -1, 1), k(0.7, 0, -1, 1.5));
        a.key(r.head, "rotation", k(0, 0, 0, 0), k(0.25, -18, 0, 0), k(0.7, -18, 0, 0));
        a.arms("rotation", k(0, 0, 0, 0), k(0.25, 40, 0, 15), k(0.7, 45, 0, 15));
        for (String t : r.tails) a.key(t, "rotation", k(0, 0, 0, 0), k(0.25, 30, 0, 0), k(0.7, 35, 0, 0));
        return a.o;
    }

    /** 내려찍기 0.6초: 뛰어올랐다가(0.25초) 쾅(0.35초) */
    static JsonObject slam(Rig r) {
        Anim a = new Anim(r, "slam", "once", 0.6);
        a.key(r.root, "position", k(0, 0, 0, 0), k(0.08, 0, -1, 0), k(0.25, 0, 7, 0), k(0.35, 0, -0.5, 0), k(0.6, 0, 0, 0));
        a.key(r.root, "scale", k(0, 1, 1, 1), k(0.08, 1.12, 0.86, 1.12), k(0.25, 0.9, 1.15, 0.9), k(0.35, 1.28, 0.72, 1.28), k(0.6, 1, 1, 1));
        a.key(r.root, "rotation", k(0, 0, 0, 0), k(0.25, -12, 0, 0), k(0.35, 10, 0, 0), k(0.6, 0, 0, 0));
        a.arms("rotation", k(0, 0, 0, 0), k(0.25, -170, 0, 20), k(0.35, -20, 0, 35), k(0.6, 0, 0, 0));
        return a.o;
    }

    /** 사라지기 0.35초 (끝 자세 유지): 살짝 부풀었다가 빙글 돌며 작아진다 */
    static JsonObject vanish(Rig r) {
        Anim a = new Anim(r, "vanish", "hold", 0.35);
        a.key(r.root, "scale", k(0, 1, 1, 1), k(0.1, 1.2, 1.2, 1.2), k(0.35, 0.02, 0.02, 0.02));
        a.key(r.root, "rotation", k(0, 0, 0, 0), k(0.35, 0, 270, 0));
        a.key(r.root, "position", k(0, 0, 0, 0), k(0.35, 0, 2, 0));
        return a.o;
    }

    /** 나타나기 0.35초: 작은 점에서 툭 커지며 자리 잡는다 */
    static JsonObject appear(Rig r) {
        Anim a = new Anim(r, "appear", "once", 0.35);
        a.key(r.root, "scale", k(0, 0.02, 0.02, 0.02), k(0.2, 1.25, 1.25, 1.25), k(0.35, 1, 1, 1));
        a.key(r.root, "rotation", k(0, 0, -270, 0), k(0.2, 0, 0, 0));
        a.key(r.root, "position", k(0, 0, 2, 0), k(0.2, 0, 0, 0));
        return a.o;
    }
}
