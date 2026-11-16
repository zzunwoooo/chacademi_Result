package com.diamondserver.client;

import dev.kosmx.playerAnim.api.IPlayable;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 플레이어 모션 재생 (playerAnimator 위에 우리 레이어를 하나 얹는다).
 * 모션 파일은 assets/diamondserver/player_animations/*.json, 파일 안의 "name" 이 모션 이름이다.
 * 지금 있는 모션: forced_dance (강제 댄스, 10.4초)
 */
final class Motion {
    private static final Identifier LAYER = Identifier.of("diamondserver", "motion");
    static final String STOP = "stop";

    private Motion() { }

    static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, 1000, player -> new ModifierLayer<>());
    }

    @SuppressWarnings("unchecked")
    static boolean play(AbstractClientPlayerEntity player, String name) {
        var layer = (ModifierLayer<IAnimation>) PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER);
        if (layer == null) return false;
        if (STOP.equals(name)) {
            if (layer.getAnimation() == null) return false;
            layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(6, Ease.INOUTSINE), null);
            return true;
        }
        IPlayable anim = PlayerAnimationRegistry.getAnimation(Identifier.of("diamondserver", name));
        if (anim == null) return false;
        // 1인칭에서는 모션을 끄고 원래 손을 보여 준다 (화면 가림 방지)
        IAnimation playing = anim.playAnimation().setFirstPersonMode(FirstPersonMode.DISABLED);
        layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(3, Ease.INOUTSINE), playing);
        return true;
    }
}
