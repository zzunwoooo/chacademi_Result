package kr.chacademy.motion.broom;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;

/**
 * 별의 잔향 (아티팩트 star_echo, 2026-10-26). 내 PC 에서만 계산하고 서버 패킷 없음.
 *
 * <ul>
 *   <li>빗자루로 나는 동안 지나간 자리를 2틱마다 기록해 {@link #LIFE_TICKS}(8초) 동안 별가루(END_ROD 입자)를 뿌린다.
 *       빗자루 궤적 VFX 보다 오래 남는 "잔향". 클라 입자라 내 화면에만 보인다.</li>
 *   <li>1초 이상 지난 내 궤적 위(2블록 안)를 같은 방향(약 45도 안)으로 날면 {@link #following} = true → BroomFlight 가 가속.</li>
 * </ul>
 * 점은 최대 {@link #SIZE}개 고정 링 버퍼, 틱마다 입자 몇 개만 (저사양에서도 가벼움).
 */
public final class StarEcho {
    static final int LIFE_TICKS = 160;
    static final int RECORD_EVERY = 2;
    static final int SIZE = LIFE_TICKS / RECORD_EVERY;
    /** 이 틱보다 오래된 점만 "따라 날기" 판정 (방금 지나간 자리 = 지금 내 위치는 제외). */
    static final int FOLLOW_MIN_AGE = 20;
    static final double FOLLOW_RADIUS = 2.0;
    static final double FOLLOW_DOT = 0.7;
    /** 궤적에서 잠깐 벗어나도 가속이 끊기지 않게 (틱). */
    static final int FOLLOW_GRACE = 8;
    static final int PARTICLES_PER_TICK = 3;

    private static final double[] X = new double[SIZE], Y = new double[SIZE], Z = new double[SIZE], DX = new double[SIZE], DZ = new double[SIZE];
    private static final long[] AT = new long[SIZE];
    private static int head, count;
    private static long ticks;
    private static int grace;
    private static Object lastLevel;

    private StarEcho() {}

    /** 나는 중 (BroomFlight.travel, 이동 뒤). 움직일 때만 기록. */
    static void record(double x, double y, double z, double vx, double vz) {
        double h = Math.sqrt(vx * vx + vz * vz);
        if (h < 0.15 || ticks % RECORD_EVERY != 0) return;
        X[head] = x; Y[head] = y; Z[head] = z; DX[head] = vx / h; DZ[head] = vz / h; AT[head] = ticks;
        head = (head + 1) % SIZE;
        if (count < SIZE) count++;
    }

    /** 지금 내 궤적을 따라 날고 있는지 (같은 틱에 여러 번 불러도 같음). */
    static boolean following(double x, double y, double z, double vx, double vz) {
        double h = Math.sqrt(vx * vx + vz * vz);
        boolean hit = false;
        if (h >= 0.2) {
            double nx = vx / h, nz = vz / h, r2 = FOLLOW_RADIUS * FOLLOW_RADIUS;
            for (int i = 0; i < count && !hit; i++) {
                long age = ticks - AT[i];
                if (age < FOLLOW_MIN_AGE || age > LIFE_TICKS) continue;
                double ddx = X[i] - x, ddy = Y[i] - y, ddz = Z[i] - z;
                hit = ddx * ddx + ddy * ddy + ddz * ddz <= r2 && DX[i] * nx + DZ[i] * nz >= FOLLOW_DOT;
            }
        }
        if (hit) grace = FOLLOW_GRACE;
        return hit || grace > 0;
    }

    /** 클라 틱마다 (타고 있지 않아도 남은 별가루는 끝까지 보임). */
    static void tick(Minecraft mc) {
        ticks++;
        if (grace > 0) grace--;
        var level = mc.level;
        if (level != lastLevel) { lastLevel = level; clear(); }
        if (count == 0) return;
        if (level == null || mc.player == null) { clear(); return; }
        var random = mc.player.getRandom();
        int alive = 0;
        for (int i = 0; i < count; i++) if (ticks - AT[i] <= LIFE_TICKS) alive++;
        if (alive == 0) { clear(); return; }
        for (int n = 0; n < PARTICLES_PER_TICK; n++) {
            int i = random.nextInt(count);
            long age = ticks - AT[i];
            if (age < 4 || age > LIFE_TICKS) continue;
            level.addParticle(ParticleTypes.END_ROD, X[i] + (random.nextDouble() - 0.5) * 0.6, Y[i] + 0.4 + (random.nextDouble() - 0.5) * 0.6,
                    Z[i] + (random.nextDouble() - 0.5) * 0.6, 0, -0.005, 0);
        }
    }

    /** 접속 종료·월드 변경. */
    static void clear() {
        head = 0;
        count = 0;
        grace = 0;
    }
}
