package kr.chacademy.storyplugin;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 스토리 단계 HUD 패킷 (서버 → 클라만). 모드의 QuestPackets 와 같은 바이트 형식.
 * 문자열 = VarInt 길이 + UTF-8, 정수 = VarInt, 참거짓 = 1바이트, 실수 = 8바이트 빅엔디언 double.
 * <ul>
 *   <li>{@link #STEP}: 단계가 바뀔 때 / 접속·서버 이동 때 한 번. 단계 전체 (장 이름·설명·조건·호출 대상·현재 진행도).</li>
 *   <li>{@link #UPDATE}: 같은 단계 안에서 진행도나 준비 상태가 바뀔 때만. 같은 틱의 변경은 하나로 합쳐 보낸다.</li>
 * </ul>
 * session = 이 접속에서 보낸 STEP 번호 (UPDATE 는 같은 session 의 STEP 에만 적용), run = 단계에 들어간 횟수.
 * "준비 완료(ready)" = 조건을 다 채움 (포탈·징표 표시). 실제 단계 완료는 다음 STEP 이 와야 한다.
 */
final class QuestCodec {
    static final String STEP = "chacademy:quest_step";
    static final String UPDATE = "chacademy:quest_update";
    /** 길잡이 별: 지금 단계(session)의 목표를 머리 위 화살표로 가리키기 시작 (목표 10블록 안 / 단계가 바뀌면 클라가 스스로 끈다). */
    static final String GUIDE = "chacademy:quest_guide";
    /**
     * 목표 지점 여러 개 (2026-10-25, 예: 이론서 세 권): 아직 안 누른 "상호작용" 조건 블록들. 클라가 각 블록 위에 징표를 띄우고
     * 길잡이 별은 (단계 호출 목표가 없을 때) 가장 가까운 것을 가리킨다. 형식: session, 개수, (세계키, x, y, z)… 개수 0 = 지우기.
     */
    static final String POINTS = "chacademy:quest_points";
    static final int MAX_POINTS = 16;
    /**
     * 길잡이 별이 따라가는 NPC 의 지금 위치 (2026-10-26 메모 #93). 목표가 NPC 인 사람에게만, NPC 가 1.5블록 넘게 움직였거나 상태가 바뀌었을 때
     * 2초마다 보낸다. {@link #GUIDE} 보다 먼저 보낸다 (의뢰 NPC 목표는 이 패킷이 있어야 클라가 화살표를 켠다).
     * 형식: session, 상태({@link #FOLLOW_LIVE} 등), 이동 중(참거짓), 세계키, x, y, z, 엔티티 UUID(없으면 빈 글).
     */
    static final String GUIDE_TARGET = "chacademy:quest_guide_target";
    /** 보이는 NPC / 언로드(마지막 위치) / 다른 세계(마지막 위치 — 클라가 화살표를 끔). */
    static final int FOLLOW_LIVE = 0, FOLLOW_UNLOADED = 1, FOLLOW_OTHER_WORLD = 2;

    record Point(String world, double x, double y, double z) {}

    static final int TARGET_NONE = 0, TARGET_LOCATION = 1, TARGET_NPC = 2;

    record CondView(String label, int current, int required) {}

    record StepView(int session, String step, int run, String chapter, String description, boolean ready,
                    List<CondView> conds, int targetType, String world, double x, double y, double z, double radius,
                    String npcEntity) {}

    private QuestCodec() {}

    static byte[] step(StepView v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StoryCodec.writeVarInt(out, v.session());
        StoryCodec.writeString(out, v.step());
        StoryCodec.writeVarInt(out, v.run());
        StoryCodec.writeString(out, v.chapter());
        StoryCodec.writeString(out, v.description());
        writeBool(out, v.ready());
        StoryCodec.writeVarInt(out, v.conds().size());
        for (CondView c : v.conds()) {
            StoryCodec.writeString(out, c.label());
            StoryCodec.writeVarInt(out, Math.max(0, c.current()));
            StoryCodec.writeVarInt(out, Math.max(1, c.required()));
        }
        StoryCodec.writeVarInt(out, v.targetType());
        StoryCodec.writeString(out, v.world());
        writeDouble(out, v.x());
        writeDouble(out, v.y());
        writeDouble(out, v.z());
        writeDouble(out, v.radius());
        StoryCodec.writeString(out, v.npcEntity());
        return out.toByteArray();
    }

    static byte[] update(int session, String step, int run, boolean ready, int[] current) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StoryCodec.writeVarInt(out, session);
        StoryCodec.writeString(out, step);
        StoryCodec.writeVarInt(out, run);
        writeBool(out, ready);
        StoryCodec.writeVarInt(out, current.length);
        for (int c : current) StoryCodec.writeVarInt(out, Math.max(0, c));
        return out.toByteArray();
    }

    static byte[] points(int session, List<Point> points) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StoryCodec.writeVarInt(out, session);
        int n = Math.min(MAX_POINTS, points.size());
        StoryCodec.writeVarInt(out, n);
        for (int i = 0; i < n; i++) {
            Point p = points.get(i);
            StoryCodec.writeString(out, p.world());
            writeDouble(out, p.x());
            writeDouble(out, p.y());
            writeDouble(out, p.z());
        }
        return out.toByteArray();
    }

    static byte[] guide(int session) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StoryCodec.writeVarInt(out, session);
        return out.toByteArray();
    }

    static byte[] guideTarget(int session, int state, boolean moving, String world, double x, double y, double z, String entity) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StoryCodec.writeVarInt(out, session);
        StoryCodec.writeVarInt(out, state);
        writeBool(out, moving);
        StoryCodec.writeString(out, world == null ? "" : world);
        writeDouble(out, x);
        writeDouble(out, y);
        writeDouble(out, z);
        StoryCodec.writeString(out, entity == null ? "" : entity);
        return out.toByteArray();
    }

    private static void writeBool(ByteArrayOutputStream out, boolean b) { out.write(b ? 1 : 0); }

    private static void writeDouble(ByteArrayOutputStream out, double d) {
        long bits = Double.doubleToLongBits(d);
        for (int i = 7; i >= 0; i--) out.write((int) (bits >>> (i * 8)) & 0xFF);
    }
}
