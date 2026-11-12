package kr.chacademi.dungeon;

// [기능:탑카드 시작]
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * 무한의 탑 증강 카드 고르기 화면 (서버 ChacaTower). 서버가 카드 3장을 JSON 으로 보내고, 여기서는 그리고 누른 번호만 돌려준다.
 * 채널: chacademi:tower (서버 → 클라, JSON) · chacademi:tower_act (클라 → 서버, "pick:<번호>:<카드>" 카드 0~2, 다시 뽑기 -2).
 * 모드가 없는 사람은 서버가 상자 창을 연다.
 */
final class TowerUi {
    private TowerUi() {}

    static final String S2C = "chacademi:tower", C2S = "chacademi:tower_act";
    private static final int MAX = 64 * 1024;

    record ToClient(String json) implements CustomPacketPayload {
        static final Type<ToClient> TYPE = new Type<>(ResourceLocation.parse(S2C));
        static final StreamCodec<FriendlyByteBuf, ToClient> CODEC = StreamCodec.of(
                (buf, v) -> buf.writeBytes(v.json().getBytes(StandardCharsets.UTF_8)),
                buf -> {
                    int n = buf.readableBytes();
                    if (n > MAX) { buf.skipBytes(n); return new ToClient(""); }
                    byte[] b = new byte[n];
                    buf.readBytes(b);
                    return new ToClient(new String(b, StandardCharsets.UTF_8));
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    record ToServer(String text) implements CustomPacketPayload {
        static final Type<ToServer> TYPE = new Type<>(ResourceLocation.parse(C2S));
        static final StreamCodec<FriendlyByteBuf, ToServer> CODEC = StreamCodec.of(
                (buf, v) -> buf.writeBytes(v.text().getBytes(StandardCharsets.UTF_8)),
                buf -> { byte[] b = new byte[Math.min(buf.readableBytes(), 64)]; buf.readBytes(b); buf.skipBytes(buf.readableBytes()); return new ToServer(new String(b, StandardCharsets.UTF_8)); });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** grade 0 일반 · 1 희귀 · 2 영웅 · 3 전설. icon = 탑 그림 이름(icon_heal·relic_fang …, 없으면 ""), item = 그림이 없을 때 그릴 아이템 id. */
    record Card(int grade, String gradeName, int color, String name, List<String> lines, String icon, String item) {}
    record Offer(int id, String title, String sub, long leftMs, long at, int rerolls, List<Card> cards) {}

    static Offer offer;
    private static boolean pending;

    static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(ToClient.TYPE, ToClient.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(ToServer.TYPE, ToServer.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ToClient.TYPE, (p, ctx) -> ctx.client().execute(() -> receive(p.json())));
    }

    static void clear() { offer = null; pending = false; }

    private static void receive(String json) {
        var mc = Minecraft.getInstance();
        JsonObject o;
        try { o = JsonParser.parseString(json).getAsJsonObject(); apply(mc, o); }
        catch (Exception e) { org.slf4j.LoggerFactory.getLogger("chacademi_dungeon").warn("탑 메시지 무시: {}", e.toString()); }   // 빠진 칸·모양이 다른 값도 여기서 잡는다
    }

    private static void apply(Minecraft mc, JsonObject o) {
        String t = o.has("t") ? o.get("t").getAsString() : "";
        if (t.equals("close")) {
            offer = null; pending = false;
            if (mc.screen instanceof CardScreen) mc.setScreen(null);
            return;
        }
        if (!t.equals("cards")) return;
        var cards = new ArrayList<Card>();
        for (var e : o.getAsJsonArray("cards")) {
            var c = e.getAsJsonObject();
            var lines = new ArrayList<String>();
            for (var l : c.getAsJsonArray("lines")) lines.add(l.getAsString());
            int color;
            try { color = 0xFF000000 | Integer.parseInt(c.get("color").getAsString().replace("#", ""), 16); } catch (Exception ex) { color = 0xFFFFFFFF; }
            cards.add(new Card(c.get("grade").getAsInt(), c.get("gradeName").getAsString(), color, c.get("name").getAsString(), lines,
                    c.has("icon") ? c.get("icon").getAsString() : "", c.has("item") ? c.get("item").getAsString() : ""));
        }
        offer = new Offer(o.get("id").getAsInt(), o.get("title").getAsString(), o.has("sub") ? o.get("sub").getAsString() : "",
                o.get("left").getAsLong(), DungeonClient.now(), o.get("rerolls").getAsInt(), cards);
        if (mc.screen instanceof CardScreen s) s.reset();
        else pending = true;
    }

    /** 다른 창이 비면 연다. 카드가 남아 있는데 창이 닫혀 있으면 다시 연다 (고르기 전에는 진행 안 됨). */
    static void tick(Minecraft mc) {
        if (mc.player == null) return;
        // 카드가 없는데 카드 화면이 떠 있으면 닫는다 (ESC 도 막힌 화면이라 서버 신호가 한 번 빠지면 갇혔음, 11/12 점검)
        if (offer == null) { if (mc.screen instanceof CardScreen) mc.setScreen(null); return; }
        if (DungeonClient.now() - offer.at() > offer.leftMs() + 5_000) { offer = null; pending = false; if (mc.screen instanceof CardScreen) mc.setScreen(null); return; }
        if (mc.screen instanceof CardScreen cs) { cs.unstick(); return; }
        var s = mc.screen;
        if (pending || s == null) {
            if (s == null || DungeonClient.hudScreen(s) || s instanceof UiScreens.Base) { DungeonClient.open(new CardScreen()); pending = false; }
        }
    }

    static void send(int choice) {
        if (offer == null || !ClientPlayNetworking.canSend(ToServer.TYPE)) return;
        ClientPlayNetworking.send(new ToServer("pick:" + offer.id() + ":" + choice));
    }

    // ───────────────────── 화면 ─────────────────────

    private static final String[] FRAMES = {"tower/card_common", "tower/card_rare", "tower/card_epic", "tower/card_legendary"};
    private static final float W = 480, H = 270, CW = 112, CH = 168, GAP = 26;
    // 카드 그림 안 자리 (512×768 기준 비율): 위 그림 창 · 아래 글 창
    private static final float ART_TOP = .085f, ART_BOTTOM = .655f, TEXT_TOP = .705f, TEXT_BOTTOM = .925f, SIDE = .085f;

    static final class CardScreen extends UiScreens.Base {
        private final float[] lift = new float[3];
        private long openedAt = DungeonClient.now();
        private boolean sent;
        private long sentAt;

        CardScreen() { super("탑 카드"); }

        void reset() { openedAt = DungeonClient.now(); sent = false; }

        /** 고른 뒤 4초가 지나도 서버 답(닫기·새 카드)이 없으면 다시 고를 수 있게 푼다. */
        void unstick() { if (sent && DungeonClient.now() - sentAt > 4_000) sent = false; }

        @Override float designW() { return W; }
        @Override float designH() { return H; }
        @Override float fill() { return 1f; }
        @Override float maxScale() { return 6f; }
        @Override public boolean shouldCloseOnEsc() { return offer == null; }   // 고를 카드가 있는 동안만 ESC 를 막는다

        @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
            Art.cover(g, "tower/card_bg", 0, 0, width, height, 0xFFFFFFFF);
            Art.rect(g, 0, 0, width, height, 0x55000000);
        }

        @Override void paint(GuiGraphics g) {
            var o = offer;
            if (o == null) return;
            long now = DungeonClient.now();
            float appear = Math.min(1f, (now - openedAt) / 350f);
            float ease = 1 - (1 - appear) * (1 - appear) * (1 - appear);

            Art.textCenter(g, Art.Face.SERIF, o.title(), W / 2, 24, 15, UiScreens.GOLD);
            if (!o.sub().isEmpty()) Art.textCenter(g, o.sub(), W / 2, 40, 7.5f, UiScreens.MUTED);

            int n = Math.min(3, o.cards().size());
            float total = n * CW + (n - 1) * GAP, x0 = (W - total) / 2, y0 = 52;
            for (int i = 0; i < n; i++) {
                var c = o.cards().get(i);
                float cx = x0 + i * (CW + GAP);
                float delay = Math.min(1f, Math.max(0f, appear * 1.6f - i * 0.25f));
                float dy = (1 - delay) * 18;
                int alpha = Math.round(255 * delay);
                boolean hover = !sent && over(cx, y0, CW, CH);
                lift[i] += ((hover ? 1f : 0f) - lift[i]) * 0.25f;
                float y = y0 + dy - lift[i] * 5;
                drawCard(g, c, cx, y, alpha, lift[i], i);
                hit("card" + i, cx, y0, CW, CH, !sent && ease > .6f);
            }

            float by = y0 + CH + 9;
            if (o.rerolls() > 0) {
                float bw = 125, bh = 25, bx = (W - bw) / 2;
                boolean hover = !sent && over(bx, by, bw, bh);
                Art.ui(g, "tower/btn_reroll", bx, by, bw, bh, hover ? 0xFFFFFFFF : 0xE6DADADA);
                Art.textCenter(g, Art.Face.SERIF, "다시 뽑기  ·  " + o.rerolls() + "회", bx + bw / 2 + 7, by + bh / 2 + .5f, 7.5f, hover ? 0xFFFFFFFF : 0xFFE9DDF7);
                hit("reroll", bx, by, bw, bh, !sent);
            }

            long left = Math.max(0, o.leftMs() - (now - o.at()));
            String hint = sent ? "" : (left / 1000) + "초";
            Art.textCenter(g, hint, W / 2, H - 7, 6.5f, left < 10_000 ? 0xFFF2A7A7 : UiScreens.MUTED);
        }

        private void drawCard(GuiGraphics g, Card c, float x, float y, int alpha, float hover, int index) {
            int a = Math.max(0, Math.min(255, alpha));
            if (a <= 0) return;
            int tint = Art.argb(a, 0xFFFFFF);
            int grade = Math.max(0, Math.min(3, c.grade()));
            if (hover > .02f) {
                float pad = 4 + hover * 2;
                Art.ui(g, "tower/card_hover", x - pad, y - pad * 1.5f, CW + pad * 2, CH + pad * 3, Art.argb(Math.round(a * hover), c.color() & 0xFFFFFF));
            }
            Art.ui(g, FRAMES[grade], x, y, CW, CH, tint);

            // 그림 창: 등급 이름 · 아이콘
            float artX = x + CW * SIDE, artY = y + CH * ART_TOP, artW = CW * (1 - SIDE * 2), artH = CH * (ART_BOTTOM - ART_TOP);
            Art.textCenter(g, c.gradeName(), x + CW / 2, artY + 9, 7, Art.argb(a, c.color() & 0xFFFFFF));
            float icon = Math.min(artW, artH) * 0.78f, icx = x + CW / 2, icy = artY + artH / 2 + 5;
            if (!c.icon().isEmpty()) Art.ui(g, "tower/" + c.icon(), icx - icon / 2, icy - icon / 2, icon, icon, tint);
            else if (!c.item().isEmpty()) {
                g.flush();
                var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(c.item())).map(ItemStack::new).orElse(ItemStack.EMPTY);
                if (!item.isEmpty()) {
                    var pose = g.pose();
                    pose.pushPose();
                    float k = icon * 0.7f / 16f;
                    pose.translate(icx - 8 * k, icy - 8 * k, 0);
                    pose.scale(k, k, 1);
                    g.renderItem(item, 0, 0);
                    pose.popPose();
                }
            }

            // 글 창: 이름 · 설명
            float tx = x + CW * SIDE + 3, tw = CW * (1 - SIDE * 2) - 6, ty = y + CH * TEXT_TOP, th = CH * (TEXT_BOTTOM - TEXT_TOP);
            float nameSize = Art.shrink(g, Art.Face.SERIF, c.name(), 8.5f, tw, 6);
            Art.textCenter(g, Art.Face.SERIF, c.name(), x + CW / 2, ty + 6, nameSize, Art.argb(a, c.color() & 0xFFFFFF));
            // 설명이 글 창(3줄)에 안 들어가면 글자를 줄여서 다 보이게 한다 (예전엔 넘치는 줄이 말없이 잘림, 11/12 점검)
            float size = 5.6f, start = ty + 15;
            List<String> lines = wrap(g, c.lines(), size, tw);
            for (float tryFont : new float[]{5.0f, 4.5f, 4.0f}) {
                if (lines.size() * size * 1.28f <= th - 15) break;
                size = tryFont;
                lines = wrap(g, c.lines(), size, tw);
            }
            float lineH = size * 1.28f;
            int max = Math.max(1, (int) ((th - 15) / lineH));
            for (int i = 0; i < lines.size() && i < max; i++) {
                String line = lines.get(i);
                if (i == max - 1 && lines.size() > max) line = line + "…";
                Art.textCenter(g, Art.Face.MEDIUM, line, x + CW / 2, start + i * lineH, size, Art.argb(a, 0xEDEFF3));
            }
        }

        /** 설명 줄을 카드 폭에 맞게 자른다 (띄어쓰기 기준, 긴 단어는 글자 단위). */
        private static List<String> wrap(GuiGraphics g, List<String> src, float size, float maxW) {
            var out = new ArrayList<String>();
            for (String line : src) {
                var cur = new StringBuilder();
                for (String word : line.split(" ")) {
                    String next = cur.isEmpty() ? word : cur + " " + word;
                    if (Art.width(g, Art.Face.MEDIUM, next, size) <= maxW) { cur.setLength(0); cur.append(next); continue; }
                    if (!cur.isEmpty()) { out.add(cur.toString()); cur.setLength(0); }
                    for (char ch : word.toCharArray()) {
                        if (Art.width(g, Art.Face.MEDIUM, cur.toString() + ch, size) > maxW && !cur.isEmpty()) { out.add(cur.toString()); cur.setLength(0); }
                        cur.append(ch);
                    }
                }
                if (!cur.isEmpty()) out.add(cur.toString());
            }
            return out;
        }

        @Override void clicked(String id) {
            if (sent || offer == null) return;
            if (id.equals("reroll")) { choose(-2); return; }
            if (id.startsWith("card")) choose(Integer.parseInt(id.substring(4)));
        }

        private void choose(int choice) {
            if (offer == null) return;
            if (choice >= 0 && choice >= offer.cards().size()) return;
            if (choice == -2 && offer.rerolls() <= 0) return;
            send(choice);
            Minecraft.getInstance().player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), .6f, 1.2f);
            if (choice >= 0) { sent = true; sentAt = DungeonClient.now(); } // 다시 뽑기는 새 카드가 오면 reset
        }

        @Override public boolean keyPressed(int key, int scan, int mods) {
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_3) { if (!sent) choose(key - GLFW.GLFW_KEY_1); return true; }
            if (key == GLFW.GLFW_KEY_R) { if (!sent) choose(-2); return true; }
            return super.keyPressed(key, scan, mods);
        }
    }
}
// [기능:탑카드 끝]
