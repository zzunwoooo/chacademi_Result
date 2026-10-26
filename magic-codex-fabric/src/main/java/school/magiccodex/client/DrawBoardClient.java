package school.magiccodex.client;

import java.io.ByteArrayInputStream;
import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.DrawBoardProtocol;
import school.magiccodex.protocol.DrawBoardProtocol.*;

/**
 * 운영자 전용 「행운의 뽑기판」 (§44) 클라 연결. 판 상태는 서버(공유 DB)가 주고, 여기서는 받아서 창에 넘기기만 한다.
 * 등록 아이템 그림(Catalog)은 목록 버전(itemsRevision)별로 한 번만 받아 두고, 그릴 때 ItemStack 으로 바꿔 캐시한다.
 */
public final class DrawBoardClient {
    private DrawBoardClient() {}

    private interface Bytes { byte[] bytes(); }
    public record StatePayload(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<StatePayload> ID = new Id<>(Identifier.of(DrawBoardProtocol.STATE));
        static final PacketCodec<RegistryByteBuf, StatePayload> CODEC = codec(StatePayload::new);
        public Id<StatePayload> getId() { return ID; }
    }
    public record ActionPayload(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<ActionPayload> ID = new Id<>(Identifier.of(DrawBoardProtocol.ACTION));
        static final PacketCodec<RegistryByteBuf, ActionPayload> CODEC = codec(ActionPayload::new);
        public Id<ActionPayload> getId() { return ID; }
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf, T> codec(java.util.function.Function<byte[], T> make) {
        return new PacketCodec<>() {
            public T decode(RegistryByteBuf b) { int n = b.readableBytes(); if (n < 4 || n > DrawBoardProtocol.MAX_BYTES) { b.skipBytes(n); return make.apply(new byte[0]); } byte[] a = new byte[n]; b.readBytes(a); return make.apply(a); }
            public void encode(RegistryByteBuf b, T v) { b.writeBytes(v.bytes()); }
        };
    }

    /** 뽑기판 창과 관리 창이 같이 쓰는 받는 쪽. */
    interface Viewer { long session(); void receive(State state); }

    private static long sequence;
    private static long catalogRevision = Long.MIN_VALUE;
    private static String[] ids = new String[0];
    private static byte[][] previews = new byte[0][];
    private static final Map<Integer, ItemStack> decoded = new HashMap<>();

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(StatePayload.ID, StatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ActionPayload.ID, ActionPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(StatePayload.ID, (payload, context) -> {
            int kind = DrawBoardProtocol.kind(payload.bytes());
            if (kind == DrawBoardProtocol.KIND_CATALOG) {
                Catalog c;
                try { c = DrawBoardProtocol.catalog(payload.bytes()); } catch (IllegalArgumentException e) { return; }
                context.client().execute(() -> catalog(c));
            } else if (kind == DrawBoardProtocol.KIND_STATE) {
                State s;
                try { s = DrawBoardProtocol.state(payload.bytes()); } catch (IllegalArgumentException e) { return; }
                context.client().execute(() -> state(context.client(), s));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(DrawBoardClient::reset));
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> c.execute(DrawBoardClient::reset));
    }

    private static void reset() { sequence = 0; catalogRevision = Long.MIN_VALUE; ids = new String[0]; previews = new byte[0][]; decoded.clear(); }

    private static void catalog(Catalog c) {
        if (c.itemsRevision() != catalogRevision || ids.length != c.total()) {
            catalogRevision = c.itemsRevision();
            ids = new String[c.total()]; previews = new byte[c.total()][]; decoded.clear();
        }
        for (int i = 0; i < c.entries().size(); i++) {
            int at = c.start() + i;
            if (at >= ids.length) break;
            ids[at] = c.entries().get(i).id(); previews[at] = c.entries().get(i).preview(); decoded.remove(at);
        }
    }

    private static void state(MinecraftClient client, State s) {
        if (client.player == null) return;
        if (s.screen() == DrawBoardProtocol.SCREEN_BOARD || s.screen() == DrawBoardProtocol.SCREEN_ADMIN) {
            MagicCodexClient.dismiss();
            var screen = s.screen() == DrawBoardProtocol.SCREEN_BOARD ? new DrawBoardScreen(s.session()) : new DrawBoardAdminScreen(s.session());
            client.setScreen(screen);
            ((Viewer) screen).receive(s);
            return;
        }
        if (client.currentScreen instanceof Viewer v && v.session() == s.session()) v.receive(s);
    }

    /** 요청 보내기. 보낸 요청 번호(답장 짝), 못 보내면 0. */
    static long send(long session, int action, int round, int cell, boolean reveal, int slot, String slotItem, String id) {
        if (!ClientPlayNetworking.canSend(ActionPayload.ID)) return 0;
        long seq = ++sequence;
        try { ClientPlayNetworking.send(new ActionPayload(DrawBoardProtocol.encode(new Action(action, session, seq, round, cell, reveal, slot, slotItem, id)))); }
        catch (IllegalArgumentException e) { return 0; }
        return seq;
    }

    static void close(long session) { send(session, DrawBoardProtocol.CLOSE, 0, -1, false, -1, "", ""); }

    /** 받은 목록이 이 버전인지 (그림이 다 왔는지는 {@link #item} 이 비었는지로). */
    static boolean catalogFor(long itemsRevision) { return catalogRevision == itemsRevision; }
    static int catalogSize() { return ids.length; }
    static String id(int index) { return index >= 0 && index < ids.length ? ids[index] : null; }

    /** 목록 index 번 아이템 (아직 안 왔거나 못 읽으면 EMPTY). */
    static ItemStack item(long itemsRevision, int index) {
        if (catalogRevision != itemsRevision || index < 0 || index >= previews.length || previews[index] == null) return ItemStack.EMPTY;
        var hit = decoded.get(index);
        if (hit != null) return hit;
        var client = MinecraftClient.getInstance();
        if (client.world == null) return ItemStack.EMPTY;
        ItemStack stack = ItemStack.EMPTY;
        byte[] b = previews[index];
        if (b.length > 0) {
            try { stack = ItemStack.fromNbt(client.world.getRegistryManager(), NbtIo.readCompressed(new ByteArrayInputStream(b), NbtSizeTracker.of(2097152))).orElse(ItemStack.EMPTY); }
            catch (Exception e) { stack = ItemStack.EMPTY; }
        }
        decoded.put(index, stack);
        return stack;
    }
}
