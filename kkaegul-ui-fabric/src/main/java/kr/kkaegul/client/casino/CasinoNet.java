package kr.kkaegul.client.casino;

import kr.kkaegul.client.casino.holdem.HoldemScreen;
import kr.kkaegul.client.casino.holdem.HoldemState;
import kr.kkaegul.client.casino.seotda.SeotdaScreen;
import kr.kkaegul.client.casino.seotda.SeotdaState;
import kr.kkaegul.client.mc.KkaegulClient;
import net.minecraft.client.MinecraftClient;

/**
 * 포커(홀덤)·섯다 화면 ↔ 서버 연결 (엘시 카지노 화면 이식).
 * 서버 → 클라: kkaegul:ui {"type":"casino","game":"holdem|seotda","state":"<엘시 형식 JSON 문자열>"}
 * 클라 → 서버: kkaegul:action {"action":"casino","game":"holdem|seotda","json":"<엘시 형식 액션 JSON>"}
 * AI 대전·방 목록은 없음: 서버가 미니게임으로 테이블을 짜서 바로 연다.
 */
public final class CasinoNet {
    private CasinoNet() {}

    public static void holdem(String json) { send("holdem", json); }

    public static void seotda(String json) { send("seotda", json); }

    private static void send(String game, String json) {
        KkaegulClient k = KkaegulClient.get();
        if (k != null) k.send("casino", "game", game, "json", json);
    }

    /** 서버에서 받은 상태 */
    public static void receive(String game, String state) {
        if (game.equals("holdem")) onHoldem(HoldemState.apply(state));
        else if (game.equals("seotda")) onSeotda(SeotdaState.apply(state));
    }

    public static void onHoldem(int r) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (r > 0 && !(mc.currentScreen instanceof HoldemScreen)) { UiSound.open(); mc.setScreen(new HoldemScreen()); }
        else if (r < 0 && mc.currentScreen instanceof HoldemScreen s) s.closeQuietly();
    }

    public static void onSeotda(int r) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (r > 0 && !(mc.currentScreen instanceof SeotdaScreen)) { UiSound.open(); mc.setScreen(new SeotdaScreen()); }
        else if (r < 0 && mc.currentScreen instanceof SeotdaScreen s) s.closeQuietly();
    }
}
