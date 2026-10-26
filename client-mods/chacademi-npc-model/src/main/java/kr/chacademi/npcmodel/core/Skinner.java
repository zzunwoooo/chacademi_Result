package kr.chacademi.npcmodel.core;

/**
 * 노드 월드 행렬 → 관절 행렬 → 정점 선형 블렌드 스키닝 (CPU).
 * 결과 정점은 모델 좌표(장면 기준). 렌더러가 여기에 발바닥 보정·크기·NPC 회전을 곱한다.
 */
public final class Skinner {
    private Skinner() {
    }

    public static void worldMatrices(ModelData m, Pose p, float[] world) {
        float[] local = new float[16];
        for (int idx : m.order) {
            MathUtil.compose(p.t, idx * 3, p.r, idx * 4, p.s, idx * 3, local, 0);
            int par = m.parent[idx];
            if (par < 0) {
                System.arraycopy(local, 0, world, idx * 16, 16);
            } else {
                MathUtil.mul(world, par * 16, local, 0, world, idx * 16);
            }
        }
    }

    /** 스킨 하나의 관절 행렬 = world[joint] * inverseBind. */
    public static void jointMatrices(ModelData.Skin skin, float[] world, float[] out) {
        for (int j = 0; j < skin.joints.length; j++) {
            MathUtil.mul(world, skin.joints[j] * 16, skin.inverseBind, j * 16, out, j * 16);
        }
    }

    /**
     * primitive 하나 변형. joints 가 없으면 nodeMatrix(16개, nodeOff 부터)를 그대로 곱한다.
     * outPos/outNrm 은 정점×3.
     */
    public static void skin(ModelData.Primitive p, float[] jointMats, float[] nodeMatrix, int nodeOff, float[] outPos, float[] outNrm) {
        float[] pos = p.positions, nrm = p.normals;
        int vc = p.vertexCount();
        if (p.joints == null || jointMats == null) {
            float[] m = nodeMatrix;
            int o = nodeOff;
            for (int v = 0; v < vc; v++) {
                float x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
                outPos[v * 3] = m[o] * x + m[o + 4] * y + m[o + 8] * z + m[o + 12];
                outPos[v * 3 + 1] = m[o + 1] * x + m[o + 5] * y + m[o + 9] * z + m[o + 13];
                outPos[v * 3 + 2] = m[o + 2] * x + m[o + 6] * y + m[o + 10] * z + m[o + 14];
                float nx = nrm[v * 3], ny = nrm[v * 3 + 1], nz = nrm[v * 3 + 2];
                setNormal(outNrm, v, m[o] * nx + m[o + 4] * ny + m[o + 8] * nz,
                        m[o + 1] * nx + m[o + 5] * ny + m[o + 9] * nz, m[o + 2] * nx + m[o + 6] * ny + m[o + 10] * nz);
            }
            return;
        }
        int[] js = p.joints;
        float[] ws = p.weights;
        for (int v = 0; v < vc; v++) {
            float x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
            float nx = nrm[v * 3], ny = nrm[v * 3 + 1], nz = nrm[v * 3 + 2];
            float px = 0, py = 0, pz = 0, qx = 0, qy = 0, qz = 0;
            for (int k = 0; k < 4; k++) {
                float w = ws[v * 4 + k];
                if (w == 0) {
                    continue;
                }
                int o = js[v * 4 + k] * 16;
                float[] m = jointMats;
                px += w * (m[o] * x + m[o + 4] * y + m[o + 8] * z + m[o + 12]);
                py += w * (m[o + 1] * x + m[o + 5] * y + m[o + 9] * z + m[o + 13]);
                pz += w * (m[o + 2] * x + m[o + 6] * y + m[o + 10] * z + m[o + 14]);
                qx += w * (m[o] * nx + m[o + 4] * ny + m[o + 8] * nz);
                qy += w * (m[o + 1] * nx + m[o + 5] * ny + m[o + 9] * nz);
                qz += w * (m[o + 2] * nx + m[o + 6] * ny + m[o + 10] * nz);
            }
            outPos[v * 3] = px;
            outPos[v * 3 + 1] = py;
            outPos[v * 3 + 2] = pz;
            setNormal(outNrm, v, qx, qy, qz);
        }
    }

    private static void setNormal(float[] out, int v, float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-12f) {
            out[v * 3] = 0;
            out[v * 3 + 1] = 1;
            out[v * 3 + 2] = 0;
        } else {
            out[v * 3] = x / l;
            out[v * 3 + 1] = y / l;
            out[v * 3 + 2] = z / l;
        }
    }

    /** 기본 자세에서 모든 정점의 [최소 y, 최대 y]. */
    public static float[] restHeightRange(ModelData m) {
        Pose p = new Pose(m.nodes.length);
        p.setRest(m);
        float[] world = new float[m.nodes.length * 16];
        worldMatrices(m, p, world);
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (ModelData.MeshNode mn : m.meshNodes) {
            float[] jm = null;
            if (mn.skin >= 0) {
                ModelData.Skin s = m.skins[mn.skin];
                jm = new float[s.joints.length * 16];
                jointMatrices(s, world, jm);
            }
            for (ModelData.Primitive pr : mn.primitives) {
                float[] pos = new float[pr.positions.length], nrm = new float[pr.positions.length];
                skin(pr, jm, world, mn.node * 16, pos, nrm);
                for (int v = 1; v < pos.length; v += 3) {
                    lo = Math.min(lo, pos[v]);
                    hi = Math.max(hi, pos[v]);
                }
            }
        }
        if (!(hi > lo)) {
            throw new ModelFormatException("기본 자세 높이가 0 이하 (스킨/관절 행렬 확인 필요)");
        }
        return new float[]{lo, hi};
    }
}
