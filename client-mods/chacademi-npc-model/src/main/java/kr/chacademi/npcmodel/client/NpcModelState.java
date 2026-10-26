package kr.chacademi.npcmodel.client;

/** 렌더 상태(EntityRenderState)에 붙이는 NPC 모델 정보 (mixin 이 구현). 추출 때마다 다시 채운다. */
public interface NpcModelState {
    NpcInstance chacademi$npc();

    float chacademi$speed();

    double chacademi$distance();

    void chacademi$set(NpcInstance instance, float speed, double distance);
}
