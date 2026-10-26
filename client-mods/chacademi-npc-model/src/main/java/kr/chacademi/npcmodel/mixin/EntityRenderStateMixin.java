package kr.chacademi.npcmodel.mixin;

import kr.chacademi.npcmodel.client.NpcInstance;
import kr.chacademi.npcmodel.client.NpcModelState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** 렌더 상태에 NPC 모델 정보를 붙인다 (플레이어 렌더 상태 추출 때만 채워짐, 나머지 엔티티는 항상 null). */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements NpcModelState {
    @Unique
    private NpcInstance chacademi$npc;
    @Unique
    private float chacademi$speed;
    @Unique
    private double chacademi$distance;

    @Override
    public NpcInstance chacademi$npc() {
        return chacademi$npc;
    }

    @Override
    public float chacademi$speed() {
        return chacademi$speed;
    }

    @Override
    public double chacademi$distance() {
        return chacademi$distance;
    }

    @Override
    public void chacademi$set(NpcInstance instance, float speed, double distance) {
        this.chacademi$npc = instance;
        this.chacademi$speed = speed;
        this.chacademi$distance = distance;
    }
}
