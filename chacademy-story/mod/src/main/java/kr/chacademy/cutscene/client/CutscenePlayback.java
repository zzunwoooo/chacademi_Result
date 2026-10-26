package kr.chacademy.cutscene.client;

import kr.chacademy.cutscene.ChacademyCutsceneClient;
import kr.chacademy.cutscene.net.CutscenePackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

/**
 * 컷신 재생 관리 (화면 하나를 띄우고, 끝·중단·실패를 서버에 알린다). 모두 클라이언트 스레드에서 부른다.
 * <ul>
 *   <li>재생 요청이 왔는데 지금은 보여 줄 수 없으면 (다른 화면이 떠 있음, 사망) 보여 줄 수 있을 때까지 들고 있는다</li>
 *   <li>ESC 로 게임 메뉴를 열면 컷신은 잠깐 내려가 있고, 화면이 비면 다시 띄운다</li>
 *   <li>끝까지 봄/건너뜀 → cutscene_done, 다른 화면이 덮어서 사라짐 → cutscene_abort (서버가 다시 보냄),
 *       파일이 없거나 깨짐 → story_fail (서버는 끝난 것으로 치지 않음)</li>
 * </ul>
 */
public final class CutscenePlayback {
    private record Request(String id, int mode, boolean fromServer) {
    }

    /** 지금은 보여 줄 수 없어서 기다리는 재생 요청. */
    private static Request deferred;
    /** 떠 있거나 (게임 메뉴 때문에) 잠깐 내려가 있는 컷신. */
    private static CutsceneScreen current;
    /** 서버가 알려 준 프로토콜 버전 (0 = 아직 모름 / 예전 플러그인). */
    private static int serverProtocol = 0;
    private static boolean warnedOutdated = false;

    private CutscenePlayback() {}

    /** 컷신이 재생 중이거나, 메뉴 때문에 내려가 있거나, 재생을 기다리는 중. 이 동안 다른 스토리 화면을 띄우지 말 것. */
    public static boolean busy() {
        return deferred != null || (current != null && !current.isEnded());
    }

    /** 서버 플러그인이 보낸 프로토콜 버전. 서버가 더 새 버전이면 한 번 알린다. */
    public static void serverHello(int protocol) {
        serverProtocol = protocol;
        ChacademyCutsceneClient.LOGGER.info("서버 스토리 프로토콜 {} (이 모드 {})", protocol, CutscenePackets.PROTOCOL);
        if (protocol > CutscenePackets.PROTOCOL && !warnedOutdated) {
            warnedOutdated = true;
            chat("차카데미 스토리 모드가 서버보다 오래된 버전입니다. 모드를 업데이트해 주세요.");
        }
    }

    public static int serverProtocol() {
        return serverProtocol;
    }

    /** 컷신 재생 요청. 지금 보여 줄 수 없으면 보여 줄 수 있을 때 시작한다. */
    public static void play(String id, int mode, boolean fromServer) {
        Minecraft mc = Minecraft.getInstance();
        // 앞의 것은 조용히 치운다 (서버가 새로 보냈으면 서버도 이미 알고 있다)
        CutsceneScreen old = current;
        current = null;
        if (old != null) old.stop();
        deferred = null;
        Request r = new Request(id, mode, fromServer);
        if (canShow(mc)) start(mc, r);
        else deferred = r;
    }

    /** 서버가 멈추라고 할 때. 끝난 것으로 치지 않는다. */
    public static void stop() {
        deferred = null;
        CutsceneScreen s = current;
        current = null;
        if (s != null) s.stop();
    }

    /** 접속이 끊길 때: 전부 정리 (다시 접속하면 서버가 다시 보낸다). */
    public static void disconnect() {
        stop();
        serverProtocol = 0;
    }

    private static boolean canShow(Minecraft mc) {
        if (mc.player == null || !mc.player.isAlive()) return false;
        return mc.screen == null || mc.screen instanceof ChatScreen;
    }

    private static void start(Minecraft mc, Request r) {
        CutsceneScreen screen = new CutsceneScreen(r.id(), r.mode(), new CutsceneScreen.Listener() {
            @Override
            public void done(CutsceneScreen.Result result) {
                if (result.completed()) SeenStore.markSeen(result.id());
                if (r.fromServer()) send(new CutscenePackets.DoneC2S(result.id(), result.skipped()));
            }

            @Override
            public void aborted(String id) {
                if (r.fromServer()) send(new CutscenePackets.AbortC2S(id));
            }

            @Override
            public void failed(String id, String reason, String detail) {
                ChacademyCutsceneClient.LOGGER.warn("컷신 {} 을 보여 줄 수 없음 ({}): {}", id, reason, detail);
                chat(Component.translatable("chaca_story.not_found", id).getString() + " (" + detail + ")");
                if (!r.fromServer()) return;
                try {
                    if (!CutscenePackets.sendContentMissing(CutscenePackets.KIND_CUTSCENE, id, reason)) {
                        // 예전 서버 플러그인은 실패 신호를 모른다: 예전처럼 건너뛴 것으로 알린다
                        send(new CutscenePackets.DoneC2S(id, true));
                    }
                } catch (RuntimeException e) {
                    ChacademyCutsceneClient.LOGGER.warn("컷신 실패를 서버에 알리지 못함", e);
                }
            }
        });
        current = screen;
        mc.setScreen(screen);
    }

    private static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        try {
            if (ClientPlayNetworking.canSend(payload.type())) ClientPlayNetworking.send(payload);
        } catch (RuntimeException e) {
            // 접속이 끊기는 중 등
            ChacademyCutsceneClient.LOGGER.debug("스토리 패킷을 보내지 못함", e);
        }
    }

    /** 화면이 게임 메뉴를 여느라 내려갈 때 부른다. */
    static void suspend(CutsceneScreen screen) {
        current = screen;
    }

    /** 화면이 끝났을 때 (끝·중단·실패·멈춤). */
    static void forget(CutsceneScreen screen) {
        if (current == screen) current = null;
    }

    /** 매 틱: 내려가 있던 컷신을 다시 띄우고, 기다리던 재생 요청을 시작한다. */
    public static void tick(Minecraft mc) {
        CutsceneScreen s = current;
        if (s != null) {
            if (s.isEnded()) current = null;
            else if (s.isSuspended() && mc.screen == null) {
                if (mc.player == null) stop();
                else mc.setScreen(s);
            }
            return;
        }
        Request r = deferred;
        if (r != null && canShow(mc) && !(mc.screen instanceof ChatScreen)) {
            deferred = null;
            start(mc, r);
        }
    }

    private static void chat(String message) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.RED), false);
    }
}
