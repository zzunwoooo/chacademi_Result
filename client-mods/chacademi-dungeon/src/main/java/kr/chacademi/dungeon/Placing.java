package kr.chacademi.dungeon;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

/**
 * 기믹 설치 미리보기 중 클릭 (클라에서 가로채서 바닐라 상호작용은 보내지 않는다):
 * 블록 우클릭 = 그 면 바깥 칸에 설치 요청 · Shift+우클릭 = 미리보기 90° 회전 · 좌클릭 = 취소.
 */
final class Placing {
    private Placing() {}

    static void install() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide() || EditorState.placeModel == null) return InteractionResult.PASS;
            if (player.isShiftKeyDown()) { rotate(); return InteractionResult.FAIL; }
            var pos = hit.getBlockPos().relative(hit.getDirection());
            EditorState.send("gimmick.place", "model", EditorState.placeModel, "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "facing", EditorState.placeFacing);
            EditorState.placeModel = null;
            return InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClientSide() || EditorState.placeModel == null) return InteractionResult.PASS;
            if (player.isShiftKeyDown()) rotate();
            return InteractionResult.FAIL;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (!world.isClientSide() || EditorState.placeModel == null) return InteractionResult.PASS;
            EditorState.placeModel = null;
            player.displayClientMessage(Component.literal("기믹 설치를 취소했어요"), true);
            return InteractionResult.FAIL;
        });
    }

    private static void rotate() {
        EditorState.placeFacing = Math.floorMod(EditorState.placeFacing + 1, 4);
    }
}
