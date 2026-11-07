package kr.kkaegul.client.mc;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

/** 서버 플러그인 메시지(UTF-8 JSON 그대로, 길이 접두사 없음) */
public final class Payloads {
    private Payloads() {}
    static final int MAX = 1 << 20;

    private static byte[] read(RegistryByteBuf b) {
        int n = b.readableBytes();
        if (n > MAX) { b.skipBytes(n); return new byte[0]; }
        byte[] v = new byte[n];
        b.readBytes(v);
        return v;
    }

    /** 서버 → 클라 kkaegul:ui */
    public record Ui(byte[] bytes) implements CustomPayload {
        public static final Id<Ui> ID = new Id<>(Identifier.of("kkaegul", "ui"));
        public static final PacketCodec<RegistryByteBuf, Ui> CODEC = new PacketCodec<>() {
            public Ui decode(RegistryByteBuf b) { return new Ui(read(b)); }
            public void encode(RegistryByteBuf b, Ui p) { b.writeBytes(p.bytes); }
        };
        public String json() { return new String(bytes, StandardCharsets.UTF_8); }
        @Override public Id<Ui> getId() { return ID; }
    }

    /** 클라 → 서버 kkaegul:action */
    public record Action(byte[] bytes) implements CustomPayload {
        public static final Id<Action> ID = new Id<>(Identifier.of("kkaegul", "action"));
        public static final PacketCodec<RegistryByteBuf, Action> CODEC = new PacketCodec<>() {
            public Action decode(RegistryByteBuf b) { return new Action(read(b)); }
            public void encode(RegistryByteBuf b, Action p) { b.writeBytes(p.bytes); }
        };
        public static Action of(String json) { return new Action(json.getBytes(StandardCharsets.UTF_8)); }
        @Override public Id<Action> getId() { return ID; }
    }
}
