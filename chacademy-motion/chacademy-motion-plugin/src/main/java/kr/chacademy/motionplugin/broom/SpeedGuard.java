package kr.chacademy.motionplugin.broom;

/**
 * 빗자루 속도 검사. 움직임은 모드가 계산하므로 서버는 "1초 동안 이동한 거리"만 본다.
 * 패킷이 몰려 들어오는 순간(렉)은 1초 합계로 흡수하고, 3번 연속 넘으면 위반으로 본다.
 * Bukkit 에 의존하지 않는다.
 */
public final class SpeedGuard {
    static final int WINDOW = 20;
    private final double[] ring = new double[WINDOW];
    private int index;
    private int filled;
    private double sum;
    private int strikes;
    private int cooldown;

    /** 순간이동 등으로 위치가 튀었을 때 기록을 비운다. */
    public void reset() {
        java.util.Arrays.fill(ring, 0);
        index = 0;
        filled = 0;
        sum = 0;
        strikes = 0;
        cooldown = 0;
    }

    /**
     * 틱마다 이동 거리를 넣는다.
     *
     * @param distance 이번 틱 이동 거리 (블록)
     * @param cap      허용 최고 속도 (블록/틱, 부스트 포함)
     * @return 위반이 확정되면 true
     */
    public boolean tick(double distance, double cap) {
        if (!Double.isFinite(distance)) distance = 1000;
        sum += distance - ring[index];
        ring[index] = distance;
        index = (index + 1) % WINDOW;
        if (filled < WINDOW) filled++;
        if (cooldown > 0) cooldown--;
        double allowed = filled * cap * 1.3 + 6;
        if (sum > allowed) {
            if (cooldown == 0) {
                strikes++;
                cooldown = WINDOW;
            }
        } else if (cooldown == 0 && strikes > 0) {
            strikes--;
            cooldown = WINDOW;
        }
        return strikes >= 3;
    }
}
