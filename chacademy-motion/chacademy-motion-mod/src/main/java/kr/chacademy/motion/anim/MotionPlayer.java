package kr.chacademy.motion.anim;

import dev.kosmx.playerAnim.api.IPlayable;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import kr.chacademy.motion.ChacademyMotionClient;
import kr.chacademy.motion.broom.BroomRiders;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * playerAnimator 위에서 모션을 재생하는 부분.
 * 플레이어마다 "spell" 레이어를 하나씩 붙이고, 거기에 키프레임 애니메이션을 넣는다.
 */
public final class MotionPlayer {
    private static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(ChacademyMotionClient.MOD_ID, "spell");

    /** 숫자가 클수록 다른 모드(Better Combat 등)의 애니메이션보다 위에 그려진다. */
    private static final int LAYER_PRIORITY = 1000;

    private MotionPlayer() {}

    public static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
                LAYER_ID, LAYER_PRIORITY, player -> new ModifierLayer<>());
    }

    /**
     * @param name assets/chacademy_motion/player_animations/*.json 안의 "name" 값 (예: wand_cast)
     * @return 재생했으면 true
     */
    @SuppressWarnings("unchecked")
    public static boolean play(AbstractClientPlayer player, String name) {
        if (STOP.equals(name)) return stop(player);
        IPlayable anim = PlayerAnimationRegistry.getAnimation(
                ResourceLocation.fromNamespaceAndPath(ChacademyMotionClient.MOD_ID, name));
        if (anim == null) {
            ChacademyMotionClient.LOGGER.warn("모션을 찾을 수 없음: {}", name);
            return false;
        }
        var layer = (ModifierLayer<IAnimation>) PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER_ID);
        if (layer == null) return false;

        // 빗자루를 타는 중이면 팔·상체만 시전 모션을 쓰고, 다리와 몸 전체는 빗자루 자세(아래 레이어)를 유지
        if (BroomRiders.isRiding(player) && anim instanceof KeyframeAnimation keyframes) {
            anim = upperBodyOnly(keyframes);
        }
        IAnimation playing = anim.playAnimation()
                // 1인칭에서는 모션을 끄고 바닐라 손을 보여줌 (화면 가림 방지)
                .setFirstPersonMode(FirstPersonMode.DISABLED);

        // 이전 모션이 재생 중이면 3틱 동안 섞어서 넘어감 (재생 중이 아니면 바로 시작)
        layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(3, Ease.INOUTSINE), playing);
        return true;
    }

    /**
     * 서버가 보내는 예약 이름. 반복 모션(아티팩트 졸기·등껍질 숨기 등)을 6틱 동안 풀며 끝낸다.
     * 재생 중인 모션이 없으면 아무것도 하지 않는다.
     */
    public static final String STOP = "stop";

    @SuppressWarnings("unchecked")
    private static boolean stop(AbstractClientPlayer player) {
        var layer = (ModifierLayer<IAnimation>) PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER_ID);
        if (layer == null || layer.getAnimation() == null) return false;
        layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(6, Ease.INOUTSINE), null);
        return true;
    }

    private static KeyframeAnimation upperBodyOnly(KeyframeAnimation anim) {
        try {
            var b = anim.mutableCopy();
            b.getPart("rightLeg").setEnabled(false);
            b.getPart("leftLeg").setEnabled(false);
            b.getPart("body").setEnabled(false);
            return b.build();
        } catch (RuntimeException e) {
            return anim;
        }
    }
}
