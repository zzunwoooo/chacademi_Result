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
    record Card(int grade, String gradeName, int color, String name, List<String> lines, String icon, String item, String spell) {}
    record Offer(int id, String title, String sub, long leftMs, long at, int rerolls, List<Card> cards) {}

    static Offer offer;
    private static boolean pending;
    // [기능:탑내증강 시작]
    /** 내 증강 창의 한 칸 (카드 그림 그대로 + 고른 횟수). 서버가 카드와 같이 보내거나 /탑 증강 때 보낸다. */
    record Owned(Card card, int count) {}
    static List<Owned> build = List.of();
    static String buildSub = "";
    // [기능:탑내증강 끝]

    static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(ToClient.TYPE, ToClient.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(ToServer.TYPE, ToServer.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ToClient.TYPE, (p, ctx) -> ctx.client().execute(() -> receive(p.json())));
    }

    static void clear() { offer = null; pending = false; build = List.of(); buildSub = ""; TowerLoadout.clear(); }

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
            if (mc.screen instanceof CardScreen || mc.screen instanceof BuildScreen b && b.fromCards) mc.setScreen(null);
            return;
        }
        // [기능:탑내증강 시작]
        // [기능:탑마법키] 탑에서 배운 마법 → 마법 키에 왼쪽부터 (active false = 원래 키로)
        if (t.equals("loadout")) {
            var list = new ArrayList<String>();
            for (var e : o.getAsJsonArray("spells")) list.add(e.getAsString());
            TowerLoadout.set(o.get("active").getAsBoolean(), list);
            return;
        }
        if (t.equals("build")) {   // /탑 증강
            readBuild(o);
            var s = mc.screen;
            if (s == null || DungeonClient.hudScreen(s) || s instanceof UiScreens.Base) DungeonClient.open(new BuildScreen(offer != null));
            return;
        }
        // [기능:탑내증강 끝]
        if (!t.equals("cards")) return;
        var cards = new ArrayList<Card>();
        for (var e : o.getAsJsonArray("cards")) cards.add(card(e.getAsJsonObject()));
        if (o.has("build")) { try { readBuild(o.getAsJsonObject("build")); } catch (Exception ex) { build = List.of(); } }   // [기능:탑내증강]
        offer = new Offer(o.get("id").getAsInt(), o.get("title").getAsString(), o.has("sub") ? o.get("sub").getAsString() : "",
                o.get("left").getAsLong(), DungeonClient.now(), o.get("rerolls").getAsInt(), cards);
        if (mc.screen instanceof CardScreen s) s.reset();
        else pending = true;
    }

    private static Card card(JsonObject c) {
        var lines = new ArrayList<String>();
        for (var l : c.getAsJsonArray("lines")) lines.add(l.getAsString());
        int color;
        try { color = 0xFF000000 | Integer.parseInt(c.get("color").getAsString().replace("#", ""), 16); } catch (Exception ex) { color = 0xFFFFFFFF; }
        return new Card(c.get("grade").getAsInt(), c.get("gradeName").getAsString(), color, c.get("name").getAsString(), lines,
                c.has("icon") ? c.get("icon").getAsString() : "", c.has("item") ? c.get("item").getAsString() : "", c.has("spell") ? c.get("spell").getAsString() : "");
    }

    // [기능:탑내증강 시작]
    private static void readBuild(JsonObject o) {
        var list = new ArrayList<Owned>();
        for (var e : o.getAsJsonArray("items")) {
            var j = e.getAsJsonObject();
            list.add(new Owned(card(j), j.has("count") ? j.get("count").getAsInt() : 1));
        }
        build = list;
        buildSub = o.has("sub") ? o.get("sub").getAsString() : "";
    }
    // [기능:탑내증강 끝]

    /** 다른 창이 비면 연다. 카드가 남아 있는데 창이 닫혀 있으면 다시 연다 (고르기 전에는 진행 안 됨). */
    static void tick(Minecraft mc) {
        if (mc.player == null) return;
        TowerLoadout.tick();   // [기능:탑마법키]
        // 카드가 없는데 카드 화면이 떠 있으면 닫는다 (ESC 도 막힌 화면이라 서버 신호가 한 번 빠지면 갇혔음, 11/12 점검)
        if (offer == null) { if (mc.screen instanceof CardScreen) mc.setScreen(null); return; }
        if (DungeonClient.now() - offer.at() > offer.leftMs() + 5_000) { offer = null; pending = false; if (mc.screen instanceof CardScreen) mc.setScreen(null); return; }
        if (mc.screen instanceof CardScreen cs) { cs.unstick(); return; }
        var s = mc.screen;
        if (pending || s == null) {
            if (s == null || DungeonClient.hudScreen(s) || s instanceof UiScreens.Base) { DungeonClient.open(new CardScreen()); pending = false; }
        }
    }

// [기능:탑포기 시작]
private static long quitArmedAt;

/** 포기 버튼 (두 번 눌러야 포기). 카드 화면·내 증강 창이 같이 쓴다. */
static void quitButton(UiScreens.Base s, GuiGraphics g, float x, float y, float w, float h) {
    boolean armed = DungeonClient.now() - quitArmedAt < 3_000, hover = s.over(x, y, w, h);
    plainButton(g, x, y, w, h, hover ? 0xFFFFFFFF : 0xE6DADADA);
    Art.textCenter(g, Art.Face.SERIF, armed ? "한 번 더 누르면 포기" : "탑 포기", x + w / 2, y + h / 2 + .5f, armed ? 5.8f : 6.5f, armed ? 0xFFF2A7A7 : hover ? 0xFFFFFFFF : 0xFFE9DDF7);
    s.hit("quit", x, y, w, h, true);
}

static void quitClicked() {
    var mc = Minecraft.getInstance();
    long now = DungeonClient.now();
    if (now - quitArmedAt >= 3_000) { quitArmedAt = now; if (mc.player != null) mc.player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), .7f, .7f); return; }
    quitArmedAt = 0;
    if (ClientPlayNetworking.canSend(ToServer.TYPE)) ClientPlayNetworking.send(new ToServer("quit"));
    offer = null; pending = false;
    mc.setScreen(null);
}
// [기능:탑포기 끝]

static void send(int choice) {
        if (offer == null || !ClientPlayNetworking.canSend(ToServer.TYPE)) return;
        ClientPlayNetworking.send(new ToServer("pick:" + offer.id() + ":" + choice));
    }

    // ───────────────────── 화면 ─────────────────────

    private static final String[] FRAMES = {"tower/card_common", "tower/card_rare", "tower/card_epic", "tower/card_legendary"};
    private static final float W = 480, H = 270, CW = 112, CH = 168, GAP = 26;
    // 카드 그림 안 자리 (512×768 기준 비율): 위 그림 창 · 아래 글 창
    private static final float ART_TOP = .085f, ART_BOTTOM = .655f, TEXT_TOP = .705f, TEXT_BOTTOM = .925f, SIDE = .085f;

    /** 카드 한 장 (112×168 기준). mini = 내 증강 창의 작은 칸: 설명·등급 글자 없이 이름만 크게. */
    static void drawCard(GuiGraphics g, Card c, float x, float y, int alpha, float hover, boolean mini) {
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
        if (!mini) Art.textCenter(g, c.gradeName(), x + CW / 2, artY + 9, 7, Art.argb(a, c.color() & 0xFFFFFF));
        float icon = Math.min(artW, artH) * 0.78f, icx = x + CW / 2, icy = artY + artH / 2 + 5;
        // [기능:탑아이콘] 탑 그림 → (마법 카드면) 그 마법의 진짜 아이콘 → 아직 그림이 없는 종류만 아이템 그림
        if (!c.icon().isEmpty() && Art.ui("tower/" + c.icon()).isPresent()) Art.ui(g, "tower/" + c.icon(), icx - icon / 2, icy - icon / 2, icon, icon, tint);
        else if (!c.spell().isEmpty() && SpellIcons.draw(g, c.spell(), icx - icon / 2, icy - icon / 2, icon, tint)) { }
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
        if (mini) {
            float big = Art.shrink(g, Art.Face.SERIF, c.name(), 17f, tw, 10f);
            Art.textCenter(g, Art.Face.SERIF, Art.fit(g, Art.Face.SERIF, c.name(), big, tw), x + CW / 2, ty + th / 2, big, Art.argb(a, c.color() & 0xFFFFFF));
            return;
        }
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
// [기능:탑마법설명 시작] 마법 카드에 마우스를 올리면 마법도감의 설명·마나·재사용 시간
static void spellTip(UiScreens.Base s, GuiGraphics g, String spell) {
    var info = SpellIcons.info(spell);
    if (info == null) return;
    float bw = 168, pad = 6, size = 5.6f, lineH = size * 1.32f;
    var body = info.description().isBlank() ? List.<String>of() : wrap(g, List.of(info.description()), size, bw - pad * 2);
    String meta = (info.mana() >= 0 ? "마나 " + info.mana() : "") + (info.cooldown() >= 0 ? (info.mana() >= 0 ? "  ·  " : "") + "재사용 " + info.cooldown() + "초" : "");
    float bh = pad + 10 + body.size() * lineH + (meta.isEmpty() ? 0 : 9) + pad;
    float x = s.mx + 10, y = s.my + 10;
    if (x + bw > W - 4) x = s.mx - bw - 10;
    if (y + bh > H - 4) y = H - 4 - bh;
    x = Math.max(4, x); y = Math.max(4, y);
    var pose = g.pose();
    pose.pushPose();
    pose.translate(0, 0, 300);
    Art.rect(g, x, y, bw, bh, 0xF0120F1C);
    Art.rect(g, x, y, bw, .8f, 0xFF7E63B8); Art.rect(g, x, y + bh - .8f, bw, .8f, 0xFF7E63B8);
    Art.rect(g, x, y, .8f, bh, 0xFF7E63B8); Art.rect(g, x + bw - .8f, y, .8f, bh, 0xFF7E63B8);
    float cy = y + pad + 4;
    Art.text(g, Art.Face.SERIF, info.name().isBlank() ? spell : info.name(), x + pad, cy, 7f, UiScreens.GOLD);
    cy += 10;
    for (String line : body) { Art.text(g, Art.Face.MEDIUM, line, x + pad, cy, size, 0xFFE6E8EF); cy += lineH; }
    if (!meta.isEmpty()) Art.text(g, Art.Face.MEDIUM, meta, x + pad, cy + 1, 5.2f, UiScreens.MUTED);
    pose.popPose();
}
// [기능:탑마법설명 끝]

// [기능:탑카드소리] 카드가 한 장씩 뜰 때 등급별 소리
static void cardSound(int grade) {
    var p = Minecraft.getInstance().player;
    if (p == null) return;
    switch (grade) {
        case 0 -> p.playSound(SoundEvents.BOOK_PAGE_TURN, .8f, 1.25f);
        case 1 -> p.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.1f);
        case 2 -> { p.playSound(SoundEvents.ENCHANTMENT_TABLE_USE, .9f, 1.15f); p.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, .7f, 1.5f); }
        default -> { p.playSound(SoundEvents.END_PORTAL_FRAME_FILL, 1f, 1.1f); p.playSound(SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, 1.3f); p.playSound(SoundEvents.BEACON_POWER_SELECT, .7f, 1.7f); }
    }
}

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

    // [기능:탑내증강 시작]
    /** 다시 뽑기 버튼 틀에서 왼쪽 화살표 그림을 뺀 모양 (오른쪽 절반을 좌우로 뒤집어 양쪽에 그린다). */
    static void plainButton(GuiGraphics g, float x, float y, float w, float h, int color) {
        Art.ui("tower/btn_reroll").ifPresent(t -> {
            Art.quad(g, t, x, y, x + w / 2, y + h, 1, 0, .5f, 1, color);
            Art.quad(g, t, x + w / 2, y, x + w, y + h, .5f, 0, 1, 1, color);
        });
    }

    /**
     * 내 증강: 지금까지 고른 증강·유물·마법을 작은 카드로 늘어놓고, 가리킨 칸을 오른쪽에 큰 카드로 보여 준다.
     * 카드 화면의 그림(틀·아이콘·배경)을 그대로 쓴다. 카드 고르는 중에 열었으면 닫을 때 카드 화면으로 돌아간다.
     */
    static final class BuildScreen extends UiScreens.Base {
        private static final int COLS = 6, ROWS = 3;
        private static final float TW = 44, TH = 66, GX = 6, GY = 5, X0 = 20, Y0 = 44, K = TW / CW;
        final boolean fromCards;
        private int page, focus;

        BuildScreen(boolean fromCards) { super("내 증강"); this.fromCards = fromCards; }

        @Override float designW() { return W; }
        @Override float designH() { return H; }
        @Override float fill() { return 1f; }
        @Override float maxScale() { return 6f; }

        @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
            Art.cover(g, "tower/card_bg", 0, 0, width, height, 0xFFFFFFFF);
            Art.rect(g, 0, 0, width, height, 0x77000000);
        }

        private int pages() { return Math.max(1, (build.size() + COLS * ROWS - 1) / (COLS * ROWS)); }

        @Override void paint(GuiGraphics g) {
            var list = build;
            page = Math.max(0, Math.min(page, pages() - 1));
            Art.textCenter(g, Art.Face.SERIF, "내 증강", W / 2, 18, 14, UiScreens.GOLD);
            if (!buildSub.isEmpty()) Art.textCenter(g, buildSub, W / 2, 33, 6.5f, UiScreens.MUTED);

            int per = COLS * ROWS, from = page * per, tipIdx = -1;
            for (int i = 0; i < per && from + i < list.size(); i++) {
                int idx = from + i;
                float tx = X0 + (i % COLS) * (TW + GX), ty = Y0 + (i / COLS) * (TH + GY);
                if (over(tx, ty, TW, TH)) { focus = idx; tipIdx = idx; }
                var o = list.get(idx);
                var pose = g.pose();
                pose.pushPose();
                pose.translate(tx, ty, 0);
                pose.scale(K, K, 1);
                drawCard(g, o.card(), 0, 0, 255, idx == focus ? 1f : 0f, true);
                pose.popPose();
                if (o.count() > 1) {
                    String n = "×" + o.count();
                    float bw = Art.width(g, n, 5.5f) + 5;
                    Art.rect(g, tx + TW - bw - 3, ty + 3.5f, bw, 8, 0xD0101018);
                    Art.textCenter(g, n, tx + TW - bw / 2 - 3, ty + 7.5f, 5.5f, 0xFFFFE9A8);
                }
                hit("tile" + idx, tx, ty, TW, TH, true);
            }

            // 오른쪽: 가리킨 칸을 큰 카드로
            float bx = 338, by = 44;
            if (focus >= list.size()) focus = 0;
            if (!list.isEmpty()) {
                var o = list.get(focus);
                drawCard(g, o.card(), bx, by, 255, 0f, false);
                if (o.count() > 1) Art.textCenter(g, o.count() + "번 골랐어요", bx + CW / 2, by + CH + 8, 6.5f, UiScreens.MUTED);
            }

            // 쪽 넘기기 (마우스 휠도 됨)
            if (pages() > 1) {
                float py = 259, mid = X0 + (COLS * TW + (COLS - 1) * GX) / 2;
                boolean l = page > 0, r = page < pages() - 1;
                Art.textCenter(g, (page + 1) + " / " + pages(), mid, py, 6.5f, UiScreens.MUTED);
                Art.textCenter(g, "이전", mid - 44, py, 6.5f, !l ? 0x55FFFFFF : over(mid - 62, py - 6, 36, 12) ? 0xFFFFFFFF : 0xFFE9DDF7);
                Art.textCenter(g, "다음", mid + 44, py, 6.5f, !r ? 0x55FFFFFF : over(mid + 26, py - 6, 36, 12) ? 0xFFFFFFFF : 0xFFE9DDF7);
                hit("prev", mid - 62, py - 6, 36, 12, l);
                hit("next", mid + 26, py - 6, 36, 12, r);
            }

            // 돌아가기
            boolean back = fromCards && offer != null;
            float bw = 90, bh = 18, cx = bx + (CW - bw) / 2, cy = 232;
            boolean hover = over(cx, cy, bw, bh);
            plainButton(g, cx, cy, bw, bh, hover ? 0xFFFFFFFF : 0xE6DADADA);
            Art.textCenter(g, Art.Face.SERIF, back ? "카드 고르기로" : "닫기", cx + bw / 2, cy + bh / 2 + .5f, 7f, hover ? 0xFFFFFFFF : 0xFFE9DDF7);
            hit("close", cx, cy, bw, bh, true);
            quitButton(this, g, X0, 252, 70, 14);   // [기능:탑포기]
            if (back) {
                long left = Math.max(0, offer.leftMs() - (DungeonClient.now() - offer.at()));
                Art.textCenter(g, "카드 고르기 " + (left / 1000) + "초 남음", bx + CW / 2, 259, 6.5f, left < 10_000 ? 0xFFF2A7A7 : UiScreens.MUTED);
            }
            if (tipIdx >= 0 && !list.get(tipIdx).card().spell().isEmpty()) spellTip(this, g, list.get(tipIdx).card().spell());   // [기능:탑마법설명] 맨 위에 그리게 마지막에
        }

        @Override void clicked(String id) {
            if (id.equals("quit")) { quitClicked(); return; }   // [기능:탑포기]
            if (id.equals("prev")) page--;
            else if (id.equals("next")) page++;
            else if (id.startsWith("tile")) { try { focus = Integer.parseInt(id.substring(4)); } catch (NumberFormatException ignored) { } }
            else return;
            Minecraft.getInstance().player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), .4f, 1.3f);
        }

        @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
            if (sy > 0 && page > 0) page--;
            else if (sy < 0 && page < pages() - 1) page++;
            return true;
        }

        @Override public boolean keyPressed(int key, int scan, int mods) {
            if (key == GLFW.GLFW_KEY_TAB) { onClose(); return true; }
            return super.keyPressed(key, scan, mods);
        }

        @Override public void onClose() {
            if (fromCards && offer != null) Minecraft.getInstance().setScreen(new CardScreen());
            else super.onClose();
        }
    }
    // [기능:탑내증강 끝]

    static final class CardScreen extends UiScreens.Base {
        private final float[] lift = new float[3];
        private final boolean[] played = new boolean[3];   // [기능:탑카드소리]
        private long openedAt = DungeonClient.now();
        private boolean sent;
        private long sentAt;

        CardScreen() { super("탑 카드"); }

        void reset() { openedAt = DungeonClient.now(); sent = false; java.util.Arrays.fill(played, false); }

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
            Card tipCard = null;   // [기능:탑마법설명]
            for (int i = 0; i < n; i++) {
                var c = o.cards().get(i);
                float cx = x0 + i * (CW + GAP);
                float delay = Math.min(1f, Math.max(0f, appear * 1.6f - i * 0.25f));
                float dy = (1 - delay) * 18;
                int alpha = Math.round(255 * delay);
                boolean hover = !sent && over(cx, y0, CW, CH);
                if (over(cx, y0, CW, CH) && delay > .9f) tipCard = c;
                if (delay > 0 && !played[i]) { played[i] = true; cardSound(c.grade()); }   // [기능:탑카드소리]
                lift[i] += ((hover ? 1f : 0f) - lift[i]) * 0.25f;
                float y = y0 + dy - lift[i] * 5;
                drawCard(g, c, cx, y, alpha, lift[i], false);
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

            // [기능:탑내증강 시작] 지금까지 고른 증강 보기 (요약 한 칸만 있으면 아직 볼 게 없어 숨김)
            if (build.size() > 1) {
                float bw = 80, bh = 16, bx = W - 22 - bw, by2 = by + 4.5f;
                boolean hover = over(bx, by2, bw, bh);
                plainButton(g, bx, by2, bw, bh, hover ? 0xFFFFFFFF : 0xE6DADADA);
                Art.textCenter(g, Art.Face.SERIF, "내 증강  ·  Tab", bx + bw / 2, by2 + bh / 2 + .5f, 6.5f, hover ? 0xFFFFFFFF : 0xFFE9DDF7);
                hit("build", bx, by2, bw, bh, true);
            }
            // [기능:탑내증강 끝]
            quitButton(this, g, 22, by + 4.5f, 80, 16);   // [기능:탑포기]
            if (tipCard != null && !tipCard.spell().isEmpty()) spellTip(this, g, tipCard.spell());   // [기능:탑마법설명]

            long left = Math.max(0, o.leftMs() - (now - o.at()));
            String hint = sent ? "" : (left / 1000) + "초";
            Art.textCenter(g, hint, W / 2, H - 7, 6.5f, left < 10_000 ? 0xFFF2A7A7 : UiScreens.MUTED);
        }

        @Override void clicked(String id) {
            if (id.equals("build")) { if (build.size() > 1) Minecraft.getInstance().setScreen(new BuildScreen(true)); return; }   // [기능:탑내증강]
            if (id.equals("quit")) { quitClicked(); return; }   // [기능:탑포기]
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
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_3) { if (!sent && DungeonClient.now() - openedAt > 400) choose(key - GLFW.GLFW_KEY_1); return true; }   // 새 카드가 뜬 직후의 키 연타는 무시
            if (key == GLFW.GLFW_KEY_R) { if (!sent) choose(-2); return true; }
            if (key == GLFW.GLFW_KEY_TAB) { if (build.size() > 1) Minecraft.getInstance().setScreen(new BuildScreen(true)); return true; }   // [기능:탑내증강]
            return super.keyPressed(key, scan, mods);
        }
    }
}
// [기능:탑카드 끝]
