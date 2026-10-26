package school.magiccodex.client;

import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.client.kui.ApplicationView;
import school.magiccodex.client.kui.DashboardView.Tab;
import school.magiccodex.client.kui.Pen;
import school.magiccodex.protocol.KyudichiQueueProtocol;
import school.magiccodex.protocol.KyudichiQueueProtocol.*;

/**
 * 큐디치 대기열 (Velocity 프록시 ↔ 클라이언트, chacademi:kyudichi_queue).
 *  - 매칭 중: 오른쪽 위 "매칭 대기 중" 카드 (application/queue_panel, 상태 아이콘 아래). 취소는 Ctrl 커서·채팅창에서.
 *  - 경기를 찾으면 수락 팝업(KyudichiAcceptScreen)을 연다.
 *  - 서버가 부탁하지 않은 APPLY·DASH·RANKING·RECORD를 보내면(/큐디치, /큐디치 현황·랭킹·정보) 해당 창을 연다.
 *  - 결투장 로비 (2026-10-18): LOBBY(open) 이면 KyudichiLobbyScreen 을 연다. BOARD · RANKS · PROFILE 은 모드별로 보관.
 */
public final class KyudichiQueueClient {
    private KyudichiQueueClient() {}

    public record Payload(byte[] bytes) implements CustomPayload {
        public static final Id<Payload> ID = new Id<>(Identifier.of(KyudichiQueueProtocol.QUEUE));
        public static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) { int n = b.readableBytes(); if (n > KyudichiQueueProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); } byte[] d = new byte[n]; b.readBytes(d); return new Payload(d); }
            public void encode(RegistryByteBuf b, Payload v) { b.writeBytes(v.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    static Status status = new Status(0, 0, 0, 0, 0, false, 0, 0); static long statusAt;
    static Lobby lobby; static final Board[] board = new Board[2]; static final Ranks[] ranks = new Ranks[2]; static final ProfileReply[] profile = new ProfileReply[2];
    static long boardAt, ranksAt, profileAt, lobbyAt;
    static Dash dash; static Ranking ranking; static RecordReply record; static Apply apply;
    static boolean wantDash, wantRanking, wantRecord, wantApply;
    private static long offerSoundAt;
    private static List<Pen.Hit> panelHits = List.of();
    private static float panelX, panelY, panelS = 1;
    /** Card offset from its default place (GUI px), moved with Ctrl + drag, right click = reset. config/magiccodex/kyudichi-queue-card.txt */
    private static float offX, offY; private static boolean dragging, loaded; private static double grabX, grabY;
    private static final java.nio.file.Path CARD_FILE = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("magiccodex/kyudichi-queue-card.txt");

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        PayloadTypeRegistry.playC2S().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            ToClient m;
            try { m = KyudichiQueueProtocol.decodeToClient(payload.bytes()); } catch (IllegalArgumentException bad) { return; }
            context.client().execute(() -> { KyudichiPreview.realQueue(m); receive(m); });
        });
        // Tell the proxy this client draws the queue itself (no action-bar fallback). Sent again after every server switch.
        ClientPlayConnectionEvents.JOIN.register((h, sender, c) -> c.execute(() -> send(new ClientHello())));
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> { status = new Status(0, 0, 0, 0, 0, false, 0, 0); dash = null; ranking = null; record = null; apply = null; clearLobby(); });
        ClientTickEvents.END_CLIENT_TICK.register(KyudichiQueueClient::tick);
        HudLayerRegistrationCallback.EVENT.register(d -> d.attachLayerAfter(IdentifiedLayer.CROSSHAIR, Identifier.of("magiccodex", "kyudichi_queue"), KyudichiQueueClient::render));
    }

    static void send(FromClient m) {
        if (KyudichiPreview.active()) { KyudichiPreview.queueAction(m); return; }
        if (ClientPlayNetworking.canSend(Payload.ID)) ClientPlayNetworking.send(new Payload(KyudichiQueueProtocol.encode(m)));
    }
    static void requestDash() { wantDash = true; send(new DashRequest()); }
    static void requestRanking() { wantRanking = true; send(new RankingRequest()); }
    static void requestRecord(String nick) { wantRecord = true; send(new RecordRequest(nick == null ? "" : nick)); }
    static void requestApply() { wantApply = true; send(new ApplyRequest()); }
    static void clearLobby() { lobby = null; for (int i = 0; i < 2; i++) { board[i] = null; ranks[i] = null; profile[i] = null; } }

    static void receive(ToClient m) {
        var c = MinecraftClient.getInstance();
        switch (m) {
            case Status x -> {
                if (x.state() == KyudichiQueueProtocol.S_OFFER && status.state() != KyudichiQueueProtocol.S_OFFER && Util.getMeasuringTimeMs() - offerSoundAt > 3000) {
                    offerSoundAt = Util.getMeasuringTimeMs();
                    c.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.2f, 0.7f));
                }
                status = x; statusAt = Util.getMeasuringTimeMs();
            }
            case Dash x -> { dash = x; boolean asked = wantDash; wantDash = false; if (!asked) openDashboard(Tab.LIVE); }
            case Ranking x -> { ranking = x; boolean asked = wantRanking; wantRanking = false; if (!asked) openDashboard(Tab.RANKING); }
            case RecordReply x -> { record = x; boolean asked = wantRecord; wantRecord = false; if (!asked) openDashboard(Tab.RECORD); }
            case Lobby x -> {
                lobby = x; lobbyAt = Util.getMeasuringTimeMs();
                if (x.open()) {
                    if (c.currentScreen instanceof KyudichiLobbyScreen s) s.show(x.tab(), x.mode());
                    else if (c.currentScreen == null || HudPointer.hudVisibleUnder(c.currentScreen) || c.currentScreen instanceof KyudichiViewScreen)
                        c.setScreen(new KyudichiLobbyScreen(x.tab(), x.mode()));
                }
            }
            case Board x -> { board[Math.clamp(x.mode(), 0, 1)] = x; boardAt = Util.getMeasuringTimeMs(); }
            case Ranks x -> { ranks[Math.clamp(x.mode(), 0, 1)] = x; ranksAt = Util.getMeasuringTimeMs(); }
            case ProfileReply x -> { if (StatsRanks.take(x) || HousingShowcase.take(x)) return; profile[Math.clamp(x.mode(), 0, 1)] = x; profileAt = Util.getMeasuringTimeMs(); }
            case Apply x -> {
                apply = x; boolean asked = wantApply; wantApply = false;
                if (!asked && !(c.currentScreen instanceof KyudichiApplicationScreen) && (c.currentScreen == null || HudPointer.hudVisibleUnder(c.currentScreen)))
                    c.setScreen(new KyudichiApplicationScreen());
            }
        }
    }

    private static void openDashboard(Tab tab) {
        var c = MinecraftClient.getInstance();
        if (c.currentScreen instanceof KyudichiDashboardScreen d) { d.show(tab); return; }
        if (c.currentScreen == null || HudPointer.hudVisibleUnder(c.currentScreen)) c.setScreen(new KyudichiDashboardScreen(tab));
    }

    static int state() { return status.state(); }

    /** A found match opens the accept popup over the game (not over other windows the player is using). */
    private static void tick(MinecraftClient c) {
        if (state() == KyudichiQueueProtocol.S_OFFER && !status.iAccepted() && !(c.currentScreen instanceof KyudichiAcceptScreen)
                && (c.currentScreen == null || HudPointer.hudVisibleUnder(c.currentScreen) || c.currentScreen instanceof KyudichiApplicationScreen || c.currentScreen instanceof KyudichiLobbyScreen)
                && c.player != null)
            c.setScreen(new KyudichiAcceptScreen());
    }

    // ================================================================== 매칭 대기 중 card (top right)
    private static HudTextureCache images; private static CodexTypography text; private static int generation = -1;

    private static void render(DrawContext ctx, RenderTickCounter ticks) {
        panelHits = List.of();
        var c = MinecraftClient.getInstance();
        if (state() != KyudichiQueueProtocol.S_SEARCHING && state() != KyudichiQueueProtocol.S_MOVING) return;
        if (c.player == null || c.options.hudHidden || !HudPointer.hudVisibleUnder(c.currentScreen)) return;
        if (KyudichiClient.active() && !KyudichiClient.spectator()) return;
        if (generation != UiResources.generation()) { if (images != null) images.close(); if (text != null) text.close(); images = null; text = null; generation = UiResources.generation(); }
        if (images == null) images = new HudTextureCache(c);
        if (text == null) text = new CodexTypography(c);
        images.beginFrame(); text.beginFrame();
        float w = c.getWindow().getScaledWidth(), h = c.getWindow().getScaledHeight(), s = KyudichiHud.scale(w, h) * .8f;
        // layout card at x 1307..1651, y 35..348 of the 1672 canvas: keep its right margin, start under the status effects
        if (!loaded) loadCard();
        if (dragging) {
            if (HudPointer.active()) { offX = (float) (HudPointer.x() - grabX); offY = (float) (HudPointer.y() - grabY); }
            else { dragging = false; saveCard(); }
        }
        float baseX = w - 1672 * s, baseY = KyudichiHud.rightTop() + (KyudichiClient.active() ? 200 * s : 0) - 35 * s;
        // keep the whole card (1307..1651 × 35..348 of the layout) on screen
        offX = Math.clamp(offX, -(baseX + 1307 * s), w - (baseX + 1651 * s));
        offY = Math.clamp(offY, -(baseY + 35 * s), h - (baseY + 348 * s));
        panelX = baseX + offX; panelY = baseY + offY; panelS = s;
        try(var customization=HudCustomization.begin(ctx,"queue",panelX+1307*s,panelY+35*s,344*s,313*s)){
        var canvas = new KyudichiCanvas(ctx, images, text);
        try {
            ctx.getMatrices().push(); ctx.getMatrices().translate(0, 0, 150);
            canvas.push(panelX, panelY, s);
            float mx = (float) ((HudPointer.x() - panelX) / s), my = (float) ((HudPointer.y() - panelY) / s);
            var pen = new Pen(canvas, HudPointer.active() ? mx : -1, HudPointer.active() ? my : -1, null);
            int waited = status.waitedSec() + (int) ((Util.getMeasuringTimeMs() - statusAt) / 1000);
            ApplicationView.queue(pen, new ApplicationView.Queue(waited, state() == KyudichiQueueProtocol.S_MOVING ? "이동 중" : ApplicationView.range(waited), false), Util.getMeasuringTimeMs());
            canvas.pop();
            panelHits = pen.hits();
        } finally { ctx.getMatrices().pop(); images.endFrame(); }

        }
}

    /** Ctrl cursor / chat click on the card: 취소, drag to move, right click = back to the default place. */
    public static boolean click(double x, double y, int button) {
        if(HudCustomization.editingGesture())return false;var hudPoint=HudCustomization.pointer("queue",x,y);x=hudPoint[0];y=hudPoint[1];
        if (panelHits.isEmpty()) return false;
        float dx = (float) ((x - panelX) / panelS), dy = (float) ((y - panelY) / panelS);
        boolean onCard = dx >= 1307 && dx <= 1651 && dy >= 35 && dy <= 348;
        if (!onCard) return false;
        if (button == 1) return false;
        if (button != 0) return false;
        for (var hit : panelHits) if (hit.contains(dx, dy) && hit.id().equals(ApplicationView.CANCEL)) { send(new Leave()); return true; }
        // Position/size editing is exclusively managed by HudCustomization.
        return true;
    }
    public static boolean release(double x, double y, int button) {
        if (!dragging || button != 0) return false;
        dragging = false; saveCard(); return true;
    }
    static void cursorClosed() { if (dragging) { dragging = false; saveCard(); } }
    private static void loadCard() {
        loaded = true;
        try { var t = java.nio.file.Files.readString(CARD_FILE).trim().split("\\s+"); offX = Float.parseFloat(t[0]); offY = Float.parseFloat(t[1]); }
        catch (Exception missing) { offX = offY = 0; }
    }
    private static void saveCard() {
        try { java.nio.file.Files.createDirectories(CARD_FILE.getParent()); java.nio.file.Files.writeString(CARD_FILE, offX + " " + offY); }
        catch (java.io.IOException ignored) { }
    }
}
