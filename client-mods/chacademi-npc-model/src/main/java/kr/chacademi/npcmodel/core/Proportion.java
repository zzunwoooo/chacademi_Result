package kr.chacademi.npcmodel.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 읽은 모델의 비율 조정 (다리·몸통·팔 길이). tools/npc-model/normalize_npc_model.py 의 reproportion 과 같은 계산.
 *
 * <p>바인드 자세 기준으로 관절 위치를 새로 잡고, 정점은 붙은 뼈의 길이 방향으로만 줄인다(두께 유지).
 * 노드 로컬 위치·inverse bind 를 같이 고쳐서 기본 자세·애니메이션(회전)·스킨이 모두 새 길이를 쓴다.
 * 원본 ModelData 는 건드리지 않고 새 ModelData 를 돌려준다.</p>
 */
public final class Proportion {
    private Proportion() {
    }

    /** 바꾸는 뼈 구간 (Mixamo 이름 기준, 접두어·Left/Right 무시). */
    static final Map<String, String> GROUP = Map.of(
            "UpLeg", "legs", "Leg", "legs",
            "Spine", "torso", "Spine1", "torso", "Spine2", "torso",
            "Arm", "arms", "ForeArm", "arms");
    static final Map<String, String> PREFER_CHILD = Map.of(
            "UpLeg", "Leg", "Leg", "Foot", "Spine", "Spine1", "Spine1", "Spine2", "Spine2", "Neck", "Arm", "ForeArm", "ForeArm", "Hand");

    public record Factors(float legs, float torso, float arms) {
        public static final Factors NONE = new Factors(1, 1, 1);

        public boolean identity() {
            return Math.abs(legs - 1) < 1e-6 && Math.abs(torso - 1) < 1e-6 && Math.abs(arms - 1) < 1e-6;
        }

        float of(String group) {
            return switch (group) {
                case "legs" -> legs;
                case "torso" -> torso;
                case "arms" -> arms;
                default -> 1;
            };
        }
    }

    static String bare(String name) {
        String n = name.substring(name.lastIndexOf(':') + 1);
        if (n.startsWith("Left")) {
            return n.substring(4);
        }
        if (n.startsWith("Right")) {
            return n.substring(5);
        }
        return n;
    }

    public static ModelData apply(ModelData m, Factors f, List<String> diag) {
        if (f == null || f.identity() || m.skins.length == 0) {
            return m;
        }
        int nn = m.nodes.length;
        // 스킨 0 의 바인드 관절 행렬 (메쉬0 좌표)
        ModelData.Skin s0 = m.skins[0];
        Map<Integer, double[]> bind = new HashMap<>();
        for (int k = 0; k < s0.joints.length; k++) {
            float[] inv = new float[16];
            if (!MathUtil.invert(s0.inverseBind, k * 16, inv, 0)) {
                throw new ModelFormatException("비율 조정: inverse bind 역행렬 실패");
            }
            bind.put(s0.joints[k], toD(inv));
        }
        // 스킨별 메쉬 좌표 → 메쉬0 좌표 변환 T_s = B0_j · IBM_s,j (모든 관절에서 같아야 함)
        double[][] skinT = new double[m.skins.length][];
        for (int si = 0; si < m.skins.length; si++) {
            ModelData.Skin s = m.skins[si];
            double[] first = null;
            for (int k = 0; k < s.joints.length; k++) {
                double[] b = bind.get(s.joints[k]);
                if (b == null) {
                    continue;
                }
                double[] t = mul(b, toD(s.inverseBind, k * 16));
                if (first == null) {
                    first = t;
                } else if (maxDiff(first, t) > 1e-3 * Math.max(1, maxAbs(first))) {
                    diag.add("비율 조정 건너뜀: 스킨마다 바인드 자세가 다름");
                    return m;
                }
            }
            if (first == null) {
                diag.add("비율 조정 건너뜀: 스킨 관절이 서로 다름");
                return m;
            }
            skinT[si] = first;
        }
        Map<Integer, double[]> pos = new HashMap<>();
        for (Map.Entry<Integer, double[]> e : bind.entrySet()) {
            double[] b = e.getValue();
            pos.put(e.getKey(), new double[]{b[12], b[13], b[14]});
        }
        // 구간별 길이 방향 축소 행렬 A (3x3, 행 우선)
        Map<Integer, double[]> A = new HashMap<>();
        int changed = 0;
        for (int j : pos.keySet()) {
            String b = bare(m.nodes[j].name);
            String group = GROUP.get(b);
            float k = group == null ? 1 : f.of(group);
            int c = mainChild(m, j, pos, PREFER_CHILD.get(b));
            if (Math.abs(k - 1) < 1e-9 || c < 0) {
                A.put(j, ident3());
                continue;
            }
            double[] d = sub(pos.get(c), pos.get(j));
            double l = Math.sqrt(dot(d, d));
            if (l < 1e-12) {
                A.put(j, ident3());
                continue;
            }
            for (int i = 0; i < 3; i++) {
                d[i] /= l;
            }
            double[] a = ident3();
            for (int r = 0; r < 3; r++) {
                for (int q = 0; q < 3; q++) {
                    a[r * 3 + q] += (k - 1) * d[r] * d[q];
                }
            }
            A.put(j, a);
            changed++;
        }
        if (changed == 0) {
            diag.add("비율 조정 건너뜀: 다리·몸통·팔 뼈(UpLeg/Leg/Spine/Arm…)를 찾지 못함");
            return m;
        }
        // 새 관절 위치 (부모 → 자식)
        Map<Integer, double[]> np = new HashMap<>();
        for (int j : m.order) {
            if (!pos.containsKey(j)) {
                continue;
            }
            int p = m.parent[j];
            if (p < 0 || !pos.containsKey(p)) {
                np.put(j, pos.get(j).clone());
            } else {
                np.put(j, add(np.get(p), mul3(A.get(p), sub(pos.get(j), pos.get(p)))));
            }
        }
        // 노드: 관절 자식의 로컬 위치 = 부모 바인드 회전 기준 새 오프셋
        ModelData.Node[] nodes = m.nodes.clone();
        for (int j : np.keySet()) {
            int p = m.parent[j];
            if (p < 0 || !np.containsKey(p)) {
                continue;
            }
            double[] bp = bind.get(p);
            double[] r3 = {bp[0], bp[4], bp[8], bp[1], bp[5], bp[9], bp[2], bp[6], bp[10]}; // 열 우선 → 행 우선 3x3
            double[] t = solve3(r3, sub(np.get(j), np.get(p)));
            ModelData.Node o = nodes[j];
            nodes[j] = new ModelData.Node(o.name, o.children, new float[]{(float) t[0], (float) t[1], (float) t[2]}, o.r, o.s);
        }
        // 정점·법선
        Map<ModelData.Primitive, ModelData.Primitive> done = new HashMap<>();
        ModelData.MeshNode[] meshNodes = new ModelData.MeshNode[m.meshNodes.length];
        for (int mi = 0; mi < m.meshNodes.length; mi++) {
            ModelData.MeshNode mn = m.meshNodes[mi];
            if (mn.skin < 0) {
                meshNodes[mi] = mn;
                continue;
            }
            int[] jl = m.skins[mn.skin].joints;
            double[] T = skinT[mn.skin];
            double[] Ti = invert4(T);
            ModelData.Primitive[] prims = new ModelData.Primitive[mn.primitives.length];
            for (int pi = 0; pi < prims.length; pi++) {
                ModelData.Primitive pr = mn.primitives[pi];
                ModelData.Primitive cached = done.get(pr);
                if (cached == null) {
                    cached = deform(pr, jl, T, Ti, pos, np, A);
                    done.put(pr, cached);
                }
                prims[pi] = cached;
            }
            meshNodes[mi] = new ModelData.MeshNode(mn.node, mn.skin, prims);
        }
        // inverse bind
        ModelData.Skin[] skins = new ModelData.Skin[m.skins.length];
        for (int si = 0; si < m.skins.length; si++) {
            ModelData.Skin s = m.skins[si];
            float[] ibm = new float[s.inverseBind.length];
            for (int k = 0; k < s.joints.length; k++) {
                double[] b = bind.get(s.joints[k]);
                if (b == null) {
                    System.arraycopy(s.inverseBind, k * 16, ibm, k * 16, 16);
                    continue;
                }
                double[] bn = b.clone();
                double[] p = np.get(s.joints[k]);
                bn[12] = p[0];
                bn[13] = p[1];
                bn[14] = p[2];
                double[] r = mul(invert4(bn), skinT[si]);
                for (int q = 0; q < 16; q++) {
                    ibm[k * 16 + q] = (float) r[q];
                }
            }
            skins[si] = new ModelData.Skin(s.joints, ibm);
        }
        // 애니메이션의 위치 채널(블렌더 내보내기는 모든 뼈에 고정 위치를 넣음)도 바뀐 길이만큼 옮긴다
        Map<String, ModelData.Clip> clips = new java.util.LinkedHashMap<>();
        int moved = 0;
        for (Map.Entry<String, ModelData.Clip> ce : m.clips.entrySet()) {
            ModelData.Clip c = ce.getValue();
            ModelData.Channel[] chs = c.channels.clone();
            for (int i = 0; i < chs.length; i++) {
                ModelData.Channel ch = chs[i];
                if (ch.path != ModelData.PATH_T || nodes[ch.node] == m.nodes[ch.node]) {
                    continue;
                }
                float[] d = {nodes[ch.node].t[0] - m.nodes[ch.node].t[0], nodes[ch.node].t[1] - m.nodes[ch.node].t[1],
                        nodes[ch.node].t[2] - m.nodes[ch.node].t[2]};
                float[] v = ch.values.clone();
                int stride = ch.interpolation == ModelData.INTERP_CUBIC ? 9 : 3;
                int off = ch.interpolation == ModelData.INTERP_CUBIC ? 3 : 0; // CUBIC: 접선은 그대로, 값만
                for (int k = off; k + 2 < v.length; k += stride) {
                    v[k] += d[0];
                    v[k + 1] += d[1];
                    v[k + 2] += d[2];
                }
                chs[i] = new ModelData.Channel(ch.node, ch.path, ch.interpolation, ch.times, v);
                moved++;
            }
            clips.put(ce.getKey(), moved > 0 ? new ModelData.Clip(c.name, c.duration, chs) : c);
        }
        ModelData tmp = new ModelData(nodes, m.roots, m.parent, m.order, meshNodes, skins, m.materials, m.images, clips,
                m.diagnostics, 0, 0, m.clipRootSpeed, m.triangleCount, m.vertexCount);
        float[] range = Skinner.restHeightRange(tmp);
        List<String> d2 = new ArrayList<>(m.diagnostics);
        d2.add(String.format("비율 조정: 다리 %.2f · 몸통 %.2f · 팔 %.2f", f.legs(), f.torso(), f.arms()));
        return new ModelData(nodes, m.roots, m.parent, m.order, meshNodes, skins, m.materials, m.images, clips,
                List.copyOf(d2), range[0], range[1], m.clipRootSpeed, m.triangleCount, m.vertexCount);
    }

    private static ModelData.Primitive deform(ModelData.Primitive pr, int[] jl, double[] T, double[] Ti,
                                              Map<Integer, double[]> pos, Map<Integer, double[]> np, Map<Integer, double[]> A) {
        int vc = pr.vertexCount();
        float[] P = new float[vc * 3], N = new float[vc * 3];
        Map<Integer, double[]> Ainv = new HashMap<>();
        for (int v = 0; v < vc; v++) {
            double[] x = xform(T, pr.positions[v * 3], pr.positions[v * 3 + 1], pr.positions[v * 3 + 2], 1);
            double[] n = mulT3inv(T, pr.normals[v * 3], pr.normals[v * 3 + 1], pr.normals[v * 3 + 2]);
            double[] ox = new double[3], on = new double[3];
            for (int k = 0; k < 4; k++) {
                float w = pr.weights == null ? (k == 0 ? 1 : 0) : pr.weights[v * 4 + k];
                if (w == 0) {
                    continue;
                }
                int j = pr.joints == null ? jl[0] : jl[pr.joints[v * 4 + k]];
                double[] p0 = pos.get(j), p1 = np.get(j), a = A.get(j);
                if (p0 == null) {
                    for (int i = 0; i < 3; i++) {
                        ox[i] += w * x[i];
                        on[i] += w * n[i];
                    }
                    continue;
                }
                double[] r = mul3(a, sub(x, p0));
                double[] ai = Ainv.computeIfAbsent(j, q -> transpose3(inv3(a)));
                double[] rn = mul3(ai, n);
                for (int i = 0; i < 3; i++) {
                    ox[i] += w * (p1[i] + r[i]);
                    on[i] += w * rn[i];
                }
            }
            double[] back = xform(Ti, ox[0], ox[1], ox[2], 1);
            double[] nb = mulRot3(T, on); // 메쉬0 → 원래 메쉬: (T^-1)^-T = T^T 의 회전부
            double l = Math.sqrt(dot(nb, nb));
            for (int i = 0; i < 3; i++) {
                P[v * 3 + i] = (float) back[i];
                N[v * 3 + i] = (float) (l < 1e-12 ? (i == 1 ? 1 : 0) : nb[i] / l);
            }
        }
        return new ModelData.Primitive(P, N, pr.uvs, pr.joints, pr.weights, pr.indices, pr.material);
    }

    private static int mainChild(ModelData m, int j, Map<Integer, double[]> pos, String prefer) {
        int best = -1;
        double bestLen = -1;
        for (int c : m.nodes[j].children) {
            if (!pos.containsKey(c)) {
                continue;
            }
            if (prefer != null && bare(m.nodes[c].name).equals(prefer)) {
                return c;
            }
            double[] d = sub(pos.get(c), pos.get(j));
            double l = dot(d, d);
            if (l > bestLen) {
                bestLen = l;
                best = c;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 작은 행렬 계산 (4x4 열 우선, 3x3 행 우선)

    static double[] toD(float[] f) {
        return toD(f, 0);
    }

    static double[] toD(float[] f, int o) {
        double[] d = new double[16];
        for (int i = 0; i < 16; i++) {
            d[i] = f[o + i];
        }
        return d;
    }

    static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++) {
            for (int rr = 0; rr < 4; rr++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + rr] * b[c * 4 + k];
                }
                r[c * 4 + rr] = s;
            }
        }
        return r;
    }

    static double[] invert4(double[] m) {
        double[] r = new double[16];
        double[] a = m;
        double[] inv = new double[16];
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        double det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (Math.abs(det) < 1e-300) {
            throw new ModelFormatException("비율 조정: 행렬 역변환 실패");
        }
        for (int i = 0; i < 16; i++) {
            r[i] = inv[i] / det;
        }
        return r;
    }

    static double[] xform(double[] m, double x, double y, double z, double w) {
        return new double[]{m[0] * x + m[4] * y + m[8] * z + m[12] * w, m[1] * x + m[5] * y + m[9] * z + m[13] * w,
                m[2] * x + m[6] * y + m[10] * z + m[14] * w};
    }

    /** 법선 n 을 (M^-1)^T 의 3x3 으로 = 행 우선으로 쓴 M^-1 의 전치 곱. */
    static double[] mulT3inv(double[] m, double x, double y, double z) {
        double[] r3 = {m[0], m[4], m[8], m[1], m[5], m[9], m[2], m[6], m[10]};
        double[] it = transpose3(inv3(r3));
        return mul3(it, new double[]{x, y, z});
    }

    /** 메쉬0 → 원래 메쉬 법선: (T^-1)^-T = T^T 의 3x3. */
    static double[] mulRot3(double[] m, double[] n) {
        double[] r3t = {m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10]}; // T^T (행 우선)
        return mul3(r3t, n);
    }

    static double[] ident3() {
        return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    static double[] mul3(double[] a, double[] v) {
        return new double[]{a[0] * v[0] + a[1] * v[1] + a[2] * v[2], a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
                a[6] * v[0] + a[7] * v[1] + a[8] * v[2]};
    }

    static double[] transpose3(double[] a) {
        return new double[]{a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    static double[] inv3(double[] a) {
        double det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6]);
        if (Math.abs(det) < 1e-300) {
            throw new ModelFormatException("비율 조정: 3x3 역변환 실패");
        }
        return new double[]{
                (a[4] * a[8] - a[5] * a[7]) / det, (a[2] * a[7] - a[1] * a[8]) / det, (a[1] * a[5] - a[2] * a[4]) / det,
                (a[5] * a[6] - a[3] * a[8]) / det, (a[0] * a[8] - a[2] * a[6]) / det, (a[2] * a[3] - a[0] * a[5]) / det,
                (a[3] * a[7] - a[4] * a[6]) / det, (a[1] * a[6] - a[0] * a[7]) / det, (a[0] * a[4] - a[1] * a[3]) / det};
    }

    static double[] solve3(double[] a, double[] b) {
        return mul3(inv3(a), b);
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double maxDiff(double[] a, double[] b) {
        double m = 0;
        for (int i = 0; i < a.length; i++) {
            m = Math.max(m, Math.abs(a[i] - b[i]));
        }
        return m;
    }

    static double maxAbs(double[] a) {
        double m = 0;
        for (double v : a) {
            m = Math.max(m, Math.abs(v));
        }
        return m;
    }
}
