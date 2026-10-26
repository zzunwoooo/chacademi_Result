package kr.chacademi.npcmodel.core;

/** 열 우선(column-major) 4x4 행렬·쿼터니언 계산. 할당 없이 배열 오프셋으로 다룬다. */
public final class MathUtil {
    private MathUtil() {
    }

    /** out = T * R * S (TRS 는 각각 t[to..], r[ro..] (x,y,z,w), s[so..]). */
    public static void compose(float[] t, int to, float[] r, int ro, float[] s, int so, float[] out, int oo) {
        float x = r[ro], y = r[ro + 1], z = r[ro + 2], w = r[ro + 3];
        float sx = s[so], sy = s[so + 1], sz = s[so + 2];
        float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
        out[oo] = (1 - 2 * (yy + zz)) * sx;
        out[oo + 1] = (2 * (xy + wz)) * sx;
        out[oo + 2] = (2 * (xz - wy)) * sx;
        out[oo + 3] = 0;
        out[oo + 4] = (2 * (xy - wz)) * sy;
        out[oo + 5] = (1 - 2 * (xx + zz)) * sy;
        out[oo + 6] = (2 * (yz + wx)) * sy;
        out[oo + 7] = 0;
        out[oo + 8] = (2 * (xz + wy)) * sz;
        out[oo + 9] = (2 * (yz - wx)) * sz;
        out[oo + 10] = (1 - 2 * (xx + yy)) * sz;
        out[oo + 11] = 0;
        out[oo + 12] = t[to];
        out[oo + 13] = t[to + 1];
        out[oo + 14] = t[to + 2];
        out[oo + 15] = 1;
    }

    /** out = a * b. out 이 a/b 와 겹치면 안 된다. */
    public static void mul(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        for (int c = 0; c < 4; c++) {
            float b0 = b[bo + c * 4], b1 = b[bo + c * 4 + 1], b2 = b[bo + c * 4 + 2], b3 = b[bo + c * 4 + 3];
            for (int r = 0; r < 4; r++) {
                out[oo + c * 4 + r] = a[ao + r] * b0 + a[ao + 4 + r] * b1 + a[ao + 8 + r] * b2 + a[ao + 12 + r] * b3;
            }
        }
    }

    public static void identity(float[] m, int o) {
        for (int k = 0; k < 16; k++) {
            m[o + k] = (k % 5 == 0) ? 1 : 0;
        }
    }

    /** 일반 4x4 역행렬. 특이 행렬이면 false. */
    public static boolean invert(float[] m, int mo, float[] out, int oo) {
        double[] a = new double[16];
        for (int k = 0; k < 16; k++) {
            a[k] = m[mo + k];
        }
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
        if (Math.abs(det) < 1e-30) {
            return false;
        }
        for (int k = 0; k < 16; k++) {
            out[oo + k] = (float) (inv[k] / det);
        }
        return true;
    }

    /** 행렬을 TRS 로 분해 (기울임 없는 행렬 가정). r 은 단위 쿼터니언. */
    public static void decompose(float[] m, float[] t, float[] r, float[] s) {
        t[0] = m[12];
        t[1] = m[13];
        t[2] = m[14];
        float sx = len(m[0], m[1], m[2]), sy = len(m[4], m[5], m[6]), sz = len(m[8], m[9], m[10]);
        float det = m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2]);
        if (det < 0) {
            sx = -sx;
        }
        s[0] = sx;
        s[1] = sy;
        s[2] = sz;
        float r00 = m[0] / sx, r10 = m[1] / sx, r20 = m[2] / sx;
        float r01 = m[4] / sy, r11 = m[5] / sy, r21 = m[6] / sy;
        float r02 = m[8] / sz, r12 = m[9] / sz, r22 = m[10] / sz;
        float tr = r00 + r11 + r22;
        if (tr > 0) {
            float q = (float) Math.sqrt(tr + 1) * 2;
            r[3] = 0.25f * q;
            r[0] = (r21 - r12) / q;
            r[1] = (r02 - r20) / q;
            r[2] = (r10 - r01) / q;
        } else if (r00 > r11 && r00 > r22) {
            float q = (float) Math.sqrt(1 + r00 - r11 - r22) * 2;
            r[3] = (r21 - r12) / q;
            r[0] = 0.25f * q;
            r[1] = (r01 + r10) / q;
            r[2] = (r02 + r20) / q;
        } else if (r11 > r22) {
            float q = (float) Math.sqrt(1 + r11 - r00 - r22) * 2;
            r[3] = (r02 - r20) / q;
            r[0] = (r01 + r10) / q;
            r[1] = 0.25f * q;
            r[2] = (r12 + r21) / q;
        } else {
            float q = (float) Math.sqrt(1 + r22 - r00 - r11) * 2;
            r[3] = (r10 - r01) / q;
            r[0] = (r02 + r20) / q;
            r[1] = (r12 + r21) / q;
            r[2] = 0.25f * q;
        }
        normalizeQuat(r, 0);
    }

    static float len(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public static void normalizeQuat(float[] q, int o) {
        float l = (float) Math.sqrt(q[o] * q[o] + q[o + 1] * q[o + 1] + q[o + 2] * q[o + 2] + q[o + 3] * q[o + 3]);
        if (l < 1e-12f) {
            q[o] = q[o + 1] = q[o + 2] = 0;
            q[o + 3] = 1;
            return;
        }
        q[o] /= l;
        q[o + 1] /= l;
        q[o + 2] /= l;
        q[o + 3] /= l;
    }

    /** out = a * b (쿼터니언 x,y,z,w). out 은 a/b 와 겹치면 안 된다. */
    public static void quatMul(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        float x1 = a[ao], y1 = a[ao + 1], z1 = a[ao + 2], w1 = a[ao + 3];
        float x2 = b[bo], y2 = b[bo + 1], z2 = b[bo + 2], w2 = b[bo + 3];
        out[oo] = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2;
        out[oo + 1] = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2;
        out[oo + 2] = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2;
        out[oo + 3] = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2;
    }

    /** out = slerp(a, b, f). 짧은 경로. out 은 a/b 와 겹쳐도 된다. */
    public static void slerp(float[] a, int ao, float[] b, int bo, float f, float[] out, int oo) {
        float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
        float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
        float d = ax * bx + ay * by + az * bz + aw * bw;
        if (d < 0) {
            d = -d;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }
        float ka, kb;
        if (d > 0.9995f) {
            ka = 1 - f;
            kb = f;
        } else {
            float th = (float) Math.acos(Math.min(1, d));
            float sn = (float) Math.sin(th);
            ka = (float) Math.sin((1 - f) * th) / sn;
            kb = (float) Math.sin(f * th) / sn;
        }
        out[oo] = ax * ka + bx * kb;
        out[oo + 1] = ay * ka + by * kb;
        out[oo + 2] = az * ka + bz * kb;
        out[oo + 3] = aw * ka + bw * kb;
        normalizeQuat(out, oo);
    }
}
