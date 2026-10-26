package kr.chacademy.story.quest;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.UUID;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 월드 표시 (본인 화면에만):
 * <ul>
 *   <li>목표 지점 포탈: 바닥 링(천천히 회전) + 안쪽 링(반대로 회전) + 별 4개가 차례로 반짝 + 링 둘레의 빛 띠가 위로 흘러 오르며 위쪽이 사라짐
 *       + 작은 빛 입자 + 위에 "퀘스트 목표 지점" 글자. 들어가면 밝아지며 퍼져 사라진다.</li>
 *   <li>블룸: 후처리 셰이더 없이, 흐린 빛 그림을 빛 더하기(additive)로 겹쳐 그린다 (바닥 빛·링 번짐·빛 띠·별·글자 뒤). 숨 쉬듯 천천히 밝아졌다 어두워진다.</li>
 *   <li>깜빡임 방지 (2026-10-22): 그림은 밉맵 + 선형 필터 (QuestArt.worldLayer). 예전 바닐라 엔티티 레이어는 최근접 필터라
 *       링의 가는 금색 선이 비스듬히 보일 때 깜빡였다. 바닥에서 0.04 띄움.</li>
 *   <li>NPC 징표: 말 걸어야 하는 NPC 머리 위 "!" (둥실거림).</li>
 * </ul>
 * 준비 완료(조건을 다 채움)일 때만 보인다. 모든 움직임은 클라이언트 시계.
 */
final class QuestWorld {
    private QuestWorld() {}

    private static final String RING = "portal_ring", RING_GLOW = "portal_ring_glow", GROUND_GLOW = "portal_glow", BAND = "portal_band",
            MARKER = "marker", GLINT = "glint", HALO = "halo", ARROW = "guide_arrow";
    /** 길잡이 별: 목표 수평 거리가 이 안이면 화살표를 끈다 (서버 QuestEngine.GUIDE_NEAR_DISTANCE 와 같게). */
    private static final double GUIDE_NEAR = QuestGuidePolicy.ARRIVAL;
    private static final String LABEL = "퀘스트 목표 지점";
    /** 이 모드 전용 버퍼 (그리는 순서대로 바로 그린다 → 겹치는 순서가 매번 같다). */
    private static final MultiBufferSource.BufferSource OWN = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));
    private static final double MAX_DIST_SQ = 96 * 96;
    /** 링 그림에서 링 반지름이 그림 반폭의 몇 배인지 (portal_ring.png 기준). */
    private static final float RING_IN_IMAGE = 0.66f, STAR_IN_IMAGE = 0.65f;
    private static final int SEGMENTS = 20;

    private static Entity npcCache;
    private static UUID npcCacheId;
    private static int npcSearch;

    /** 길잡이 별이 따라가는 NPC 의 화면 위치 (부드럽게 옮겨감) · "이동 중" 판단용 표본 (#93). */
    private static Vec3 followShown, moveSample;
    private static long followShownAt, moveSampleAt;
    private static boolean moveSeen;

    static void reset() { npcCache = null; npcCacheId = null; npcSearch = 0; followShown = null; moveSample = null; moveSeen = false; }

    private static boolean sameWorld(QuestClient.View v) {
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension().location().toString().equals(v.world);
    }

    // ---------------------------------------------------------------- 입자 (틱)

    static void tick(Minecraft mc) {
        var v = QuestClient.view;
        if (mc.level == null || mc.player == null || mc.isPaused() || !v.active() || !v.ready || v.targetType != QuestPackets.TARGET_LOCATION || !sameWorld(v)) return;
        if (mc.player.distanceToSqr(v.x, v.y, v.z) > 48 * 48) return;
        var rand = mc.level.random;
        if (mc.level.getGameTime() % 3 != 0) return;
        double r = Math.max(0.6, v.radius) * 0.85 * Math.sqrt(rand.nextDouble());
        double ang = rand.nextDouble() * Math.PI * 2;
        mc.level.addParticle(ParticleTypes.END_ROD, v.x + Math.cos(ang) * r, v.y + 0.1 + rand.nextDouble() * 0.3, v.z + Math.sin(ang) * r,
                0, 0.012 + rand.nextDouble() * 0.02, 0);
    }

    // ---------------------------------------------------------------- 그리기

    static void render(WorldRenderContext ctx) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        var v = QuestClient.view;
        long now = QuestClient.now();
        boolean portal = v.active() && v.ready && v.targetType == QuestPackets.TARGET_LOCATION && sameWorld(v);
        boolean marker = v.active() && v.ready && v.targetType == QuestPackets.TARGET_NPC && sameWorld(v);
        QuestClient.View gone = QuestClient.vanishing;
        float vanish = gone == null ? 1 : (now - QuestClient.vanishAt) / 800f;
        if (gone != null && vanish >= 1) { QuestClient.vanishing = null; gone = null; }
        boolean guide = QuestClient.guideSession != 0;
        boolean points = v.active() && !v.points.isEmpty();
        if (!portal && !marker && gone == null && !guide && !points) return;

        PoseStack pose = ctx.matrixStack() != null ? ctx.matrixStack() : new PoseStack();
        var camera = ctx.camera();
        Vec3 cam = camera.getPosition();
        float pt = ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        float t = (now % 3_600_000L) / 1000f;
        var camRot = camera.rotation();

        if (portal && cam.distanceToSqr(v.x, v.y, v.z) < MAX_DIST_SQ) {
            float appear = Mth.clamp((now - v.readyAt) / 500f, 0, 1);
            drawPortal(pose, camRot, v.x - cam.x, v.y - cam.y, v.z - cam.z, (float) v.radius, t, appear, 1f, 0f);
            double dist = Math.sqrt(cam.distanceToSqr(v.x, v.y, v.z));
            float labelAlpha = appear * Mth.clamp((float) (48 - dist) / 12f, 0, 1);
            drawLabel(mc, pose, camRot, v.x - cam.x, v.y - cam.y, v.z - cam.z, (float) v.radius, t, labelAlpha);
        }
        if (gone != null && sameWorld(gone)) {
            float k = vanish;
            float alpha = (1 - k) * (1 - k);
            drawPortal(pose, camRot, gone.x - cam.x, gone.y - cam.y, gone.z - cam.z, (float) gone.radius, t, alpha, 1 + 0.35f * k, 0.6f * (1 - k));
            drawLabel(mc, pose, camRot, gone.x - cam.x, gone.y - cam.y, gone.z - cam.z, (float) gone.radius, t, alpha * (1 - k));
        }
        if (marker) {
            Vec3 at = markerPos(mc, v, pt);
            if (at != null && at.distanceToSqr(cam) < 64 * 64) {
                float appear = Mth.clamp((now - v.readyAt) / 400f, 0, 1);
                float bob = Mth.sin(t * 2.4f) * 0.07f;
                float s = 0.46f * (0.96f + 0.04f * Mth.sin(t * 3.1f));
                billboard(pose, camRot, HALO, true, at.x - cam.x, at.y + bob - cam.y, at.z - cam.z, s * 2.2f, s * 2.2f, argb(appear * 0.35f, 0.55f, 0.85f, 1f), false);
                billboard(pose, camRot, MARKER, false, at.x - cam.x, at.y + bob - cam.y, at.z - cam.z, s, s, white(appear), false);
            }
        }
        if (points) {
            // 목표 지점 여러 개 (이론서 세 권 등): NPC 징표와 같은 그림을 각 블록 위에, 조금씩 엇갈려 둥실
            String here = mc.level.dimension().location().toString();
            int i = 0;
            for (QuestPackets.Point p : v.points) {
                i++;
                if (!QuestGuidePolicy.finite(p.x(), p.y(), p.z()) || !p.world().equals(here) || cam.distanceToSqr(p.x(), p.y(), p.z()) > 48 * 48) continue;
                float appear = Mth.clamp((now - v.shownAt) / 400f, 0, 1);
                float bob = Mth.sin(t * 2.4f + i * 1.3f) * 0.06f;
                float s = 0.34f * (0.96f + 0.04f * Mth.sin(t * 3.1f + i));
                double y = p.y() + 0.55 + bob;
                billboard(pose, camRot, HALO, true, p.x() - cam.x, y - cam.y, p.z() - cam.z, s * 2.2f, s * 2.2f, argb(appear * 0.35f, 0.55f, 0.85f, 1f), false);
                billboard(pose, camRot, MARKER, false, p.x() - cam.x, y - cam.y, p.z() - cam.z, s, s, white(appear), false);
            }
        }
        if (guide) drawGuide(mc, pose, camera, cam, v, now, pt, t);
        OWN.endBatch();
    }

    // ---------------------------------------------------------------- 길잡이 별 화살표 (2026-10-23 기능, 2026-10-25 이 브랜치에 다시 연결)

    /**
     * 목표 쪽을 가리키는 화살표. 3인칭은 내 머리 위, 1인칭은 눈앞 조금 아래 (머리 위는 1인칭에서 안 보임). 아래에 남은 거리(m).
     * 목표 수평 10블록 안 · 단계 변경 · 다른 세계 · 목표 없음 · 다시 시전 → 서서히 사라지고 끝.
     */
    private static void drawGuide(Minecraft mc, PoseStack pose, net.minecraft.client.Camera camera, Vec3 cam, QuestClient.View v, long now, float pt, float t) {
        QuestClient.Follow follow = QuestClient.follow != null && QuestClient.follow.session == QuestClient.guideSession ? QuestClient.follow : null;
        Vec3 nearestPoint = follow == null && v.targetType == QuestPackets.TARGET_NONE ? nearestPoint(mc, v, pt) : null;
        boolean valid = QuestClient.guideEndAt == 0
                && (QuestGuidePolicy.tracks(v.active(), v.session, QuestClient.guideSession, v.targetType, sameWorld(v))
                    || QuestGuidePolicy.accepts(v.active(), v.session, QuestClient.guideSession) && nearestPoint != null);
        Vec3 target = null;
        boolean moving = false;
        if (follow != null) {
            // 움직이는 NPC (#93): 보이면 엔티티 그대로, 멀어서 안 보이면 서버가 2초마다 보내는 위치. 둘 다 부드럽게 옮겨간다.
            boolean same = follow.state != QuestPackets.FOLLOW_OTHER_WORLD && mc.level.dimension().location().toString().equals(follow.world);
            valid = QuestClient.guideEndAt == 0 && same;
            Entity e = same && follow.entity != null ? findEntity(mc, follow.entity) : null;
            if (same) {
                Vec3 raw = e != null ? e.getPosition(pt).add(0, headHeight(e) + 0.75, 0) : new Vec3(follow.x, follow.y + 2.55, follow.z);
                target = smoothFollow(raw, now);
                moving = e != null ? entityMoving(e, now) : follow.moving && follow.state == QuestPackets.FOLLOW_LIVE;
            } else {
                target = followShown; // 다른 세계로 감: 마지막 자리에서 서서히 사라진다
            }
            if (target != null) {
                Vec3 me = mc.player.getPosition(pt);
                if (QuestGuidePolicy.reached(target.x - me.x, target.z - me.z)) valid = false;
            }
        } else if (v.active() && nearestPoint != null) {
            // 호출 목표가 없는 단계: 아직 안 누른 목표 지점 중 가장 가까운 곳 (10블록 안이면 다음 가까운 곳은 다시 시전해서)
            target = nearestPoint;
            Vec3 me = mc.player.getPosition(pt);
            double dx = target.x - me.x, dz = target.z - me.z;
            if (QuestGuidePolicy.reached(dx, dz)) valid = false;
        } else if (v.active() && v.targetType != QuestPackets.TARGET_NONE && sameWorld(v)) {
            Vec3 npc = v.targetType == QuestPackets.TARGET_NPC ? markerPos(mc, v, pt) : null;
            target = npc != null ? npc : new Vec3(v.x, v.y, v.z);
            Vec3 me = mc.player.getPosition(pt);
            double dx = target.x - me.x, dz = target.z - me.z;
            if (QuestGuidePolicy.reached(dx, dz)) valid = false;
        }
        if (target != null && !QuestGuidePolicy.finite(target.x, target.y, target.z)) { valid = false; target = null; }
        if (!valid && QuestClient.guideEndAt == 0) QuestClient.guideEndAt = now;
        float fadeOut = QuestClient.guideEndAt == 0 ? 1 : 1 - (now - QuestClient.guideEndAt) / 450f;
        if (fadeOut <= 0) { QuestClient.guideSession = 0; QuestClient.guideEndAt = 0; QuestClient.follow = null; followShown = null; moveSample = null; return; }
        if (target == null) return; // 사라지는 중인데 목표를 잃음: 그릴 방향이 없다
        float alpha = Mth.clamp((now - QuestClient.guideAt) / 300f, 0, 1) * fadeOut;

        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        Vec3 anchor;
        float size;
        if (firstPerson) {
            float yaw = camera.getYRot() * Mth.DEG_TO_RAD;
            Vec3 look = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
            anchor = cam.add(look.scale(1.5)).add(0, -0.48, 0);
            size = 0.34f;
        } else {
            Vec3 me = mc.player.getPosition(pt);
            anchor = me.add(0, mc.player.getBbHeight() + 0.7 + Mth.sin(t * 2.2f) * 0.05, 0);
            size = 0.6f;
        }
        Vec3 f = new Vec3(target.x - anchor.x, 0, target.z - anchor.z);
        if (f.lengthSqr() < 1e-6) return;
        f = f.normalize();
        // 판의 법선 = 위쪽 + 카메라 쪽 → 바닥과 비슷하게 누워 있으면서도 내 쪽으로 기울어 보인다
        Vec3 toCam = cam.subtract(anchor);
        toCam = toCam.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : toCam.normalize();
        Vec3 n = new Vec3(0, 1, 0).add(toCam.scale(0.9)).normalize();
        Vec3 fw = f.subtract(n.scale(f.dot(n)));
        if (fw.lengthSqr() < 1e-6) return;
        fw = fw.normalize();
        Vec3 right = fw.cross(n).normalize();
        float nudge = Mth.sin(t * 3.4f) * 0.06f * size;
        Vec3 c = anchor.add(fw.scale(nudge)).subtract(cam);
        float pulse = 0.85f + 0.15f * Mth.sin(t * 3.4f);

        // 뒤쪽 빛 → 화살표
        plane(pose, HALO, true, c, fw, right, size * 1.7f, size * 1.7f, argb(alpha * 0.45f * pulse, 0.55f, 0.85f, 1f));
        plane(pose, ARROW, false, c, fw, right, size, size, white(alpha));
        plane(pose, ARROW, true, c, fw, right, size, size, white(alpha * 0.25f * pulse));

        // 남은 거리
        Vec3 me = mc.player.getPosition(pt);
        long meters = Math.round(Math.sqrt((target.x - me.x) * (target.x - me.x) + (target.z - me.z) * (target.z - me.z)));
        var label = Component.literal(meters + "m").getVisualOrderText();
        Vec3 at = c.subtract(fw.scale(size * 0.62)).add(0, firstPerson ? -0.02 : -0.12, 0);
        pose.pushPose();
        pose.translate(at.x, at.y, at.z);
        pose.mulPose(camera.rotation());
        float k = firstPerson ? 0.0085f : 0.016f;
        pose.scale(k, -k, k);
        int textColor = argb(alpha, 1f, 0.95f, 0.82f), outline = argb(alpha * 0.9f, 0.04f, 0.05f, 0.08f);
        if ((textColor >>> 24) > 4) {
            mc.font.drawInBatch8xOutline(label, -mc.font.width(label) / 2f, -4, textColor, outline, pose.last().pose(), OWN, LightTexture.FULL_BRIGHT);
            if (moving) {
                // 목표 NPC 가 걷는 중 (#93): 거리 아래 작게, 같은 글씨
                var sub = Component.literal("이동 중").getVisualOrderText();
                pose.pushPose();
                pose.translate(0, 9, 0);
                pose.scale(0.8f, 0.8f, 0.8f);
                mc.font.drawInBatch8xOutline(sub, -mc.font.width(sub) / 2f, -4, textColor, outline, pose.last().pose(), OWN, LightTexture.FULL_BRIGHT);
                pose.popPose();
            }
        }
        pose.popPose();
    }

    /** 서버 위치가 2초마다 한 번씩 바뀌어도 화살표가 튀지 않게 따라간다 (멀리 순간이동하면 바로). */
    private static Vec3 smoothFollow(Vec3 raw, long now) {
        if (followShown == null || followShown.distanceToSqr(raw) > 48 * 48) followShown = raw;
        else {
            long dt = Math.max(0, Math.min(200, now - followShownAt));
            double k = 1 - Math.exp(-dt / 220.0);
            followShown = followShown.add(raw.subtract(followShown).scale(k));
        }
        followShownAt = now;
        return followShown;
    }

    /** 보이는 NPC 가 걷는 중인지: 0.5초마다 위치를 비교한다. */
    private static boolean entityMoving(Entity e, long now) {
        Vec3 p = e.position();
        if (moveSample == null) { moveSample = p; moveSampleAt = now; moveSeen = false; return false; }
        if (now - moveSampleAt >= 500) {
            double dx = p.x - moveSample.x, dz = p.z - moveSample.z;
            moveSeen = dx * dx + dz * dz > 0.15 * 0.15;
            moveSample = p;
            moveSampleAt = now;
        }
        return moveSeen;
    }

    /** 이 세계에 있는 목표 지점 중 가장 가까운 곳 (없으면 null). */
    private static Vec3 nearestPoint(Minecraft mc, QuestClient.View v, float pt) {
        if (v.points.isEmpty() || mc.level == null || mc.player == null) return null;
        String here = mc.level.dimension().location().toString();
        Vec3 me = mc.player.getPosition(pt), best = null;
        double bestD = Double.MAX_VALUE;
        for (QuestPackets.Point p : v.points) {
            if (!p.world().equals(here) || !QuestGuidePolicy.finite(p.x(), p.y(), p.z())) continue;
            double d = me.distanceToSqr(p.x(), p.y(), p.z());
            if (d < bestD) { bestD = d; best = new Vec3(p.x(), p.y(), p.z()); }
        }
        return best;
    }

    /** 중심 c(카메라 기준), 앞 방향 fw(그림 위쪽 = 화살표 끝), 오른쪽 right 로 놓인 사각형. */


    /** NPC 3D 외형 모드(chacademi-npc-model)가 그 NPC를 모델로 그리는 중이면 모델 꼭대기, 아니면 히트박스 높이. 모드가 없어도 동작. */
    private static java.lang.reflect.Method npcModelTop;
    private static boolean npcModelLooked;

    private static double headHeight(Entity e) {
        if (!npcModelLooked) {
            npcModelLooked = true;
            try {
                npcModelTop = Class.forName("kr.chacademi.npcmodel.NpcModelClient").getMethod("modelTopHeight", Entity.class);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                npcModelTop = null;
            }
        }
        if (npcModelTop != null) {
            try {
                double h = (double) npcModelTop.invoke(null, e);
                if (h >= 0) return h;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return e.getBbHeight();
    }

    // ---------------------------------------------------------------- 길잡이 별 화살표

    /**
     * 길잡이 별: 목표 쪽을 가리키는 화살표. 3인칭은 내 머리 위, 1인칭은 눈앞 조금 아래(머리 위는 1인칭에서 안 보임).
     * 화살표 판은 바닥과 나란히 두되 카메라 쪽으로 기울여 어느 시점에서도 읽히게 한다. 아래에 남은 거리(m).
     * 목표 수평 10블록 안 · 단계 변경 · 다른 세계 · 목표 없음 → 서서히 사라지고 끝.
     */


    /** 중심 c(카메라 기준), 앞 방향 fw(그림 위쪽 = 화살표 끝), 오른쪽 right 로 놓인 사각형. */
    private static void plane(PoseStack pose, String name, boolean additive, Vec3 c, Vec3 fw, Vec3 right, float w, float h, int color) {
        if ((color >>> 24) == 0 || (additive && guideShaderPack())) return;
        var tex = QuestArt.image(name).orElse(null);
        if (tex == null) return;
        VertexConsumer vc = OWN.getBuffer(guideShaderPack() ? net.minecraft.client.renderer.RenderType.entityTranslucentEmissive(tex.id()) : QuestArt.worldLayer(tex, additive));
        var last = pose.last();
        Vec3 hf = fw.scale(h / 2), hr = right.scale(w / 2);
        Vec3 n = right.cross(fw);
        Vec3 tl = c.add(hf).subtract(hr), tr = c.add(hf).add(hr), br = c.subtract(hf).add(hr), bl = c.subtract(hf).subtract(hr);
        guideVertex(vc, last, (float) tl.x, (float) tl.y, (float) tl.z, 0, 0, color, (float) n.x, (float) n.y, (float) n.z);
        guideVertex(vc, last, (float) bl.x, (float) bl.y, (float) bl.z, 0, 1, color, (float) n.x, (float) n.y, (float) n.z);
        guideVertex(vc, last, (float) br.x, (float) br.y, (float) br.z, 1, 1, color, (float) n.x, (float) n.y, (float) n.z);
        guideVertex(vc, last, (float) tr.x, (float) tr.y, (float) tr.z, 1, 0, color, (float) n.x, (float) n.y, (float) n.z);
    }

    private static java.lang.reflect.Method guideIrisInUse;
    private static Object guideIrisApi;
    private static boolean guideIrisChecked;
    private static boolean guideShaderPack() {
        if (!guideIrisChecked) {
            guideIrisChecked = true;
            try { var api = Class.forName("net.irisshaders.iris.api.v0.IrisApi"); guideIrisApi = api.getMethod("getInstance").invoke(null); guideIrisInUse = api.getMethod("isShaderPackInUse"); }
            catch (ReflectiveOperationException | LinkageError ignored) { guideIrisInUse = null; }
        }
        if (guideIrisInUse == null) return false;
        try { return Boolean.TRUE.equals(guideIrisInUse.invoke(guideIrisApi)); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }
    private static void guideVertex(VertexConsumer vc, PoseStack.Pose last, float x, float y, float z, float u, float v, int color, float nx, float ny, float nz) {
        vc.addVertex(last, x, y, z).setUv(u, v).setColor(color).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(nx, ny, nz);
    }

    private static Vec3 markerPos(Minecraft mc, QuestClient.View v, float pt) {
        Entity e = v.npc == null ? null : findEntity(mc, v.npc);
        if (e != null) return e.getPosition(pt).add(0, headHeight(e) + 0.75, 0);
        return new Vec3(v.x, v.y + 2.55, v.z);
    }

    /** 내 화면에 불러와진 NPC 엔티티 (없으면 null). 못 찾으면 20프레임에 한 번만 다시 찾는다. */
    private static Entity findEntity(Minecraft mc, UUID id) {
        if (npcCache == null || npcCache.isRemoved() || !id.equals(npcCacheId)) {
            if (!id.equals(npcCacheId)) npcSearch = 0;
            if (npcSearch-- <= 0) {
                npcSearch = 20;
                npcCache = null;
                npcCacheId = id;
                for (Entity e : mc.level.entitiesForRendering()) if (id.equals(e.getUUID())) { npcCache = e; break; }
            }
        }
        return npcCache != null && !npcCache.isRemoved() ? npcCache : null;
    }

    /** extraGlow: 들어갈 때 더 밝게 (0~1). */
    private static void drawPortal(PoseStack pose, org.joml.Quaternionf camRot,
                                   double x, double y, double z, float radius, float t, float alpha, float scale, float extraGlow) {
        if (alpha <= 0.01f) return;
        float r = Math.max(0.8f, radius) * scale;
        float half = r / RING_IN_IMAGE;
        float breath = 0.86f + 0.14f * Mth.sin(t * 1.7f);
        float boost = Math.min(1f, breath + extraGlow);
        // "우웅" — 블룸은 링보다 느리고 깊게 숨 쉰다
        float hum = 0.5f + 0.5f * Mth.sin(t * 1.25f);
        float glow = Math.min(1.4f, (0.75f + 0.35f * hum) + extraGlow * 1.5f);

        pose.pushPose();
        pose.translate(x, y, z);
        // 바닥 빛 (블룸): 링 안쪽이 은은하게 차오르고 링 둘레가 번진다
        pose.pushPose();
        pose.translate(0, 0.02, 0);
        flat(pose, GROUND_GLOW, true, half * 1.02f, argb(alpha * 0.55f * glow, 0.55f, 0.85f, 1f));
        pose.popPose();
        // 바깥 링: 천천히 시계 방향 (번짐을 같이 돌린다)
        pose.pushPose();
        pose.translate(0, 0.04, 0);
        pose.mulPose(Axis.YP.rotationDegrees(t * 12f));
        flat(pose, RING_GLOW, true, half * 1.03f, argb(alpha * 0.5f * glow, 1f, 1f, 1f));
        flat(pose, RING, false, half, white(alpha * boost));
        flat(pose, RING, true, half, white(alpha * 0.35f * glow));
        pose.popPose();
        // 안쪽 링: 조금 작게, 반대로 더 빠르게, 은은하게
        pose.pushPose();
        pose.translate(0, 0.05, 0);
        pose.mulPose(Axis.YP.rotationDegrees(-t * 20f + 45f));
        flat(pose, RING, false, half * 0.78f, white(alpha * 0.42f * boost));
        flat(pose, RING, true, half * 0.78f, white(alpha * 0.2f * glow));
        pose.popPose();
        // 빛 띠: 두 겹이 엇갈려 위로 흘러 오르며 나타났다 사라짐 → 끊기지 않고 계속 오르는 느낌. 더해서 그려 밝게.
        for (int k = 0; k < 2; k++) {
            float phase = ((t * 0.32f) + k * 0.5f) % 1f;
            float a = Mth.sin(phase * Mth.PI) * alpha;
            float rise = phase * 0.45f;
            pose.pushPose();
            pose.translate(0, 0.04 + rise, 0);
            pose.mulPose(Axis.YP.rotationDegrees(k * 37f + t * 6f * (k == 0 ? 1 : -1)));
            float h = 1.75f * (0.9f + 0.1f * scale);
            band(pose, false, r * 0.93f, h, a * 0.9f * boost);
            band(pose, true, r * 0.93f, h, a * 0.75f * glow);
            band(pose, true, r * 0.99f, h * 1.1f, a * 0.3f * glow);
            pose.popPose();
        }
        pose.popPose();
        // 별 4개: 바깥 링의 별 자리(링과 같이 돈다)에서 차례로 반짝 — 꺼지지 않고 은은하게 남아 있다가 부드럽게 커짐
        double turn = Math.toRadians(t * 12f);
        float d = half * STAR_IN_IMAGE;
        for (int i = 0; i < 4; i++) {
            float wave = 0.5f + 0.5f * Mth.sin(t * 2.2f - i * Mth.HALF_PI);
            float pulse = wave * wave * (3 - 2 * wave);
            double ang = i * Math.PI / 2 + turn;
            double sx = x + Math.sin(ang) * d, sz = z + Math.cos(ang) * d, sy = y + 0.16;
            float size = (0.2f + 0.16f * pulse) * scale;
            billboard(pose, camRot, HALO, true, sx, sy, sz, size * 2.6f, size * 2.6f, argb(alpha * (0.18f + 0.4f * pulse) * glow, 0.6f, 0.88f, 1f), false);
            billboard(pose, camRot, GLINT, true, sx, sy, sz, size, size, white(alpha * (0.25f + 0.75f * pulse)), false);
        }
    }

    /** 포탈 위 "퀘스트 목표 지점" (항상 나를 바라봄, 살짝 둥실). MagicCodex 폰트가 없으면 바닐라 글꼴. */
    private static void drawLabel(Minecraft mc, PoseStack pose, org.joml.Quaternionf camRot, double x, double y, double z, float radius, float t, float alpha) {
        if (alpha <= 0.02f) return;
        double ly = y + 2.1 + Math.min(0.5, Math.max(0, radius - 1) * 0.25) + Mth.sin(t * 1.6f) * 0.05;
        float hum = 0.5f + 0.5f * Mth.sin(t * 1.25f);
        var text = QuestArt.worldText(LABEL);
        float h = 0.3f;
        if (text.isPresent()) {
            var tex = text.get();
            float w = h * tex.width() / (float) tex.height();
            // 뒤쪽 은은한 빛 (블룸)
            billboardTex(pose, camRot, QuestArt.image(HALO).orElse(null), true, x, ly, z, w * 1.5f, h * 3.2f, argb(alpha * (0.22f + 0.12f * hum), 0.5f, 0.82f, 1f), false);
            // 검은 외곽선 → 채운 글자 (차카데미 글자 규칙)
            float o = h * 0.045f;
            int outline = argb(alpha * 0.85f, 0.04f, 0.04f, 0.06f);
            float[][] offs = {{-o, 0}, {o, 0}, {0, -o}, {0, o}, {-o * .7f, -o * .7f}, {o * .7f, -o * .7f}, {-o * .7f, o * .7f}, {o * .7f, o * .7f}};
            for (float[] off : offs) billboardTex(pose, camRot, tex, false, x, ly, z, w, h, outline, false, off[0], off[1]);
            billboardTex(pose, camRot, tex, false, x, ly, z, w, h, argb(alpha, 1f, 0.95f, 0.8f), false);
            billboardTex(pose, camRot, tex, true, x, ly, z, w, h, argb(alpha * 0.25f * hum, 0.6f, 0.88f, 1f), false);
        } else {
            pose.pushPose();
            pose.translate(x, ly, z);
            pose.mulPose(camRot);
            pose.scale(0.025f, -0.025f, 0.025f);
            var label = Component.literal(LABEL);
            float lx = -mc.font.width(label) / 2f;
            mc.font.drawInBatch(label, lx, -4, argb(alpha, 1f, 0.95f, 0.8f), true, pose.last().pose(), OWN,
                    net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
    }

    /** 바닥에 눕힌 정사각형 (양면이 한 번에 보인다 — 컬링 끔). */
    private static void flat(PoseStack pose, String name, boolean additive, float half, int color) {
        if (additive && QuestArt.shaderPack()) return;
        var tex = QuestArt.image(name).orElse(null);
        if (tex == null || (color >>> 24) == 0) return;
        VertexConsumer vc = OWN.getBuffer(guideShaderPack() ? net.minecraft.client.renderer.RenderType.entityTranslucentEmissive(tex.id()) : QuestArt.worldLayer(tex, additive));
        var last = pose.last();
        vertex(vc, last, -half, 0, -half, 0, 0, color, 0, 1, 0);
        vertex(vc, last, -half, 0, half, 0, 1, color, 0, 1, 0);
        vertex(vc, last, half, 0, half, 1, 1, color, 0, 1, 0);
        vertex(vc, last, half, 0, -half, 1, 0, color, 0, 1, 0);
    }

    /** 링 둘레의 원통. 아래는 진하고 위로 갈수록 사라진다. 둘레를 따라 그림이 두 번 반복된다. */
    private static void band(PoseStack pose, boolean additive, float radius, float height, float alpha) {
        if (alpha <= 0.01f || (additive && QuestArt.shaderPack())) return;
        var tex = QuestArt.image(BAND).orElse(null);
        if (tex == null) return;
        VertexConsumer vc = OWN.getBuffer(guideShaderPack() ? net.minecraft.client.renderer.RenderType.entityTranslucentEmissive(tex.id()) : QuestArt.worldLayer(tex, additive));
        var last = pose.last();
        int bottom = white(Math.min(1f, alpha)), top = white(0);
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = (float) (i * Math.PI * 2 / SEGMENTS), a1 = (float) ((i + 1) * Math.PI * 2 / SEGMENTS);
            float x0 = Mth.cos(a0) * radius, z0 = Mth.sin(a0) * radius, x1 = Mth.cos(a1) * radius, z1 = Mth.sin(a1) * radius;
            float u0 = (float) i / SEGMENTS * 2f, u1 = (float) (i + 1) / SEGMENTS * 2f;
            float nx = Mth.cos((a0 + a1) / 2), nz = Mth.sin((a0 + a1) / 2);
            vertex(vc, last, x0, 0, z0, u0, 1, bottom, nx, 0, nz);
            vertex(vc, last, x1, 0, z1, u1, 1, bottom, nx, 0, nz);
            vertex(vc, last, x1, height, z1, u1, 0, top, nx, 0, nz);
            vertex(vc, last, x0, height, z0, u0, 0, top, nx, 0, nz);
        }
    }

    private static void billboard(PoseStack pose, org.joml.Quaternionf camRot, String name, boolean additive,
                                  double x, double y, double z, float w, float h, int color, boolean mirror) {
        billboardTex(pose, camRot, QuestArt.image(name).orElse(null), additive, x, y, z, w, h, color, mirror);
    }

    private static void billboardTex(PoseStack pose, org.joml.Quaternionf camRot, QuestArt.Tex tex, boolean additive,
                                     double x, double y, double z, float w, float h, int color, boolean mirror) {
        billboardTex(pose, camRot, tex, additive, x, y, z, w, h, color, mirror, 0, 0);
    }

    /** 나를 바라보는 사각형. 화면 기준 오른쪽 +x, 위 +y (글자가 뒤집히지 않게). dx·dy = 화면 기준 밀기 (외곽선). */
    private static void billboardTex(PoseStack pose, org.joml.Quaternionf camRot, QuestArt.Tex tex, boolean additive,
                                     double x, double y, double z, float w, float h, int color, boolean mirror, float dx, float dy) {
        if (tex == null || (color >>> 24) == 0 || (additive && QuestArt.shaderPack())) return;
        pose.pushPose();
        pose.translate(x, y, z);
        pose.mulPose(camRot);
        VertexConsumer vc = OWN.getBuffer(guideShaderPack() ? net.minecraft.client.renderer.RenderType.entityTranslucentEmissive(tex.id()) : QuestArt.worldLayer(tex, additive));
        var last = pose.last();
        float hw = w / 2, hh = h / 2;
        float uL = mirror ? 1 : 0, uR = mirror ? 0 : 1;
        vertex(vc, last, dx - hw, dy - hh, 0, uL, 1, color, 0, 0, 1);
        vertex(vc, last, dx + hw, dy - hh, 0, uR, 1, color, 0, 0, 1);
        vertex(vc, last, dx + hw, dy + hh, 0, uR, 0, color, 0, 0, 1);
        vertex(vc, last, dx - hw, dy + hh, 0, uL, 0, color, 0, 0, 1);
        pose.popPose();
    }

    /** 두 레이어 모두에 맞게 쓴다: 직접 만든 레이어(위치·UV·색)는 나머지 값을 무시, 셰이더팩용 바닐라 발광 레이어는 오버레이·빛·법선까지 필요. */
    private static void vertex(VertexConsumer vc, PoseStack.Pose last, float x, float y, float z, float u, float v, int color, float nx, float ny, float nz) {
        vc.addVertex(last, x, y, z).setColor(color).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(last, nx, ny, nz);
    }

    private static int white(float alpha) { return argb(alpha, 1f, 1f, 1f); }

    private static int argb(float alpha, float r, float g, float b) {
        int a = Mth.clamp(Math.round(alpha * 255), 0, 255);
        return (a << 24) | (Mth.clamp(Math.round(r * 255), 0, 255) << 16) | (Mth.clamp(Math.round(g * 255), 0, 255) << 8) | Mth.clamp(Math.round(b * 255), 0, 255);
    }
}
