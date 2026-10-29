package kr.chacademi.dungeon;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import school.chacademia.dungeon.protocol.DungeonProtocol;

/** 서버 플러그인과 주고받는 바이트 (본문 = DungeonProtocol 형식 그대로, 플러그인 메시지). */
final class Net {
    private Net() {}

    record ToClient(byte[] data) implements CustomPacketPayload {
        static final Type<ToClient> TYPE = new Type<>(ResourceLocation.parse(DungeonProtocol.S2C));
        static final StreamCodec<FriendlyByteBuf, ToClient> CODEC = StreamCodec.of(
                (buf, v) -> buf.writeBytes(v.data()),
                buf -> {
                    int n = buf.readableBytes();
                    if (n > DungeonProtocol.MAX_BYTES) { buf.skipBytes(n); return new ToClient(new byte[0]); }
                    byte[] b = new byte[n];
                    buf.readBytes(b);
                    return new ToClient(b);
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    record ToServer(byte[] data) implements CustomPacketPayload {
        static final Type<ToServer> TYPE = new Type<>(ResourceLocation.parse(DungeonProtocol.C2S));
        static final StreamCodec<FriendlyByteBuf, ToServer> CODEC = StreamCodec.of(
                (buf, v) -> buf.writeBytes(v.data()),
                buf -> { byte[] b = new byte[Math.min(buf.readableBytes(), 64)]; buf.readBytes(b); buf.skipBytes(buf.readableBytes()); return new ToServer(b); });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void send(int kind, int session) {
        if (!ClientPlayNetworking.canSend(ToServer.TYPE)) return;
        ClientPlayNetworking.send(new ToServer(DungeonProtocol.encode(new DungeonProtocol.Action(kind, session))));
    }

    /** 서버가 보낸 아이템 바이트 (Paper serializeAsBytes = 압축 NBT) → ItemStack. 실패하면 빈 아이템(목록에서 빠짐). */
    static List<ItemStack> items(List<byte[]> raw) {
        var mc = Minecraft.getInstance();
        var out = new ArrayList<ItemStack>();
        if (mc.level == null) return out;
        for (byte[] b : raw) {
            try {
                var tag = NbtIo.readCompressed(new ByteArrayInputStream(b), NbtAccounter.create(1 << 20));
                ItemStack.parse(mc.level.registryAccess(), tag).filter(s -> !s.isEmpty()).ifPresent(out::add);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("보상 아이템을 읽지 못함: {}", e.toString());
            }
        }
        return out;
    }

    static String time(long ms) {
        if (ms < 0) return "—";
        long cs = ms / 10;
        return String.format("%02d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100);
    }
}
