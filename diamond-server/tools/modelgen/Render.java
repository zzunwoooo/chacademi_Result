import com.google.gson.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * .bbmodel 을 게임 없이 그림으로 그려 보는 검수 도구 (텍스처 내장 모델용, 애니메이션의 한 순간도 그릴 수 있다).
 * 사용: java -cp gson.jar Render.java <모델.bbmodel> <출력 폴더> [배율] [기준선 y]
 *   → <이름>_preview.png (앞·뒤 비스듬히), <이름>_anims.png (애니메이션마다 6장)
 * 반투명은 뒤에서 앞으로 덧칠하는 근사라서 실제 게임과 조금 다를 수 있다.
 */
public class Render {
    interface Xf { double[] ap(double[] q); }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }
    static double[] mv(double[][] m, double[] v) { return new double[]{m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2], m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2], m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]}; }

    /** Blockbench: ZYX 오일러 각(도) */
    static double[][] rotm(double[] r) {
        double x = Math.toRadians(r[0]), y = Math.toRadians(r[1]), z = Math.toRadians(r[2]);
        double[][] rx = {{1, 0, 0}, {0, Math.cos(x), -Math.sin(x)}, {0, Math.sin(x), Math.cos(x)}};
        double[][] ry = {{Math.cos(y), 0, Math.sin(y)}, {0, 1, 0}, {-Math.sin(y), 0, Math.cos(y)}};
        double[][] rz = {{Math.cos(z), -Math.sin(z), 0}, {Math.sin(z), Math.cos(z), 0}, {0, 0, 1}};
        return mul(rz, mul(ry, rx));
    }

    static double[] vec(JsonArray a) { if (a == null) return new double[]{0, 0, 0}; return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()}; }

    static double[] sample(JsonArray kfs, String ch, double t, double[] def) {
        List<JsonObject> k = new ArrayList<>();
        if (kfs != null) for (JsonElement e : kfs) if (e.getAsJsonObject().get("channel").getAsString().equals(ch)) k.add(e.getAsJsonObject());
        if (k.isEmpty()) return def;
        k.sort(Comparator.comparingDouble(o -> o.get("time").getAsDouble()));
        if (t <= k.get(0).get("time").getAsDouble()) return val(k.get(0));
        for (int i = 0; i + 1 < k.size(); i++) {
            double ta = k.get(i).get("time").getAsDouble(), tb = k.get(i + 1).get("time").getAsDouble();
            if (t <= tb) {
                double f = tb == ta ? 1 : (t - ta) / (tb - ta);
                double[] a = val(k.get(i)), b = val(k.get(i + 1));
                return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
            }
        }
        return val(k.get(k.size() - 1));
    }
    static double[] val(JsonObject k) {
        JsonObject p = k.getAsJsonArray("data_points").get(0).getAsJsonObject();
        return new double[]{Double.parseDouble(p.get("x").getAsString()), Double.parseDouble(p.get("y").getAsString()), Double.parseDouble(p.get("z").getAsString())};
    }

    record Quad(double z, int[] xs, int[] ys, int argb, String face) { }

    static BufferedImage render(JsonObject m, BufferedImage tex, String anim, double t, double yaw, int W, int H, double scale, double base) {
        Map<String, JsonObject> el = new HashMap<>();
        for (JsonElement e : m.getAsJsonArray("elements")) el.put(e.getAsJsonObject().get("uuid").getAsString(), e.getAsJsonObject());
        Map<String, JsonArray> A = new HashMap<>();
        if (anim != null) for (JsonElement e : m.getAsJsonArray("animations")) {
            JsonObject a = e.getAsJsonObject();
            if (!a.get("name").getAsString().endsWith("." + anim) && !a.get("name").getAsString().equals(anim)) continue;
            for (Map.Entry<String, JsonElement> en : a.getAsJsonObject("animators").entrySet())
                A.put(en.getValue().getAsJsonObject().get("name").getAsString(), en.getValue().getAsJsonObject().getAsJsonArray("keyframes"));
        }
        final double yw = Math.toRadians(yaw), pt = Math.toRadians(-20);
        List<Quad> quads = new ArrayList<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        for (JsonElement g : m.getAsJsonArray("outliner")) if (g.isJsonObject() && (!g.getAsJsonObject().has("export") || g.getAsJsonObject().get("export").getAsBoolean())) stack.push(new Object[]{g.getAsJsonObject(), (Xf) q -> q});
        while (!stack.isEmpty()) {
            Object[] it = stack.pop();
            JsonObject g = (JsonObject) it[0];
            final Xf M = (Xf) it[1];
            JsonArray k = A.get(g.get("name").getAsString());
            final double[] o = vec(g.getAsJsonArray("origin"));
            double[] p0 = sample(k, "position", t, new double[]{0, 0, 0}), r0 = sample(k, "rotation", t, new double[]{0, 0, 0});
            final double[] s = sample(k, "scale", t, new double[]{1, 1, 1});
            final double[] p = {-p0[0], p0[1], p0[2]};
            double[] gr = g.has("rotation") ? vec(g.getAsJsonArray("rotation")) : new double[]{0, 0, 0};
            final double[][] R = rotm(new double[]{gr[0] - r0[0], gr[1] - r0[1], gr[2] + r0[2]});
            final Xf M2 = q -> {
                double[] d = {s[0] * (q[0] - o[0]), s[1] * (q[1] - o[1]), s[2] * (q[2] - o[2])};
                double[] r = mv(R, d);
                return M.ap(new double[]{o[0] + r[0] + p[0], o[1] + r[1] + p[1], o[2] + r[2] + p[2]});
            };
            for (JsonElement c : g.getAsJsonArray("children")) {
                if (c.isJsonObject()) { stack.push(new Object[]{c.getAsJsonObject(), M2}); continue; }
                JsonObject e = el.get(c.getAsString());
                if (e == null || e.get("name").getAsString().contains("me_outline")) continue;
                final double[] eo = vec(e.getAsJsonArray("origin"));
                final double[][] ER = rotm(vec(e.getAsJsonArray("rotation")));
                Xf Mc = q -> { double[] r = mv(ER, new double[]{q[0] - eo[0], q[1] - eo[1], q[2] - eo[2]}); return M2.ap(new double[]{eo[0] + r[0], eo[1] + r[1], eo[2] + r[2]}); };
                double[] f = vec(e.getAsJsonArray("from")), to = vec(e.getAsJsonArray("to"));
                double X0 = f[0], Y0 = f[1], Z0 = f[2], X1 = to[0], Y1 = to[1], Z1 = to[2];
                for (Map.Entry<String, JsonElement> fe : e.getAsJsonObject("faces").entrySet()) {
                    if (!fe.getValue().getAsJsonObject().has("texture") || fe.getValue().getAsJsonObject().get("texture").isJsonNull()) continue;
                    JsonArray uv = fe.getValue().getAsJsonObject().getAsJsonArray("uv");
                    double us = tex.getWidth() / (m.has("resolution") ? m.getAsJsonObject("resolution").get("width").getAsDouble() : tex.getWidth()), vs = tex.getHeight() / (m.has("resolution") ? m.getAsJsonObject("resolution").get("height").getAsDouble() : tex.getHeight());
                    int x1 = (int) Math.round(us * Math.min(uv.get(0).getAsDouble(), uv.get(2).getAsDouble())), x2 = (int) Math.round(us * Math.max(uv.get(0).getAsDouble(), uv.get(2).getAsDouble()));
                    int y1 = (int) Math.round(vs * Math.min(uv.get(1).getAsDouble(), uv.get(3).getAsDouble())), y2 = (int) Math.round(vs * Math.max(uv.get(1).getAsDouble(), uv.get(3).getAsDouble()));
                    if (x2 <= x1 || y2 <= y1) continue;
                    double[][] P = switch (fe.getKey()) {
                        case "north" -> new double[][]{{X1, Y1, Z0}, {X0, Y1, Z0}, {X1, Y0, Z0}};
                        case "south" -> new double[][]{{X0, Y1, Z1}, {X1, Y1, Z1}, {X0, Y0, Z1}};
                        case "east" -> new double[][]{{X1, Y1, Z1}, {X1, Y1, Z0}, {X1, Y0, Z1}};
                        case "west" -> new double[][]{{X0, Y1, Z0}, {X0, Y1, Z1}, {X0, Y0, Z0}};
                        case "up" -> new double[][]{{X1, Y1, Z1}, {X0, Y1, Z1}, {X1, Y1, Z0}};
                        default -> new double[][]{{X1, Y0, Z0}, {X0, Y0, Z0}, {X1, Y0, Z1}};
                    };
                    int nu = x2 - x1, nv = y2 - y1;
                    for (int j = 0; j < nv; j++) for (int i = 0; i < nu; i++) {
                        int col = tex.getRGB(x1 + i, y1 + j);
                        if ((col >>> 24) < 10) continue;
                        int[] xs = new int[4], ys = new int[4];
                        double zs = 0;
                        double[][] ab = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                        for (int n = 0; n < 4; n++) {
                            double a = (i + ab[n][0]) / nu, b = (j + ab[n][1]) / nv;
                            double[] q = Mc.ap(new double[]{P[0][0] + (P[1][0] - P[0][0]) * a + (P[2][0] - P[0][0]) * b, P[0][1] + (P[1][1] - P[0][1]) * a + (P[2][1] - P[0][1]) * b, P[0][2] + (P[1][2] - P[0][2]) * a + (P[2][2] - P[0][2]) * b});
                            double xr = q[0] * Math.cos(yw) - q[2] * Math.sin(yw), zr = q[0] * Math.sin(yw) + q[2] * Math.cos(yw);
                            double yr = q[1] * Math.cos(pt) - zr * Math.sin(pt), zz = q[1] * Math.sin(pt) + zr * Math.cos(pt);
                            xs[n] = (int) Math.round(W / 2.0 - xr * scale); ys[n] = (int) Math.round(base - yr * scale); zs += zz;
                        }
                        quads.add(new Quad(zs / 4, xs, ys, col, fe.getKey()));
                    }
                }
            }
        }
        quads.sort((a, b) -> Double.compare(b.z, a.z));
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(new Color(74, 96, 120)); g2.fillRect(0, 0, W, H);
        for (Quad q : quads) {
            double L = switch (q.face) { case "south" -> .75; case "east", "west" -> .85; case "up" -> 1.05; case "down" -> .6; default -> 1; };
            int a = q.argb >>> 24, r = Math.min(255, (int) (((q.argb >> 16) & 255) * L)), gg = Math.min(255, (int) (((q.argb >> 8) & 255) * L)), b = Math.min(255, (int) ((q.argb & 255) * L));
            g2.setColor(new Color(r, gg, b, a));
            g2.fillPolygon(q.xs, q.ys, 4);
            if (a > 250) g2.drawPolygon(q.xs, q.ys, 4);
        }
        g2.dispose();
        return img;
    }

    public static void main(String[] a) throws Exception {
        Path f = Path.of(a[0]);
        File out = new File(a[1]);
        out.mkdirs();
        double scale = a.length > 2 ? Double.parseDouble(a[2]) : 6, base = a.length > 3 ? Double.parseDouble(a[3]) : 380;
        JsonObject m = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        String src = m.getAsJsonArray("textures").get(0).getAsJsonObject().get("source").getAsString();
        BufferedImage tex = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(src.substring(src.indexOf(',') + 1))));
        String name = f.getFileName().toString().replace(".bbmodel", "");
        int W = 360, H = 420;
        double[] yaws = {-35, 145, 0};
        BufferedImage sheet = new BufferedImage(W * yaws.length, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        for (int i = 0; i < yaws.length; i++) g.drawImage(render(m, tex, null, 0, yaws[i], W, H, scale, base), i * W, 0, null);
        g.dispose();
        ImageIO.write(sheet, "png", new File(out, name + "_preview.png"));
        JsonArray anims = m.getAsJsonArray("animations");
        int frames = 6, fw = 240, fh = 300;
        BufferedImage strip = new BufferedImage(fw * frames, fh * anims.size(), BufferedImage.TYPE_INT_RGB);
        g = strip.createGraphics();
        for (int r = 0; r < anims.size(); r++) {
            JsonObject an = anims.get(r).getAsJsonObject();
            String full = an.get("name").getAsString(), sh = full.substring(full.lastIndexOf('.') + 1);
            double len = an.get("length").getAsDouble();
            for (int i = 0; i < frames; i++) g.drawImage(render(m, tex, sh, len * i / (frames - 1), Double.parseDouble(System.getProperty("yaw", "-35")), fw, fh, scale * fh / H, base * fh / H), i * fw, r * fh, null);
            g.setColor(Color.WHITE);
            g.drawString(sh + " (" + an.get("loop").getAsString() + ", " + len + "s)", 6, r * fh + 14);
        }
        g.dispose();
        ImageIO.write(strip, "png", new File(out, name + "_anims.png"));
        System.out.println(name + ": " + m.getAsJsonArray("elements").size() + " elements, " + anims.size() + " animations");
    }
}
