package school.magiccodex.client;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * "누르고 있기 충전" 마법 (2026-10-25, 338 화염 투창). In the aim preview a left PRESS starts charging (at most
 * {@link #MAX_MS}), the RELEASE (or the full charge) reports {@code <spell id>;<charge 0..1>} (UTF-8) on
 * chacademia:spell_charge right before the cast (magic-mythic-paper ChargeInput). Servers without the channel: the
 * spell keeps the plain click-to-cast.
 */
final class SpellChargeClient {
    static final String CHANNEL = "chacademia:spell_charge";
    /** Spells that charge while left click is held. */
    static final Set<String> SPELLS = Set.of("flame_javelin");
    static final long MAX_MS = 1500;

    private SpellChargeClient() {}

    record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) { int size = b.readableBytes(); byte[] data = new byte[Math.min(size, 128)]; b.readBytes(data); b.skipBytes(b.readableBytes()); return new Payload(data); }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    private static String spell;
    private static long startedAt;

    static void initialize() { PayloadTypeRegistry.playC2S().register(Payload.ID, Payload.CODEC); }

    /** This spell charges and the server takes the report. */
    static boolean supported(String id) {
        return id != null && SPELLS.contains(id) && ClientPlayNetworking.canSend(Payload.ID);
    }

    static void begin(String id) { spell = id; startedAt = Util.getMeasuringTimeMs(); }
    static void reset() { spell = null; }
    static boolean charging() { return spell != null; }
    static String spell() { return spell; }

    /** 0..1 of the full charge (1.5 s). */
    static float progress() {
        if (spell == null) return 0;
        return Math.clamp((Util.getMeasuringTimeMs() - startedAt) / (float) MAX_MS, 0f, 1f);
    }

    /** Sends the charge right before the cast packet. Silently skipped when the server has no channel. */
    static void send(String id, double charge) {
        if (id == null || !ClientPlayNetworking.canSend(Payload.ID)) return;
        String text = id + ";" + String.format(Locale.ROOT, "%.3f", Math.clamp(charge, 0, 1));
        ClientPlayNetworking.send(new Payload(text.getBytes(StandardCharsets.UTF_8)));
    }
}
