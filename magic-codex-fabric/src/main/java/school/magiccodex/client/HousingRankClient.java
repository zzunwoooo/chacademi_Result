package school.magiccodex.client;

import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import school.magiccodex.protocol.HousingRankProtocol;
import school.magiccodex.protocol.HousingRankProtocol.*;

/** 하우징 랭킹 채널 (#51): 서버가 OPEN 을 보내면 창을 열고, 창이 탭·쪽을 물어본다. 늦게 온 옛 응답은 generation 으로 버린다. */
final class HousingRankClient {
    record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(HousingRankProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) { int n = b.readableBytes(); if (n > HousingRankProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); } var a = new byte[n]; b.readBytes(a); return new Payload(a); }
            public void encode(RegistryByteBuf b, Payload p) { b.writeBytes(p.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    static long generation, sentAt;
    static Page page;
    static boolean loading;
    static String notice = "";
    static long noticeUntil;

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        PayloadTypeRegistry.playC2S().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            ToClient m;
            try { m = HousingRankProtocol.toClient(payload.bytes()); } catch (IllegalArgumentException bad) { return; }
            context.client().execute(() -> receive(context.client(), m));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> c.execute(() -> { page = null; loading = false; generation++; }));
    }

    private static void receive(MinecraftClient mc, ToClient m) {
        switch (m) {
            case Open o -> mc.setScreen(new HousingRankScreen(o.metric()));
            case Page p -> { if (p.generation() != generation) return; page = p; loading = false; }
            case Notice n -> { notice = n.text(); noticeUntil = Util.getMeasuringTimeMs() + 4000; }
        }
    }

    static void query(int metric, int number) {
        generation++; loading = true; sentAt = Util.getMeasuringTimeMs();
        if (MinecraftClient.getInstance().getNetworkHandler() == null || !ClientPlayNetworking.canSend(Payload.ID)) {
            loading = false; page = new Page(generation, metric, 0, 1, java.util.List.of(), "하우징 랭킹 서버 업데이트가 필요해요."); return;
        }
        ClientPlayNetworking.send(new Payload(HousingRankProtocol.encode(new Query(generation, metric, number))));
    }

    static void visit(UUID owner) {
        if (ClientPlayNetworking.canSend(Payload.ID)) ClientPlayNetworking.send(new Payload(HousingRankProtocol.encode(new Visit(owner))));
    }

    /** 6초 안에 답이 없으면 안내. */
    static void tick() {
        if (loading && Util.getMeasuringTimeMs() - sentAt > 6000) {
            loading = false; generation++;
            int metric = page == null ? 0 : page.metric();
            page = new Page(generation, metric, 0, 1, java.util.List.of(), "응답이 늦어요. 탭을 다시 눌러 주세요.");
        }
    }

    private HousingRankClient() {}
}
