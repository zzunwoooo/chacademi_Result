package kr.kkaegul.client.casino.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 화면 픽셀 1:1 렌더 캐시.
 * <p>
 * 그림·글자를 "지금 화면에 실제로 그려질 픽셀 크기"로 미리 만들어(고품질 리샘플) 텍스처로 올리고,
 * 화면 픽셀 경계에 딱 맞춰 그린다. → 마인크래프트 GUI 배율·해상도와 상관없이 원본 PNG처럼 선명.
 * <p>
 * 큰 그림은 백그라운드 스레드에서 만들고, 준비되기 전 한두 프레임은 기존 방식으로 그린다.
 * 오래 안 쓰인 텍스처는 자동으로 지운다.
 */
public final class HqCache {

    /** 픽셀 배열 생성기 (가로 pw, 세로 ph, ARGB) */
    public interface Gen {
        int[] make(int pw, int ph);
    }

    private static final class Entry {
        final String key;
        final int pw, ph;
        volatile int[] pixels;      // 백그라운드에서 완성
        volatile boolean failed;
        Identifier id;               // 업로드 후
        long lastUsed;

        Entry(String key, int pw, int ph) {
            this.key = key;
            this.pw = pw;
            this.ph = ph;
        }
    }

    /** 끄면 예전 방식으로만 그림 (문제 생길 때 비상용) */
    public static boolean enabled = true;
    /** 테스트용: 백그라운드 없이 바로 만들기 */
    public static boolean sync;

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Elsea-HqCache");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final long MAX_BYTES = 320L * 1024 * 1024;
    private static final long IDLE_MS = 30_000;
    private static long bytes;
    private static long lastSweep;
    /** 한 프레임에 GPU로 올리는 최대 개수 (끊김 방지) */
    private static final int UPLOADS_PER_FRAME = 24;
    private static int uploadsThisFrame;
    private static long frameMark;

    private HqCache() {
    }

    // ------------------------------------------------------------------ 좌표

    /** 현재 행렬이 배율 1(이동만)인지. 확대·축소 애니메이션 중이면 false */
    public static boolean unitMatrix(DrawContext ctx) {
        Matrix4f pm = ctx.getMatrices().peek().getPositionMatrix();
        return Math.abs(pm.m00() - 1f) < 1e-4f && Math.abs(pm.m11() - 1f) < 1e-4f
                && Math.abs(pm.m01()) < 1e-5f && Math.abs(pm.m10()) < 1e-5f;
    }

    public static double gui() {
        return MinecraftClient.getInstance().getWindow().getScaleFactor();
    }

    /**
     * (x, y, w, h) GUI 영역을 화면 픽셀에 맞춰 그린다.
     *
     * @return 그렸으면 true. false면 호출한 쪽이 예전 방식으로 그려야 함
     */
    public static boolean drawCrisp(DrawContext ctx, String key, float x, float y, float w, float h, boolean async, Gen gen) {
        if (!enabled || w <= 0 || h <= 0 || !unitMatrix(ctx)) return false;
        MatrixStack m = ctx.getMatrices();
        Matrix4f pm = m.peek().getPositionMatrix();
        double gui = gui();
        double tx = pm.m30() + x, ty = pm.m31() + y;
        long px0 = Math.round(tx * gui), py0 = Math.round(ty * gui);
        long px1 = Math.round((tx + w) * gui), py1 = Math.round((ty + h) * gui);
        int pw = (int) (px1 - px0), ph = (int) (py1 - py0);
        if (pw <= 0 || ph <= 0) return true;
        if ((long) pw * ph > 12_000_000L) return false;
        Identifier id = obtain(key + "@" + pw + "x" + ph, pw, ph, async, gen);
        if (id == null) return false;
        m.push();
        m.translate((float) (x + (px0 / gui - tx)), (float) (y + (py0 / gui - ty)), 0);
        m.scale((float) (1 / gui), (float) (1 / gui), 1f);
        ctx.drawTexture(RenderLayer::getGuiTextured, id, 0, 0, 0, 0, pw, ph, pw, ph, pw, ph);
        m.pop();
        return true;
    }

    /** 미리 만든 텍스처 (준비 안 됐으면 null) */
    public static Identifier obtain(String key, int pw, int ph, boolean async, Gen gen) {
        if (sync) async = false;
        long now = System.currentTimeMillis();
        sweep(now);
        Entry e = CACHE.get(key);
        if (e == null) {
            Entry ne = new Entry(key, pw, ph);
            e = CACHE.putIfAbsent(key, ne);
            if (e == null) {
                e = ne;
                final Entry job = ne;
                Runnable r = () -> {
                    try {
                        job.pixels = gen.make(pw, ph);
                        if (job.pixels == null) job.failed = true;
                    } catch (Throwable t) {
                        job.failed = true;
                    }
                };
                if (async) POOL.execute(r);
                else r.run();
            }
        }
        e.lastUsed = now;
        if (e.id != null) return e.id;
        if (e.failed || e.pixels == null) return null;
        // GPU 업로드 (렌더 스레드)
        if (now != frameMark) {
            frameMark = now;
            uploadsThisFrame = 0;
        }
        if (async && uploadsThisFrame >= UPLOADS_PER_FRAME) return null;
        uploadsThisFrame++;
        try {
            e.id = upload(e.pixels, pw, ph);
            bytes += (long) pw * ph * 4;
            e.pixels = null;
        } catch (Throwable t) {
            e.failed = true;
            return null;
        }
        return e.id;
    }

    private static Identifier upload(int[] px, int pw, int ph) {
        NativeImage img = new NativeImage(pw, ph, false);
        for (int y = 0; y < ph; y++) {
            int row = y * pw;
            for (int x = 0; x < pw; x++) img.setColorArgb(x, y, px[row + x]);
        }
        NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
        tex.setFilter(true, false);
        Identifier id = Identifier.of("kkaegul", "casino_dyn/hq_" + SEQ.incrementAndGet());
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
        return id;
    }

    /** 오래 안 쓴 것 · 너무 많으면 오래된 것부터 지우기 */
    private static void sweep(long now) {
        if (now - lastSweep < 2000) return;
        lastSweep = now;
        List<Entry> list = new ArrayList<>(CACHE.values());
        list.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
        Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            boolean idle = now - e.lastUsed > IDLE_MS;
            if (!idle && bytes <= MAX_BYTES) break;
            if (e.id == null && !e.failed && e.pixels == null && !idle) continue; // 만드는 중
            remove(e);
        }
    }

    private static void remove(Entry e) {
        CACHE.remove(e.key);
        if (e.id != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(e.id);
            bytes -= (long) e.pw * e.ph * 4;
            e.id = null;
        }
    }

    /** 화면 배율이 바뀌는 등 전부 버릴 때 */
    public static void clear() {
        for (Entry e : List.copyOf(CACHE.values())) remove(e);
        bytes = 0;
    }
}
