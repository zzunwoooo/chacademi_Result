package kr.chacademy.motion.broom;

import dev.kosmx.playerAnim.api.IPlayable;
import dev.kosmx.playerAnim.api.PartKey;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import kr.chacademy.motion.ChacademyMotionClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 빗자루 탑승 자세. playerAnimator 에 "broom" 레이어를 따로 두고(마법 시전 레이어보다 아래),
 * 반복 모션(broom_ride 등) 위에 몸 기울기(선회·급강하·배럴롤)를 실시간으로 더한다.
 */
public final class BroomPose {
    private static final ResourceLocation LAYER_ID = ResourceLocation.fromNamespaceAndPath(ChacademyMotionClient.MOD_ID, "broom");
    /** 시전 모션(spell, 1000) 보다 낮게: 타고 있는 중에 마법을 쓰면 팔은 시전 모션, 다리는 빗자루 자세. */
    private static final int LAYER_PRIORITY = 900;

    public static final String RIDE = "broom_ride";
    public static final String BOOST = "broom_boost";
    public static final String WOBBLE = "broom_wobble";
    public static final String FALL = "broom_fall";
    /** 큐디치: 한 팔로 매달려 흔들흔들 (반복) / 다시 올라타기 (2026-10-20) */
    public static final String HANG = "broom_hang";
    public static final String CLIMB = "broom_climb";

    /** 플레이어별 지금 틀어 둔 모션 이름 */
    private static final Map<UUID, String> CURRENT = new HashMap<>();

    private BroomPose() {}

    public static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER_ID, LAYER_PRIORITY, player -> {
            UUID id = player.getUUID();
            var lean = new AdjustmentModifier(part -> lean(id, part));
            lean.fadeIn = false;
            lean.fadeOut = false;
            return new ModifierLayer<>(null, lean);
        });
    }

    private static Optional<AdjustmentModifier.PartModifier> lean(UUID id, PartKey part) {
        if (part != PartKey.BODY) return Optional.empty();
        var r = BroomRiders.get(id);
        if (r == null || !CURRENT.containsKey(id) || r.hanging || r.climbTicks > 0) return Optional.empty();
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // 급강하면 앞으로 숙이고(pitch 음수), 선회 방향으로 몸을 기울임(roll 양수 = 왼쪽)
        float pitch = -r.pitch(pt) * 0.45f;
        float roll = r.bank(pt) * 0.8f + r.spin(pt);
        return Optional.of(new AdjustmentModifier.PartModifier(
                new Vec3f(pitch * Mth.DEG_TO_RAD, 0f, roll * Mth.DEG_TO_RAD), Vec3f.ZERO));
    }

    @SuppressWarnings("unchecked")
    private static ModifierLayer<IAnimation> layer(Player p) {
        if (!(p instanceof AbstractClientPlayer cp)) return null;
        return (ModifierLayer<IAnimation>) PlayerAnimationAccess.getPlayerAssociatedData(cp).get(LAYER_ID);
    }

    private static boolean play(Player p, String name, int fade) {
        var layer = layer(p);
        if (layer == null) return false;
        IPlayable anim = PlayerAnimationRegistry.getAnimation(ResourceLocation.fromNamespaceAndPath(ChacademyMotionClient.MOD_ID, name));
        if (anim == null) {
            ChacademyMotionClient.LOGGER.warn("빗자루 모션 없음: {}", name);
            return false;
        }
        IAnimation playing = anim.playAnimation().setFirstPersonMode(FirstPersonMode.DISABLED);
        layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(fade, Ease.INOUTSINE), playing);
        CURRENT.put(p.getUUID(), name);
        return true;
    }

    /** 탑승 시작: 반복 탑승 자세. */
    static void start(Player p) {
        CURRENT.remove(p.getUUID());
        play(p, RIDE, 6);
    }

    /** 틱마다: 부스트/흔들림/기본 자세 중 고름 (바뀔 때만 교체). */
    static void update(Player p, BroomRiders.Rider r) {
        String want = r.hanging ? HANG : r.climbTicks > 0 ? CLIMB : r.boosting ? BOOST : r.wobbling ? WOBBLE : RIDE;
        // 같은 이름이어도 레이어가 비어 있으면 다시 튼다: 죽었다 살아나거나 순간이동·시야 밖에서 돌아오면
        // 그 플레이어 엔티티가 새로 만들어져 레이어가 비는데, 이름만 보고 건너뛰면 남 화면에서 서 있는 자세로 보였음
        if (!want.equals(CURRENT.get(p.getUUID())) || idle(p)) play(p, want, want.equals(CLIMB) ? 2 : 5);
    }

    private static boolean idle(Player p) {
        var layer = layer(p);
        if (layer == null) return false;
        var anim = layer.getAnimation();
        return anim == null || !anim.isActive();
    }

    /** 시야 밖으로 나가 목록에서 빠진 사람: 다시 보이면 처음부터 틀도록 */
    static void forget(java.util.UUID id) {
        CURRENT.remove(id);
    }

    /** 내림. fell 이면 허우적 모션(끝나면 저절로 바닐라 자세로). */
    static void stop(AbstractClientPlayer p, boolean fell) {
        CURRENT.remove(p.getUUID());
        if (fell) {
            if (play(p, FALL, 2)) CURRENT.remove(p.getUUID()); // 낙하 모션 중에는 기울기 끔
            return;
        }
        var layer = layer(p);
        if (layer != null) layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(5, Ease.INOUTSINE), null);
    }

    public static void clear() {
        CURRENT.clear();
    }
}
