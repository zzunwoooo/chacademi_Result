package kr.chacademi.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 월드에 그리는 것:
 * <ul>
 *   <li>문 홀로그램 (모든 유저, 닫힌 문만): 보라 반투명 면 + 블록 격자 + 밝은 테두리, 숨 쉬듯 밝아졌다 어두워짐</li>
 *   <li>설정 도구를 든 운영진: 고른 구역(하늘색), 칸 구역(회색), 방(파랑), 몹 구역(초록), 문(보라), 표시점(노랑) + 이름표</li>
 *   <li>기믹 설치 미리보기: 보는 블록 위에 차지할 칸 상자(주황) + 모델 이름·조작 안내</li>
 * </ul>
 */
final class WorldDraw {
    private WorldDraw() {}

    private static final MultiBufferSource.BufferSource OWN = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));

    static void render(WorldRenderContext ctx) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        String here = mc.level.dimension().location().toString();
        PoseStack pose = ctx.matrixStack() != null ? ctx.matrixStack() : new PoseStack();
        Vec3 cam = ctx.camera().getPosition();
        float t = (DungeonClient.now() % 3_600_000L) / 1000f;
        var camRot = ctx.camera().rotation();
        boolean any = false;

        // 문 홀로그램
        var doors = EditorState.doors;
        if (here.equals(EditorState.str(doors, "dim")) && doors.has("doors")) {
            for (var e : doors.getAsJsonArray("doors")) { door(pose, cam, e.getAsJsonObject(), t, false); any = true; }
        }
        // 설정 도구 화면
        var v = EditorState.view;
        if (here.equals(EditorState.str(v, "dim"))) {
            any = true;
            box(pose, cam, v.getAsJsonObject("region"), 0.6f, 0.6f, 0.65f, 0.7f, camRot, true);
            each(v, "rooms", o -> box(pose, cam, o, 0.35f, 0.6f, 1f, 1f, camRot, true));
            each(v, "zones", o -> box(pose, cam, o, 0.35f, 1f, 0.5f, 1f, camRot, true));
            each(v, "doors", o -> { door(pose, cam, o, t, true); label(pose, cam, camRot, center(o, true), str(o), 0xFFE7B6FF); });
            each(v, "marks", o -> mark(pose, cam, camRot, o));
            if (v.has("a")) mark(pose, cam, camRot, v.getAsJsonObject("a"));
            if (v.has("b")) mark(pose, cam, camRot, v.getAsJsonObject("b"));
            box(pose, cam, v.getAsJsonObject("sel"), 0.3f, 0.95f, 1f, 1f, camRot, true);
        }
        // 기믹 설치 미리보기
        if (EditorState.placeModel != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            any = true;
            var pos = hit.getBlockPos().relative(hit.getDirection());
            int[] f = forward(EditorState.placeFacing), r = new int[]{-f[1], f[0]};
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            int lw = -(EditorState.placeW - 1) / 2, ld = -(EditorState.placeD - 1) / 2;
            for (int i = lw; i < lw + EditorState.placeW; i++)
                for (int j = ld; j < ld + EditorState.placeD; j++) {
                    int x = pos.getX() + r[0] * i + f[0] * j, z = pos.getZ() + r[1] * i + f[1] * j;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
            var o = new JsonObject();
            o.addProperty("x1", minX); o.addProperty("y1", pos.getY()); o.addProperty("z1", minZ);
            o.addProperty("x2", maxX); o.addProperty("y2", pos.getY() + EditorState.placeH - 1); o.addProperty("z2", maxZ);
            o.addProperty("label", EditorState.placeName + " · " + FACING[EditorState.placeFacing]);
            box(pose, cam, o, 1f, 0.65f, 0.2f, 1f, camRot, true);
            // 앞쪽 표시 (방향)
            double fx = (minX + maxX + 1) / 2.0 + f[0] * (EditorState.placeD / 2.0 + 0.3), fz = (minZ + maxZ + 1) / 2.0 + f[1] * (EditorState.placeD / 2.0 + 0.3);
            label(pose, cam, camRot, new Vec3(fx, pos.getY() + 0.3, fz), "▶ 앞", 0xFFFFC066);
            label(pose, cam, camRot, new Vec3((minX + maxX + 1) / 2.0, pos.getY() + EditorState.placeH + 0.9, (minZ + maxZ + 1) / 2.0),
                    "우클릭 설치 · Shift+우클릭 회전 · 좌클릭 취소", 0xFFFFFFFF);
        }
        if (any) OWN.endBatch();
    }

    static final String[] FACING = {"남", "서", "북", "동"};
    static int[] forward(int facing) {
        return switch (Math.floorMod(facing, 4)) { case 0 -> new int[]{0, 1}; case 1 -> new int[]{-1, 0}; case 2 -> new int[]{0, -1}; default -> new int[]{1, 0}; };
    }

    private interface Each { void run(JsonObject o); }
    private static void each(JsonObject v, String key, Each fn) {
        if (!v.has(key) || !v.get(key).isJsonArray()) return;
        JsonArray a = v.getAsJsonArray(key);
        for (var e : a) if (e.isJsonObject()) fn.run(e.getAsJsonObject());
    }

    private static String str(JsonObject o) { return EditorState.str(o, "label"); }

    private static Vec3 center(JsonObject o, boolean top) {
        double x1 = o.get("x1").getAsDouble(), y1 = o.get("y1").getAsDouble(), z1 = o.get("z1").getAsDouble();
        double x2 = o.get("x2").getAsDouble() + 1, y2 = o.get("y2").getAsDouble() + 1, z2 = o.get("z2").getAsDouble() + 1;
        return new Vec3((x1 + x2) / 2, top ? y2 + 0.5 : (y1 + y2) / 2, (z1 + z2) / 2);
    }

    /** 상자 테두리 + (label 있으면) 위에 이름표. 멀면 안 그림. */
    private static void box(PoseStack pose, Vec3 cam, JsonObject o, float r, float g, float b, float a, org.joml.Quaternionf camRot, boolean withLabel) {
        if (o == null || !o.has("x1")) return;
        double x1 = o.get("x1").getAsDouble(), y1 = o.get("y1").getAsDouble(), z1 = o.get("z1").getAsDouble();
        double x2 = o.get("x2").getAsDouble() + 1, y2 = o.get("y2").getAsDouble() + 1, z2 = o.get("z2").getAsDouble() + 1;
        double cx = Mth.clamp(cam.x, x1, x2), cy = Mth.clamp(cam.y, y1, y2), cz = Mth.clamp(cam.z, z1, z2);
        if (cam.distanceToSqr(cx, cy, cz) > 160 * 160) return;
        var vc = OWN.getBuffer(RenderType.lines());
        ShapeRenderer.renderLineBox(pose, vc, x1 - cam.x, y1 - cam.y, z1 - cam.z, x2 - cam.x, y2 - cam.y, z2 - cam.z, r, g, b, a);
        if (withLabel && !str(o).isBlank()) label(pose, cam, camRot, center(o, true), str(o), Art.argb(1f, r * 0.5f + 0.5f, g * 0.5f + 0.5f, b * 0.5f + 0.5f));
    }

    /** 문: 반투명 보라 면(앞뒤 모두) + 블록 격자 선. editing 이면 조금 옅게 (설정 중 안이 보이게). */
    private static void door(PoseStack pose, Vec3 cam, JsonObject o, float t, boolean editing) {
        if (o == null || !o.has("x1")) return;
        float x1 = (float) (o.get("x1").getAsDouble() - cam.x), y1 = (float) (o.get("y1").getAsDouble() - cam.y), z1 = (float) (o.get("z1").getAsDouble() - cam.z);
        float x2 = (float) (o.get("x2").getAsDouble() + 1 - cam.x), y2 = (float) (o.get("y2").getAsDouble() + 1 - cam.y), z2 = (float) (o.get("z2").getAsDouble() + 1 - cam.z);
        if (Math.min(Math.abs(x1), Math.abs(x2)) > 128 || Math.min(Math.abs(z1), Math.abs(z2)) > 128) return;
        var white = Art.white().orElse(null);
        if (white == null) return;
        float pulse = 0.5f + 0.5f * Mth.sin(t * 2.1f);
        int fill = Art.argb((editing ? 0.14f : 0.22f) + 0.08f * pulse, 0.62f, 0.32f, 1f);
        var vc = OWN.getBuffer(Art.worldLayer(white, false));
        var last = pose.last();
        float e = 0.002f;
        // 여섯 면 (안쪽으로 살짝 들여 깜빡임 방지)
        quad(vc, last, x1 + e, y1, z1 + e, x2 - e, y1, z1 + e, x2 - e, y2, z1 + e, x1 + e, y2, z1 + e, fill);
        quad(vc, last, x1 + e, y1, z2 - e, x2 - e, y1, z2 - e, x2 - e, y2, z2 - e, x1 + e, y2, z2 - e, fill);
        quad(vc, last, x1 + e, y1, z1 + e, x1 + e, y1, z2 - e, x1 + e, y2, z2 - e, x1 + e, y2, z1 + e, fill);
        quad(vc, last, x2 - e, y1, z1 + e, x2 - e, y1, z2 - e, x2 - e, y2, z2 - e, x2 - e, y2, z1 + e, fill);
        quad(vc, last, x1 + e, y2 - e, z1 + e, x2 - e, y2 - e, z1 + e, x2 - e, y2 - e, z2 - e, x1 + e, y2 - e, z2 - e, fill);
        // 격자: 한 블록마다 세로·가로 선 (홀로그램 느낌)
        var lines = OWN.getBuffer(RenderType.lines());
        int gridA = Art.argb(0.35f + 0.25f * pulse, 0.85f, 0.65f, 1f);
        for (float x = x1; x <= x2 + 1e-3; x += 1) { line(lines, last, x, y1, z1, x, y2, z1, gridA); line(lines, last, x, y1, z2, x, y2, z2, gridA); }
        for (float z = z1; z <= z2 + 1e-3; z += 1) { line(lines, last, x1, y1, z, x1, y2, z, gridA); line(lines, last, x2, y1, z, x2, y2, z, gridA); }
        for (float y = y1; y <= y2 + 1e-3; y += 1) { line(lines, last, x1, y, z1, x2, y, z1, gridA); line(lines, last, x1, y, z2, x2, y, z2, gridA); line(lines, last, x1, y, z1, x1, y, z2, gridA); line(lines, last, x2, y, z1, x2, y, z2, gridA); }
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose last, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int color) {
        vertex(vc, last, ax, ay, az, 0, 1, color); vertex(vc, last, bx, by, bz, 1, 1, color);
        vertex(vc, last, cx, cy, cz, 1, 0, color); vertex(vc, last, dx, dy, dz, 0, 0, color);
    }

    private static void line(VertexConsumer vc, PoseStack.Pose last, float ax, float ay, float az, float bx, float by, float bz, int color) {
        float nx = bx - ax, ny = by - ay, nz = bz - az, len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-4) return;
        nx /= len; ny /= len; nz /= len;
        vc.addVertex(last, ax, ay, az).setColor(color).setNormal(last, nx, ny, nz);
        vc.addVertex(last, bx, by, bz).setColor(color).setNormal(last, nx, ny, nz);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose last, float x, float y, float z, float u, float v, int color) {
        vc.addVertex(last, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(last, 0, 1, 0);
    }

    /** 표시점: 작은 노란 상자 + 이름표. */
    private static void mark(PoseStack pose, Vec3 cam, org.joml.Quaternionf camRot, JsonObject o) {
        if (o == null || !o.has("x")) return;
        double x = o.get("x").getAsDouble(), y = o.get("y").getAsDouble(), z = o.get("z").getAsDouble();
        if (cam.distanceToSqr(x, y, z) > 128 * 128) return;
        var vc = OWN.getBuffer(RenderType.lines());
        boolean corner = "1".equals(str(o)) || "2".equals(str(o));
        if (corner) ShapeRenderer.renderLineBox(pose, vc, x - cam.x - .02, y - cam.y - .02, z - cam.z - .02, x + 1.02 - cam.x, y + 1.02 - cam.y, z + 1.02 - cam.z, 0.3f, 0.95f, 1f, 1f);
        else ShapeRenderer.renderLineBox(pose, vc, x - .3 - cam.x, y - cam.y, z - .3 - cam.z, x + .3 - cam.x, y + 1.8 - cam.y, z + .3 - cam.z, 1f, 0.9f, 0.3f, 1f);
        label(pose, cam, camRot, corner ? new Vec3(x + .5, y + 1.5, z + .5) : new Vec3(x, y + 2.3, z), corner ? "모서리 " + str(o) : str(o), corner ? 0xFF9EF5FF : 0xFFFFE89A);
    }

    /** 나를 바라보는 이름표 (MagicCodex 글꼴, 검은 외곽선). */
    private static void label(PoseStack pose, Vec3 cam, org.joml.Quaternionf camRot, Vec3 at, String text, int color) {
        if (text == null || text.isBlank() || at.distanceToSqr(cam) > 64 * 64) return;
        var tex = Art.worldText(text).orElse(null);
        if (tex == null) return;
        float h = 0.28f, w = h * tex.width() / (float) tex.height();
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        pose.mulPose(camRot);
        var vc = OWN.getBuffer(Art.shaderPack() ? RenderType.entityTranslucentEmissive(tex.id()) : Art.worldLayer(tex, false));
        var last = pose.last();
        float o = h * 0.05f;
        int outline = 0xE00A0A0E;
        for (float[] d : new float[][]{{-o, 0}, {o, 0}, {0, -o}, {0, o}}) billboard(vc, last, w, h, d[0], d[1], outline);
        billboard(vc, last, w, h, 0, 0, color);
        pose.popPose();
    }

    private static void billboard(VertexConsumer vc, PoseStack.Pose last, float w, float h, float dx, float dy, int color) {
        float hw = w / 2, hh = h / 2;
        vertex(vc, last, dx - hw, dy - hh, 0, 0, 1, color);
        vertex(vc, last, dx + hw, dy - hh, 0, 1, 1, color);
        vertex(vc, last, dx + hw, dy + hh, 0, 1, 0, color);
        vertex(vc, last, dx - hw, dy + hh, 0, 0, 0, color);
    }
}
