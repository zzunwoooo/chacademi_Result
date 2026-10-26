package kr.chacademi.npcmodel.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * NPC 모델용 렌더 타입. 바닐라 엔티티 셰이더·조명맵·피격 오버레이·깊이 상태를 그대로 쓰고(셰이더팩도 엔티티로 처리),
 * 텍스처만 선형 필터 + 밉맵으로 묶는다 (바닐라 entityCutout* 은 NEAREST·밉맵 없음이라 2048 텍스처가 멀리서 지글거림).
 * chacademy-motion HudArt 와 같은 방식(RenderType 생성자 + 상태 조각의 setup/clear 직접 호출).
 */
public final class NpcRenderTypes extends RenderType {
    private static final Map<String, RenderType> CACHE = new HashMap<>();

    private NpcRenderTypes(String name, boolean sort, Runnable setup, Runnable clear) {
        super(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 1536, true, sort, setup, clear);
    }

    /** 불투명/컷아웃 (alphaMode OPAQUE·MASK). cull = 한 면 재질만 뒷면 제거. */
    public static RenderType cutout(ResourceLocation texture, boolean cull) {
        return CACHE.computeIfAbsent("cutout|" + cull + "|" + texture, k -> make("chacademi_npc_cutout", texture, false, cull,
                new RenderStateShard[]{RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER, NO_TRANSPARENCY, cull ? CULL : NO_CULL,
                        LIGHTMAP, OVERLAY, LEQUAL_DEPTH_TEST, COLOR_DEPTH_WRITE}));
    }

    /** 반투명 (alphaMode BLEND). 삼각형 정렬은 버퍼 단위 → 겹치는 반투명 면은 순서가 틀릴 수 있다. */
    public static RenderType translucent(ResourceLocation texture, boolean cull) {
        return CACHE.computeIfAbsent("translucent|" + cull + "|" + texture, k -> make("chacademi_npc_translucent", texture, true, cull,
                new RenderStateShard[]{RENDERTYPE_ENTITY_TRANSLUCENT_SHADER, TRANSLUCENT_TRANSPARENCY, cull ? CULL : NO_CULL,
                        LIGHTMAP, OVERLAY, LEQUAL_DEPTH_TEST, COLOR_DEPTH_WRITE}));
    }

    private static RenderType make(String name, ResourceLocation texture, boolean sort, boolean cull, RenderStateShard[] shards) {
        return new NpcRenderTypes(name, sort, () -> {
            for (RenderStateShard s : shards) {
                s.setupRenderState();
            }
            var tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
            tex.setFilter(true, true);
            RenderSystem.setShaderTexture(0, tex.getId());
        }, () -> {
            for (int i = shards.length - 1; i >= 0; i--) {
                shards[i].clearRenderState();
            }
        });
    }

    /** 텍스처를 내릴 때 캐시에서도 뺀다. */
    public static void forget(ResourceLocation texture) {
        String suffix = "|" + texture;
        CACHE.keySet().removeIf(k -> k.endsWith(suffix));
    }
}
