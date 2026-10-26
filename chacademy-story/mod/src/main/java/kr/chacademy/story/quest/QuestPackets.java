package kr.chacademy.story.quest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 스토리 단계 HUD 패킷 (서버 → 클라만). 바이트 형식은 플러그인 QuestCodec 과 같아야 한다.
 * 문자열 = VarInt 길이 + UTF-8, 정수 = VarInt, 참거짓 = 1바이트, 실수 = 8바이트 double.
 */
public final class QuestPackets {
    public static final int MAX_CONDS = 16;
    public static final int TARGET_NONE = 0, TARGET_LOCATION = 1, TARGET_NPC = 2;

    private QuestPackets() {}

    public record Cond(String label, int current, int required) {}

    /** 단계 전체. step 이 비어 있으면 진행할 단계 없음 (HUD 숨김). ready = 조건을 다 채움 (포탈·징표 표시). */
    public record StepS2C(int session, String step, int run, String chapter, String description, boolean ready, List<Cond> conds,
                          int targetType, String world, double x, double y, double z, double radius, String npcEntity) implements CustomPacketPayload {
        public static final Type<StepS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("chacademy", "quest_step"));
        /** 깨진·모르는 형식의 패킷 (핸들러가 무시). */
        public static final StepS2C INVALID = new StepS2C(0, "", 0, "", "", false, List.of(), TARGET_NONE, "", 0, 0, 0, 0, "");
        public static final StreamCodec<RegistryFriendlyByteBuf, StepS2C> CODEC = SafeCodec.failSafe("quest_step", StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.session()); buf.writeUtf(v.step()); buf.writeVarInt(v.run()); buf.writeUtf(v.chapter()); buf.writeUtf(v.description());
            buf.writeBoolean(v.ready()); buf.writeVarInt(v.conds().size());
            for (Cond c : v.conds()) { buf.writeUtf(c.label()); buf.writeVarInt(c.current()); buf.writeVarInt(c.required()); }
            buf.writeVarInt(v.targetType()); buf.writeUtf(v.world()); buf.writeDouble(v.x()); buf.writeDouble(v.y()); buf.writeDouble(v.z());
            buf.writeDouble(v.radius()); buf.writeUtf(v.npcEntity());
        }, buf -> {
            int session = buf.readVarInt();
            String step = buf.readUtf(64);
            int run = buf.readVarInt();
            String chapter = buf.readUtf(256);
            String description = buf.readUtf(1024);
            boolean ready = buf.readBoolean();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_CONDS) throw new IllegalArgumentException("too many quest conditions: " + n);
            List<Cond> conds = new ArrayList<>(n);
            for (int i = 0; i < n; i++) conds.add(new Cond(buf.readUtf(256), buf.readVarInt(), Math.max(1, buf.readVarInt())));
            int type = buf.readVarInt();
            String world = buf.readUtf(256);
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble(), radius = buf.readDouble();
            String npc = buf.readUtf(64);
            return new StepS2C(session, step, run, chapter, description, ready, List.copyOf(conds), type, world, x, y, z, radius, npc);
        }), INVALID);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 같은 단계 안의 진행도·준비 상태 변경. 같은 session·step·run 의 단계에만 적용한다. */
    public record UpdateS2C(int session, String step, int run, boolean ready, int[] current) implements CustomPacketPayload {
        public static final Type<UpdateS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("chacademy", "quest_update"));
        /** 깨진·모르는 형식의 패킷 (핸들러가 무시). */
        public static final UpdateS2C INVALID = new UpdateS2C(0, "", 0, false, new int[0]);
        public static final StreamCodec<RegistryFriendlyByteBuf, UpdateS2C> CODEC = SafeCodec.failSafe("quest_update", StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.session()); buf.writeUtf(v.step()); buf.writeVarInt(v.run()); buf.writeBoolean(v.ready());
            buf.writeVarInt(v.current().length);
            for (int c : v.current()) buf.writeVarInt(c);
        }, buf -> {
            int session = buf.readVarInt();
            String step = buf.readUtf(64);
            int run = buf.readVarInt();
            boolean ready = buf.readBoolean();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_CONDS) throw new IllegalArgumentException("too many quest conditions: " + n);
            int[] cur = new int[n];
            for (int i = 0; i < n; i++) cur[i] = buf.readVarInt();
            return new UpdateS2C(session, step, run, ready, cur);
        }), INVALID);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static final int MAX_POINTS = 16;

    public record Point(String world, double x, double y, double z) {}

    /**
     * 목표 지점 여러 개 (2026-10-25, 예: 이론서 세 권): 아직 안 누른 상호작용 블록 위 자리. 같은 session 의 단계에만 적용, 개수 0 = 지우기.
     * 플러그인 QuestCodec.points 와 같은 형식.
     */
    public record PointsS2C(int session, List<Point> points) implements CustomPacketPayload {
        public static final Type<PointsS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("chacademy", "quest_points"));
        public static final PointsS2C INVALID = new PointsS2C(-1, List.of());
        public static final StreamCodec<RegistryFriendlyByteBuf, PointsS2C> CODEC = SafeCodec.failSafe("quest_points", StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.session()); buf.writeVarInt(v.points().size());
            for (Point p : v.points()) { buf.writeUtf(p.world()); buf.writeDouble(p.x()); buf.writeDouble(p.y()); buf.writeDouble(p.z()); }
        }, buf -> {
            int session = buf.readVarInt();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_POINTS) throw new IllegalArgumentException("too many quest points: " + n);
            List<Point> points = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String world = buf.readUtf(256);
                double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
                if (!QuestGuidePolicy.finite(x, y, z)) throw new IllegalArgumentException("invalid quest point");
                points.add(new Point(world, x, y, z));
            }
            return new PointsS2C(session, List.copyOf(points));
        }), INVALID);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * 길잡이 별: 이 session 의 단계 목표를 화살표로 가리키기 시작. session 0 = 끄기 (다시 시전).
     * 목표 10블록 안·단계 변경·다른 세계면 클라가 스스로 끈다.
     */
    public record GuideS2C(int session) implements CustomPacketPayload {
        public static final Type<GuideS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("chacademy", "quest_guide"));
        /** 깨진 패킷 (핸들러가 무시). */
        public static final GuideS2C INVALID = new GuideS2C(-1);
        public static final StreamCodec<RegistryFriendlyByteBuf, GuideS2C> CODEC = SafeCodec.failSafe("quest_guide", StreamCodec.of(
                (buf, v) -> buf.writeVarInt(v.session()), buf -> {
                    int session = buf.readVarInt();
                    // Zero is the opening-prep toggle-off frame; malformed negative sessions remain invalid.
                    if (session < 0) throw new IllegalArgumentException("invalid guide session");
                    return new GuideS2C(session);
                }), INVALID);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static final int FOLLOW_LIVE = 0, FOLLOW_UNLOADED = 1, FOLLOW_OTHER_WORLD = 2;

    /**
     * 길잡이 별이 따라가는 NPC 의 지금 위치 (2026-10-26 메모 #93, 플러그인 QuestCodec.guideTarget 과 같은 형식).
     * 움직이는 NPC 를 가리킬 때만 오고, NPC 가 1.5블록 넘게 움직였거나 상태가 바뀌었을 때 2초마다 온다. GuideS2C 보다 먼저 온다.
     * state: 보임 / 언로드(마지막 위치) / 다른 세계(화살표 끔). moving = 걷는 중 ("이동 중" 표시). entity = NPC 엔티티 UUID (없으면 빈 글).
     */
    public record GuideTargetS2C(int session, int state, boolean moving, String world, double x, double y, double z, String entity) implements CustomPacketPayload {
        public static final Type<GuideTargetS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("chacademy", "quest_guide_target"));
        public static final GuideTargetS2C INVALID = new GuideTargetS2C(-1, FOLLOW_OTHER_WORLD, false, "", 0, 0, 0, "");
        public static final StreamCodec<RegistryFriendlyByteBuf, GuideTargetS2C> CODEC = SafeCodec.failSafe("quest_guide_target", StreamCodec.of((buf, v) -> {
            buf.writeVarInt(v.session()); buf.writeVarInt(v.state()); buf.writeBoolean(v.moving()); buf.writeUtf(v.world());
            buf.writeDouble(v.x()); buf.writeDouble(v.y()); buf.writeDouble(v.z()); buf.writeUtf(v.entity());
        }, buf -> {
            int session = buf.readVarInt();
            int state = buf.readVarInt();
            boolean moving = buf.readBoolean();
            String world = buf.readUtf(256);
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            String entity = buf.readUtf(64);
            if (session <= 0 || state < FOLLOW_LIVE || state > FOLLOW_OTHER_WORLD || !QuestGuidePolicy.finite(x, y, z)) throw new IllegalArgumentException("invalid guide target");
            return new GuideTargetS2C(session, state, moving, world, x, y, z, entity);
        }), INVALID);

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
