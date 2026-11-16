package com.diamondserver.client;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

/** 서버 플러그인(DiamondCore)과 주고받는 통신. 채널 diamond:main, 내용은 JSON 글자 그대로 */
public record JsonPayload(String json) implements CustomPayload {
    public static final CustomPayload.Id<JsonPayload> ID = new CustomPayload.Id<>(Identifier.of("diamond", "main"));
    public static final PacketCodec<PacketByteBuf, JsonPayload> CODEC = PacketCodec.of(JsonPayload::write, JsonPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeBytes(json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonPayload read(PacketByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        return new JsonPayload(new String(b, StandardCharsets.UTF_8));
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
