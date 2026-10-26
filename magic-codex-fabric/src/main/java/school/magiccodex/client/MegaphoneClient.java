package school.magiccodex.client;

import static school.magiccodex.client.MegaphoneLayout.*;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.MegaphoneProtocol;

/**
 * 확성기 (메모 #102): 입력 창 열기·답하기, 화면 위 공지 (받은 순서대로 하나씩), Ctrl 커서로 아이템 툴팁.
 *
 * <p>Ctrl 은 새 키가 아니라 기존 키 설정 "마우스 포인터"(UiKeys CURSOR, 기본 왼쪽 Ctrl)를 그대로 쓴다: 누르고 있는 동안
 * HudCursorScreen 이 열려 커서가 나오고, 떼면 닫혀 커서가 다시 잠긴다 (PlayerHudClient). 공지 아이콘 위에 커서가 오면 툴팁.
 * 공지가 사라지면 툴팁도 사라진다. 커서 자체는 다른 HUD(파티·알림 카드)도 쓰는 공용이라 Ctrl 을 떼야 닫힌다.
 *
 * <p>화면 배치·자산은 UI v2 납품 패키지(MegaphoneLayout). 공지는 받은 순서대로 하나씩, 화면이 보일 때(다른 창이 덮지 않을 때)
 * 꺼내 띄우고 그 시각 t0 부터 remaining = clamp(1 - (now - t0) / 표시 시간, 0, 1) 로 타이머 폭을 정한다 (프레임 수를 세지 않음).
 * 표시 시간이 지나면 카드·아이템·힌트·툴팁을 함께 숨긴다. 대기열 최대 {@link #QUEUE_MAX}개, 넘치면 가장 오래 기다린 것부터 버린다.
 */
public final class MegaphoneClient {
    private MegaphoneClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(MegaphoneProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new, MegaphoneProtocol.MAX_REQUEST_BYTES);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(MegaphoneProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new, MegaphoneProtocol.MAX_BYTES);
        public Id<Response> getId() { return ID; }
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf, T> codec(java.util.function.Function<byte[], T> f, int max) {
        return new PacketCodec<>() {
            public T decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > max) { b.skipBytes(n); return f.apply(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return f.apply(a);
            }
            public void encode(RegistryByteBuf b, T value) { b.writeBytes(value.bytes()); }
        };
    }

    /** 화면에 띄울 공지 하나 (아이템은 받을 때 한 번 풀어 둔다). */
    record Notice(String id, String sender, String text, long duration, ItemStack item) {}

    static final int QUEUE_MAX = 30;
    private static final ArrayDeque<Notice> QUEUE = new ArrayDeque<>();
    private static Notice current;
    /** 지금 공지를 띄우기 시작한 시각 t0 (Util.getMeasuringTimeMs). */
    private static long shownAt;
    /** 지금 공지의 한 줄 맞춘 닉네임·메시지 (공지마다 한 번 계산). */
    private static MegaphoneLayout.Line name, message;
    private static HudTextureCache images;
    private static CodexTypography text;
    private static int generation = -1;

    static void initialize() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID, (payload, context) -> {
            MegaphoneProtocol.Frame frame;
            try { frame = MegaphoneProtocol.frame(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            var connection = context.client().getNetworkHandler();
            context.client().execute(() -> { if (connection == context.client().getNetworkHandler()) receive(context.client(), frame); });
        });
        HudRenderCallback.EVENT.register((ctx, ticks) -> render(ctx));
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(() -> {
            reset();
            if (c.currentScreen instanceof MegaphoneScreen screen) screen.serverClosed();
        }));
    }

    private static void receive(MinecraftClient c, MegaphoneProtocol.Frame f) {
        switch (f.kind()) {
            case MegaphoneProtocol.OPEN -> {
                if (c.player == null) return;
                var o = f.open();
                DeferredScreens.open(() -> new MegaphoneScreen(o.token(), o.maxChars()));
            }
            case MegaphoneProtocol.CLOSE -> { if (c.currentScreen instanceof MegaphoneScreen s && s.token() == f.closeToken()) s.serverClosed(); }
            case MegaphoneProtocol.SHOW -> {
                var s = f.show();
                if (QUEUE.stream().anyMatch(n -> n.id().equals(s.id())) || current != null && current.id().equals(s.id())) return;
                while (QUEUE.size() >= QUEUE_MAX) QUEUE.pollFirst();
                QUEUE.addLast(new Notice(s.id(), s.sender(), s.text(), s.durationMs(), decode(c, s.item())));
            }
            default -> {}
        }
    }

    /** 상점 미리보기와 같은 방식 (Paper serializeAsBytes = 압축 NBT). 실패하면 아이템 없음. */
    private static ItemStack decode(MinecraftClient c, byte[] b) {
        if (b.length == 0 || c.world == null) return ItemStack.EMPTY;
        try {
            return ItemStack.fromNbt(c.world.getRegistryManager(), NbtIo.readCompressed(new ByteArrayInputStream(b), NbtSizeTracker.of(262144))).orElse(ItemStack.EMPTY);
        } catch (Exception e) { return ItemStack.EMPTY; }
    }

    /** 입력 창 답 (보내기 = proceed). 연결이 없으면 false. */
    static boolean answer(long token, String value, boolean proceed) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        try {
            ClientPlayNetworking.send(new Request(MegaphoneProtocol.request(new MegaphoneProtocol.Request(
                    proceed ? MegaphoneProtocol.SEND : MegaphoneProtocol.CANCEL, token, proceed ? value : ""))));
            return true;
        } catch (IllegalArgumentException e) { return false; }
    }

    private static void reset() { QUEUE.clear(); current = null; shownAt = 0; name = message = null; close(); }
    private static void close() { if (text != null) { text.close(); text = null; } if (images != null) { images.close(); images = null; } generation = -1; }

    private static boolean visible(MinecraftClient c) {
        return c.player != null && c.world != null && !c.options.hudHidden && c.getOverlay() == null && HudPointer.hudVisibleUnder(c.currentScreen);
    }

    // ------------------------------------------------------------------ 그리기 (UI v2: MegaphoneLayout 좌표 그대로)

    private static void render(DrawContext ctx) {
        var c = MinecraftClient.getInstance();
        long now = Util.getMeasuringTimeMs();
        if (current == null && QUEUE.isEmpty()) return;
        if (!visible(c)) return;                       // 다른 창이 덮고 있으면 다음 공지를 꺼내지 않는다
        if (current == null) {
            current = QUEUE.pollFirst();
            shownAt = now; name = message = null;
            NotificationPresentation.playSound(c);
        }
        long elapsed = now - shownAt;
        if (elapsed >= current.duration()) { current = null; name = message = null; return; }   // 카드·아이템·힌트·툴팁 함께 숨김
        if (generation != UiResources.generation()) { close(); generation = UiResources.generation(); name = message = null; }
        if (text == null) text = new CodexTypography(c);
        if (images == null) images = new HudTextureCache(c);
        if (name == null || message == null) {
            var t = text;
            name = MegaphoneLayout.fit(current.sender().isBlank() ? "?" : current.sender(), NICKNAME_SIZE, NICKNAME_MIN_SIZE, NICKNAME.w(),
                    (s, size) -> t.width(s, size, NICKNAME_FACE));
            message = MegaphoneLayout.fit(current.text(), MESSAGE_SIZE, MESSAGE_MIN_SIZE, MESSAGE.w(), (s, size) -> t.width(s, size, MESSAGE_FACE));
        }
        boolean hasItem = !current.item().isEmpty();
        var window = c.getWindow();
        var f = MegaphoneLayout.canvas(window.getScaledWidth(), window.getScaledHeight());
        var m = ctx.getMatrices();
        text.beginFrame(); images.beginFrame();
        m.push();
        try {
            m.translate(f.x(), f.y(), 350);
            m.scale(f.scale(), f.scale(), 1);
            nine(ctx, NOTICE_CARD, 640, 110, CARD_CORNER, CARD);
            int tx = Math.round(TIMER.x()), ty = Math.round(TIMER.y()), th = Math.round(TIMER.h());
            images.drawStretched(ctx, TIMER_TRACK, tx, ty, Math.round(TIMER.w()), th, 0xFFFFFFFF);
            int fill = MegaphoneLayout.timerWidth(elapsed, current.duration());
            images.drawPart(ctx, TIMER_FILL, tx, ty, fill, th, 0, 0, fill, th, 0xFFFFFFFF);   // 늘이지 않고 왼쪽부터 잘라 그림
            text.draw(ctx, name.text(), NICKNAME.centerX(), textY(NICKNAME, NICKNAME_SIZE), name.size(), TEXT_COLOR, NICKNAME_FACE, true);
            text.draw(ctx, message.text(), MESSAGE.centerX(), textY(MESSAGE, MESSAGE_SIZE), message.size(), TEXT_COLOR, MESSAGE_FACE, true);
            if (hasItem) {
                nine(ctx, ITEM_BACKING, 96, 96, ITEM_BACKING_CORNER, ITEM_BACKING_RECT);
                drawIcon(ctx, current.item());
                String key = UiKeys.cursorName();
                if (!key.isEmpty()) text.draw(ctx, key + HINT_SUFFIX, HINT.x(), textY(HINT, HINT_SIZE), HINT_SIZE, TEXT_COLOR, HINT_FACE, false);
            }
        } finally { m.pop(); images.endFrame(); }
        // Ctrl("마우스 포인터" 키)로 꺼낸 커서가 아이템 배경 위에 있으면 게임 아이템 툴팁: 카드 아래 (960, 282) 에서 아래로, 화면 안으로 보정
        if (hasItem && HudPointer.active()
                && ITEM_BACKING_RECT.contains(f.localX(HudPointer.x()), f.localY(HudPointer.y()))) {
            int cx = Math.round(f.x() + TOOLTIP_X * f.scale()), top = Math.round(f.y() + TOOLTIP_Y * f.scale());
            int margin = Math.round(TOOLTIP_MARGIN * f.scale());
            if (!ItemTooltipRenderer.renderBelow(ctx, c.textRenderer, current.item(), cx, top, margin))
                ctx.drawItemTooltip(c.textRenderer, current.item(), cx - 12, top + 12);   // 바닐라 툴팁 (가방 등): 왼쪽 위가 (cx, top) 근처
        }
    }

    private static void nine(DrawContext ctx, Identifier id, int sw, int sh, int corner, MegaphoneLayout.Rect r) {
        NotificationSkin.nine(images, ctx, id, sw, sh, corner, r.x(), r.y(), r.w(), r.h(), 1f, 0xFFFFFFFF);
    }

    /** 실제 아이템 아이콘 80px: 큰 아이템 그림은 그 크기로 새로 그리고(ExactItemIcons), 아니면 GUI 아이템 렌더를 그 크기로 (GalaxyBagScreen 과 같은 방식). */
    private static void drawIcon(DrawContext ctx, ItemStack stack) {
        var m = ctx.getMatrices();
        m.push();
        try {
            if (!ExactItemIcons.draw(ctx, stack, ICON.x(), ICON.y(), ICON.w())) {
                m.translate(ICON.x(), ICON.y(), 0);
                m.scale(ICON.w() / 16f, ICON.h() / 16f, 1);
                ctx.drawItem(stack, 0, 0);
            }
        } finally { m.pop(); }
    }
}
