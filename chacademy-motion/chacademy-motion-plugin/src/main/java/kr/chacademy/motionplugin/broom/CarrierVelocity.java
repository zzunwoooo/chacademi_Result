package kr.chacademy.motionplugin.broom;

/**
 * 빗자루 탄 사람의 현재 비행 속도(블록/틱) 추정. 서버가 받은 위치 변화로만 계산한다.
 *
 * <p>모드가 보내는 이동 패킷은 렉 때 몰려서 들어오므로(어떤 틱은 0, 다음 틱은 두 배) 지수 평균으로 부드럽게 만든다.
 * 투사체 마법이 이 값을 받아 "탄 사람 속도만큼 더 빠르게, 지연만큼 앞에서" 출발시킨다.
 * Bukkit 에 의존하지 않는다.
 */
public final class CarrierVelocity {
    /** 새 표본 비중. 0.35 ≈ 최근 3~4틱 평균. */
    static final double ALPHA = 0.35;
    /** 이보다 큰 한 틱 이동은 순간이동으로 보고 버린다. */
    static final double MAX_STEP = 6.0;

    private double x, y, z;
    private boolean primed;

    public void reset() {
        x = y = z = 0;
        primed = false;
    }

    /** 틱마다 이번 틱의 위치 변화를 넣는다. */
    public void sample(double dx, double dy, double dz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || dx * dx + dy * dy + dz * dz > MAX_STEP * MAX_STEP) {
            reset();
            return;
        }
        if (!primed) {
            x = dx;
            y = dy;
            z = dz;
            primed = true;
            return;
        }
        x += (dx - x) * ALPHA;
        y += (dy - y) * ALPHA;
        z += (dz - z) * ALPHA;
    }

    /** {x, y, z} 블록/틱. */
    public double[] get() {
        return new double[]{x, y, z};
    }
}
