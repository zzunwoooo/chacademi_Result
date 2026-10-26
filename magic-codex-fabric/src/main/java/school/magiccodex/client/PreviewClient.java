package school.magiccodex.client;

import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.PreviewProtocol;

/**
 * 서버 → 클라이언트 미리보기 (2026-10-20 명령어 정리). 서버 /테스트 … 가 {@link PreviewProtocol#CHANNEL}로
 * "종류 값…"을 보내면 예전 클라이언트 미리보기 명령(/큐디치미리보기, /대상체력바미리보기, /hud season·temperature,
 * /마법효과음테스트, /discoverypreview, /ascensionpreview)이 하던 일을 그대로 띄운다. 모르는 값은 조용히 무시.
 */
public final class PreviewClient {
    private PreviewClient() {}

    public record Payload(byte[] bytes) implements CustomPayload {
        static final Id<Payload> ID = new Id<>(Identifier.of(PreviewProtocol.CHANNEL));
        static final PacketCodec<RegistryByteBuf, Payload> CODEC = new PacketCodec<>() {
            public Payload decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > PreviewProtocol.MAX_BYTES) { b.skipBytes(n); return new Payload(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return new Payload(a);
            }
            public void encode(RegistryByteBuf b, Payload value) { b.writeBytes(value.bytes()); }
        };
        public Id<Payload> getId() { return ID; }
    }

    static void initialize() {
        PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> {
            List<String> parts = PreviewProtocol.decode(payload.bytes());
            if (parts.isEmpty()) return;
            context.client().execute(() -> show(parts));
        });
    }

    public static void show(List<String> parts) {
        String kind = parts.getFirst();
        String value = parts.size() > 1 ? parts.get(1) : "";
        switch (kind) {
            case "kyudichi" -> KyudichiPreview.show(value.isEmpty() ? "lobby" : value);
            case "targethud" -> TargetHudClient.showPreview(value.isEmpty() ? "normal" : value);
            case "season" -> PlayerHudClient.previewSeason(value);
            case "temperature" -> temperature(value);
            case "notice" -> notice(value.isEmpty() ? "system" : value);
            case "sound" -> { if (!value.isEmpty()) ScreenVfxClient.previewSound(value); }
            case "discovery" -> {
                if (value.isEmpty() || DiscoveryClient.preview(value) == 0) DiscoveryClient.preview("harvest_wind");
            }
            case "ascension" -> AscensionClient.preview(integer(value, 5));
            default -> { }
        }
    }

    private static void temperature(String value) {
        if (value.isEmpty() || value.equals("auto")) { TemperatureClient.auto(); return; }
        try {
            float celsius = Float.parseFloat(value);
            if (Float.isFinite(celsius)) TemperatureClient.preview(celsius);
        } catch (NumberFormatException ignored) { }
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return fallback; }
    }

    /** One sample card for each notice kind (/테스트 화면 알림 정보|경고|실패|보상). */
    private static void notice(String kind) {
        switch (kind) {
            case "warning" -> SharedNotifications.offer("preview:warning", "warning", "사용할 수 없는 마법", "빗자루에서 내린 뒤 사용할 수 있어요.");
            case "error" -> SharedNotifications.offer("preview:error", "error", "명령어 사용 실패", "명령어 사용에 실패했습니다");
            case "shop" -> SharedNotifications.offer("preview:shop", "shop", "보상을 받았어요", "마력코어 1개");
            default -> SharedNotifications.offer("preview:system", "system", "알림", "설정을 저장했습니다.");
        }
    }
}
