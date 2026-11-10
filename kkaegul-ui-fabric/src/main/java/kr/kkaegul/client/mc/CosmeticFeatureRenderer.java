package kr.kkaegul.client.mc;

import kr.kkaegul.client.ui.Cosmetics;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.feature.HeadFeatureRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;

/**
 * 치장품(모자·얼굴·등)을 플레이어 렌더에 직접 붙여 그림 — 엔티티를 안 쓰니 끊김·지연이 없고 텔포·리스폰·월드 이동과 무관.
 * 누가 뭘 입었는지는 Cosmetics (계정명 소문자 키). 손(offhand)은 PlayerCosmeticMixin 이 진짜 손 아이템 칸에 채워 바닐라가 그림, 풍선은 CosmeticWorld.
 *
 * 변환 (모두 LivingEntityRenderer 가 걸어 둔 모델 공간 = y 아래, 1단위 = 1칸 × 플레이어 배율 0.9375, 몸 방향(bodyYaw)으로 돌아간 상태에서 시작):
 *  - head / face: 바닐라 HeadFeatureRenderer 가 머리 칸 일반 아이템을 그리는 것과 똑같이 — root.rotate → head.rotate → HeadFeatureRenderer.translate
 *    (y -0.25, Y축 180°, 0.625 배율) → HEAD 모드 아이템. 머리 회전(고개 돌림·끄덕임)을 그대로 따라감.
 *  - back: HMCCosmetics 백팩 기준점에 맞춤. HMCC 는 갑옷거치대(배율 1)를 플레이어에 태우고 머리 칸에 아이템을 씌움 →
 *    기준점 = 플레이어 발 + 1.8(탑승 위치) + 1.501 - 1/16(거치대 머리 피벗) = 발 위 3.2385칸, 몸 방향(bodyYaw)을 따라 돎.
 *    여기선 root.rotate → body.rotate (몸 뼈대: 웅크리기 기울기·내려감 반영) → 서 있을 때 몸 피벗(발 위 0.9375×1.501 = 1.4072칸)에서
 *    1.8313칸 위 = 모델 단위 -1.9534 로 옮기고 → 플레이어 배율 0.9375 를 되돌려(÷0.9375) 거치대와 같은 크기 → HeadFeatureRenderer.translate → HEAD 모드.
 *    서 있을 땐 HMCC 와 같은 자리·크기, 웅크리면 몸통 기울기(0.5rad)를 따라 등에 붙은 채로 기울어짐.
 *  - 1인칭 자기 자신: 월드 렌더 중(CosmeticWorld.inWorld)이고 1인칭이면 head/face/back 안 그림 (1인칭 몸 보이는 모드 대비). 인벤토리·옷장 미리보기에선 그림.
 *  - 투명화·관전 모드 플레이어는 안 그림.
 */
final class CosmeticFeatureRenderer extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    /** 서 있을 때 몸 피벗 → HMCC 백팩 기준점 (모델 단위, y 위 = 음수) */
    private static final float BACK_UP = -(1.8f + 1.501f - 0.0625f - 0.9375f * 1.501f) / 0.9375f;
    private static final float PLAYER_SCALE = 0.9375f;

    private final ItemRenderState item = new ItemRenderState();

    CosmeticFeatureRenderer(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> ctx) {
        super(ctx);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider vcp, int light, PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        if (!Cosmetics.any() || state.invisible || state.spectator || state.name == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean hideOwn = CosmeticWorld.inWorld && mc.options.getPerspective().isFirstPerson()
                && mc.player != null && state.id == mc.player.getId();
        if (hideOwn) return;
        String name = state.name;
        // 치장 그리기에서 무슨 오류가 나도 게임은 안 꺼지게 (그 장면에서만 치장을 건너뜀)
        try {
            head(matrices, vcp, light, Cosmetics.icon(name, Cosmetics.HEAD));
            head(matrices, vcp, light, Cosmetics.icon(name, Cosmetics.FACE));
            back(matrices, vcp, light, Cosmetics.icon(name, Cosmetics.BACK));
        } catch (Throwable t) {
            Cosmetics.fail("치장 그리기", t);
        }
    }

    private boolean prepare(String icon) {
        if (icon == null) return false;
        ItemStack st = ItemIcons.stack(icon);
        if (st.isEmpty()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        // 인자 7개짜리 update = 비우기 + 표시 모드(HEAD) 지정까지 함 → 모델의 머리 표시 위치(display.head)가 적용되고 예전 모델이 안 쌓임
        mc.getItemModelManager().update(item, st, ModelTransformationMode.HEAD, false, mc.world, null, 0);
        return !item.isEmpty();
    }

    private void head(MatrixStack m, VertexConsumerProvider vcp, int light, String icon) {
        if (!prepare(icon)) return;
        m.push();
        PlayerEntityModel model = getContextModel();
        model.getRootPart().rotate(m);
        model.head.rotate(m);
        try {
            HeadFeatureRenderer.translate(m, HeadFeatureRenderer.HeadTransformation.DEFAULT);
            item.render(m, vcp, light, OverlayTexture.DEFAULT_UV);
        } finally {
            m.pop();
        }
    }

    private void back(MatrixStack m, VertexConsumerProvider vcp, int light, String icon) {
        if (!prepare(icon)) return;
        m.push();
        PlayerEntityModel model = getContextModel();
        model.getRootPart().rotate(m);
        model.body.rotate(m);
        m.translate(0, BACK_UP, 0);
        try {
            m.scale(1 / PLAYER_SCALE, 1 / PLAYER_SCALE, 1 / PLAYER_SCALE);
            HeadFeatureRenderer.translate(m, HeadFeatureRenderer.HeadTransformation.DEFAULT);
            item.render(m, vcp, light, OverlayTexture.DEFAULT_UV);
        } finally {
            m.pop();
        }
    }
}
