package kr.chacademi.npcmodel.client;

import com.mojang.blaze3d.platform.GlStateManager;
import kr.chacademi.npcmodel.NpcModelClient;
import kr.chacademi.npcmodel.core.GlbReader;
import kr.chacademi.npcmodel.core.ModelData;
import kr.chacademi.npcmodel.core.ModelFormatException;
import kr.chacademi.npcmodel.core.ModelLimits;
import kr.chacademi.npcmodel.core.ModelRegistry;
import kr.chacademi.npcmodel.core.Proportion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 모델 목록·파일 읽기·GPU 자원. 같은 모델을 쓰는 NPC는 메쉬·텍스처·클립을 하나만 공유하고, 인스턴스는 자세만 따로 가진다.
 *
 * <ul>
 *   <li>파일 읽기·sha256 확인·파싱·텍스처 디코딩·BC7 인코딩: 전용 작업 스레드 (렌더 스레드 안 막음)</li>
 *   <li>GL 텍스처 만들기·지우기: 렌더 스레드에서만</li>
 *   <li>BC7 결과는 {@code npc-models/.cache/} 에 저장해 두 번째부터는 인코딩 없이 올린다</li>
 *   <li>쓰는 NPC가 없는 모델은 {@link #IDLE_UNLOAD_MS} 뒤 GPU에서 내린다</li>
 * </ul>
 */
public final class ModelManager {
    static final long IDLE_UNLOAD_MS = 120_000;
    private static final int BPTC = 0x8E8C;

    private final Path dir;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "chacademi-npc-model-loader");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private volatile ModelRegistry registry;
    private final Map<String, Handle> handles = new HashMap<>();
    private final Settings settings;
    /** BPTC(BC7) 지원 여부. 렌더 스레드에서 한 번 알아내고, 작업 스레드는 이 값으로 압축할지 정한다. */
    private volatile Boolean bptc;
    private int ticks;

    public ModelManager(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        this.settings = Settings.load(this.dir.resolve("client.properties"));
        this.registry = ModelRegistry.empty(this.dir, "아직 안 읽음");
    }

    public Settings settings() {
        return settings;
    }

    /** 접속할 때마다: models.json 다시 읽기. 해시가 같은 모델은 그대로 두고, 바뀐 것만 내린다. */
    public void reloadRegistry() {
        ModelRegistry r = ModelRegistry.load(dir);
        for (String d : r.diagnostics) {
            NpcModelClient.LOG.warn("[npc-models] {}", d);
        }
        NpcModelClient.LOG.info("[npc-models] {} 에서 모델 {}개 등록", dir, r.models.size());
        registry = r;
        handles.entrySet().removeIf(e -> {
            ModelRegistry.Entry now = r.models.get(e.getKey());
            Handle h = e.getValue();
            if (now == null || !now.equals(h.entry)) {
                h.discard();
                return true;
            }
            if (h.state == State.FAILED) {
                return true; // 실패한 모델은 다음 접속 때 다시 시도
            }
            return false;
        });
    }

    enum State { LOADING, READY, FAILED }

    final class Handle {
        final ModelRegistry.Entry entry;
        volatile State state = State.LOADING;
        LoadedModel model;
        long lastUsed = System.currentTimeMillis();
        boolean discarded;

        Handle(ModelRegistry.Entry entry) {
            this.entry = entry;
        }

        void discard() {
            discarded = true;
            if (model != null) {
                model.release();
                model = null;
            }
        }
    }

    /** 미리 읽기 요청 (이미 있으면 아무것도 안 함). */
    public void request(String id) {
        if (handles.containsKey(id)) {
            return;
        }
        ModelRegistry.Entry e = registry.models.get(id);
        if (e == null) {
            NpcModelClient.LOG.warn("[npc-models] 서버가 모델 '{}' 을 요청했지만 models.json 에 없음 → 원래 외형", id);
            Handle h = new Handle(null);
            h.state = State.FAILED;
            handles.put(id, h);
            return;
        }
        Handle h = new Handle(e);
        handles.put(id, h);
        CompletableFuture.supplyAsync(() -> prepare(e), worker).whenComplete((cpu, ex) -> Minecraft.getInstance().execute(() -> {
            if (h.discarded) {
                return;
            }
            if (ex != null) {
                Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                h.state = State.FAILED;
                NpcModelClient.LOG.warn("[npc-models] 모델 '{}' 읽기 실패 → 원래 외형: {}", id,
                        c instanceof ModelFormatException ? c.getMessage() : c.toString());
                return;
            }
            try {
                h.model = upload(cpu);
                h.state = State.READY;
                NpcModelClient.LOG.info("[npc-models] 모델 '{}' 준비됨: 삼각형 {}, 관절 {}, 클립 {}, LOD {}, 텍스처 {} ({})", id,
                        cpu.base.triangleCount, cpu.base.skins.length == 0 ? 0 : cpu.base.skins[0].joints.length,
                        cpu.clips.keySet(), cpu.lods.size(), cpu.textureCount(), h.model.textureMode);
                for (String d : cpu.diagnostics) {
                    NpcModelClient.LOG.info("[npc-models] '{}' 진단: {}", id, d);
                }
            } catch (RuntimeException up) {
                h.state = State.FAILED;
                NpcModelClient.LOG.warn("[npc-models] 모델 '{}' GPU 올리기 실패 → 원래 외형", id, up);
            }
        }));
    }

    /** 준비된 모델 (아니면 null, 아직 요청 안 했으면 요청). */
    public LoadedModel ready(String id) {
        Handle h = handles.get(id);
        if (h == null) {
            request(id);
            return null;
        }
        if (h.state != State.READY) {
            return null;
        }
        h.lastUsed = System.currentTimeMillis();
        return h.model;
    }

    /** 지금 쓰는 모델 목록 (조절 명령 포함). */
    public ModelRegistry registry() {
        return registry;
    }

    /**
     * 게임 안 조절: 이 모델의 비율·키를 바꾸고 다시 읽는다 (models.json 은 '저장' 전까지 그대로).
     * 텍스처는 BC7 캐시를 다시 써서 빠르다.
     */
    public boolean override(String id, Proportion.Factors p, float height) {
        ModelRegistry.Entry e = registry.models.get(id);
        if (e == null) {
            return false;
        }
        registry = registry.replace(e.with(p, height));
        Handle h = handles.remove(id);
        if (h != null) {
            h.discard();
        }
        return true;
    }

    /** 렌더 스레드에서 첫 틱에 GPU 기능 확인 (작업 스레드가 BC7 로 만들지 결정). */
    public void detectGpu() {
        if (bptc == null) {
            var caps = GL.getCapabilities();
            bptc = caps.OpenGL42 || caps.GL_ARB_texture_compression_bptc;
            if (!bptc) {
                NpcModelClient.LOG.info("[npc-models] GPU가 BC7(BPTC)을 지원하지 않음 → 텍스처를 압축하지 않고 올림");
            }
        }
    }

    /** 그리다 예외가 난 모델: 실패로 두고 GPU 에서 내림 (다음 접속 때 다시 시도). */
    public void markFailed(String id, Throwable t) {
        Handle h = handles.get(id);
        if (h != null && h.state != State.FAILED) {
            NpcModelClient.LOG.error("[npc-models] 모델 '{}' 그리기 실패 → 원래 외형으로 되돌림", id, t);
            h.discard();
            h.state = State.FAILED;
        }
    }

    public void tick() {
        if (++ticks % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        handles.values().removeIf(h -> {
            if (h.state == State.READY && h.model != null && h.model.refs == 0
                    && now - Math.max(h.lastUsed, h.model.lastReleased) > IDLE_UNLOAD_MS) {
                h.discard();
                return true;
            }
            return false;
        });
    }

    /** 접속 종료 뒤: 쓰는 NPC가 0이 됐으니 IDLE_UNLOAD_MS 뒤 내려간다 (곧 재접속하면 그대로 재사용). */
    public void scheduleIdleUnload() {
        long now = System.currentTimeMillis();
        for (Handle h : handles.values()) {
            h.lastUsed = now; // 지금부터 IDLE_UNLOAD_MS 동안 재접속하면 그대로 재사용
        }
    }

    // ================================================================ 작업 스레드: 파일 → CPU 데이터

    static final class CpuTexture {
        final int[] w, h;
        final byte[][] levels;
        final boolean bc7;

        CpuTexture(int[] w, int[] h, byte[][] levels, boolean bc7) {
            this.w = w;
            this.h = h;
            this.levels = levels;
            this.bc7 = bc7;
        }
    }

    static final class CpuModel {
        ModelRegistry.Entry entry;
        ModelData base;
        Map<String, ModelData.Clip> clips;
        List<ModelData> lods = new ArrayList<>();
        List<Float> lodFrom = new ArrayList<>();
        /** [lod+1][image] (0 = 기본 모델). */
        List<CpuTexture[]> textures = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        int textureCount() {
            return textures.stream().mapToInt(a -> a.length).sum();
        }
    }

    private CpuModel prepare(ModelRegistry.Entry e) {
        CpuModel cpu = new CpuModel();
        cpu.entry = e;
        cpu.base = Proportion.apply(parse(e.file()), e.proportion(), cpu.diagnostics);
        cpu.diagnostics.addAll(cpu.base.diagnostics);
        Map<String, ModelData.Clip> clips = new LinkedHashMap<>(cpu.base.clips);
        for (ModelRegistry.FileRef f : e.clipFiles()) {
            ModelData src = parse(f);
            String stem = f.name().substring(0, f.name().length() - 4); // "teacher_ellna.glb" → "teacher_ellna"
            for (ModelData.Clip c : src.clips.values()) {
                ModelData.Clip moved = ModelRegistry.retarget(c, src, cpu.base, e.boneMap(), cpu.diagnostics);
                // 항상 "<파일>:<클립>" 이름으로 쓸 수 있고, 모델 자기 클립과 이름이 겹치지 않으면 원래 이름으로도 (자기 클립 우선)
                clips.put(stem + ":" + c.name, new ModelData.Clip(stem + ":" + c.name, moved.duration, moved.channels));
                clips.putIfAbsent(c.name, moved);
            }
        }
        for (String need : new String[]{e.idleClip(), e.walkClip(), e.talkClip(), e.greetClip()}) {
            if (need != null && !clips.containsKey(need)) {
                cpu.diagnostics.add("models.json 이 가리키는 클립 '" + need + "' 이 파일에 없음 → 대체 동작 사용 (있는 클립: " + clips.keySet() + ")");
            }
        }
        cpu.clips = clips;
        cpu.textures.add(textures(cpu.base, e.file().sha256(), 0));
        int li = 1;
        for (ModelRegistry.Lod l : e.lods()) {
            ModelData m = Proportion.apply(parse(l.file()), e.proportion(), cpu.diagnostics);
            cpu.lods.add(m);
            cpu.lodFrom.add(l.fromBlocks());
            cpu.textures.add(textures(m, l.file().sha256(), li++));
        }
        return cpu;
    }

    private ModelData parse(ModelRegistry.FileRef f) {
        try {
            if (!Files.isRegularFile(f.path(), LinkOption.NOFOLLOW_LINKS)) {
                throw new ModelFormatException("파일 없음: " + f.path().getFileName());
            }
            long size = Files.size(f.path());
            if (size > ModelLimits.DEFAULT.maxFileBytes) {
                throw new ModelFormatException("파일이 너무 큼: " + size);
            }
            byte[] data = Files.readAllBytes(f.path());
            String sha = sha256(data);
            if (!sha.equals(f.sha256())) {
                throw new ModelFormatException(f.name() + " 해시 불일치 (손상 또는 다른 버전) — models.json " + f.sha256().substring(0, 12)
                        + "…, 파일 " + sha.substring(0, 12) + "…");
            }
            return GlbReader.read(data, ModelLimits.DEFAULT);
        } catch (IOException ex) {
            throw new ModelFormatException(f.name() + " 읽기 실패: " + ex.getMessage());
        }
    }

    private CpuTexture[] textures(ModelData m, String fileSha, int lod) {
        CpuTexture[] out = new CpuTexture[m.images.length + 1];
        for (int i = 0; i < m.images.length; i++) {
            out[i] = texture(m.images[i], fileSha + "-" + lod + "-" + i);
        }
        // 마지막 칸 = 텍스처 없는 재질용 흰색 4x4
        byte[] white = new byte[4 * 4 * 4];
        java.util.Arrays.fill(white, (byte) 0xFF);
        out[m.images.length] = new CpuTexture(new int[]{4}, new int[]{4}, new byte[][]{white}, false);
        return out;
    }

    private CpuTexture texture(ModelData.Image img, String cacheKey) {
        boolean wantBc7 = settings.bc7 && Boolean.TRUE.equals(bptc); // BC7 미지원 GPU: 압축했다 다시 푸는 낭비·멈춤 방지
        Path cache = dir.resolve(".cache").resolve(cacheKey + "-e1.bc7"); // e1 = 인코더 버전 (바뀌면 올림)
        if (wantBc7) {
            CpuTexture cached = readCache(cache);
            if (cached != null) {
                return cached;
            }
        }
        BufferedImage bi;
        try {
            bi = ImageIO.read(new ByteArrayInputStream(img.bytes));
        } catch (IOException ex) {
            throw new ModelFormatException("그림 디코딩 실패: " + ex.getMessage());
        }
        if (bi == null) {
            throw new ModelFormatException("그림 디코딩 실패 (" + img.mime + ")");
        }
        int w = bi.getWidth(), h = bi.getHeight();
        byte[] rgba = new byte[w * h * 4];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bi.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x], o = (y * w + x) * 4;
                rgba[o] = (byte) (p >> 16);
                rgba[o + 1] = (byte) (p >> 8);
                rgba[o + 2] = (byte) p;
                rgba[o + 3] = (byte) (p >>> 24);
            }
        }
        List<int[]> dims = new ArrayList<>();
        List<byte[]> levels = new ArrayList<>();
        byte[] cur = rgba;
        int cw = w, ch = h;
        boolean bc7 = wantBc7 && w % 4 == 0 && h % 4 == 0;
        while (true) {
            dims.add(new int[]{cw, ch});
            levels.add(cur);
            if (cw <= (bc7 ? 4 : 1) || ch <= (bc7 ? 4 : 1) || (bc7 && ((cw / 2) % 4 != 0 || (ch / 2) % 4 != 0))) {
                break;
            }
            cur = halve(cur, cw, ch);
            cw /= 2;
            ch /= 2;
        }
        int n = levels.size();
        int[] ws = new int[n], hs = new int[n];
        byte[][] data = new byte[n][];
        for (int k = 0; k < n; k++) {
            ws[k] = dims.get(k)[0];
            hs[k] = dims.get(k)[1];
            data[k] = bc7 ? Bc7Codec.encode(levels.get(k), ws[k], hs[k]) : levels.get(k);
        }
        CpuTexture t = new CpuTexture(ws, hs, data, bc7);
        if (bc7) {
            writeCache(cache, t);
        }
        return t;
    }

    /** 2x2 평균 축소 (가로·세로 홀수면 마지막 줄 버림). */
    static byte[] halve(byte[] src, int w, int h) {
        int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
        byte[] out = new byte[nw * nh * 4];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                for (int c = 0; c < 4; c++) {
                    int a = src[((2 * y) * w + 2 * x) * 4 + c] & 0xFF;
                    int b = src[((2 * y) * w + Math.min(w - 1, 2 * x + 1)) * 4 + c] & 0xFF;
                    int d = src[(Math.min(h - 1, 2 * y + 1) * w + 2 * x) * 4 + c] & 0xFF;
                    int e = src[(Math.min(h - 1, 2 * y + 1) * w + Math.min(w - 1, 2 * x + 1)) * 4 + c] & 0xFF;
                    out[(y * nw + x) * 4 + c] = (byte) ((a + b + d + e + 2) >> 2);
                }
            }
        }
        return out;
    }

    private static final int CACHE_MAGIC = 0x434E4237; // "CNB7"

    private static CpuTexture readCache(Path p) {
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(p))) {
            if (in.readInt() != CACHE_MAGIC || in.readInt() != 1) {
                return null;
            }
            int n = in.readInt();
            if (n < 1 || n > 16) {
                return null;
            }
            int[] w = new int[n], h = new int[n];
            byte[][] lv = new byte[n][];
            for (int k = 0; k < n; k++) {
                w[k] = in.readInt();
                h[k] = in.readInt();
                int len = in.readInt();
                if (w[k] <= 0 || h[k] <= 0 || w[k] > 8192 || h[k] > 8192 || len != (w[k] / 4) * (h[k] / 4) * 16) {
                    return null;
                }
                lv[k] = in.readNBytes(len);
                if (lv[k].length != len) {
                    return null;
                }
            }
            return new CpuTexture(w, h, lv, true);
        } catch (IOException ex) {
            return null;
        }
    }

    private static void writeCache(Path p, CpuTexture t) {
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            try (DataOutputStream o = new DataOutputStream(Files.newOutputStream(tmp))) {
                o.writeInt(CACHE_MAGIC);
                o.writeInt(1);
                o.writeInt(t.levels.length);
                for (int k = 0; k < t.levels.length; k++) {
                    o.writeInt(t.w[k]);
                    o.writeInt(t.h[k]);
                    o.writeInt(t.levels[k].length);
                    o.write(t.levels[k]);
                }
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            NpcModelClient.LOG.debug("[npc-models] BC7 캐시 저장 실패(다음에 다시 인코딩): {}", ex.toString());
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ================================================================ 렌더 스레드: GPU

    private LoadedModel upload(CpuModel cpu) {
        detectGpu();
        List<ResourceLocation[]> locs = new ArrayList<>();
        List<ResourceLocation> all = new ArrayList<>();
        String mode = "RGBA8";
        try {
            for (int lod = 0; lod < cpu.textures.size(); lod++) {
                CpuTexture[] ts = cpu.textures.get(lod);
                ResourceLocation[] arr = new ResourceLocation[ts.length];
                for (int i = 0; i < ts.length; i++) {
                    ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(NpcModelClient.MOD_ID,
                            "npc/" + cpu.entry.id() + "/" + lod + "/" + i + "_" + Integer.toHexString(System.identityHashCode(ts[i])));
                    all.add(rl); // 올리다 실패해도 등록된 텍스처를 풀 수 있게 먼저 기록
                    uploadTexture(rl, ts[i]);
                    arr[i] = rl;
                    if (ts[i].bc7 && bptc) {
                        mode = "BC7";
                    }
                }
                locs.add(arr);
            }
        } catch (RuntimeException ex) {
            for (ResourceLocation rl : all) {
                Minecraft.getInstance().getTextureManager().release(rl);
            }
            throw ex;
        }
        return new LoadedModel(cpu.entry, cpu.base, cpu.clips, cpu.lods, cpu.lodFrom, locs, mode);
    }

    private void uploadTexture(ResourceLocation rl, CpuTexture t) {
        DynamicTexture tex = new DynamicTexture(1, 1, false);
        Minecraft.getInstance().getTextureManager().register(rl, tex);
        tex.bind();
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        for (int lv = 0; lv < t.levels.length; lv++) {
            byte[] bytes = t.levels[lv];
            boolean compressed = t.bc7 && bptc;
            if (t.bc7 && !bptc) {
                bytes = Bc7Codec.decode(bytes, t.w[lv], t.h[lv]);
            }
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
            try {
                buf.put(bytes).flip();
                if (compressed) {
                    GL13.glCompressedTexImage2D(GL11.GL_TEXTURE_2D, lv, BPTC, t.w[lv], t.h[lv], 0, buf);
                } else {
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, lv, GL11.GL_RGBA8, t.w[lv], t.h[lv], 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
                }
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, t.levels.length - 1);
        // DynamicTexture 준비 단계가 MAX_LOD 를 0 으로 둬서, 안 풀면 밉맵이 안 쓰인다 (HudArt 와 같은 처리)
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0f);
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, t.levels.length - 1);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != t.w[0]) {
            throw new IllegalStateException("텍스처 업로드 실패 " + rl);
        }
    }

    // ================================================================ 공유 모델

    /** GPU에 올라간 공유 모델. 인스턴스가 refs 를 올리고 내린다. */
    public static final class LoadedModel {
        public final ModelRegistry.Entry entry;
        public final ModelData base;
        public final Map<String, ModelData.Clip> clips;
        public final List<ModelData> lods;
        public final List<Float> lodFrom;
        /** [lod][image] 텍스처, 마지막 칸 = 흰색. lod 0 = 기본 모델. */
        final List<ResourceLocation[]> textures;
        /** LOD 노드 → 기본 모델 노드 (이름으로 맞춤, 없으면 -1). */
        final List<int[]> lodNodeMap = new ArrayList<>();
        public final String textureMode;
        int refs;
        /** 마지막 인스턴스가 내려간 시각 (쓰는 NPC가 없어진 뒤부터 IDLE_UNLOAD_MS 를 센다). */
        long lastReleased;
        private boolean alive = true;

        LoadedModel(ModelRegistry.Entry entry, ModelData base, Map<String, ModelData.Clip> clips, List<ModelData> lods,
                    List<Float> lodFrom, List<ResourceLocation[]> textures, String textureMode) {
            this.entry = entry;
            this.base = base;
            this.clips = clips;
            this.lods = lods;
            this.lodFrom = lodFrom;
            this.textures = textures;
            this.textureMode = textureMode;
            for (ModelData l : lods) {
                int[] map = new int[l.nodes.length];
                for (int k = 0; k < map.length; k++) {
                    map[k] = base.nodeByName(l.nodes[k].name);
                }
                lodNodeMap.add(map);
            }
        }

        public boolean alive() {
            return alive;
        }

        /** 기본 자세 높이 (모델 단위, 발바닥 보정 전). */
        public float restHeight() {
            return base.restMaxY - base.restMinY;
        }

        void release() {
            if (!alive) {
                return;
            }
            alive = false;
            for (ResourceLocation[] arr : textures) {
                for (ResourceLocation rl : arr) {
                    NpcRenderTypes.forget(rl);
                    Minecraft.getInstance().getTextureManager().release(rl);
                }
            }
        }
    }

    // ================================================================ 설정 (선택: npc-models/client.properties)

    public static final class Settings {
        public final boolean bc7;
        public final float lodNear, lodMid, lodFar;
        public final float frameBudgetMs;

        Settings(boolean bc7, float lodNear, float lodMid, float lodFar, float frameBudgetMs) {
            this.bc7 = bc7;
            this.lodNear = lodNear;
            this.lodMid = lodMid;
            this.lodFar = lodFar;
            this.frameBudgetMs = frameBudgetMs;
        }

        static Settings load(Path p) {
            Properties pr = new Properties();
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                try (var in = Files.newBufferedReader(p)) {
                    pr.load(in);
                } catch (IOException ex) {
                    NpcModelClient.LOG.warn("[npc-models] client.properties 읽기 실패 → 기본값", ex);
                }
            }
            return new Settings(!"off".equalsIgnoreCase(pr.getProperty("textureCompression", "bc7")),
                    num(pr, "lodNear", 16), num(pr, "lodMid", 32), num(pr, "lodFar", 64), num(pr, "frameBudgetMs", 3));
        }

        private static float num(Properties pr, String k, float def) {
            try {
                float v = Float.parseFloat(pr.getProperty(k, String.valueOf(def)));
                return v > 0 && v < 1000 ? v : def;
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }
}
