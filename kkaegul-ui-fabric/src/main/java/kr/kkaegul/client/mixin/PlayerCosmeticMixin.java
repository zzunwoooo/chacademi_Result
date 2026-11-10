package kr.kkaegul.client.mixin;

import kr.kkaegul.client.mc.ItemIcons;
import kr.kkaegul.client.ui.Cosmetics;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 손(offhand) 치장품: 보조손이 비어 있으면 그 손 아이템 칸(렌더 상태)에 치장품을 채움 → 바닐라 손 아이템 렌더가
 * 진짜로 든 것과 똑같이 그림 (HMCC 가 보조손 칸에 가짜 아이템을 보내는 것과 같은 모양, 팔도 '아이템 든' 자세).
 * 실제 보조손 아이템이 있거나 투명화·관전 중이면 건드리지 않음.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerCosmeticMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V", at = @At("TAIL"))
    private void kkaegul$offhandCosmetic(AbstractClientPlayerEntity player, PlayerEntityRenderState state, float tickDelta, CallbackInfo ci) {
        try {
            offhand(player, state);
        } catch (Throwable t) {
            Cosmetics.fail("손 치장", t);
        }
    }

    private static void offhand(AbstractClientPlayerEntity player, PlayerEntityRenderState state) {
        if (!Cosmetics.any() || player.isInvisible() || player.isSpectator()) return;
        String icon = Cosmetics.icon(player.getGameProfile().getName(), Cosmetics.OFFHAND);
        if (icon == null || !player.getOffHandStack().isEmpty()) return;
        ItemStack st = ItemIcons.stack(icon);
        if (st.isEmpty()) return;
        Arm arm = player.getMainArm().getOpposite();
        ItemRenderState target = arm == Arm.LEFT ? state.leftHandItemState : state.rightHandItemState;
        if (!target.isEmpty()) return;
        MinecraftClient.getInstance().getItemModelManager().updateForLivingEntity(target, st,
                arm == Arm.LEFT ? ModelTransformationMode.THIRD_PERSON_LEFT_HAND : ModelTransformationMode.THIRD_PERSON_RIGHT_HAND,
                arm == Arm.LEFT, player);
        if (arm == Arm.LEFT) { if (state.leftArmPose == BipedEntityModel.ArmPose.EMPTY) state.leftArmPose = BipedEntityModel.ArmPose.ITEM; }
        else if (state.rightArmPose == BipedEntityModel.ArmPose.EMPTY) state.rightArmPose = BipedEntityModel.ArmPose.ITEM;
    }
}
