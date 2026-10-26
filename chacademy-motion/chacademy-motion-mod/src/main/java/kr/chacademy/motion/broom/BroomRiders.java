package kr.chacademy.motion.broom;

import kr.chacademy.motion.net.BroomPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 화면에 그릴 빗자루들 (나 + 주변 유저).
 * 서버는 "누가 어떤 빗자루를 탔다"만 한 번 알려주고, 기울기·방향·흔들림은 각 PC 가
 * 그 플레이어의 움직임을 보고 직접 계산한다 (추가 패킷 없음).
 */
public final class BroomRiders {

    /** 빗자루 탄 플레이어 한 명의 화면 표시 상태. */
    public static final class Rider {
        public final UUID id;
        public String broom;
        public float yaw, prevYaw;
        public float pitch, prevPitch;   // 양수 = 앞이 아래로
        public float bank, prevBank;     // 양수 = 왼쪽이 아래로
        public float spin, prevSpin;     // 배럴롤 진행 (도)
        int spinTicks, spinDir;
        public int age;                  // 탄 뒤 지난 틱 (소환 연출)
        public float speed;              // 블록/틱
        public boolean boosting;
        public boolean wobbling;
        /** 큐디치: 한 팔로 매달림 / 다시 올라타는 남은 틱 (모든 유저 화면에 같은 모션) */
        public boolean hanging;
        public int climbTicks;
        Vec3 lastPos;
        Vec3 approachFrom;
        boolean hasYaw;
        int missing;

        Rider(UUID id, String broom) {
            this.id = id;
            this.broom = broom;
        }

        public float yaw(float pt) {
            return prevYaw + Mth.wrapDegrees(yaw - prevYaw) * pt;
        }

        public float pitch(float pt) {
            return Mth.lerp(pt, prevPitch, pitch);
        }

        public float bank(float pt) {
            return Mth.lerp(pt, prevBank, bank);
        }

        public float spin(float pt) {
            return Mth.lerp(pt, prevSpin, spin);
        }

        /** 0→1 소환 크기 */
        /** 매달린 동안 빗자루가 손 높이(머리 위)로 올라가는 양 (블록). 올라타는 동안 0으로 내려옴. */
        public float hangLift(float pt) {
            if (hanging) return BroomRenderer.HANG_LIFT;
            if (climbTicks <= 0) return 0f;
            float t = Mth.clamp((climbTicks - pt) / (float) BroomFlight.CLIMB_TICKS, 0f, 1f);
            return BroomRenderer.HANG_LIFT * t * t * (3 - 2 * t);
        }

        public float summonScale(float pt) {
            return approachFrom!=null?1f:Mth.clamp((age + pt) / 6f, 0f, 1f);
        }

        void startRoll(int dir) {
            spinTicks = BroomPhysics.ROLL_TICKS;
            spinDir = dir;
        }
    }

    /** 떨어지는 빗자루 (낙마 후 잠깐 혼자 날아가다 떨어짐). 화면 연출 전용. */
    public static final class Drop {
        public final String broom;
        public double x, y, z, px, py, pz;
        double vx, vy, vz;
        public float yaw, pitch, roll, pYaw, pPitch, pRoll;
        final float spinYaw, spinPitch, spinRoll;
        public int age;
        public final int life;

        Drop(String broom, Vec3 pos, Vec3 vel, float yaw, int life, long seed) {
            this.broom = broom;
            this.x = this.px = pos.x;
            this.y = this.py = pos.y;
            this.z = this.pz = pos.z;
            this.vx = vel.x;
            this.vy = vel.y + 0.15;
            this.vz = vel.z;
            this.yaw = this.pYaw = yaw;
            var r = new java.util.Random(seed);
            spinYaw = (r.nextFloat() - 0.5f) * 30f;
            spinPitch = 8f + r.nextFloat() * 10f;
            spinRoll = (r.nextFloat() - 0.5f) * 40f;
            this.life = life;
        }

        public Vec3 pos(float pt) {
            return new Vec3(Mth.lerp(pt, px, x), Mth.lerp(pt, py, y), Mth.lerp(pt, pz, z));
        }

        boolean tick(net.minecraft.world.level.Level level) {
            px = x;
            py = y;
            pz = z;
            pYaw = yaw;
            pPitch = pitch;
            pRoll = roll;
            age++;
            vy -= 0.04;
            vx *= 0.97;
            vy *= 0.98;
            vz *= 0.97;
            double nx = x + vx, ny = y + vy, nz = z + vz;
            var pos = net.minecraft.core.BlockPos.containing(nx, ny, nz);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                // 땅/벽에 닿으면 멈춤
                vx = vy = vz = 0;
            } else {
                x = nx;
                y = ny;
                z = nz;
                yaw += spinYaw;
                pitch += spinPitch;
                roll += spinRoll;
            }
            return age < life;
        }

        /** 끝나갈수록 작아짐 */
        public float scale(float pt) {
            float left = life - (age + pt);
            return Mth.clamp(left / 8f, 0f, 1f);
        }
    }

    private static final Map<UUID, Rider> RIDERS = new HashMap<>();
    private static final List<Drop> DROPS = new ArrayList<>();

    private BroomRiders() {}

    public static Map<UUID, Rider> riders() {
        return RIDERS;
    }

    public static List<Drop> drops() {
        return DROPS;
    }

    public static Rider get(UUID id) {
        return RIDERS.get(id);
    }

    public static boolean isRiding(Player p) {
        return RIDERS.containsKey(p.getUUID());
    }

    public static void clear() {
        for (var id : new ArrayList<>(RIDERS.keySet())) remove(id, false);
        DROPS.clear();
    }

    /** 서버의 broom_state 수신. 나 자신에 대한 것도 온다. */
    public static void onState(BroomPackets.StateS2C s) {
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player != null && mc.player.getUUID().equals(s.player());
        if (self) {
            // 내 빗자루는 BroomFlight 가 관리. 서버가 내리게 한 경우(시간 끝, 구역, 명령어)만 여기로 온다
            if (s.broom().isEmpty()) BroomFlight.serverStopped(s.reason());
            else if (s.reason() == BroomPackets.STATE_ROLL_LEFT || s.reason() == BroomPackets.STATE_ROLL_RIGHT) {
                // 내 구르기는 이미 로컬에서 재생함
            }
            return;
        }
        if (s.broom().isEmpty()) {
            remove(s.player(), s.reason() == BroomPackets.STATE_FALL);
            return;
        }
        Rider r = RIDERS.get(s.player());
        if (r == null) {
            r = new Rider(s.player(), s.broom());
            if (s.reason() != BroomPackets.STATE_START) r.age = 100; // 이미 타고 있던 사람 (나중에 시야에 들어옴)
            RIDERS.put(s.player(), r);
        } else {
            r.broom = s.broom();
        }
        if (s.reason() == BroomPackets.STATE_HANG) { r.hanging = true; r.climbTicks = 0; }
        else if (s.reason() == BroomPackets.STATE_CLIMB) { if (r.hanging) r.climbTicks = BroomFlight.CLIMB_TICKS; r.hanging = false; }
        if (s.reason() == BroomPackets.STATE_ROLL_LEFT) r.startRoll(-1);
        if (s.reason() == BroomPackets.STATE_ROLL_RIGHT) r.startRoll(1);
    }

    /** 내가 타기 시작 (BroomFlight 가 부름). */
    static Rider addSelf(LocalPlayer p, String broom, boolean resumed) {
        Rider r = new Rider(p.getUUID(), broom);
        r.yaw = r.prevYaw = p.getYRot();
        r.hasYaw = true;
        if (resumed) r.age = 100;
        RIDERS.put(p.getUUID(), r);
        BroomPose.start(p);
        return r;
    }

    /** 빗자루에서 내림. fell 이면 빗자루가 날아가 떨어지는 연출 + 허우적 모션. */
    static void remove(UUID id, boolean fell) {
        Rider r = RIDERS.remove(id);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Player p = mc.level.getPlayerByUUID(id);
        if (p instanceof AbstractClientPlayer cp) BroomPose.stop(cp, fell);
        if (r != null && fell && p != null) {
            Vec3 vel = r.lastPos == null ? p.getDeltaMovement() : p.position().subtract(r.lastPos);
            dropAt(r.broom, p.position().add(0, BroomRenderer.SEAT_Y, 0), vel, r.yaw, BroomFlight.CATCH_TICKS);
        }
    }

    static Drop dropAt(String broom, Vec3 pos, Vec3 vel, float yaw, int life) {
        Drop d = new Drop(broom, pos, vel, yaw, life, (long) (pos.x * 31 + pos.z * 17));
        DROPS.add(d);
        return d;
    }

    /** 틱마다: 남들의 빗자루 방향·기울기를 움직임으로 추정, 떨어지는 빗자루 진행. */
    public static void tick(Minecraft mc) {
        if (mc.level == null) {
            if (!RIDERS.isEmpty() || !DROPS.isEmpty()) clear();
            return;
        }
        for (Iterator<Drop> it = DROPS.iterator(); it.hasNext(); ) {
            if (!it.next().tick(mc.level)) it.remove();
        }
        for (Iterator<Rider> it = RIDERS.values().iterator(); it.hasNext(); ) {
            Rider r = it.next();
            r.prevYaw = r.yaw;
            r.prevPitch = r.pitch;
            r.prevBank = r.bank;
            r.prevSpin = r.spin;
            r.age++;
            if (r.spinTicks > 0) {
                r.spinTicks--;
                float t = 1f - r.spinTicks / (float) BroomPhysics.ROLL_TICKS;
                float eased = t * t * (3 - 2 * t);
                r.spin = -r.spinDir * 360f * eased; // 오른쪽(+1)으로 구르면 오른쪽이 아래로 → bank 음수 방향
                if (r.spinTicks == 0) r.spin = r.prevSpin = 0;
            }
            Player p = mc.level.getPlayerByUUID(r.id);
            if (p == null) {
                // 시야 밖으로 나간 사람: 서버는 내릴 때 시야 안 유저에게만 알려주므로, 오래 안 보이면 지움
                // (다시 보이면 서버가 "타는 중"을 다시 보내 줌)
                r.lastPos = null;
                if (++r.missing > 40) { it.remove(); BroomPose.forget(r.id); }
                continue;
            }
            r.missing = 0;
            if (p == mc.player && BroomFlight.active()) {
                BroomFlight.fillVisual(r);
            } else {
                estimate(r, p);
                if (r.climbTicks > 0) r.climbTicks--;
                if (r.hanging || r.climbTicks > 0) { r.pitch = 0; r.bank = 0; r.boosting = false; }
            }
            BroomPose.update(p, r);
        }
    }

    private static void estimate(Rider r, Player p) {
        Vec3 pos = p.position();
        Vec3 v = r.lastPos == null ? Vec3.ZERO : pos.subtract(r.lastPos);
        r.lastPos = pos;
        if (v.lengthSqr() > 16) v = Vec3.ZERO; // 순간이동
        double h = Math.sqrt(v.x * v.x + v.z * v.z);
        r.speed = (float) h;
        float target;
        if (h > 0.08) target = (float) (Mth.atan2(v.z, v.x) * Mth.RAD_TO_DEG) - 90f;
        else target = p.getYRot();
        if (!r.hasYaw) {
            r.yaw = r.prevYaw = target;
            r.hasYaw = true;
        }
        float d = Mth.wrapDegrees(target - r.yaw);
        float step = Mth.clamp(d * 0.35f, -14f, 14f);
        r.yaw = Mth.wrapDegrees(r.yaw + step);
        float pitchTarget = h < 0.05 && Math.abs(v.y) < 0.05 ? 0f : (float) (-Mth.atan2(v.y, Math.max(h, 0.2)) * Mth.RAD_TO_DEG) * 0.6f;
        r.pitch += (Mth.clamp(pitchTarget, -28f, 28f) - r.pitch) * 0.3f;
        float bankTarget = Mth.clamp(-step * 2.2f * Mth.clamp((float) h / 0.6f, 0f, 1f), -28f, 28f);
        r.bank += (bankTarget - r.bank) * 0.3f;
        r.boosting = h > 1.25;
        r.wobbling = false;
    }
}
