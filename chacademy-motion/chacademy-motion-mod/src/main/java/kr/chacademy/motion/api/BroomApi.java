package kr.chacademy.motion.api;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.math.Axis;
import kr.chacademy.motion.broom.BroomModels;
import kr.chacademy.motion.broom.model.BroomModel;
import kr.chacademy.motion.net.BroomPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.List;

/**
 * 다른 모드(MagicCodex 장비창)가 빗자루를 보여주고 장착할 때 쓰는 공개 API.
 *
 * <p>컴파일 의존 없이 리플렉션으로 부를 수 있도록 매개변수·반환값은 String/float/배열/Object 만 쓴다.
 * 모든 메서드는 클라이언트(렌더) 스레드에서 부른다.
 *
 * <pre>
 * Class&lt;?&gt; api = Class.forName("kr.chacademy.motion.api.BroomApi");
 * String[] owned = (String[]) api.getMethod("owned").invoke(null);
 * api.getMethod("renderGui", Object.class, String.class, float.class, float.class, float.class, float.class, float.class, float.class)
 *    .invoke(null, drawContext, "broom_7", cx, cy, 220f, 90f, 12f, spin);
 * </pre>
 */
public final class BroomApi {
    private BroomApi() {}

    // ------------------------------------------------------------------ 비행 궤적 덮어쓰기 (아티팩트 등)

    /**
     * 이 플레이어의 비행 궤적을 broom_trails.json 의 궤적 키로 바꾼다. 비행 중이면 옛 궤적은 사라지고(꼬리 남김) 새 궤적이 켜진다.
     * <b>이 PC 화면에만</b> 적용된다. 다른 사람 화면에도 보이게 하려면 서버가 각 클라이언트에 알려 주는 별도 연동이 필요하다.
     * 접속 종료 때 모두 해제된다.
     *
     * @param playerUuid 플레이어 UUID 문자열
     * @param trailKey   broom_trails.json 의 effects 키 (예: "l_dragon", "u_sonic")
     * @return 없는 키·잘못된 UUID 면 false
     */
    public static boolean setTrailOverride(String playerUuid, String trailKey) {
        try {
            return kr.chacademy.motion.broom.trail.BroomTrails.setOverride(java.util.UUID.fromString(playerUuid), trailKey);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 덮어쓰기 해제 → 빗자루 기본 궤적으로 돌아감. */
    public static void clearTrailOverride(String playerUuid) {
        try {
            kr.chacademy.motion.broom.trail.BroomTrails.clearOverride(java.util.UUID.fromString(playerUuid));
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** 모든 덮어쓰기 해제. */
    public static void clearTrailOverrides() {
        kr.chacademy.motion.broom.trail.BroomTrails.clearOverrides();
    }

    // ------------------------------------------------------------------ 보유 목록 (서버 chacademy:broom_list)

    private static List<BroomPackets.Owned> owned = List.of();
    private static String equipped = "";
    private static float mastery;
    private static long version;

    /** 서버 목록 수신 (ChacademyMotionClient 가 부름). */
    public static void onList(BroomPackets.ListS2C list) {
        owned = list.brooms();
        equipped = list.equipped();
        mastery = list.mastery();
        version++;
    }

    public static void clear() {
        owned = List.of();
        equipped = "";
        mastery = 0;
        version++;
    }

    /** 서버에서 목록이 바뀔 때마다 1씩 커짐. 화면이 다시 그릴지 판단할 때. */
    public static long version() {
        return version;
    }

    /** 빗자루 비행 서버(ChacademyMotion 플러그인)와 연결돼 있는지. false 면 장비창의 빗자루 칸을 숨기면 된다. */
    public static boolean available() {
        return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(BroomPackets.RequestC2S.TYPE);
    }

    /** 가진 빗자루 ID (기본 → 전설 순). 권한 chacademy.broom.own.&lt;ID&gt;. */
    public static String[] owned() {
        return owned.stream().map(BroomPackets.Owned::id).toArray(String[]::new);
    }

    /** 장착 중인(비행 마법에 쓰일) 빗자루 ID. 없으면 빈 문자열 → 연습용 빗자루. */
    public static String equipped() {
        return equipped;
    }

    /** 비행 숙련도 0~1. */
    public static float mastery() {
        return mastery;
    }

    public static String name(String id) {
        var b = find(id);
        return b == null ? id : b.name();
    }

    /** 등급 표시 문구 (일반/희귀/유일/전설). */
    public static String grade(String id) {
        var b = find(id);
        return b == null ? "" : b.grade();
    }

    /** 게이지 이름. {@link #gauges} 와 같은 순서. */
    public static String[] gaugeLabels() {
        return new String[]{"최고 속도", "가속", "선회", "제동", "안정성"};
    }

    /**
     * 마우스 올렸을 때 보여줄 게이지 값 0~1 (5개, {@link #gaugeLabels} 순서).
     * 2026-10-16 30종 기획의 1~10단계를 수치에서 되돌려 계산해 단계/10 으로 준다 (UI 는 ×10 해서 칸 수로 쓴다).
     */
    public static float[] gauges(String id) {
        var b = find(id);
        if (b == null) return new float[5];
        return new float[]{
                level((b.maxSpeed() * 20 - 10.9f) * 9 / 22.1f),
                level((b.accel() - 0.025f) / 0.005f),
                level(b.turn() - 7f),
                level((b.brake() - 0.025f) / 0.005f),
                level((b.stability() - 0.6f) * 7.5f)};
    }

    /** (단계 − 1) 값 → 단계/10 (1~10 단계로 반올림). */
    static float level(float stepsAboveOne) {
        float l = Math.round(stepsAboveOne) + 1;
        return Math.max(1, Math.min(10, l)) / 10f;
    }

    /** 게이지 옆에 쓸 실제 값 문구 (예: "27 b/s"). */
    public static String[] gaugeTexts(String id) {
        var b = find(id);
        if (b == null) return new String[5];
        return new String[]{
                Math.round(b.maxSpeed() * 20) + " b/s",
                String.format("%.0f", b.accel() * 1000),
                String.format("%.1f°", b.turn()),
                String.format("%.1f%%", b.brake() * 100),
                String.format("%.2f", b.stability())};
    }

    /** 장착 요청. 서버가 확인한 뒤 목록을 다시 보내 준다 ({@link #version} 증가). */
    public static void equip(String id) {
        if (available()) ClientPlayNetworking.send(new BroomPackets.RequestC2S(BroomPackets.REQ_EQUIP, id));
    }

    /** 장비창을 열 때 한 번 불러 최신 보유 목록을 받는다 (권한이 바뀌었을 수 있으므로). */
    public static void refresh() {
        if (available()) ClientPlayNetworking.send(new BroomPackets.RequestC2S(BroomPackets.REQ_REFRESH, ""));
    }

    private static BroomPackets.Owned find(String id) {
        for (var b : owned) if (b.id().equals(id)) return b;
        return null;
    }

    // ------------------------------------------------------------------ 화면에 3D 모델 그리기

    /**
     * 빗자루 3D 모델을 GUI 에 그린다 (게임 속과 같은 bbmodel, 같은 텍스처).
     *
     * @param drawContext GuiGraphics (Yarn 이름으로는 DrawContext — 실행 중에는 같은 클래스)
     * @param cx,cy       화면(현재 행렬 기준) 중심
     * @param length      빗자루 전체 길이를 몇 픽셀로 그릴지
     * @param yaw         90 = 손잡이 왼쪽·솔 오른쪽 (가로 장착 칸). 세로 카드는 yaw 0 + pitch 90 = 손잡이 위·솔 아래
     * @param pitch       가로 칸: 12~18 (위에서 살짝 내려다봄). 세로 카드: 90
     * @param spin        손잡이 축으로 돌리기 (마우스 올렸을 때 천천히 돌리면 좋음)
     * @return 그렸으면 true (모델이 없으면 false)
     */
    public static boolean renderGui(Object drawContext, String id, float cx, float cy, float length, float yaw, float pitch, float spin) {
        if (!(drawContext instanceof GuiGraphics g)) return false;
        BroomModel model = BroomModels.get(id);
        if (model == null) return false;
        float[] b = model.bounds;
        float extent = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
        float s = length / Math.max(1f, extent);
        var tex = BroomModels.texture(model);
        var pose = g.pose();
        pose.pushPose();
        try {
        pose.translate(cx, cy, 200);
        // GUI 는 y 가 아래로 → y·z 를 함께 뒤집어 앞뒷면 방향(컬링)을 유지
        pose.scale(s, -s, -s);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.mulPose(Axis.ZP.rotationDegrees(spin));
        pose.translate(-(b[0] + b[3]) / 2f, -(b[1] + b[4]) / 2f, -(b[2] + b[5]) / 2f);
        Lighting.setupForEntityInInventory();
        var last = pose.last();
        g.drawSpecial(buffers -> {
            var vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
            for (var q : model.quads) {
                float[] p = q.pos(), uv = q.uv(), n = q.normal();
                for (int i = 0; i < 4; i++) {
                    vc.addVertex(last, p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
                            .setColor(255, 255, 255, 255)
                            .setUv(uv[i * 2], uv[i * 2 + 1])
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(LightTexture.FULL_BRIGHT)
                            .setNormal(last, n[0], n[1], n[2]);
                }
            }
        });
        g.flush();
        } finally {
            Lighting.setupFor3DItems();
            pose.popPose();
        }
        return true;
    }
}
