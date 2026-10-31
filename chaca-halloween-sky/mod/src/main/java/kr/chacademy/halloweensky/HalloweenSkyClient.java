package kr.chacademy.halloweensky;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;

/**
 * 서버가 보내는 "소리" 를 신호로 쓴다 (서버 재시작·플러그인 없이 /playsound 로 켜고 끈다).
 * chaca_sky:halloween_on  = 서쪽 끝부터 할로윈 하늘로 쓸려 바뀜
 * chaca_sky:halloween_off = 원래 하늘로 쓸려 돌아감
 * chaca_sky:halloween_now = 바로 할로윈 하늘 (켜진 동안 들어온 사람용)
 * 다른 서버로 옮기거나 나가면 원래 하늘로 돌아간다.
 */
public final class HalloweenSkyClient implements ClientModInitializer {
    public static final String NAMESPACE = "chaca_sky";

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> SkyState.reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SkyState.reset());
    }

    /** 신호 소리면 처리하고 true (그 소리는 재생하지 않음). */
    public static boolean handleSignal(ResourceLocation id) {
        if (!NAMESPACE.equals(id.getNamespace())) return false;
        switch (id.getPath()) {
            case "halloween_on" -> SkyState.turnOn(false);
            case "halloween_now" -> SkyState.turnOn(true);
            case "halloween_off" -> SkyState.turnOff();
            default -> { }
        }
        return true;
    }
}
