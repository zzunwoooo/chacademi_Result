package kr.chacademi.npcmodel.core;

/** 노드별 로컬 TRS (인스턴스마다 하나씩, 모델 데이터는 공유). */
public final class Pose {
    public final float[] t, r, s;
    public final int size;

    public Pose(int nodes) {
        this.size = nodes;
        t = new float[nodes * 3];
        r = new float[nodes * 4];
        s = new float[nodes * 3];
    }

    public void setRest(ModelData m) {
        for (int k = 0; k < size; k++) {
            ModelData.Node n = m.nodes[k];
            System.arraycopy(n.t, 0, t, k * 3, 3);
            System.arraycopy(n.r, 0, r, k * 4, 4);
            System.arraycopy(n.s, 0, s, k * 3, 3);
        }
    }

    public void copyFrom(Pose o) {
        System.arraycopy(o.t, 0, t, 0, t.length);
        System.arraycopy(o.r, 0, r, 0, r.length);
        System.arraycopy(o.s, 0, s, 0, s.length);
    }

    /** this = lerp(this, other, w). 회전은 slerp. */
    public void blendToward(Pose o, float w) {
        if (w <= 0) {
            return;
        }
        if (w >= 1) {
            copyFrom(o);
            return;
        }
        for (int k = 0; k < t.length; k++) {
            t[k] += (o.t[k] - t[k]) * w;
            s[k] += (o.s[k] - s[k]) * w;
        }
        for (int k = 0; k < size; k++) {
            MathUtil.slerp(r, k * 4, o.r, k * 4, w, r, k * 4);
        }
    }
}
