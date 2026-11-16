import com.google.gson.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * UFO 소품 모델(farm_ufo_beam) 생성기. 다른 소품 모델들과 같은 형식으로 만든다:
 * Blockbench "Modded Entity" · Box UV · 텍스처 내장 · 애니메이션 이름 animation.<id>.<name>.
 * (원래 이 형식은 파이썬 도구로 만들지만 이 PC 에는 파이썬이 없어서 같은 규칙을 자바로 옮겼다.)
 *
 * 사용: java -cp gson.jar UfoGen.java <models 폴더>
 *   → <models>/farm_ufo_beam.bbmodel, farm_ufo_beam.png
 *
 * 좌표: 1블럭 = 16. 모델의 기준점(0,0,0) = 비행접시 바닥 가운데. 빔은 아래(-y)로 5블럭 뻗는다.
 *       → 게임에서는 대상의 발밑에서 5블럭쯤 위에 세운다.
 * 애니메이션: spawn(내려와 자리 잡음) · idle(떠서 돎, 빔 꺼짐) · beam(빔 켜짐) · leave(빔 끄고 날아감)
 */
public class UfoGen {
    static final String ID = "farm_ufo_beam";

    // ── 모델 묘사 ─────────────────────────────────────────────
    interface Painter { void paint(BufferedImage img, String face, int x1, int y1, int x2, int y2, Random rng); }

    static abstract class Node { String name; final String uuid = UUID.randomUUID().toString(); }

    static final class Cube extends Node {
        double[] from, to, rot = {0, 0, 0}, origin;
        Painter paint;
        int[] uv;
        Cube(String name, double[] from, double[] to, Painter paint) {
            this.name = name; this.from = from; this.to = to; this.paint = paint;
            this.origin = new double[]{(from[0] + to[0]) / 2, (from[1] + to[1]) / 2, (from[2] + to[2]) / 2};
        }
        int[] size() { return new int[]{(int) Math.round(to[0] - from[0]), (int) Math.round(to[1] - from[1]), (int) Math.round(to[2] - from[2])}; }
    }

    static final class Group extends Node {
        double[] origin, rot = {0, 0, 0};
        List<Node> children = new ArrayList<>();
        Group(String name, double[] origin, Node... kids) { this.name = name; this.origin = origin; children.addAll(Arrays.asList(kids)); }
    }

    static double[] v(double x, double y, double z) { return new double[]{x, y, z}; }

    static void cubes(Group g, List<Cube> out) { for (Node n : g.children) if (n instanceof Cube c) out.add(c); else cubes((Group) n, out); }
    static void groups(Group g, List<Group> out) { out.add(g); for (Node n : g.children) if (n instanceof Group c) groups(c, out); }

    /** Box UV 에서 면마다 텍스처 자리 {x1,y1,x2,y2} */
    static Map<String, int[]> boxFaces(Cube c) {
        int[] s = c.size();
        int w = s[0], h = s[1], d = s[2], ox = c.uv[0], oy = c.uv[1];
        Map<String, int[]> m = new LinkedHashMap<>();
        m.put("north", new int[]{ox + d, oy + d, ox + d + w, oy + d + h});
        m.put("east", new int[]{ox, oy + d, ox + d, oy + d + h});
        m.put("south", new int[]{ox + d + w + d, oy + d, ox + 2 * d + 2 * w, oy + d + h});
        m.put("west", new int[]{ox + d + w, oy + d, ox + 2 * d + w, oy + d + h});
        m.put("up", new int[]{ox + d + w, oy + d, ox + d, oy});
        m.put("down", new int[]{ox + d + 2 * w, oy, ox + d + w, oy + d});
        return m;
    }

    static int[] pack(List<Cube> cubes) {
        List<Cube> sorted = new ArrayList<>(cubes);
        sorted.sort((a, b) -> (b.size()[1] + b.size()[2]) - (a.size()[1] + a.size()[2]));
        for (int[] wh : new int[][]{{64, 64}, {128, 64}, {128, 128}, {256, 128}, {256, 256}}) {
            int x = 0, y = 0, rowh = 0;
            boolean ok = true;
            for (Cube c : sorted) {
                int[] s = c.size();
                int bw = 2 * (s[0] + s[2]), bh = s[2] + s[1];
                if (x + bw > wh[0]) { x = 0; y += rowh; rowh = 0; }
                if (y + bh > wh[1] || bw > wh[0]) { ok = false; break; }
                c.uv = new int[]{x, y};
                x += bw;
                rowh = Math.max(rowh, bh);
            }
            if (ok) return wh;
        }
        throw new IllegalStateException("텍스처가 256x256 에 안 들어감");
    }

    // ── 칠하기 ────────────────────────────────────────────────
    static double shade(String face) {
        return switch (face) { case "south" -> 0.88; case "east", "west" -> 0.92; case "up" -> 1.1; case "down" -> 0.72; default -> 1.0; };
    }
    static int cl(double x) { return Math.max(0, Math.min(255, (int) x)); }
    static int argb(int[] c, double f, int a) { return (cl(a) << 24) | (cl(c[0] * f) << 16) | (cl(c[1] * f) << 8) | cl(c[2] * f); }

    static final int[] HULL = {150, 158, 172}, HULL_LIGHT = {196, 204, 216}, HULL_DARK = {92, 98, 114}, STRIPE = {72, 220, 232},
            GLASS = {120, 232, 250}, ALIEN = {104, 204, 84}, LAMP_A = {255, 214, 84}, LAMP_B = {110, 244, 255},
            EMIT = {160, 255, 250}, BEAM = {150, 250, 238}, ANT = {226, 70, 70};

    /** 금속 선체: 약한 얼룩 + 판 이음선. stripe 면 옆면 가운데에 발광 띠 */
    static Painter metal(int[] base, boolean stripe) {
        return (img, face, x1, y1, x2, y2, rng) -> {
            boolean flat = face.equals("up") || face.equals("down");
            for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) {
                int lx = x - x1, ly = y - y1;
                double f = shade(face) * (1 + (rng.nextDouble() - 0.5) * 0.10);
                if (flat ? (lx % 7 == 3 || ly % 7 == 3) : lx % 6 == 5) f *= 0.84;        // 판 이음선
                if (!flat && ly == 0) f *= 1.12;                                          // 위쪽 모서리 반사
                int[] col = base;
                if (stripe && !flat && (y2 - y1) >= 3 && ly == (y2 - y1) / 2) { col = STRIPE; f = 1.0 + (lx % 4 == 0 ? 0.15 : 0); }
                img.setRGB(x, y, argb(col, f, 255));
            }
        };
    }

    /** 스스로 빛나는 색 덩어리 (램프·방출구): 가운데가 더 밝다 */
    static Painter lamp(int[] base) {
        return (img, face, x1, y1, x2, y2, rng) -> {
            for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) {
                double cx = (x - x1 + 0.5) / (x2 - x1) - 0.5, cy = (y - y1 + 0.5) / (y2 - y1) - 0.5;
                double f = 1.25 - Math.sqrt(cx * cx + cy * cy) * 0.7;
                img.setRGB(x, y, argb(base, f, 255));
            }
        };
    }

    /** 유리 덮개: 반투명 + 비스듬한 반사 줄 */
    static Painter glass() {
        return (img, face, x1, y1, x2, y2, rng) -> {
            for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) {
                int lx = x - x1, ly = y - y1;
                boolean glint = (lx + ly) % 9 == 2 || (lx + ly) % 9 == 3;
                boolean edge = lx == 0 || ly == 0 || x == x2 - 1 || y == y2 - 1;
                img.setRGB(x, y, argb(GLASS, shade(face) * (glint ? 1.2 : 1.0), edge ? 170 : glint ? 150 : 96));
            }
        };
    }

    /** 조종석의 외계인 머리: 앞면에 큰 눈 두 개 */
    static Painter alien() {
        return (img, face, x1, y1, x2, y2, rng) -> {
            for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) img.setRGB(x, y, argb(ALIEN, shade(face) * (1 + (rng.nextDouble() - 0.5) * 0.08), 255));
            if (!face.equals("north")) return;
            int[][] eye = {{1, 1}, {1, 2}, {4, 1}, {4, 2}};
            for (int[] e : eye) if (x1 + e[0] < x2 && y1 + e[1] < y2) img.setRGB(x1 + e[0], y1 + e[1], 0xFF101418);
            if (x1 + 1 < x2) img.setRGB(x1 + 1, y1 + 1, 0xFFE8FFFF);
            if (x1 + 4 < x2) img.setRGB(x1 + 4, y1 + 1, 0xFFE8FFFF);
        };
    }

    /** 견인 빔 한 토막: 옆면만 보이고(위·아래 면은 투명), 위에서 아래로 갈수록 옅어진다. 세로 줄무늬 */
    static Painter beam(int aTop, int aBottom) {
        return (img, face, x1, y1, x2, y2, rng) -> {
            if (face.equals("up") || face.equals("down")) { for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) img.setRGB(x, y, 0); return; }
            for (int x = x1; x < x2; x++) {
                double col = 0.75 + rng.nextDouble() * 0.5;                                // 줄마다 밝기가 다르다
                for (int y = y1; y < y2; y++) {
                    double t = (y - y1) / (double) Math.max(1, y2 - y1 - 1);
                    double a = (aTop + (aBottom - aTop) * t) * col * (rng.nextDouble() < 0.06 ? 1.6 : 1.0);
                    img.setRGB(x, y, argb(BEAM, 0.9 + col * 0.2, (int) Math.max(12, a)));
                }
            }
        };
    }

    /** 바닥 고리(두께 0 판): 위·아래 면에 빛나는 고리 */
    static Painter ring() {
        return (img, face, x1, y1, x2, y2, rng) -> {
            double c = (x2 - x1) / 2.0;
            for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) {
                double d = Math.hypot(x - x1 + 0.5 - c, y - y1 + 0.5 - c);
                int a = d > c ? 0 : d > c - 3 ? 190 : d > c - 5 ? 90 : 34;
                img.setRGB(x, y, a == 0 ? 0 : argb(EMIT, 1.0, a));
            }
        };
    }

    // ── 모델 ──────────────────────────────────────────────────
    static Cube lampAt(String name, double x, double z, int[] col) {
        return new Cube(name, v(x - 1, 5.5, z - 1), v(x + 1, 7.5, z + 1), lamp(col));
    }

    static Group build() {
        // 조종석
        Group dome = new Group("dome", v(0, 14, 0),
                new Cube("alien_head", v(-3, 14, -3), v(3, 19, 3), alien()),
                new Cube("alien_neck", v(-1, 13, -1), v(1, 14, 1), metal(ALIEN, false)),
                new Cube("dome_glass", v(-7, 14, -7), v(7, 20, 7), glass()),
                new Cube("dome_top", v(-5, 20, -5), v(5, 22, 5), glass()),
                new Cube("antenna", v(-0.5, 22, -0.5), v(0.5, 26, 0.5), metal(HULL_DARK, false)),
                new Cube("antenna_ball", v(-1, 26, -1), v(1, 28, 1), lamp(ANT)));

        // 선체: 넓은 판 세 장을 엇갈려 겹쳐 팔각 원반처럼
        Group saucer = new Group("saucer", v(0, 6, 0),
                new Cube("hull_wide_x", v(-22, 4, -14), v(22, 9, 14), metal(HULL, true)),
                new Cube("hull_wide_z", v(-14, 4, -22), v(14, 9, 22), metal(HULL, true)),
                new Cube("hull_diag", v(-18, 4, -18), v(18, 9, 18), metal(HULL, true)),
                new Cube("hull_upper", v(-14, 9, -14), v(14, 12, 14), metal(HULL_LIGHT, false)),
                new Cube("hull_crown", v(-10, 12, -10), v(10, 14, 10), metal(HULL_LIGHT, false)),
                new Cube("hull_lower", v(-14, 1, -14), v(14, 4, 14), metal(HULL_DARK, false)),
                new Cube("hull_belly", v(-9, -1, -9), v(9, 1, 9), metal(HULL_DARK, false)),
                new Cube("emitter_rim", v(-6, -2, -6), v(6, -1, 6), metal(HULL_LIGHT, false)),
                new Cube("emitter", v(-4, -3, -4), v(4, -2, 4), lamp(EMIT)),
                lampAt("lamp_n", 0, -22.5, LAMP_A), lampAt("lamp_s", 0, 22.5, LAMP_A),
                lampAt("lamp_e", 22.5, 0, LAMP_A), lampAt("lamp_w", -22.5, 0, LAMP_A),
                lampAt("lamp_ne", 18.5, -18.5, LAMP_B), lampAt("lamp_nw", -18.5, -18.5, LAMP_B),
                lampAt("lamp_se", 18.5, 18.5, LAMP_B), lampAt("lamp_sw", -18.5, 18.5, LAMP_B),
                // 아래쪽 받침 세 개 (120도 간격 대신 네모 선체에 맞춰 네 귀퉁이 중 셋)
                new Cube("fin_a", v(-12, -2, -12), v(-9, 1, -9), metal(HULL, false)),
                new Cube("fin_b", v(9, -2, -12), v(12, 1, -9), metal(HULL, false)),
                new Cube("fin_c", v(-12, -2, 9), v(-9, 1, 12), metal(HULL, false)),
                new Cube("fin_d", v(9, -2, 9), v(12, 1, 12), metal(HULL, false)),
                dome);

        // 견인 빔: 아래로 갈수록 넓어지는 네 토막 + 바닥 고리. 토막마다 뼈를 따로 둬서 물결치듯 움직인다
        Group beam = new Group("beam", v(0, -3, 0));
        int[] width = {10, 14, 18, 22};
        int[][] alpha = {{150, 120}, {120, 92}, {92, 66}, {66, 40}};
        for (int i = 0; i < 4; i++) {
            double top = -3 - 19 * i, bottom = top - 19, w = width[i] / 2.0;
            beam.children.add(new Group("beam_seg" + (i + 1), v(0, top, 0),
                    new Cube("beam_seg" + (i + 1), v(-w, bottom, -w), v(w, top, w), beam(alpha[i][0], alpha[i][1]))));
        }
        beam.children.add(new Group("beam_ring", v(0, -79, 0), new Cube("beam_ring", v(-13, -79, -13), v(13, -79, 13), ring())));

        return new Group("root", v(0, 0, 0), saucer, beam);
    }

    // ── 애니메이션 ────────────────────────────────────────────
    static String num(double x) { double r = Math.round(x * 10000) / 10000.0; return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r); }

    /** Blockbench 애니메이터 규약: rotation x/y · position x 는 부호가 반대 */
    static JsonObject kf(String ch, double t, double x, double y, double z, String interp) {
        if (ch.equals("rotation")) { x = -x; y = -y; }
        if (ch.equals("position")) x = -x;
        JsonObject p = new JsonObject();
        p.addProperty("x", num(x)); p.addProperty("y", num(y)); p.addProperty("z", num(z));
        JsonArray dp = new JsonArray(); dp.add(p);
        JsonObject k = new JsonObject();
        k.addProperty("channel", ch); k.add("data_points", dp); k.addProperty("uuid", UUID.randomUUID().toString());
        k.addProperty("time", t); k.addProperty("color", -1); k.addProperty("interpolation", interp);
        return k;
    }

    static final class Track { final String group; final List<JsonObject> keys = new ArrayList<>(); Track(String g) { group = g; }
        Track k(String ch, double t, double x, double y, double z) { keys.add(kf(ch, t, x, y, z, "linear")); return this; }
        Track s(String ch, double t, double x, double y, double z) { keys.add(kf(ch, t, x, y, z, "catmullrom")); return this; } }

    static JsonObject anim(String name, double len, String loop, Map<String, Group> gmap, Track... tracks) {
        JsonObject animators = new JsonObject();
        for (Track t : tracks) {
            Group g = Objects.requireNonNull(gmap.get(t.group), t.group);
            JsonObject a = new JsonObject();
            a.addProperty("name", g.name); a.addProperty("type", "bone");
            JsonArray ks = new JsonArray(); t.keys.forEach(ks::add); a.add("keyframes", ks);
            animators.add(g.uuid, a);
        }
        JsonObject o = new JsonObject();
        o.addProperty("uuid", UUID.randomUUID().toString());
        o.addProperty("name", "animation." + ID + "." + name);
        o.addProperty("loop", loop); o.addProperty("override", false); o.addProperty("length", len);
        o.addProperty("snapping", 24); o.addProperty("selected", false);
        o.addProperty("anim_time_update", ""); o.addProperty("blend_weight", ""); o.addProperty("start_delay", ""); o.addProperty("loop_delay", "");
        o.add("animators", animators);
        return o;
    }

    static Track off(String g, double len) { return new Track(g).k("scale", 0, 0, 0, 0).k("scale", len, 0, 0, 0); }

    static JsonArray anims(Map<String, Group> g) {
        JsonArray out = new JsonArray();
        // 내려와 자리 잡는다 (빔은 꺼져 있다)
        out.add(anim("spawn", 1.0, "once", g,
                new Track("root").s("position", 0, 0, 70, 0).s("position", 0.7, 0, -3, 0).s("position", 1.0, 0, 0, 0)
                        .k("scale", 0, 0.2, 0.2, 0.2).s("scale", 0.6, 1, 1, 1).k("scale", 1.0, 1, 1, 1),
                new Track("saucer").k("rotation", 0, 0, 0, 0).k("rotation", 1.0, 0, 360, 0),
                off("beam", 1.0)));
        // 떠서 천천히 돈다 (빔 꺼짐)
        out.add(anim("idle", 4.0, "loop", g,
                new Track("saucer").k("rotation", 0, 0, 0, 0).k("rotation", 4.0, 0, 360, 0)
                        .s("position", 0, 0, 0, 0).s("position", 2.0, 0, 1.5, 0).s("position", 4.0, 0, 0, 0),
                off("beam", 4.0)));
        // 빔을 쏜다: 접시는 더 빨리 돌고, 빔 토막이 위에서 아래로 차례로 부풀며 물결친다
        Track[] bt = new Track[8];
        bt[0] = new Track("saucer").k("rotation", 0, 0, 0, 0).k("rotation", 2.0, 0, 360, 0)
                .s("position", 0, 0, 0, 0).s("position", 1.0, 0, 0.8, 0).s("position", 2.0, 0, 0, 0);
        bt[1] = new Track("beam").k("scale", 0, 1, 1, 1).k("scale", 2.0, 1, 1, 1);
        for (int i = 0; i < 4; i++) {
            double ph = 0.25 * i;                                   // 토막마다 0.25초씩 늦게
            Track t = new Track("beam_seg" + (i + 1));
            double[] times = {0, 0.5, 1.0, 1.5, 2.0};
            for (double tm : times) {
                double s = 1 + 0.12 * Math.sin((tm - ph) * Math.PI * 2);   // 주기 1초 → 2초 루프에서 처음과 끝이 같다
                t.s("scale", tm, s, 1, s);
            }
            bt[2 + i] = t;
        }
        bt[6] = new Track("beam_ring").k("rotation", 0, 0, 0, 0).k("rotation", 2.0, 0, -360, 0)
                .s("scale", 0, 1, 1, 1).s("scale", 1.0, 1.15, 1, 1.15).s("scale", 2.0, 1, 1, 1);
        bt[7] = new Track("dome").s("rotation", 0, 0, 0, 0).s("rotation", 1.0, 0, 20, 0).s("rotation", 2.0, 0, 0, 0);
        out.add(anim("beam", 2.0, "loop", g, bt));
        // 빔을 끄고 기울며 날아가 사라진다
        out.add(anim("leave", 1.0, "hold", g,
                new Track("beam").k("scale", 0, 1, 1, 1).k("scale", 0.15, 0, 0, 0).k("scale", 1.0, 0, 0, 0),
                new Track("saucer").k("rotation", 0, 0, 0, 0).s("rotation", 0.3, 0, 90, -16).k("rotation", 1.0, 0, 540, -16),
                new Track("root").s("position", 0, 0, 0, 0).s("position", 0.3, 0, 6, 0).s("position", 1.0, 40, 150, -60)
                        .k("scale", 0, 1, 1, 1).k("scale", 0.4, 1, 1, 1).k("scale", 1.0, 0.05, 0.05, 0.05)));
        return out;
    }

    // ── 내보내기 ──────────────────────────────────────────────
    static JsonArray arr(double... a) { JsonArray j = new JsonArray(); for (double x : a) { if (x == Math.rint(x)) j.add((long) x); else j.add(x); } return j; }
    static JsonArray arr(int[] a) { JsonArray j = new JsonArray(); for (int x : a) j.add(x); return j; }

    static JsonObject outline(Group g) {
        JsonObject o = new JsonObject();
        o.addProperty("name", g.name); o.add("origin", arr(g.origin)); o.add("rotation", arr(g.rot)); o.addProperty("color", 0);
        o.addProperty("uuid", g.uuid); o.addProperty("export", true); o.addProperty("mirror_uv", false); o.addProperty("isOpen", true);
        o.addProperty("locked", false); o.addProperty("visibility", true); o.addProperty("autouv", 0);
        JsonArray kids = new JsonArray();
        for (Node n : g.children) if (n instanceof Group c) kids.add(outline(c)); else kids.add(n.uuid);
        o.add("children", kids);
        return o;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Group root = build();
        List<Cube> cubes = new ArrayList<>();
        cubes(root, cubes);
        int[] wh = pack(cubes);
        BufferedImage img = new BufferedImage(wh[0], wh[1], BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(7);
        for (Cube c : cubes) for (Map.Entry<String, int[]> f : boxFaces(c).entrySet()) {
            int[] uv = f.getValue();
            int x1 = Math.min(uv[0], uv[2]), x2 = Math.max(uv[0], uv[2]), y1 = Math.min(uv[1], uv[3]), y2 = Math.max(uv[1], uv[3]);
            if (x2 > x1 && y2 > y1) c.paint.paint(img, f.getKey(), x1, y1, x2, y2, rng);
        }
        ImageIO.write(img, "png", new File(dir.toFile(), ID + ".png"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "png", buf);

        JsonArray elements = new JsonArray();
        for (Cube c : cubes) {
            JsonObject e = new JsonObject();
            e.addProperty("name", c.name); e.addProperty("box_uv", true); e.addProperty("rescale", false); e.addProperty("locked", false);
            e.addProperty("render_order", "default"); e.addProperty("allow_mirror_modeling", true);
            e.add("from", arr(c.from)); e.add("to", arr(c.to)); e.addProperty("autouv", 0); e.addProperty("color", 0); e.addProperty("inflate", 0);
            e.add("rotation", arr(c.rot)); e.add("origin", arr(c.origin)); e.add("uv_offset", arr(c.uv));
            JsonObject faces = new JsonObject();
            for (Map.Entry<String, int[]> f : boxFaces(c).entrySet()) {
                JsonObject fo = new JsonObject();
                fo.add("uv", arr(f.getValue())); fo.addProperty("texture", 0);
                faces.add(f.getKey(), fo);
            }
            e.add("faces", faces); e.addProperty("type", "cube"); e.addProperty("uuid", c.uuid);
            elements.add(e);
        }
        List<Group> gs = new ArrayList<>();
        groups(root, gs);
        Map<String, Group> gmap = new HashMap<>();
        for (Group g : gs) gmap.put(g.name, g);

        JsonObject meta = new JsonObject();
        meta.addProperty("format_version", "4.10"); meta.addProperty("model_format", "modded_entity"); meta.addProperty("box_uv", true);
        JsonObject res = new JsonObject();
        res.addProperty("width", wh[0]); res.addProperty("height", wh[1]);
        JsonObject tex = new JsonObject();
        tex.addProperty("path", ""); tex.addProperty("name", ID + ".png"); tex.addProperty("folder", ""); tex.addProperty("namespace", ""); tex.addProperty("id", "0");
        tex.addProperty("group", ""); tex.addProperty("width", wh[0]); tex.addProperty("height", wh[1]); tex.addProperty("uv_width", wh[0]); tex.addProperty("uv_height", wh[1]);
        tex.addProperty("particle", false); tex.addProperty("use_as_default", false); tex.addProperty("layers_enabled", false); tex.addProperty("sync_to_project", "");
        tex.addProperty("render_mode", "default"); tex.addProperty("render_sides", "auto"); tex.addProperty("frame_time", 1);
        tex.addProperty("frame_order_type", "loop"); tex.addProperty("frame_order", ""); tex.addProperty("frame_interpolate", false);
        tex.addProperty("visible", true); tex.addProperty("internal", true); tex.addProperty("saved", false); tex.addProperty("uuid", UUID.randomUUID().toString());
        tex.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(buf.toByteArray()));

        JsonObject model = new JsonObject();
        model.add("meta", meta);
        model.addProperty("name", ID); model.addProperty("model_identifier", ID);
        model.addProperty("modded_entity_entity_class", ""); model.addProperty("modded_entity_version", "Fabric 1.17+"); model.addProperty("modded_entity_flip_y", true);
        model.add("visible_box", arr(1, 2, 0)); model.addProperty("variable_placeholders", ""); model.add("variable_placeholder_buttons", new JsonArray());
        model.add("timeline_setups", new JsonArray()); model.add("unhandled_root_fields", new JsonObject());
        model.add("resolution", res); model.add("elements", elements);
        JsonArray outliner = new JsonArray(); outliner.add(outline(root)); model.add("outliner", outliner);
        JsonArray texs = new JsonArray(); texs.add(tex); model.add("textures", texs);
        model.add("animations", anims(gmap));

        Files.writeString(dir.resolve(ID + ".bbmodel"), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(model), StandardCharsets.UTF_8);
        System.out.println(ID + "  텍스처 " + wh[0] + "x" + wh[1] + "  큐브 " + cubes.size() + "개  뼈 " + gs.size() + "개");
    }
}
