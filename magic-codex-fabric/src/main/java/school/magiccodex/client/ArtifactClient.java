package school.magiccodex.client;

import java.util.*;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import school.magiccodex.protocol.ArtifactProtocol;
import school.magiccodex.protocol.ArtifactProtocol.Entry;

/**
 * Artifact codex data from the server (MagicCodexBridge ArtifactBridge). The catalogue comes from the server's
 * artifacts.yml, so editing that file changes the codex without rebuilding this mod. Never fabricates ownership.
 *
 * Artifact use key (default X, rebindable in Controls; never assignable to spells, see KeySettingsLayout): uses the
 * equipped usable artifact. The server checks everything and answers with the notice and the remaining cooldown.
 */
public final class ArtifactClient {
    private ArtifactClient() {}

    private interface Bytes { byte[] bytes(); }
    public record Request(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Request> ID = new Id<>(Identifier.of(ArtifactProtocol.REQUEST));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = codec(Request::new);
        public Id<Request> getId() { return ID; }
    }
    public record Response(byte[] bytes) implements CustomPayload, Bytes {
        static final Id<Response> ID = new Id<>(Identifier.of(ArtifactProtocol.RESPONSE));
        static final PacketCodec<RegistryByteBuf, Response> CODEC = codec(Response::new);
        public Id<Response> getId() { return ID; }
    }
    private static <T extends Bytes> PacketCodec<RegistryByteBuf, T> codec(java.util.function.Function<byte[], T> f) {
        return new PacketCodec<>() {
            public T decode(RegistryByteBuf b) {
                int n = b.readableBytes();
                if (n > ArtifactProtocol.MAX_BYTES) { b.skipBytes(n); return f.apply(new byte[0]); }
                byte[] a = new byte[n]; b.readBytes(a); return f.apply(a);
            }
            public void encode(RegistryByteBuf b, T value) { b.writeBytes(value.bytes()); }
        };
    }

    private static List<Entry> catalog = List.of();
    private static long revision;
    private static long building = -1;
    private static Entry[][] parts = new Entry[0][];
    private static boolean[] owned = new boolean[0];
    private static List<String> equipped = List.of();
    private static int maxEquipped = 1;
    private static String notice = "";
    private static long version;
    private static boolean supported;
    private static KeyBinding useKey;
    /** artifact id -> Util.getMeasuringTimeMs() when its use cooldown ends (from the server's last STATE). */
    private static final Map<String, Long> cooldownEnds = new HashMap<>();
    private static boolean awaitingUse;
    private static long lastUse;
    private static int summonKeys;
    private static String summonRates = "";

    static KeyBinding binding() { return useKey; }

    public static void initialize() {
        useKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.magiccodex.artifact_use", GLFW.GLFW_KEY_X, "category.magiccodex"));
        KeySettingsLayout.artifactKey(code -> useKey.matchesKey(code, 0));
        ClientTickEvents.END_CLIENT_TICK.register(client -> { while (useKey.wasPressed()) use(client); });
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Response.ID, Response.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Response.ID, (payload, context) -> {
            ArtifactProtocol.Frame frame;
            try { frame = ArtifactProtocol.decode(payload.bytes()); } catch (IllegalArgumentException ignored) { return; }
            context.client().execute(() -> accept(frame));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("아티팩트도감").executes(ctx -> {
                    var c = MinecraftClient.getInstance();
                    c.send(() -> c.setScreen(new ArtifactCodexScreen(null)));
                    return 1;
                })));
    }

    private static void accept(ArtifactProtocol.Frame frame) {
        supported = true;
        switch (frame) {
            case ArtifactProtocol.Catalog c -> {
                if (c.revision() != building || parts.length != c.parts()) { building = c.revision(); parts = new Entry[c.parts()][]; }
                parts[c.part()] = c.entries().toArray(Entry[]::new);
                for (Entry[] p : parts) if (p == null) return;
                var all = new ArrayList<Entry>();
                for (Entry[] p : parts) all.addAll(Arrays.asList(p));
                catalog = List.copyOf(all); revision = building; building = -1; parts = new Entry[0][];
            }
            case ArtifactProtocol.Summon m -> {
                summonKeys = m.keys();
                if (m.kind() == ArtifactProtocol.SUMMON_OPEN || m.kind() == ArtifactProtocol.SUMMON_STATE) summonRates = m.notice();
                var client = MinecraftClient.getInstance();
                if (m.kind() == ArtifactProtocol.SUMMON_OPEN) {
                    if (!(client.currentScreen instanceof ArtifactSummonScreen)) client.setScreen(new ArtifactSummonScreen());
                } else if (client.currentScreen instanceof ArtifactSummonScreen screen) screen.onSummon(m);
            }
            case ArtifactProtocol.SummonMulti m -> {
                summonKeys = m.keys();
                if (MinecraftClient.getInstance().currentScreen instanceof ArtifactSummonScreen screen) screen.onSummonMulti(m);
            }
            case ArtifactProtocol.State s -> {
                if (s.revision() != revision) return; // a newer catalogue is still arriving
                owned = s.owned().length == catalog.size() ? s.owned().clone() : new boolean[catalog.size()];
                equipped = s.equipped(); maxEquipped = s.maxEquipped(); notice = s.notice(); version++;
                long now = Util.getMeasuringTimeMs();
                cooldownEnds.clear();
                for (int i = 0; i < s.equipped().size(); i++) if (s.cooldownMs()[i] > 0) cooldownEnds.put(s.equipped().get(i), now + s.cooldownMs()[i]);
                if (awaitingUse) {
                    awaitingUse = false;
                    var player = MinecraftClient.getInstance().player;
                    if (player != null && !s.notice().isBlank()) player.sendMessage(Text.literal(s.notice()), true);
                }
            }
        }
    }

    private static void reset() {
        catalog = List.of(); revision = 0; building = -1; parts = new Entry[0][]; owned = new boolean[0];
        equipped = List.of(); notice = ""; supported = false; version++; cooldownEnds.clear(); awaitingUse = false;
        summonKeys = 0; summonRates = "";
    }

    private static boolean send(int action, String id) {
        if (!ClientPlayNetworking.canSend(Request.ID)) return false;
        ClientPlayNetworking.send(new Request(ArtifactProtocol.request(new ArtifactProtocol.Request(action, revision, id))));
        return true;
    }

    /** Ask for the current state (and the catalogue when ours is out of date). */
    static boolean query() { return send(ArtifactProtocol.QUERY, ""); }
    /** Summon window: key count + grade chances (answer: SUMMON_STATE). */
    static boolean summonInfo() { return send(ArtifactProtocol.SUMMON_INFO, ""); }
    /** [소환하기]: the server spends a key and answers SUMMON_RESULT or SUMMON_REFUSED. */
    static boolean summon() { return send(ArtifactProtocol.SUMMON, ""); }
    /** Five-pull (소환하기 ×5): spends 5 x cost keys (answer: SUMMON_MULTI_FRAME, or SUMMON REFUSED). */
    static boolean summonMulti() { return send(ArtifactProtocol.SUMMON_MULTI, ""); }
    static int summonKeys() { return summonKeys; }
    static String summonRates() { return summonRates; }
    static boolean equip(String id) { return send(ArtifactProtocol.EQUIP, id); }
    static boolean unequip(String id) { return send(ArtifactProtocol.UNEQUIP, id); }

    /** Artifact use key pressed. Local checks only save a packet; the server decides. */
    private static void use(MinecraftClient client) {
        if (client.player == null || client.currentScreen != null || !client.player.isAlive()) return;
        long now = Util.getMeasuringTimeMs();
        if (now - lastUse < 250) return;
        lastUse = now;
        Entry e = equipped.isEmpty() ? null : find(equipped.get(0));
        for (String id : equipped) { Entry x = find(id); if (x != null && x.usable()) { e = x; break; } }
        String local = !supported ? "아티팩트 정보를 불러오는 중입니다."
                : e == null ? "장착한 아티팩트가 없습니다."
                : !e.usable() ? e.name() + "은(는) 사용하는 아티팩트가 아닙니다."
                : cooldownLeft(e.id()) > 0 ? e.name() + " 재사용 대기 " + ((cooldownLeft(e.id()) + 999) / 1000) + "초" : null;
        if (local != null) { client.player.sendMessage(Text.literal(local), true); return; }
        if (e.id().equals(SpyglassZoom.ID)) return; // 접이식 망원경: 누르고 있는 동안 SpyglassZoom 이 직접 확대 (서버에 보내지 않음)
        if (send(ArtifactProtocol.USE, e.id())) awaitingUse = true;
    }

    /** Remaining use cooldown in ms (0 = ready), as last reported by the server. */
    static long cooldownLeft(String id) { return Math.max(0, cooldownEnds.getOrDefault(id, 0L) - Util.getMeasuringTimeMs()); }
    static String useKeyLabel() { return useKey == null ? "X" : useKey.getBoundKeyLocalizedText().getString(); }

    /** The server answered at least once on this connection. */
    static boolean supported() { return supported; }
    static boolean channelOpen() { return ClientPlayNetworking.canSend(Request.ID); }
    static List<Entry> catalog() { return catalog; }
    static boolean owned(int index) { return index >= 0 && index < owned.length && owned[index]; }
    static int ownedCount() { int n = 0; for (boolean b : owned) if (b) n++; return n; }
    static List<String> equipped() { return equipped; }
    static int maxEquipped() { return maxEquipped; }
    static String notice() { return notice; }
    static long version() { return version; }
    static Entry find(String id) { for (Entry e : catalog) if (e.id().equals(id)) return e; return null; }
}
