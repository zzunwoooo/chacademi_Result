package school.magiccodex.paper;

/**
 * 타운맵 그림 맞추기 계산 (오픈 전 메모 #53, 2026-10-25). 그림 픽셀 (px, py) ↔ 월드 (x, z) 를 "닮음 변환"(크기·회전·이동, 찌그러짐 없음)으로 본다.
 * travel.yml 에는 그림 모서리 3곳(왼쪽 위 / 오른쪽 위 / 왼쪽 아래)의 월드 좌표로 저장된다.
 * <ul>
 *   <li>기준점 1개: 그 픽셀이 지금 서 있는 곳에 오도록 그림만 옮긴다 (크기·회전 그대로).</li>
 *   <li>기준점 2개: 두 점이 다 맞도록 크기·회전까지 정한다.</li>
 * </ul>
 * 순수 계산 (서버 없이 테스트).
 */
final class TravelMapFit {
    /** world = (ox, oz) + k · pixel, k = (kr + i·ki) 복소수 곱 (픽셀 y·월드 z 모두 아래/남쪽이 +). */
    record Fit(double ox, double oz, double kr, double ki) {
        double x(double px, double py) { return ox + kr * px - ki * py; }
        double z(double px, double py) { return oz + ki * px + kr * py; }
        /** 블록/픽셀. */
        double scale() { return Math.hypot(kr, ki); }
        /** 그림 가로축이 월드 +x 에서 돈 각도 (도, 시계 방향 +). */
        double degrees() { return Math.toDegrees(Math.atan2(ki, kr)); }
        double px(double x, double z) { double dx = x - ox, dz = z - oz, d = kr * kr + ki * ki; return (kr * dx + ki * dz) / d; }
        double py(double x, double z) { double dx = x - ox, dz = z - oz, d = kr * kr + ki * ki; return (-ki * dx + kr * dz) / d; }
        /** travel.yml 모서리 [tlx, tlz, trx, trz, blx, blz]. */
        double[] corners(int w, int h) {
            return new double[] { r(x(0, 0)), r(z(0, 0)), r(x(w, 0)), r(z(w, 0)), r(x(0, h)), r(z(0, h)) };
        }
    }

    private static double r(double v) { return Math.round(v * 1000) / 1000.0; }

    /** 모서리 3곳 → 변환 (오른쪽 위 모서리로 크기·회전, 세로는 같은 비율이라고 본다). */
    static Fit of(double[] corners, int w) {
        double kr = (corners[2] - corners[0]) / w, ki = (corners[3] - corners[1]) / w;
        return new Fit(corners[0], corners[1], kr, ki);
    }

    /** 처음 값: 한 픽셀 = scale 블록, 북쪽이 위, (px, py) 가 (x, z). */
    static Fit anchored(double px, double py, double x, double z, double scale) {
        return new Fit(x - scale * px, z - scale * py, scale, 0);
    }

    /** 기준점 1개: 크기·회전은 그대로, (px, py) 가 (x, z) 로 오게 옮긴다. */
    static Fit move(Fit f, double px, double py, double x, double z) {
        return new Fit(f.ox() + x - f.x(px, py), f.oz() + z - f.z(px, py), f.kr(), f.ki());
    }

    /** 기준점 2개: (p1 → w1), (p2 → w2) 둘 다 맞춘다. 두 픽셀이 너무 가까우면 예외. */
    static Fit two(double p1x, double p1y, double w1x, double w1z, double p2x, double p2y, double w2x, double w2z) {
        double ax = p2x - p1x, ay = p2y - p1y, bx = w2x - w1x, bz = w2z - w1z, d = ax * ax + ay * ay;
        if (d < 400) throw new IllegalArgumentException("두 기준점이 그림에서 너무 가까워요 (20픽셀 이상 떨어진 곳으로)");
        if (bx * bx + bz * bz < 4) throw new IllegalArgumentException("두 기준점에서 선 자리가 너무 가까워요 (2블록 이상 떨어진 곳에서)");
        double kr = (bx * ax + bz * ay) / d, ki = (bz * ax - bx * ay) / d;
        return new Fit(w1x - (kr * p1x - ki * p1y), w1z - (ki * p1x + kr * p1y), kr, ki);
    }

    /** 크기만 바꾼다 (기준 픽셀은 제자리, 회전 그대로). */
    static Fit scale(Fit f, double px, double py, double scale) {
        double s = f.scale(), x = f.x(px, py), z = f.z(px, py);
        double kr = f.kr() / s * scale, ki = f.ki() / s * scale;
        return move(new Fit(0, 0, kr, ki), px, py, x, z);
    }

    /** 회전만 바꾼다 (기준 픽셀은 제자리, 크기 그대로). degrees = 그림 가로축이 월드 +x 에서 돈 각도. */
    static Fit rotate(Fit f, double px, double py, double degrees) {
        double s = f.scale(), a = Math.toRadians(degrees), x = f.x(px, py), z = f.z(px, py);
        return move(new Fit(0, 0, s * Math.cos(a), s * Math.sin(a)), px, py, x, z);
    }

    /** 그림 전체를 월드에서 (dx, dz) 블록 민다. */
    static Fit shift(Fit f, double dx, double dz) { return new Fit(f.ox() + dx, f.oz() + dz, f.kr(), f.ki()); }

    private TravelMapFit() {}
}
