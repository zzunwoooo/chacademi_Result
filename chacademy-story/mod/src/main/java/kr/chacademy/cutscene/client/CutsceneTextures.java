package kr.chacademy.cutscene.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 컷신에 필요한 이미지들을 텍스처로 올리고, 다 쓰면 내린다.
 * <ul>
 *   <li>PNG 읽기·줄이기는 작업 스레드 ({@link #WORKER}) 에서 한다. 렌더 스레드는 {@link #pump} 로 다 읽힌 것을
 *       한 프레임에 몇 장씩만 GPU 에 올린다 (재생을 시작할 때 화면이 멈추지 않게).</li>
 *   <li>컷신 전체를 한꺼번에 올리지 않는다: 화면이 {@link #request} 로 "지금 장면 + 다음 장면" 만 달라고 하고
 *       {@link #retain} 으로 지나간 장면의 텍스처를 내린다.</li>
 * </ul>
 * request / pump / retain / get / close 는 렌더 스레드에서만 부른다.
 */
public final class CutsceneTextures implements AutoCloseable {
    /** 이보다 큰 이미지는 줄여서 올린다 (메모리와 전환 계산량 때문). */
    public static final int MAX_SIDE = 1600;
    /** 이미지 파일 하나의 크기 한도. */
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    /** 스토리 파일 읽기 전용 스레드 (yml, PNG, ogg, 잉크 지도 계산). */
    public static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chaca-story-loader");
        t.setDaemon(true);
        return t;
    });

    public record Tex(ResourceLocation id, int width, int height, NativeImage image) {
    }

    private record Decoded(String name, NativeImage image, String error) {
    }

    private final TextureManager manager = Minecraft.getInstance().getTextureManager();
    private final Map<String, Tex> byName = new HashMap<>();
    private final List<ResourceLocation> registered = new ArrayList<>();
    private final String prefix;
    private final Path folder;
    private int counter = 0;

    /** 읽는 중인 이름 (렌더 스레드에서만 본다). */
    private final Set<String> pending = new HashSet<>();
    /** 지금 필요한 이름. 여기에 없는데 다 읽힌 것은 올리지 않고 버린다. */
    private final Set<String> wanted = new HashSet<>();
    private final ConcurrentLinkedQueue<Decoded> decoded = new ConcurrentLinkedQueue<>();
    private volatile boolean closed = false;
    private String failure;

    /** 비동기 방식: 만든 뒤 request / pump 로 필요한 것만 올린다. */
    public CutsceneTextures(String key, Path folder) {
        this.prefix = "dyn/" + key + "/" + Long.toHexString(System.nanoTime()) + "/";
        this.folder = folder;
    }

    /** 폴더 안의 PNG 들을 이 스레드에서 바로 읽어 올린다 (예전 방식. 작은 그림 몇 장일 때만). */
    public CutsceneTextures(String key, Path folder, Collection<String> names) throws IOException {
        this(key, folder, names, false);
    }

    public CutsceneTextures(String key, Path folder, Collection<String> names, boolean missingOk) throws IOException {
        this(key, folder);
        try {
            for (String name : names) {
                if (byName.containsKey(name)) continue;
                Path file = folder.resolve(name);
                if (missingOk && !Files.isRegularFile(file)) continue;
                byName.put(name, register(decode(file, name)));
            }
        } catch (IOException | RuntimeException e) {
            close();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    /** PNG 를 읽고 너무 크면 줄인다. 아무 스레드에서나 (GPU 를 건드리지 않는다). */
    private static NativeImage decode(Path file, String name) throws IOException {
        if (!Files.isRegularFile(file)) throw new java.nio.file.NoSuchFileException(name, null, "이미지 없음");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new IOException("PNG만 쓸 수 있어요 (편집기에서 내보내면 자동 변환): " + name);
        }
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("이미지 파일이 너무 커요 (32MB 까지): " + name);
        NativeImage img;
        try (InputStream in = Files.newInputStream(file)) {
            img = NativeImage.read(in);
        }
        int w = img.getWidth(), h = img.getHeight();
        int longSide = Math.max(w, h);
        if (longSide > MAX_SIDE) {
            double s = (double) MAX_SIDE / longSide;
            int nw = Math.max(1, (int) Math.round(w * s)), nh = Math.max(1, (int) Math.round(h * s));
            NativeImage scaled = new NativeImage(nw, nh, false);
            try {
                img.resizeSubRectTo(0, 0, w, h, scaled);
            } catch (RuntimeException e) {
                scaled.close();
                img.close();
                throw new IOException("이미지를 줄이지 못했어요: " + name, e);
            }
            img.close();
            img = scaled;
        }
        return img;
    }

    /** 이 이름들이 곧 필요하다: 아직 없는 것을 작업 스레드에서 읽기 시작한다. */
    public void request(Collection<String> names) {
        if (closed || folder == null) return;
        for (String name : names) {
            wanted.add(name);
            if (byName.containsKey(name) || !pending.add(name)) continue;
            Path file = folder.resolve(name);
            WORKER.execute(() -> {
                Decoded d;
                try {
                    d = closed ? null : new Decoded(name, decode(file, name), null);
                } catch (IOException | RuntimeException e) {
                    d = new Decoded(name, null, name + ": " + e.getMessage());
                } catch (OutOfMemoryError e) {
                    d = new Decoded(name, null, name + ": 메모리 부족");
                }
                if (d != null) decoded.add(d);
                // 그 사이에 닫혔으면 아무도 가져가지 않으므로 여기서 치운다
                if (closed) drain();
            });
        }
    }

    private void drain() {
        for (Decoded d; (d = decoded.poll()) != null; ) {
            if (d.image() != null) d.image().close();
        }
    }

    /** 다 읽힌 이미지를 최대 max 장 GPU 에 올린다. 매 프레임 부른다. 올린 수를 돌려준다. */
    public int pump(int max) {
        int uploaded = 0;
        while (uploaded < max) {
            Decoded d = decoded.poll();
            if (d == null) break;
            pending.remove(d.name());
            if (d.error() != null) {
                if (failure == null) failure = d.error();
                continue;
            }
            if (closed || !wanted.contains(d.name()) || byName.containsKey(d.name())) {
                d.image().close();
                continue;
            }
            byName.put(d.name(), register(d.image()));
            uploaded++;
        }
        return uploaded;
    }

    /** 이 이름들이 모두 올라와 있는지. */
    public boolean ready(Collection<String> names) {
        for (String n : names) if (!byName.containsKey(n)) return false;
        return true;
    }

    /** 읽다가 실패한 것이 있으면 그 이유 (없으면 null). */
    public String failure() {
        return failure;
    }

    /** keep 에 없는 이미지 텍스처를 내린다 (지나간 장면). 읽는 중인 것은 도착하면 버려진다. */
    public void retain(Collection<String> keep) {
        wanted.retainAll(keep);
        for (Iterator<Map.Entry<String, Tex>> it = byName.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Tex> e = it.next();
            if (keep.contains(e.getKey())) continue;
            manager.release(e.getValue().id());
            registered.remove(e.getValue().id());
            it.remove();
        }
    }

    /** NativeImage 를 새 텍스처로 올린다. image 는 텍스처가 소유한다. */
    public Tex register(NativeImage img) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("chaca_story", prefix + (counter++));
        DynamicTexture tex = new DynamicTexture(img);
        tex.setFilter(true, false);
        manager.register(id, tex);
        registered.add(id);
        return new Tex(id, img.getWidth(), img.getHeight(), img);
    }

    /** register 로 만든 텍스처 하나를 내린다 (전환용 텍스처가 끝났을 때). */
    public void release(Tex tex) {
        if (tex == null) return;
        if (registered.remove(tex.id())) manager.release(tex.id());
    }

    public Tex get(String name) {
        return byName.get(name);
    }

    /** 텍스처 내용을 바꾼 뒤 GPU에 다시 올린다. */
    public void upload(Tex tex) {
        if (manager.getTexture(tex.id()) instanceof DynamicTexture dyn) dyn.upload();
    }

    @Override
    public void close() {
        closed = true;
        for (ResourceLocation id : registered) manager.release(id);
        registered.clear();
        byName.clear();
        wanted.clear();
        pending.clear();
        drain();
    }
}
