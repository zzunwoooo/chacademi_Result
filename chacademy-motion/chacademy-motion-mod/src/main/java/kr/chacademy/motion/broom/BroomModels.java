package kr.chacademy.motion.broom;

import com.mojang.blaze3d.platform.NativeImage;
import kr.chacademy.motion.ChacademyMotionClient;
import kr.chacademy.motion.broom.model.BroomModel;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 빗자루 모델 모음. 리소스 경로 assets/chacademy_motion/brooms/&lt;ID&gt;.bbmodel 을 읽는다.
 * 모드 안에 30종(broom_1~30, 등급 순서)이 들어 있고, 서버 리소스팩(ItemsAdder)에 같은 경로로 넣으면 덮어쓰거나 새로 추가된다.
 * 텍스처는 bbmodel 안에 내장된 PNG 를 처음 그릴 때(렌더 스레드) 등록한다.
 */
public final class BroomModels {
    private static final Map<String, BroomModel> MODELS = new HashMap<>();
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
    private static final Set<String> MISSING_WARNED = new HashSet<>();

    private BroomModels() {}

    public static void init() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath(ChacademyMotionClient.MOD_ID, "brooms");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                loadAll(manager);
            }
        });
    }

    private static void loadAll(ResourceManager manager) {
        releaseTextures();
        MODELS.clear();
        MISSING_WARNED.clear();
        var found = manager.listResources("brooms", id -> id.getPath().endsWith(".bbmodel"));
        for (var e : found.entrySet()) {
            ResourceLocation id = e.getKey();
            if (!id.getNamespace().equals(ChacademyMotionClient.MOD_ID)) continue;
            String path = id.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1, path.length() - ".bbmodel".length());
            try (InputStream in = e.getValue().open()) {
                MODELS.put(name, BroomModel.parse(name, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                ChacademyMotionClient.LOGGER.warn("빗자루 모델을 읽지 못함: {} ({})", id, ex.toString());
            }
        }
        ChacademyMotionClient.LOGGER.info("빗자루 모델 {}종 불러옴", MODELS.size());
    }

    private static void releaseTextures() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (var loc : TEXTURES.values()) tm.release(loc);
        TEXTURES.clear();
    }

    /** 없으면 broom_1, 그것도 없으면 아무거나. */
    public static BroomModel get(String id) {
        BroomModel m = MODELS.get(id);
        if (m == null) {
            if (MISSING_WARNED.add(id)) ChacademyMotionClient.LOGGER.warn("빗자루 모델 없음: {} (broom_1 로 대신 그림)", id);
            m = MODELS.get("broom_1");
            if (m == null && !MODELS.isEmpty()) m = MODELS.values().iterator().next();
        }
        return m;
    }

    /** 렌더 스레드에서만 호출. */
    public static ResourceLocation texture(BroomModel model) {
        var loc = TEXTURES.get(model.name);
        if (loc != null) return loc;
        loc = ResourceLocation.fromNamespaceAndPath(ChacademyMotionClient.MOD_ID, "broom_tex/" + model.name);
        try {
            if (model.texturePng == null) throw new IllegalStateException("내장 텍스처 없음");
            NativeImage image = NativeImage.read(model.texturePng);
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(image));
        } catch (Exception ex) {
            ChacademyMotionClient.LOGGER.warn("빗자루 텍스처 등록 실패: {} ({})", model.name, ex.toString());
            loc = ResourceLocation.withDefaultNamespace("textures/block/oak_planks.png");
        }
        TEXTURES.put(model.name, loc);
        return loc;
    }
}
