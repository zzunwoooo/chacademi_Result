package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import school.magiccodex.client.SpellTargets.Mode;

/**
 * Planar holograms for aiming. Line geometry is never projected onto terrain.
 * Cost: one column lookup per covered block (area r=6 ~ 110, range ring <= 192 points); nothing when not aiming.
 */
public final class AimRenderer {
    static final Identifier AREA = Identifier.of("magiccodex", "textures/aim/area.png");
    static final Identifier FILL = Identifier.of("magiccodex", "textures/aim/fill.png");
    static final Identifier TARGET = Identifier.of("magiccodex", "textures/aim/target.png");
    static final Identifier BAND = Identifier.of("magiccodex", "textures/aim/band.png");
    /** Bloom pieces: soft halos drawn under the crisp lines (no post shader, so they cost nothing extra). */
    static final Identifier GLOW = Identifier.of("magiccodex", "textures/aim/glow.png");
    static final Identifier AREA_GLOW = Identifier.of("magiccodex", "textures/aim/area_glow.png");
    static final Identifier TARGET_GLOW = Identifier.of("magiccodex", "textures/aim/target_glow.png");
    static final Identifier RANGE_FILL = Identifier.of("magiccodex", "textures/aim/range_fill.png");
    static final Identifier LANE = Identifier.of("magiccodex", "textures/aim/lane.png");
    static final Identifier CHEVRON = Identifier.of("magiccodex", "textures/aim/chevron.png");
    /** One colour for every spell (element colours were dropped for a unified look). */
    public static final int COLOR = 0x86DFFF;
    private static final int INVALID = 0xFF6B6B;
    /** Decal lift above the surface so it never z-fights with the block face. */
    private static final double LIFT = .025;
    /** Layers further than this from the reference height are another floor (cave roof, ledge below): skipped. */
    private static final double MAX_STEP = 3.2;

    private AimRenderer() {}

    static void render(WorldRenderContext context) {
        AimView.capture(context);
        var c = MinecraftClient.getInstance();
        float tickDelta = context.tickCounter().getTickDelta(false);
        AimTargeting.update(c, tickDelta);
        var p = AimTargeting.preview();
        var spell = AimTargeting.spell();
        VertexConsumerProvider consumers = context.consumers();
        if (p == null || spell == null || consumers == null || context.matrixStack() == null || c.world == null || c.options.hudHidden) return;
        var matrices = context.matrixStack();
        Vec3d cam = context.camera().getPos();
        matrices.push();
        try {
            matrices.translate(-cam.x, -cam.y, -cam.z);
            Matrix4f m = matrices.peek().getPositionMatrix();
            int rgb = COLOR;
            int main = p.valid() ? rgb : INVALID;
            World w = c.world;
            float pulse = .5f + .5f * MathHelper.sin(Util.getMeasuringTimeMs() / 180f);
            var shape = p.shape();
            Vec3d feet = p.feet();
            double reach = AimMath.horizontalReach(shape.mode()==Mode.LINE||shape.mode()==Mode.CONE?shape.length():shape.range(), p.eye().y - feet.y);
            // Max range: translucent disc so the whole reachable area reads at a glance, then bloom + crisp ring.
            if (reach > .5) {
                decal(consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(RANGE_FILL)), m, w, feet, reach, argb(rgb, .42f));
                rangeRing(consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(GLOW)), m, w, feet, reach, .95, argb(rgb, .34f), Float.NaN);
                rangeRing(consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(BAND)), m, w, feet, reach, .2, argb(rgb, .95f), c.player.getYaw()+(Util.getMeasuringTimeMs()%36000)/100f);
            }
            switch (shape.mode()) {
                case AREA, POINT -> {
                    if (p.point() == null) break;
                    double r = Math.max(.6, shape.radius());
                    decal(consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(shape.mode() == Mode.POINT ? TARGET_GLOW : AREA_GLOW)), m, w, p.point(), r * 1.05, argb(main, .55f + .1f * pulse));
                    decal(consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(FILL)), m, w, p.point(), r, argb(main, .45f));
                    decal(consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(shape.mode() == Mode.POINT ? TARGET : AREA)), m, w, p.point(), r, argb(main, 1f));
                }
                case WALL -> {
                    if (p.point() == null) break;
                    wallHologram(consumers, m, p.point(), horizontal(p.point().subtract(feet)), shape.width(), shape.depth(), main, pulse);
                    decal(consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(TARGET)), m, w, p.point(), .7, argb(main, 1f));
                }
                case LINE -> lineHologram(consumers, m, p, shape, main, feet);
                case CONE -> {
                    Vec3d dir = horizontal(p.point().subtract(feet));
                    double half = Math.toRadians(shape.width() / 2), r = shape.length();
                    var band = consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(BAND));
                    strip(band, m, w, line(feet, dir.rotateY((float) half), r), feet.y, .16, argb(rgb, 1f), false);
                    strip(band, m, w, line(feet, dir.rotateY((float) -half), r), feet.y, .16, argb(rgb, 1f), false);
                    var coneBasis=AimPlane.frame(dir.x,0,dir.z);
                    int pieces=Math.max(8,(int)Math.ceil(shape.width()/4));
                    for(int i=0;i<pieces;i++){double a=-half+2*half*i/pieces,b=-half+2*half*(i+1)/pieces;var zero=new AimPlane.V(0,0,0);planeQuad(band,m,feet.add(0,LIFT,0),new AimPlane.V[]{zero,coneBasis.at(Math.cos(a)*r,Math.sin(a)*r),coneBasis.at(Math.cos(b)*r,Math.sin(b)*r),zero},argb(main,.16f));}
                    List<Vec3d> arc = new ArrayList<>();
                    int n = Math.max(8, (int) Math.round(shape.width() / 4));
                    for (int i = 0; i <= n; i++) arc.add(feet.add(dir.rotateY((float) (-half + 2 * half * i / n)).multiply(r)));
                    strip(band, m, w, arc, feet.y, .16, argb(rgb, 1f), false);
                }
                case UNIT -> {
                    var ring = consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(TARGET));
                    for (var e : p.candidates()) {
                        if (e == p.target()) continue;
                        ring(ring, m, w, e, e.getLerpedPos(tickDelta), ringRadius(e), argb(rgb, .42f));
                    }
                    if (p.target() != null) {
                        // Under the creature's feet, not at the aimed point on its hitbox (that is body height).
                        Vec3d at = p.target().getLerpedPos(tickDelta);
                        double r = ringRadius(p.target());
                        ring(consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(TARGET_GLOW)), m, w, p.target(), at, r * 1.08, argb(main, .5f + .15f * pulse));
                        ring(consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(TARGET)), m, w, p.target(), at, r, argb(main, 1f));
                    }
                }
            }
        } finally {
            matrices.pop();
        }
    }

    /** Kept for callers; every element now uses the single aim colour. */
    static int elementColor(CodexData.Category category) { return COLOR; }

    /**
     * Straight skill-shot hologram: starts at the caster's feet (slightly ahead), follows the aim direction to the
     * aimed point, translucent lane with bright edges, chevrons drifting forward and a brighter arrowhead at the end.
     */
    private static void lineHologram(VertexConsumerProvider consumers, Matrix4f m, AimTargeting.Preview p, SpellTargets.Shape shape, int color, Vec3d feet) {
        Vec3d flat = horizontal(p.point().subtract(feet));
        Vec3d start = feet.add(flat.multiply(.35)).add(0, LIFT + .02, 0);
        Vec3d delta = p.point().add(0, LIFT + .02, 0).subtract(start);
        double length = Math.clamp(delta.length(), .6, Math.max(.6, shape.length()));
        if (delta.lengthSquared() < 1e-6) delta = flat;
        var basis = AimPlane.frame(delta.x, delta.y, delta.z);
        double half = Math.max(.3, shape.width() / 2);
        // Lane body: fades in from the feet.
        var lane = consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(LANE));
        planeUV(lane, m, start, new AimPlane.V[]{basis.at(0, half), basis.at(0, -half), basis.at(length, -half), basis.at(length, half)},
                new float[][]{{0, 0}, {0, 1}, {1, 1}, {1, 0}}, new int[]{argb(color, .15f), argb(color, .15f), argb(color, .85f), argb(color, .85f)});
        // Soft bloom under the whole lane.
        var glow = consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(GLOW));
        double bloom = half * 1.6;
        planeUV(glow, m, start, new AimPlane.V[]{basis.at(0, bloom), basis.at(0, -bloom), basis.at(length, -bloom), basis.at(length, bloom)},
                new float[][]{{0, 0}, {0, 1}, {1, 1}, {1, 0}}, new int[]{argb(color, 0), argb(color, 0), argb(color, .28f), argb(color, .28f)});
        // Chevrons drifting toward the target.
        var chevron = consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(CHEVRON));
        double size = Math.clamp(half * 1.3, .45, 1.1), spacing = Math.max(1.1, size * 1.6);
        double drift = (Util.getMeasuringTimeMs() % 1000L) / 1000.0 * spacing;
        for (double d = .4 + drift; d < length - size * 1.2; d += spacing) {
            float a = (float) Math.min(1, Math.min(d / 1.2, (length - d) / 1.5));
            chevronQuad(chevron, m, start, basis, d, size, argb(color, .75f * a));
        }
        chevronQuad(chevron, m, start, basis, length - size * .55, size * 1.35, argb(color, 1f));
    }
    /**
     * Wall footprint: a lane (bright edges, translucent inside) across the aim with rounded ends that also fade out,
     * over a soft bloom of the same outline, so neither end is a flat cut.
     */
    private static void wallHologram(VertexConsumerProvider consumers, Matrix4f m, Vec3d center, Vec3d forward, double length, double depth, int color, float pulse) {
        Vec3d along = forward.rotateY((float) Math.PI / 2);
        Vec3d c = center.add(0, LIFT + .01, 0);
        double half = Math.max(.15, depth / 2), cap = Math.min(half, length / 2);
        var lane = consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(LANE));
        var glow = consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(GLOW));
        int n = Math.max(12, (int) Math.ceil(length / .15));
        for (int i = 0; i < n; i++) {
            double t0 = -length / 2 + length * i / n, t1 = -length / 2 + length * (i + 1) / n;
            double[] w0 = AimMath.capsuleEnd(length / 2 - Math.abs(t0), cap), w1 = AimMath.capsuleEnd(length / 2 - Math.abs(t1), cap);
            band(lane, m, c, along, forward, t0, t1, half * w0[0], half * w1[0], argb(color, .9f * (float) w0[1]), argb(color, .9f * (float) w1[1]));
            double g = 1.3 + .1 * pulse;
            band(glow, m, c, along, forward, t0, t1, (half * g + .2) * w0[0], (half * g + .2) * w1[0], argb(color, .3f * (float) w0[1]), argb(color, .3f * (float) w1[1]));
        }
    }
    /** One slice of a band: v runs across it (0 = one edge, 1 = the other), colours set per end of the slice. */
    private static void band(VertexConsumer vc, Matrix4f m, Vec3d c, Vec3d along, Vec3d across, double t0, double t1, double h0, double h1, int c0, int c1) {
        Vec3d a = c.add(along.multiply(t0)), b = c.add(along.multiply(t1));
        float y = (float) c.y;
        float[][] q = {
            {(float) (a.x + across.x * h0), y, (float) (a.z + across.z * h0), 0, 0},
            {(float) (a.x - across.x * h0), y, (float) (a.z - across.z * h0), 0, 1},
            {(float) (b.x - across.x * h1), y, (float) (b.z - across.z * h1), 1, 1},
            {(float) (b.x + across.x * h1), y, (float) (b.z + across.z * h1), 1, 0}};
        int[] colors = {c0, c0, c1, c1};
        float cross = (q[1][0] - q[0][0]) * (q[2][2] - q[0][2]) - (q[1][2] - q[0][2]) * (q[2][0] - q[0][0]);
        int[] order = cross <= 0 ? new int[]{0, 1, 2, 3} : new int[]{3, 2, 1, 0};
        for (int i : order)
            vc.vertex(m, q[i][0], q[i][1], q[i][2]).color(colors[i]).texture(q[i][3], q[i][4]).overlay(OverlayTexture.DEFAULT_UV)
              .light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(0, 1, 0);
    }
    private static void chevronQuad(VertexConsumer vc, Matrix4f m, Vec3d origin, AimPlane.Frame f, double at, double size, int color) {
        double h = size / 2;
        planeUV(vc, m, origin, new AimPlane.V[]{f.at(at - h, -h), f.at(at - h, h), f.at(at + h, h), f.at(at + h, -h)},
                new float[][]{{0, 0}, {0, 1}, {1, 1}, {1, 0}}, new int[]{color, color, color, color});
    }
    private static void planeUV(VertexConsumer vc, Matrix4f m, Vec3d origin, AimPlane.V[] q, float[][] uv, int[] colors) {
        for (int i = 0; i < 4; i++) {
            var p = q[i];
            vc.vertex(m, (float) (origin.x + p.x()), (float) (origin.y + p.y()), (float) (origin.z + p.z())).color(colors[i]).texture(uv[i][0], uv[i][1])
              .overlay(OverlayTexture.DEFAULT_UV).light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(0, 1, 0);
        }
    }

    private static int argb(int rgb, float alpha) { return (Math.round(Math.clamp(alpha, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF); }
    private static double ringRadius(net.minecraft.entity.LivingEntity e) { return Math.max(.55, Math.min(3, e.getWidth() * .8)); }
    private static Vec3d horizontal(Vec3d v) {
        Vec3d h = new Vec3d(v.x, 0, v.z);
        return h.lengthSquared() < 1e-6 ? new Vec3d(0, 0, 1) : h.normalize();
    }
    private static List<Vec3d> line(Vec3d from, Vec3d dir, double length) {
        int n = Math.max(1, (int) Math.ceil(length / .5));
        List<Vec3d> out = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) out.add(from.add(dir.multiply(length * i / n)));
        return out;
    }

    /** Target ring flat on the ground straight under a creature's feet (also under flying ones); at the feet only over a deep drop. */
    private static void ring(VertexConsumer vc, Matrix4f m, World w, net.minecraft.entity.LivingEntity e, Vec3d feet, double r, int color) {
        decal(vc, m, w, groundUnder(w, feet), r, color);
    }
    static Vec3d groundUnder(World w, Vec3d feet) {
        Double top = AimTargeting.Ground.top(w, feet.x, feet.y + .5, feet.z, 16);
        return top == null ? feet : new Vec3d(feet.x, top, feet.z);
    }

    /** Flat sprite of half-size r centred on (point.x, point.z), split per block column and laid on each column's top. */
    static void decal(VertexConsumer vc, Matrix4f m, World w, Vec3d point, double r, int color) {
        quadUp(vc,m,point.x-r,point.z-r,point.x+r,point.z+r,(float)(point.y+LIFT),0,0,1,1,color);
    }

    /** Glow ring that stays on the ground (stepped where the height changes), with four short ticks like the sprite. */
    static void rangeRing(VertexConsumer vc, Matrix4f m, World w, Vec3d center, double radius, double width, int color, float yaw) {
        if (radius <= .5) return;
        int n = AimMath.ringSegments(radius);
        List<Vec3d> ring = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            double a = Math.PI * 2 * i / n;
            ring.add(center.add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
        }
        strip(vc, m, w, ring, center.y, width, color, true);
        if (Float.isNaN(yaw)) return; // bloom pass: no ticks
        Vec3d facing = Vec3d.fromPolar(0, yaw);
        for (int k = 0; k < 4; k++) {
            Vec3d out = facing.rotateY((float) (Math.PI / 2 * k));
            Vec3d at = center.add(out.multiply(radius));
            strip(vc, m, w, List.of(at.subtract(out.multiply(.45)), at.add(out.multiply(.45))), center.y, width * .8, color, false);
        }
    }

    /**
     * Polyline ribbon of the given width on the ground. Heights come from each point's column; a jump of more than
     * 0.6 block is drawn as two flat halves (a step) instead of a slanted quad through the block.
     */
    static void strip(VertexConsumer vc, Matrix4f m, World w, List<Vec3d> points, double referenceY, double width, int color, boolean closed) {
        for(int i=0;i+1<points.size();i++){
            Vec3d a=points.get(i),b=points.get(i+1),tangent=new Vec3d(b.x-a.x,0,b.z-a.z);
            if(tangent.lengthSquared()<1e-8)continue;var side=new Vec3d(-tangent.z,0,tangent.x).normalize().multiply(width/2);
            ribbon(vc,m,a,b,side,(float)(referenceY+LIFT),(float)(referenceY+LIFT),color);
        }
    }

    private static void planeQuad(VertexConsumer vc,Matrix4f m,Vec3d origin,AimPlane.V[] q,int color){
        for(var p:q)vc.vertex(m,(float)(origin.x+p.x()),(float)(origin.y+p.y()),(float)(origin.z+p.z())).color(color).texture(.5f,.5f).overlay(OverlayTexture.DEFAULT_UV).light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(0,1,0);
    }
    private static void planeEdge(VertexConsumer vc,Matrix4f m,Vec3d origin,AimPlane.V a,AimPlane.V b,AimPlane.V right,double width,int color){var s=right.mul(width/2);planeQuad(vc,m,origin,new AimPlane.V[]{a.add(s),a.add(s.mul(-1)),b.add(s.mul(-1)),b.add(s)},color);}

    private static void ribbon(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, Vec3d side, float ya, float yb, int color) {
        // u runs along the band (uniform), v across it: 0 at one edge, 1 at the other.
        float[][] q = {
            {(float) (a.x + side.x), ya, (float) (a.z + side.z), 0, 0},
            {(float) (a.x - side.x), ya, (float) (a.z - side.z), 0, 1},
            {(float) (b.x - side.x), yb, (float) (b.z - side.z), 1, 1},
            {(float) (b.x + side.x), yb, (float) (b.z + side.z), 1, 0}};
        emit(vc, m, q, color);
    }

    private static void quadUp(VertexConsumer vc, Matrix4f m, double x0, double z0, double x1, double z1, float y, float u0, float v0, float u1, float v1, int color) {
        float[][] q = {
            {(float) x0, y, (float) z0, u0, v0},
            {(float) x0, y, (float) z1, u0, v1},
            {(float) x1, y, (float) z1, u1, v1},
            {(float) x1, y, (float) z0, u1, v0}};
        emit(vc, m, q, color);
    }

    /** Emits the quad counter-clockwise as seen from above (front face up), whatever order it was built in. */
    private static void emit(VertexConsumer vc, Matrix4f m, float[][] q, int color) {
        float cross = (q[1][0] - q[0][0]) * (q[2][2] - q[0][2]) - (q[1][2] - q[0][2]) * (q[2][0] - q[0][0]);
        int[] order = cross <= 0 ? new int[]{0, 1, 2, 3} : new int[]{3, 2, 1, 0};
        for (int i : order) {
            float[] v = q[i];
            vc.vertex(m, v[0], v[1], v[2]).color(color).texture(v[3], v[4]).overlay(OverlayTexture.DEFAULT_UV)
              .light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(0, 1, 0);
        }
    }
}
