package school.magiccodex.client;

import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;
import org.slf4j.LoggerFactory;
import school.magiccodex.protocol.PermissionProtocol;

/** No polling permission lists: OPEN once, tiny lease keepalive while visible, CLOSE on exit. */
public final class PermissionClient {
    private PermissionClient() {}
    private static boolean confirmed;
    private static long nextAttempt, nextKeepAlive;
    private static String status = "서버 권한 미확인";
    public static String status() { return status; }
    public static boolean confirmed() { return confirmed; }
    public static boolean catalogConfirmed() {
        var view=CodexCatalog.permissions();
        return confirmed && view.active()!=null && view.active().permissions().equals(view.requestable(view.allPermissions()));
    }
    public static boolean bridgeAvailable() { return ClientPlayNetworking.canSend(PermissionPayloads.Request.ID); }
    public static void initialize() {
        PayloadTypeRegistry.playC2S().register(PermissionPayloads.Request.ID, PermissionPayloads.Request.CODEC);
        PayloadTypeRegistry.playS2C().register(PermissionPayloads.Response.ID, PermissionPayloads.Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(PermissionPayloads.Response.ID, (payload, context) -> {
            // Fabric's object payload handler runs on the render/client thread.
            try {
                if (CodexCatalog.permissions().apply(PermissionProtocol.decodeResponse(payload.bytes()))) {
                    confirmed = true; status = ""; CodexCatalog.refreshScreen();
                }
            } catch (Exception error) { LoggerFactory.getLogger("magiccodex").warn("잘못된 권한 응답: {}", error.getMessage()); }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }
    public static void reset() {
        CodexCatalog.permissions().clear();
        confirmed = false; nextAttempt = nextKeepAlive = 0;
        status = "서버 권한 미확인";
        CodexCatalog.refreshScreen();
    }
    public static void close() {
        var active = CodexCatalog.permissions().active();
        if (active != null && ClientPlayNetworking.canSend(PermissionPayloads.Request.ID))
            send(new PermissionProtocol.Request(PermissionProtocol.CLOSE, active.id(), List.of()));
        CodexCatalog.permissions().clear(); confirmed = false; status = "서버 권한 미확인";
        CodexCatalog.refreshScreen();
    }
    public static void tick(MinecraftClient client) {
        boolean inWorld=client.world!=null && client.player!=null;
        boolean catalogScreen=client.currentScreen instanceof CodexScreen screen && screen.isCatalogBacked()
                || client.currentScreen instanceof KeySettingsScreen keys && keys.isCatalogBacked()
                || client.currentScreen instanceof StatsScreen stats && stats.isLive()
                // 큐디치 / 1:1 마법 세팅: the list shows every learned spell, so the whole catalog needs permission results
                || client.currentScreen instanceof KyudichiSpellPickScreen;
        var view = CodexCatalog.permissions();
        List<String> wanted=catalogScreen?view.allPermissions():CastingClient.enabled()?CastingClient.permissions():List.of();
        // Same filtering/size cap as the OPEN request, so an oversized list cannot reopen every tick.
        List<String> desired=view.requestable(wanted);
        boolean needed=inWorld && (catalogScreen || !wanted.isEmpty());
        if (!needed) { if (view.active() != null) close(); return; }
        if(view.active()!=null && !view.active().permissions().equals(desired)) close();
        if (!ClientPlayNetworking.canSend(PermissionPayloads.Request.ID)) {
            if (view.active() != null) { view.clear(); confirmed = false; CodexCatalog.refreshScreen(); }
            status = "권한 연동 플러그인 연결 필요";
            return;
        }
        long now = Util.getMeasuringTimeMs();
        if (view.active() == null) {
            status = "서버 권한 확인 중";
            if (now < nextAttempt) return;
            send(view.open(desired));
            confirmed = false;
            nextAttempt = now + 1500;
            nextKeepAlive = now + 15000;
            CodexCatalog.refreshScreen();
        } else if (!confirmed && now >= nextAttempt) {
            // Recover a rate-limited open, delayed channel registration, or plugin restart.
            send(view.active()); nextAttempt = now + 5000;
        } else if (confirmed && now >= nextKeepAlive) {
            send(new PermissionProtocol.Request(PermissionProtocol.KEEPALIVE, view.active().id(), List.of()));
            nextKeepAlive = now + 15000;
        }
    }
    private static void send(PermissionProtocol.Request request) {
        byte[] bytes;
        try { bytes = PermissionProtocol.encodeRequest(request); }
        catch (IllegalArgumentException error) {
            // Never throw from the client tick; the request is simply not sent.
            status = "권한 요청이 너무 큽니다";
            LoggerFactory.getLogger("magiccodex").warn("권한 요청을 보낼 수 없습니다: {}", error.getMessage());
            return;
        }
        ClientPlayNetworking.send(new PermissionPayloads.Request(bytes));
    }
}
