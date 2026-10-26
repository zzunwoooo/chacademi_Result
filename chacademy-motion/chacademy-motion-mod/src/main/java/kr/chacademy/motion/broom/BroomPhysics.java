package kr.chacademy.motion.broom;

import java.util.Random;

/**
 * 빗자루 조작감의 핵심 계산 (내 캐릭터만, 틱마다 1번).
 *
 * <p>바닐라 비행과 다른 점
 * <ul>
 *   <li>관성: W 로 서서히 가속하고, 손을 떼도 미끄러지듯 날아간다</li>
 *   <li>급정거 어려움: S 는 속도를 조금씩만 줄인다</li>
 *   <li>늦게 도는 빗자루: 빗자루 방향(heading)이 시선을 늦게 따라온다. 빠를수록 더 늦다</li>
 *   <li>미끄러짐: 방향을 틀면 옆으로 밀리던 속도가 천천히 사라진다(드리프트)</li>
 *   <li>실속: 너무 느리면 천천히 가라앉고, 제자리 호버링은 어렵다</li>
 *   <li>균형 게이지: 빠를 때 급회전·급제동·충돌·피격·악천후로 깎이고 0 이 되면 낙마</li>
 * </ul>
 * 마인크래프트 클래스에 의존하지 않아서 게임 밖에서 시뮬레이션으로 수치를 맞출 수 있다.
 * 좌표: 마인크래프트와 같다 (yaw 0 = +Z, yaw 90 = -X, pitch 양수 = 아래를 봄).
 */
public final class BroomPhysics {

    /** 서버가 보낸 빗자루 수치. */
    public record Stats(float maxSpeed, float accel, float turn, float brake, float stability, float climb,
                        float mastery, int boostCooldown, int rollCooldown) {}

    /** 이번 틱 입력. */
    public record Input(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean shift,
                        boolean boostPressed, int rollRequest, float camYaw, float camPitch,
                        boolean rain, boolean thunder, double altitude) {}

    // ---- 튜닝 상수 ----
    static final float BOOST_MULT = 1.45f;
    static final int BOOST_TICKS = 30;
    static final int ROLL_TICKS = 14;
    static final float ROLL_IMPULSE = 0.85f;   // 배럴롤 옆 밀기 (2026-10-17: 0.66 → 0.85, 회피 거리 약 2배)
    static final float GRIP_ROLL = 0.02f;      // 배럴롤 중 옆미끄러짐 감쇠 (평소 GRIP 0.10 대신 → 끝까지 밀려 나감)
    static final float GRIP = 0.10f;          // 옆미끄러짐이 틱마다 사라지는 비율
    static final float GRIP_DRIFT = 0.035f;   // A/D 드리프트 중
    static final float GLIDE_DRAG = 0.987f;   // 손 뗐을 때
    static final float STALL = 0.18f;         // 최고 속도 대비 이 비율 아래면 실속
    static final float CRASH_FALL_IMPACT = 0.75f; // 이만큼 속도를 한 번에 잃으면 바로 낙마
    // 위아래 (오너 2026-10-17: 스페이스 상승 · Shift 하강, 지금보다 훨씬 잘 되게)
    static final float VERTICAL_RESPONSE = 0.32f; // 목표 상승/하강 속도로 틱마다 다가가는 비율
    static final float DESCEND_SPEED = 0.85f;     // Shift 하강 속도 (블록/틱)
    /** 스페이스 상승 속도 (블록/틱): climb 0.045 → 0.54, 0.05 → 0.6. */
    float climbSpeed() { return clamp(stats.climb * 12f, 0.4f, 0.95f); }

    public final Stats stats;
    private final Random random;

    // 상태
    public double vx, vy, vz;
    public float heading;
    public float prevHeading;
    public float balance;
    public float prevBalance;
    /** 빗자루 기수가 실제로 향한 위아래 각도. 시선(camPitch)을 선회 속도로 늦게 따라간다 (NaN = 첫 틱에 시선으로 맞춤). */
    public float flightPitch = Float.NaN;
    public int boostTicks;
    public int boostCooldown;
    public int rollTicks;
    public int rollDir;
    public int rollCooldown;
    /** 서버 버프 (마법 375 불꽃 추진): speedTicks 동안 최고 속도 × speedMult. */
    public float speedMult = 1f;
    public int speedTicks;
    /** 서버 버프 (마법 380 순풍): drainTicks 동안 균형 감소 × drainMult. */
    public float drainMult = 1f;
    public int drainTicks;
    /** 이번 틱에 실제로 돈 각도 (시각 효과용, 양수 = 왼쪽으로 돎). */
    public float turnRate;
    /** 이번 틱 균형 감소량 (경고 연출용). */
    public float stress;
    /**
     * 아티팩트 보정 (BroomFlight 가 틱마다 넣음, 기본 1 = 영향 없음). 2026-10-26.
     * accelBonus·speedBonus: 빗자루 끈(이륙 3초)·별의 잔향(내 궤적 따라 날기) 가속 / 최고 속도 배율.
     * turnStress: 바람개비 장식 — 급선회·시선 홱 돌리기로 깎이는 균형 배율.
     */
    public float accelBonus = 1f, speedBonus = 1f, turnStress = 1f;
    private float lastCamYaw = Float.NaN;
    private boolean fellOff;
    private long ridingTicks;
    public long ridingTicks(){return ridingTicks;}
    /** 최대 균형. 오너 2026-10-20: 오래 탈수록 조금씩 줄던 피로 감소는 없앰 → 항상 안정성 단계의 최대치. */
    public float maximumBalance(){
        return balanceCap();
    }
    /** 안정성 단계별 최대 균형: 90 + 3×단계 (단계 = 1 + (stability−0.6)×7.5, 1~10 → 93~120). */
    public float balanceCap(){
        return balanceCap(stats.stability);
    }
    static float balanceCap(float stability){
        float level=clamp(1+(stability-0.6f)*7.5f,1,10);
        return 90+3*level;
    }

    public BroomPhysics(Stats stats, float heading, double vx, double vy, double vz, long seed) {
        this.stats = stats;
        this.heading = heading;
        this.prevHeading = heading;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.random = new Random(seed);
        this.balance = balanceCap();
        this.prevBalance = balance;
    }

    public double horizontalSpeed() {
        return Math.sqrt(vx * vx + vz * vz);
    }

    /** 최고 속도 대비 비율 (0~1.5). */
    public float speedFraction() {
        return (float) Math.min(1.5, horizontalSpeed() / stats.maxSpeed);
    }

    public boolean fellOff() {
        return fellOff;
    }

    /** 숙련도가 낮을수록, 안정성이 낮을수록 균형이 크게 깎인다. */
    float stressMultiplier() {
        return (1.6f - 0.9f * stats.mastery) / stats.stability * (drainTicks > 0 ? drainMult : 1f);
    }

    /**
     * 입력을 반영해 이번 틱에 움직일 속도를 정한다. 결과는 vx/vy/vz.
     * 그다음 실제 이동(충돌 포함) 결과를 {@link #afterMove} 로 알려줘야 한다.
     */
    public void step(Input in) {
        ridingTicks++;
        prevHeading = heading;
        prevBalance = balance;
        stress = 0;
        if (boostTicks > 0) boostTicks--;
        if (boostCooldown > 0) boostCooldown--;
        if (rollTicks > 0) rollTicks--;
        if (rollCooldown > 0) rollCooldown--;
        if (speedTicks > 0) speedTicks--;
        if (drainTicks > 0) drainTicks--;

        float sf = Math.min(1f, speedFraction());

        // 부스트 (달리기 키)
        if (in.boostPressed() && boostCooldown == 0 && boostTicks == 0) {
            boostTicks = BOOST_TICKS;
            boostCooldown = stats.boostCooldown;
        }

        // 1) 빗자루 방향이 시선을 늦게 따라감
        float diff = wrap(in.camYaw() - heading);
        float maxTurn = stats.turn * (1f - 0.4f * sf);
        if (boostTicks > 0) maxTurn *= 0.6f;
        float turn = clamp(diff, -maxTurn, maxTurn);
        heading = wrap(heading + turn);
        turnRate = -turn; // yaw 가 줄면 왼쪽으로 돎 → 양수

        // 2) 빗자루 기준으로 속도를 앞/옆/위로 나눔
        double hr = Math.toRadians(heading);
        double fx = -Math.sin(hr), fz = Math.cos(hr);   // 앞
        double rx = -Math.cos(hr), rz = -Math.sin(hr);  // 오른쪽
        double vf = vx * fx + vz * fz;
        double vr = vx * rx + vz * rz;
        double up = vy;

        // 위아래도 선회 수치만큼만 시선을 따라감 (위를 보고 W = 상승, 아래를 보고 W = 하강). 좌우와 같은 maxTurn.
        float pitchTarget = clamp(in.camPitch(), -50, 60);
        if (Float.isNaN(flightPitch)) flightPitch = pitchTarget;
        flightPitch += clamp(pitchTarget - flightPitch, -maxTurn, maxTurn);
        float pitch = flightPitch;
        double pr = Math.toRadians(pitch);
        float accel = stats.accel * accelBonus * (boostTicks > 0 ? 1.8f : 1f);

        // 3) 앞뒤
        boolean vertical = in.jump() != in.shift(); // 스페이스 또는 Shift 하나만: 키가 위아래를 정함
        if (in.forward() || boostTicks > 0) {
            vf += accel * Math.cos(pr);
            if (!vertical) {
                double pitchLift=-Math.sin(pr)*stats.maxSpeed*Math.max(.2f,sf)*.65;
                up+=(pitchLift-up)*(.08+accel); // Pitch steers vertical velocity as speed rises.
            }
        }
        if (in.back()) {
            vf *= 1 - stats.brake;
            vr *= 1 - stats.brake;
            up *= 1 - stats.brake;
            if (sf > 0.65f) stress += 1.1f * sf; // 빠를 때 급제동은 균형을 깎음
        } else if (!in.forward() && boostTicks == 0) {
            vf *= GLIDE_DRAG;
        }

        // 4) 옆: A/D 드리프트 + 미끄러짐 감쇠
        boolean drifting = in.left() != in.right();
        if (drifting) vr += (in.right() ? 1 : -1) * accel * 0.35;
        vr *= 1 - (rollTicks > 0 ? GRIP_ROLL : drifting ? GRIP_DRIFT : GRIP);

        // 배럴롤 (A/D 두 번)
        if (in.rollRequest() != 0 && rollCooldown == 0 && rollTicks == 0 && sf > 0.3f) {
            rollTicks = ROLL_TICKS;
            rollDir = in.rollRequest();
            rollCooldown = stats.rollCooldown;
            vr += rollDir * ROLL_IMPULSE;
            stress += 14f;
        }

        // 5) 위아래 + 실속: 스페이스 = 상승, Shift = 하강 (제자리에서도 바로 반응), 둘 다 떼면 서서히 수평
        if (vertical) {
            double target = in.jump() ? climbSpeed() : -DESCEND_SPEED;
            up += (target - up) * VERTICAL_RESPONSE;
        } else {
            up *= 0.88;
            if (sf < STALL) up -= 0.03 * (1 - sf / STALL);
        }
        // 오르면 속도를 조금 잃고, 내려꽂으면 속도가 붙음
        if (up > 0) vf -= up * 0.015;
        else if (in.forward()) vf += -up * 0.03;

        // 6) 속도 상한
        double cap = stats.maxSpeed * speedBonus * (boostTicks > 0 ? BOOST_MULT : 1f) * (speedTicks > 0 ? speedMult : 1f);
        vf = Math.max(-0.2, Math.min(cap, vf));
        double h = Math.sqrt(vf * vf + vr * vr);
        if (h > cap * 1.1) {
            vf *= cap * 1.1 / h;
            vr *= cap * 1.1 / h;
        }
        up = Math.max(-1.2, Math.min(1.0, up));

        // 7) 악천후·고공 바람
        if (in.thunder()) {
            vr += (random.nextFloat() - 0.5f) * 0.05f;
            stress += random.nextFloat() * 0.45f;
        } else if (in.rain()) {
            stress += random.nextFloat() * 0.12f;
        }
        if (in.altitude() > 190) {
            float wind = (float) Math.min(1, (in.altitude() - 190) / 80);
            vr += (random.nextFloat() - 0.5f) * 0.06f * wind;
            stress += random.nextFloat() * 0.3f * wind;
        }

        // 8) 균형: 빠를 때 시선을 크게 틀면 깎임
        float absDiff = Math.abs(diff);
        float safe = lerp(30f, 12f, sf);
        if (sf > 0.35f && absDiff > safe) {
            // 빗자루가 못 따라갈 만큼 크게 틀고 있으면 균형이 깎이고, 억지로 꺾느라 속도도 줄어듦
            stress += Math.min((absDiff - safe) * 0.06f * sf, 2.2f) * turnStress;
            double bleed = 1 - Math.min(0.03, absDiff / 180.0 * 0.05);
            vf *= bleed;
        }
        if (!Float.isNaN(lastCamYaw)) {
            // 한 틱에 시선을 홱 돌리는 것(급격한 방향 전환)은 한 번에 크게 깎임
            float flick = Math.abs(wrap(in.camYaw() - lastCamYaw));
            if (flick > 30 && sf > 0.5f) stress += (flick - 30) * 0.35f * sf * turnStress;
        }
        lastCamYaw = in.camYaw();
        if (boostTicks > 0 && Math.abs(turn) > maxTurn * 0.8f) stress += 0.6f * turnStress;

        applyStress();

        vx = fx * vf + rx * vr;
        vz = fz * vf + rz * vr;
        vy = up;
    }

    private void applyStress() {
        if (stress > 0) balance -= stress * stressMultiplier();
        else balance += 1.5f * stats.stability;
        balance = clamp(balance, 0, maximumBalance());
        if (balance <= 0) fellOff = true;
    }

    /**
     * 실제 이동 결과. 마인크래프트 충돌로 막힌 축은 속도가 0 이 되어 있다.
     *
     * @return 충돌로 잃은 수평 속도 (0 이면 충돌 없음)
     */
    public double afterMove(double ax, double ay, double az, boolean horizontalCollision, boolean onGround) {
        double before = horizontalSpeed();
        double impact = 0;
        if (horizontalCollision) {
            double after = Math.sqrt(ax * ax + az * az);
            impact = Math.max(0, before - after);
            if (impact > 0.08) {
                balance = clamp(balance - (float) impact * 55f * stressMultiplier(), 0, maximumBalance());
                stress += (float) impact * 55f;
                if (impact > CRASH_FALL_IMPACT || balance <= 0) fellOff = true;
                // 살짝 튕겨 나옴
                if (before > 1e-4) {
                    ax -= vx / before * impact * 0.25;
                    az -= vz / before * impact * 0.25;
                }
            }
        }
        // 급강하하다 땅에 처박으면 균형이 크게 깎이고, 너무 빠르면 바로 낙마
        if (onGround && vy < -0.6) {
            float hard = (float) (-vy - 0.6);
            balance = clamp(balance - hard * 70f * stressMultiplier(), 0, maximumBalance());
            stress += hard * 70f;
            impact = Math.max(impact, hard);
            if (-vy > 1.0 || balance <= 0) fellOff = true;
        }
        vx = ax;
        vy = ay;
        vz = az;
        if (onGround) {
            vy = Math.max(0, vy);
            vx *= 0.85;
            vz *= 0.85;
        }
        return impact;
    }

    /** 맞았을 때. */
    public void hurt() {
        balance = clamp(balance - 15f * stressMultiplier(), 0, maximumBalance());
        stress += 15f;
        if (balance <= 0) fellOff = true;
    }

    /** 공중 재탑승: 현재 최대치 이하의 낮은 균형, 경과 시간과 쿨다운은 보존. */
    public void caught() {
        balance = Math.min(18,maximumBalance());
        prevBalance = balance;
        flightPitch = Float.NaN; // 다시 잡은 순간의 시선에서 새로 시작
        fellOff = false;
    }

    /** 큐디치 매달림 시작: 균형 0, 그 자리에 멈춤 (낙마 판정은 지움). */
    public void startHang() {
        balance = 0;
        prevBalance = 0;
        vx = vy = vz = 0;
        boostTicks = 0;
        rollTicks = 0;
        stress = 0;
        fellOff = false;
    }

    /** 매달린 동안 균형 회복. @return 가득 찼으면 true (다시 올라탐). */
    public boolean recoverHang(float amount) {
        prevBalance = balance;
        balance = Math.min(maximumBalance(), balance + amount);
        stress = 0;
        fellOff = false;
        return balance >= maximumBalance();
    }

    // ---- 도구 ----
    public static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
