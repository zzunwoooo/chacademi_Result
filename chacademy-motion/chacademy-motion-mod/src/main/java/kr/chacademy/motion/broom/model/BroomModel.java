package kr.chacademy.motion.broom.model;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blockbench .bbmodel(Generic/free 형식)을 그릴 수 있는 사각형 목록으로 바꾼 것.
 *
 * <ul>
 *   <li>좌표는 Blockbench 픽셀 단위 그대로 (1블록 = 16), Y 위, 앞 = -Z</li>
 *   <li>큐브 회전·그룹(본) 회전을 모두 미리 적용해 둔다 (회전 순서는 Blockbench 와 같은 Z·Y·X)</li>
 *   <li>from/to 가 뒤집힌 "inverted" 큐브는 면이 안쪽을 향하게 그대로 둔다 (Blockbench 와 같음)</li>
 *   <li>숨김 처리(visibility:false)된 큐브는 뺀다</li>
 *   <li>p_pilot 본의 origin 을 좌석 위치로 쓴다 (ModelEngine 탑승 본 이름 규칙)</li>
 * </ul>
 * 마인크래프트 클래스에 의존하지 않아서 게임 밖에서 테스트할 수 있다.
 */
public final class BroomModel {
    /** 사각형 하나: 꼭짓점 4개(반시계, 바깥에서 볼 때) x,y,z / u,v(0~1) / 법선. */
    public record Quad(float[] pos, float[] uv, float[] normal) {}

    public final String name;
    public final List<Quad> quads;
    /** 좌석(p_pilot) 위치, 픽셀 단위. */
    public final float[] seat;
    /** 내장 텍스처 PNG 바이트 (없으면 null). */
    public final byte[] texturePng;
    /** 전체 크기 min x,y,z, max x,y,z (픽셀). 장비창에서 화면에 맞출 때 씀. */
    public final float[] bounds;

    private BroomModel(String name, List<Quad> quads, float[] seat, byte[] texturePng) {
        this.name = name;
        this.quads = quads;
        this.seat = seat;
        this.texturePng = texturePng;
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var q : quads) {
            for (int i = 0; i < 12; i++) {
                int axis = i % 3;
                b[axis] = Math.min(b[axis], q.pos()[i]);
                b[axis + 3] = Math.max(b[axis + 3], q.pos()[i]);
            }
        }
        if (quads.isEmpty()) b = new float[]{0, 0, 0, 1, 1, 1};
        this.bounds = b;
    }

    public static BroomModel parse(String name, String json) {
        Map<String, Object> root = BbJson.obj(BbJson.parse(json));
        var res = BbJson.obj(root.get("resolution"));
        float texW = (float) BbJson.num(res.get("width"), 16);
        float texH = (float) BbJson.num(res.get("height"), 16);

        // 텍스처: 첫 번째 내장 텍스처 사용. uv_width/height 가 있으면 UV 기준 크기로 씀
        byte[] png = null;
        for (Object t : BbJson.arr(root.get("textures"))) {
            var tex = BbJson.obj(t);
            String src = String.valueOf(tex.getOrDefault("source", ""));
            int comma = src.indexOf(',');
            if (src.startsWith("data:image/png;base64,") && comma > 0) {
                png = Base64.getDecoder().decode(src.substring(comma + 1));
                texW = (float) BbJson.num(tex.get("uv_width"), texW);
                texH = (float) BbJson.num(tex.get("uv_height"), texH);
                break;
            }
        }

        Map<String, Map<String, Object>> elements = new HashMap<>();
        for (Object e : BbJson.arr(root.get("elements"))) {
            var el = BbJson.obj(e);
            elements.put(String.valueOf(el.get("uuid")), el);
        }

        var quads = new ArrayList<Quad>();
        float[] seat = {0, 12, 0};
        // 아웃라이너에 없는 큐브(그룹 없이 바로 있는 것 포함)도 그리기 위해 방문 기록
        var visited = new java.util.HashSet<String>();
        float[] identity = Mat.identity();
        for (Object node : BbJson.arr(root.get("outliner"))) {
            seat = walk(node, identity, elements, visited, quads, texW, texH, seat);
        }
        for (var entry : elements.entrySet()) {
            if (!visited.contains(entry.getKey())) addCube(entry.getValue(), identity, quads, texW, texH);
        }
        return new BroomModel(name, List.copyOf(quads), seat, png);
    }

    private static float[] walk(Object node, float[] parent, Map<String, Map<String, Object>> elements,
                                java.util.Set<String> visited, List<Quad> out, float texW, float texH, float[] seat) {
        if (node instanceof String uuid) {
            visited.add(uuid);
            var el = elements.get(uuid);
            if (el != null) addCube(el, parent, out, texW, texH);
            return seat;
        }
        var group = BbJson.obj(node);
        if (Boolean.FALSE.equals(group.get("export"))) return seat;
        float[] origin = BbJson.vec3(group.get("origin"), 0);
        float[] rot = BbJson.vec3(group.get("rotation"), 0);
        float[] m = Mat.mul(parent, Mat.rotateAround(origin, rot));
        if ("p_pilot".equals(group.get("name"))) seat = Mat.apply(parent, origin);
        for (Object child : BbJson.arr(group.get("children"))) {
            seat = walk(child, m, elements, visited, out, texW, texH, seat);
        }
        return seat;
    }

    // 면 이름 → 바깥에서 볼 때 왼쪽 위, 오른쪽 위, 오른쪽 아래, 왼쪽 아래 꼭짓점.
    // a = from, b = to (뒤집힌 큐브면 자연스럽게 면이 안쪽을 향함)
    private static float[][] corners(String face, float[] a, float[] b) {
        return switch (face) {
            case "north" -> new float[][]{{b[0], b[1], a[2]}, {a[0], b[1], a[2]}, {a[0], a[1], a[2]}, {b[0], a[1], a[2]}};
            case "south" -> new float[][]{{a[0], b[1], b[2]}, {b[0], b[1], b[2]}, {b[0], a[1], b[2]}, {a[0], a[1], b[2]}};
            case "east" -> new float[][]{{b[0], b[1], b[2]}, {b[0], b[1], a[2]}, {b[0], a[1], a[2]}, {b[0], a[1], b[2]}};
            case "west" -> new float[][]{{a[0], b[1], a[2]}, {a[0], b[1], b[2]}, {a[0], a[1], b[2]}, {a[0], a[1], a[2]}};
            case "up" -> new float[][]{{a[0], b[1], a[2]}, {b[0], b[1], a[2]}, {b[0], b[1], b[2]}, {a[0], b[1], b[2]}};
            case "down" -> new float[][]{{a[0], a[1], b[2]}, {b[0], a[1], b[2]}, {b[0], a[1], a[2]}, {a[0], a[1], a[2]}};
            default -> null;
        };
    }

    private static void addCube(Map<String, Object> el, float[] parent, List<Quad> out, float texW, float texH) {
        if (!"cube".equals(el.getOrDefault("type", "cube"))) return;
        if (Boolean.FALSE.equals(el.get("visibility")) || Boolean.FALSE.equals(el.get("export"))) return;
        float[] from = BbJson.vec3(el.get("from"), 0);
        float[] to = BbJson.vec3(el.get("to"), 0);
        float inflate = (float) BbJson.num(el.get("inflate"), 0);
        if (inflate != 0) {
            for (int k = 0; k < 3; k++) {
                float sign = to[k] >= from[k] ? 1 : -1;
                from[k] -= inflate * sign;
                to[k] += inflate * sign;
            }
        }
        float[] origin = BbJson.vec3(el.get("origin"), 0);
        float[] rot = BbJson.vec3(el.get("rotation"), 0);
        float[] m = Mat.mul(parent, Mat.rotateAround(origin, rot));
        var faces = BbJson.obj(el.get("faces"));
        for (var f : faces.entrySet()) {
            var face = BbJson.obj(f.getValue());
            if (!face.containsKey("texture") || face.get("texture") == null) continue; // 텍스처 없는 면은 Blockbench 에서도 안 보임
            float[][] c = corners(f.getKey(), from, to);
            if (c == null) continue;
            var uvl = BbJson.arr(face.get("uv"));
            if (uvl.size() < 4) continue;
            float u1 = (float) BbJson.num(uvl.get(0), 0) / texW, v1 = (float) BbJson.num(uvl.get(1), 0) / texH;
            float u2 = (float) BbJson.num(uvl.get(2), 0) / texW, v2 = (float) BbJson.num(uvl.get(3), 0) / texH;
            float[][] uvs = {{u1, v1}, {u2, v1}, {u2, v2}, {u1, v2}};
            int turns = ((int) Math.round(BbJson.num(face.get("rotation"), 0)) / 90) & 3;

            // 반시계(바깥에서 볼 때) 순서: 오른쪽 위 → 왼쪽 위 → 왼쪽 아래 → 오른쪽 아래
            int[] order = {1, 0, 3, 2};
            float[] pos = new float[12];
            float[] uv = new float[8];
            for (int n = 0; n < 4; n++) {
                int corner = order[n];
                float[] p = Mat.apply(m, c[corner]);
                System.arraycopy(p, 0, pos, n * 3, 3);
                float[] t = uvs[(corner - turns + 4) & 3];
                uv[n * 2] = t[0];
                uv[n * 2 + 1] = t[1];
            }
            float[] normal = normal(pos);
            if (normal == null) continue; // 넓이가 0인 면
            out.add(new Quad(pos, uv, normal));
        }
    }

    private static float[] normal(float[] p) {
        float ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2];
        float bx = p[6] - p[3], by = p[7] - p[4], bz = p[8] - p[5];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) {
            // 한 변이 0인 면(선 모양)은 반대 대각선으로 다시 계산
            ax = p[9] - p[0];
            ay = p[10] - p[1];
            az = p[11] - p[2];
            bx = p[6] - p[0];
            by = p[7] - p[1];
            bz = p[8] - p[2];
            nx = ay * bz - az * by;
            ny = az * bx - ax * bz;
            nz = ax * by - ay * bx;
            len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) return null;
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        return new float[]{nx / len, ny / len, nz / len};
    }

    /** 행 우선 4x4 행렬. */
    static final class Mat {
        static float[] identity() {
            return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        }

        static float[] mul(float[] a, float[] b) {
            float[] r = new float[16];
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) {
                    float s = 0;
                    for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j];
                    r[i * 4 + j] = s;
                }
            return r;
        }

        static float[] translate(float x, float y, float z) {
            return new float[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1};
        }

        static float[] rx(float deg) {
            double a = Math.toRadians(deg);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            return new float[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
        }

        static float[] ry(float deg) {
            double a = Math.toRadians(deg);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            return new float[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
        }

        static float[] rz(float deg) {
            double a = Math.toRadians(deg);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            return new float[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        }

        /** origin 을 중심으로 Blockbench 회전(Euler 순서 ZYX → 행렬 Rz·Ry·Rx). */
        static float[] rotateAround(float[] o, float[] r) {
            if (r[0] == 0 && r[1] == 0 && r[2] == 0) return identity();
            float[] rot = mul(mul(rz(r[2]), ry(r[1])), rx(r[0]));
            return mul(mul(translate(o[0], o[1], o[2]), rot), translate(-o[0], -o[1], -o[2]));
        }

        static float[] apply(float[] m, float[] p) {
            return new float[]{
                    m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3],
                    m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7],
                    m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11]};
        }
    }
}
