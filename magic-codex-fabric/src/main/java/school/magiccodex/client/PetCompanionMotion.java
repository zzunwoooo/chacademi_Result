package school.magiccodex.client;

import java.util.Random;
import school.magiccodex.protocol.PetCompanionProtocol;

/**
 * 동행 펫 한 마리의 움직임 (클라 전용, 서버 통신 없음 · 오너 2026-10-26). 마인크래프트 클래스 없이 계산만 — 틱(20Hz)마다 {@link #tick},
 * 그리기는 {@link #x(float)} 처럼 이전 틱과 섞어서 부드럽게.
 * <ul>
 *   <li>땅(0): 주인 주변 2~3블록을 천천히 돌아다니고(가끔 새 지점), 4블록보다 멀어지면 따라오고, 8블록 넘으면 바로 옆에 나타남. 땅 높이에 붙음.</li>
 *   <li>날기(1): 어깨~머리 높이에서 둥둥 떠서 주인 주변을 천천히 돎.</li>
 *   <li>물(2): 주인이 물에 있으면 몸 높이에서 돌며 따라다니고, 아니면 날기처럼 조금 낮게 둥둥.</li>
 * </ul>
 */
final class PetCompanionMotion {
    static final double FOLLOW = 4, SNAP = 8, WANDER_MIN = 2, WANDER_MAX = 3, KEEP = 2;
    static final double WALK_SPEED = .07, ORBIT_STEP = .025;

    /** 땅 높이: (x, 여기서부터 아래로, z) → 밟을 수 있는 면의 y, 모르면 NaN. */
    interface Ground { double top(double x, double fromY, double z); }

    double x, y, z, px, py, pz;
    float heading, prevHeading;
    boolean placed, moving;
    int anim = PetBbModel.IDLE;
    long age, animStart;
    private double tx, tz;
    private int wait, walked;
    private double orbit;
    private final Random random;

    PetCompanionMotion(long seed) { random = new Random(seed); orbit = random.nextDouble() * Math.PI * 2; }

    /** 다음에 주인이 보이면 바로 옆에 다시 놓는다 (세계 이동·멀어짐). */
    void unplace() { placed = false; }

    void tick(int motion, double ox, double oy, double oz, boolean ownerInWater, Ground ground) {
        px = x; py = y; pz = z; prevHeading = heading;
        double dx = x - ox, dz = z - oz, dist = Math.sqrt(dx * dx + dz * dz);
        if (!placed || dist > SNAP || Math.abs(y - oy) > SNAP) { place(motion, ox, oy, oz, ownerInWater, ground); age++; return; }
        if (motion == PetCompanionProtocol.GROUND) ground(ox, oy, oz, dx, dz, dist, ground);
        else hover(motion, ox, oy, oz, dist, ownerInWater, ground);
        double vx = x - px, vz = z - pz;
        if (vx * vx + vz * vz > 1e-5) heading = approach(heading, (float) Math.atan2(-vx, -vz), .25f);
        int next = motion == PetCompanionProtocol.GROUND ? (moving ? PetBbModel.WALK : PetBbModel.IDLE) : PetBbModel.FLY;
        if (next != anim) { anim = next; animStart = age; }
        age++;
    }

    private void ground(double ox, double oy, double oz, double dx, double dz, double dist, Ground ground) {
        double speed;
        if (dist > FOLLOW) {
            // 따라오기: 주인 쪽으로 KEEP 블록 앞까지, 멀수록 빠르게
            tx = ox + dx / dist * KEEP; tz = oz + dz / dist * KEEP;
            speed = Math.clamp((dist - KEEP) * .12, .12, .6);
            moving = true; walked = 0;
        } else if (moving) {
            speed = WALK_SPEED;
            if (++walked > 200) stop();
        } else {
            speed = 0;
            if (--wait <= 0) {
                double a = random.nextDouble() * Math.PI * 2, r = WANDER_MIN + random.nextDouble() * (WANDER_MAX - WANDER_MIN);
                tx = ox + Math.cos(a) * r; tz = oz + Math.sin(a) * r; moving = true; walked = 0;
                speed = WALK_SPEED;
            }
        }
        if (moving) {
            double ex = tx - x, ez = tz - z, remain = Math.sqrt(ex * ex + ez * ez);
            if (remain < .05) stop();
            else { double step = Math.min(speed, remain) / remain; x += ex * step; z += ez * step; }
        }
        double g = ground.top(x, Math.max(y, oy) + 1.5, z);
        if (Double.isNaN(g)) g = oy;
        y += (g - y) * .45;
        if (Math.abs(g - y) < .01) y = g;
    }

    private void stop() { moving = false; wait = 40 + random.nextInt(100); }

    private void hover(int motion, double ox, double oy, double oz, double dist, boolean ownerInWater, Ground ground) {
        boolean water = motion == PetCompanionProtocol.SWIM && ownerInWater;
        orbit = (orbit + ORBIT_STEP) % (Math.PI * 2);
        double r = water ? 1.3 : 1.6, h = water ? .5 : motion == PetCompanionProtocol.SWIM ? .8 : 1.55;
        double bob = Math.sin(age * .08) * .12;
        double gx = ox + Math.cos(orbit) * r, gz = oz + Math.sin(orbit) * r, gy = oy + h + bob;
        if (!water) { double g = ground.top(gx, gy + 1, gz); if (!Double.isNaN(g) && gy < g + .3) gy = g + .3; }
        double k = dist > FOLLOW ? .25 : .12;
        x += (gx - x) * k; y += (gy - y) * k; z += (gz - z) * k;
        moving = true;
    }

    private void place(int motion, double ox, double oy, double oz, boolean ownerInWater, Ground ground) {
        double a = random.nextDouble() * Math.PI * 2;
        x = ox + Math.cos(a) * 1.5; z = oz + Math.sin(a) * 1.5;
        if (motion == PetCompanionProtocol.GROUND) { double g = ground.top(x, oy + 1.5, z); y = Double.isNaN(g) ? oy : g; }
        else { y = oy + (motion == PetCompanionProtocol.SWIM && ownerInWater ? .5 : motion == PetCompanionProtocol.SWIM ? .8 : 1.55); orbit = a; }
        px = x; py = y; pz = z;
        heading = prevHeading = (float) Math.atan2(x - ox, z - oz);   // 처음엔 주인 쪽을 봄
        placed = true; moving = false; wait = 20;
        anim = motion == PetCompanionProtocol.GROUND ? PetBbModel.IDLE : PetBbModel.FLY; animStart = age;
    }

    double x(float delta) { return px + (x - px) * delta; }
    double y(float delta) { return py + (y - py) * delta; }
    double z(float delta) { return pz + (z - pz) * delta; }
    float heading(float delta) { return prevHeading + wrap(heading - prevHeading) * delta; }
    /** 지금 애니메이션이 시작된 뒤 지난 시간(초). */
    float animTime(float delta) { return (age - animStart + delta) / 20f; }

    /** -π..π 로 감기. */
    static float wrap(float a) {
        float p = (float) Math.PI, t = p * 2;
        a %= t;
        if (a > p) a -= t; else if (a < -p) a += t;
        return a;
    }
    /** from 에서 to 쪽으로 (짧은 방향) 비율 k 만큼 돈다. */
    static float approach(float from, float to, float k) { return wrap(from + wrap(to - from) * k); }
}
