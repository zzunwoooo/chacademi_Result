package kr.chacademy.motion;

import kr.chacademy.motion.anim.MotionPlayer;
import kr.chacademy.motion.api.BroomApi;
import kr.chacademy.motion.broom.BroomFlight;
import kr.chacademy.motion.broom.BroomHud;
import kr.chacademy.motion.broom.BroomModels;
import kr.chacademy.motion.broom.BroomPose;
import kr.chacademy.motion.broom.BroomRenderer;
import kr.chacademy.motion.broom.BroomRiders;
import kr.chacademy.motion.broom.trail.BroomTrails;
import kr.chacademy.motion.net.BroomPackets;
import kr.chacademy.motion.net.MotionPackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 흐름
 * <pre>
 * [내 클라] 시전 → 내 화면에서 바로 재생 → chacademy:cast 전송
 * [플러그인] 검증 → 주변 유저에게 chacademy:anim 전송 (시전자 제외)
 * [다른 클라] chacademy:anim 수신 → 해당 플레이어 모션 재생
 * </pre>
 */
public class ChacademyMotionClient implements ClientModInitializer {
    public static final String MOD_ID = "chacademy_motion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 같은 플레이어가 너무 자주 보내지 않게 하는 클라 쪽 쿨타임 (플러그인도 따로 검사함). */
    private static final int CAST_COOLDOWN_TICKS = 14;
    private static int cooldown = 0;


    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playC2S().register(MotionPackets.CastC2S.TYPE, MotionPackets.CastC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(MotionPackets.PlayAnimS2C.TYPE, MotionPackets.PlayAnimS2C.CODEC);

        PayloadTypeRegistry.playS2C().register(BroomPackets.RideS2C.TYPE, BroomPackets.RideS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(BroomPackets.StateS2C.TYPE, BroomPackets.StateS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(BroomPackets.EventC2S.TYPE, BroomPackets.EventC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(BroomPackets.ListS2C.TYPE, BroomPackets.ListS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(BroomPackets.FxS2C.TYPE, BroomPackets.FxS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(BroomPackets.PerkS2C.TYPE, BroomPackets.PerkS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(BroomPackets.RequestC2S.TYPE, BroomPackets.RequestC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(BroomPackets.BuffS2C.TYPE, BroomPackets.BuffS2C.CODEC);

        MotionPlayer.init();
        BroomPose.init();
        BroomModels.init();
        BroomTrails.init(); // 빗자루 비행 궤적 (PortableVFX 있을 때만)

        // 빗자루: 서버가 "타라"(나만) / "누가 탔다·내렸다"(나와 주변)를 알려줌. 둘 다 클라 스레드에서 실행됨.
        ClientPlayNetworking.registerGlobalReceiver(BroomPackets.RideS2C.TYPE, (payload, context) -> { if (payload != BroomPackets.RideS2C.INVALID) BroomFlight.onRide(payload); });
        ClientPlayNetworking.registerGlobalReceiver(BroomPackets.StateS2C.TYPE, (payload, context) -> { if (payload != BroomPackets.StateS2C.INVALID) BroomRiders.onState(payload); });
        ClientPlayNetworking.registerGlobalReceiver(BroomPackets.ListS2C.TYPE, (payload, context) -> { if (payload != BroomPackets.ListS2C.INVALID) BroomApi.onList(payload); });
        ClientPlayNetworking.registerGlobalReceiver(BroomPackets.PerkS2C.TYPE, (payload, context) -> { if (payload != BroomPackets.PerkS2C.INVALID) BroomFlight.onPerks(payload.perks()); });
        ClientPlayNetworking.registerGlobalReceiver(BroomPackets.FxS2C.TYPE, (payload, context) -> { if (payload != BroomPackets.FxS2C.INVALID) context.client().execute(() -> BroomFlight.onFx(payload.kind())); });
        // 시간제 비행 버프 (마법 375 불꽃 추진 · 380 순풍): 속도·균형은 내 빗자루만, 궤적 숨김은 누구든
        ClientPlayNetworking.registerGlobalReceiver(BroomPackets.BuffS2C.TYPE, (payload, context) -> {
            if (payload == BroomPackets.BuffS2C.INVALID) return;
            context.client().execute(() -> {
                if (payload.kind() == BroomPackets.BUFF_HIDE_TRAIL) { BroomTrails.hide(payload.player(), payload.ticks()); return; }
                var me = context.client().player;
                if (me != null && me.getUUID().equals(payload.player())) BroomFlight.onBuff(payload.kind(), payload.ticks(), payload.value());
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            BroomFlight.reset();
            BroomRiders.clear();
            BroomPose.clear();
            BroomApi.clear();
            BroomTrails.reset();
        }));
        WorldRenderEvents.AFTER_ENTITIES.register(BroomRenderer::render);
        HudRenderCallback.EVENT.register(BroomHud::render);

        // 서버(플러그인)가 "이 플레이어가 이 모션을 재생했다"고 알려줌. 렌더 스레드에서 호출됨.
        ClientPlayNetworking.registerGlobalReceiver(MotionPackets.PlayAnimS2C.TYPE, (payload, context) -> {
            var level = context.client().level;
            if (level == null || payload == MotionPackets.PlayAnimS2C.INVALID) return;
            if (level.getPlayerByUUID(payload.player()) instanceof AbstractClientPlayer target) {
                MotionPlayer.play(target, payload.animation());
            }
        });

        // 모션 테스트 키(R)는 없앰 (오너 2026-10-26). 마법 모션은 cast() 를 부르는 쪽에서만 재생.

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BroomFlight.tick(client);
            BroomRiders.tick(client);
            if (cooldown > 0) cooldown--;
        });

        LOGGER.info("Chacademy Motion 로드됨");
    }

    /**
     * 내 캐릭터가 마법을 쓸 때 호출. 마법 시스템에서는 이 메서드만 부르면 된다.
     *
     * @param animation 모션 이름 (예: "wand_cast")
     */
    public static void cast(String animation) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || cooldown > 0) return;
        if (!MotionPlayer.play(player, animation)) return;
        cooldown = CAST_COOLDOWN_TICKS;

        // 플러그인이 없는 서버라도 보내는 건 문제 없음 (서버가 무시함)
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPlayNetworking.send(new MotionPackets.CastC2S(animation));
        }
    }
}
