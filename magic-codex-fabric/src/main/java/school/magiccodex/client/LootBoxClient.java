package school.magiccodex.client;

import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.LootBoxProtocol;
import school.magiccodex.protocol.LootBoxProtocol.*;

/**
 * 뽑기 상자 (API 상자) 클라 연결. PLAY 가 오면 연출 창({@link LootBoxScreen})을 연다 — 그때는 서버 지급이 이미 끝났다.
 * DONE 은 연출이 끝났다는 알림일 뿐 지급과 무관. 운영자 관리 창은 상점 관리 창(ShopAdminClient)과 같은 요청/응답 방식.
 */
public final class LootBoxClient {
    private LootBoxClient() {}
    private static long sequence, waiting, session, deadline;
    private static LootBoxAdminScreen owner;
    private interface Bytes { byte[] bytes(); }
    public record PlayPayload(byte[] bytes) implements CustomPayload, Bytes {
        public static final Id<PlayPayload> ID = new Id<>(Identifier.of(LootBoxProtocol.PLAY));
        public static final PacketCodec<RegistryByteBuf, PlayPayload> CODEC = codec(PlayPayload::new, LootBoxProtocol.PLAY_MAX_BYTES);
        public Id<PlayPayload> getId() { return ID; }
    }
    public record DonePayload(byte[] bytes) implements CustomPayload, Bytes {
        public static final Id<DonePayload> ID = new Id<>(Identifier.of(LootBoxProtocol.DONE));
        public static final PacketCodec<RegistryByteBuf, DonePayload> CODEC = codec(DonePayload::new, LootBoxProtocol.MAX_BYTES);
        public Id<DonePayload> getId() { return ID; }
    }
    public record Query(byte[] bytes) implements CustomPayload, Bytes {
        public static final Id<Query> ID = new Id<>(Identifier.of(LootBoxProtocol.ADMIN_REQUEST));
        public static final PacketCodec<RegistryByteBuf, Query> CODEC = codec(Query::new, LootBoxProtocol.MAX_BYTES);
        public Id<Query> getId() { return ID; }
    }
    public record Reply(byte[] bytes) implements CustomPayload, Bytes {
        public static final Id<Reply> ID = new Id<>(Identifier.of(LootBoxProtocol.ADMIN_RESPONSE));
        public static final PacketCodec<RegistryByteBuf, Reply> CODEC = codec(Reply::new, LootBoxProtocol.MAX_BYTES);
        public Id<Reply> getId() { return ID; }
    }

    private static <T extends Bytes> PacketCodec<RegistryByteBuf, T> codec(Function<byte[], T> make, int max) {
        return new PacketCodec<>() {
            public T decode(RegistryByteBuf b) { int n = b.readableBytes(); if (n < 4 || n > max) { b.skipBytes(n); return make.apply(new byte[0]); } byte[] bytes = new byte[n]; b.readBytes(bytes); return make.apply(bytes); }
            public void encode(RegistryByteBuf b, T v) { b.writeBytes(v.bytes()); }
        };
    }

    public static void initialize() {
        PayloadTypeRegistry.playS2C().register(PlayPayload.ID, PlayPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DonePayload.ID, DonePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(Query.ID, Query.CODEC);
        PayloadTypeRegistry.playS2C().register(Reply.ID, Reply.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(PlayPayload.ID, (payload, context) -> {
            Play play;
            try { play = LootBoxProtocol.play(payload.bytes()); } catch (IllegalArgumentException e) { return; }
            context.client().execute(() -> {
                if (context.client().player == null) return;
                MagicCodexClient.dismiss();
                context.client().setScreen(new LootBoxScreen(play));
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Reply.ID, (payload, context) -> {
            Response r;
            try { r = LootBoxProtocol.response(payload.bytes()); } catch (IllegalArgumentException e) { return; }
            context.client().execute(() -> {
                if (r.sequence() == 0) { waiting = 0; owner = null; MagicCodexClient.dismiss(); var s = new LootBoxAdminScreen(); context.client().setScreen(s); s.receive(r); return; }
                if (r.sequence() != waiting || r.session() != session) return;
                var s = owner; waiting = 0; owner = null;
                if (s != null && context.client().currentScreen == s) s.receive(r);
            });
        });
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (waiting != 0 && Util.getMeasuringTimeMs() > deadline) { waiting = 0; var s = owner; owner = null; if (s != null) s.failed("응답이 늦습니다. 새로고침으로 저장 결과를 확인하세요. 자동으로 다시 저장하지 않습니다."); }
        });
    }

    private static void reset() { sequence = waiting = session = deadline = 0; owner = null; }

    /** 연출이 끝났다는 알림 (지급과 무관, 보내지 못해도 괜찮다). */
    static void done(long opening) { if (ClientPlayNetworking.canSend(DonePayload.ID)) ClientPlayNetworking.send(new DonePayload(LootBoxProtocol.encode(new Done(opening)))); }

    static boolean request(LootBoxAdminScreen s, Request r) {
        if (waiting != 0 || !ClientPlayNetworking.canSend(Query.ID)) return false;
        long seq = ++sequence;
        if (r.action() != LootBoxProtocol.CLOSE) { waiting = seq; session = r.session(); owner = s; deadline = Util.getMeasuringTimeMs() + 15000; }
        ClientPlayNetworking.send(new Query(LootBoxProtocol.encode(new Request(r.action(), seq, r.session(), r.box(), r.revision(), r.index(), r.grade(), r.amount(), r.weight(), r.slot(), r.slotItem(), r.text(), r.offset()))));
        return true;
    }

    static void detach(LootBoxAdminScreen s) { if (owner == s) owner = null; }
}
