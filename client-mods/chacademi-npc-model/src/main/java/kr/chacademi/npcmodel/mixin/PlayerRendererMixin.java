package kr.chacademi.npcmodel.mixin;

import kr.chacademi.npcmodel.NpcModelClient;
import kr.chacademi.npcmodel.client.NpcInstance;
import kr.chacademi.npcmodel.client.NpcModelState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 플레이어 렌더 상태 추출 끝에서: 3D 모델이 준비된 NPC면 바닐라 몸·장비를 숨기고 이름표 높이를 모델에 맞춘다.
 * 준비 안 된 NPC·진짜 플레이어는 아무것도 바꾸지 않는다 (원래 외형). 클릭 판정·엔티티는 서버 그대로.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
            at = @At("TAIL"))
    private void chacademi$extract(AbstractClientPlayer player, PlayerRenderState state, float partialTick, CallbackInfo ci) {
        NpcModelState ext = (NpcModelState) (Object) state;
        if (state.isInvisible) {
            ext.chacademi$set(null, 0, 0); // 원래 투명한 NPC(투명화·관전 등)는 모델도 그리지 않음
            return;
        }
        NpcInstance inst;
        try {
            inst = NpcModelClient.bindings() == null ? null : NpcModelClient.bindings().instanceFor(player);
        } catch (RuntimeException ex) {
            NpcModelClient.LOG.error("[npc-models] NPC 모델 준비 실패 → 원래 외형", ex);
            inst = null;
        }
        if (inst == null) {
            ext.chacademi$set(null, 0, 0);
            return;
        }
        double dx = player.getX() - player.xo, dz = player.getZ() - player.zo;
        float speed = (float) (Math.sqrt(dx * dx + dz * dz) * 20.0);
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double dist = Math.sqrt(player.distanceToSqr(cam));
        ext.chacademi$set(inst, speed, dist);

        // 바닐라 몸 숨김: 몸이 안 보이고(isInvisible) 반투명으로도 안 그림(isInvisibleToPlayer) → 몸 모델 렌더 타입 없음
        state.isInvisible = true;
        state.isInvisibleToPlayer = true;
        state.appearsGlowing = false;
        // 장비·손 아이템·어깨 앵무새도 바닐라 위치에 떠 보이지 않게
        state.headEquipment = ItemStack.EMPTY;
        state.chestEquipment = ItemStack.EMPTY;
        state.legsEquipment = ItemStack.EMPTY;
        state.feetEquipment = ItemStack.EMPTY;
        state.rightHandItem.clear();
        state.leftHandItem.clear();
        state.headItem.clear();
        state.wornHeadType = null; // 머리 칸 해골 블록
        state.arrowCount = 0;     // 몸에 박힌 화살·벌침이 허공에 뜨지 않게
        state.stingerCount = 0;
        state.parrotOnLeftShoulder = null;
        state.parrotOnRightShoulder = null;
        // 이름표: 모델 꼭대기 (바닐라는 여기서 0.5 위에 그림)
        if (state.nameTag != null) {
            state.nameTagAttachment = new Vec3(0, inst.topHeight(), 0);
        }
    }
}
