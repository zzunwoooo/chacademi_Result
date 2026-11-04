package kr.chacademi.dungeon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import school.chacademia.dungeon.protocol.DungeonProtocol;

/**
 * 던전 창들. 디자인 캔버스(designW×designH)를 화면에 맞춰 키우고 줄여 가운데에 놓는다 (GUI 배율·작은 화면 대응).
 * 테두리·버튼은 9-slice/3-slice, 글자·시간·닉네임·칭호·아이템은 전부 서버 값으로 따로 그린다.
 */
final class UiScreens {
    private UiScreens() {}

    static final int WHITE = 0xFFFFFFFF, MUTED = 0xFFB9C2CE, GOLD = 0xFFFFE08A, SILVER = 0xFFE3E8EF;

    /** 공통: 캔버스 배치·버튼 클릭 판정·보상 칸 그리기. */
    abstract static class Base extends Screen {
        private record Hit(String id, float x, float y, float w, float h, boolean enabled) {}
        private final List<Hit> hits = new ArrayList<>();
        private String pressed;
        protected float scale = 1, ox, oy;
        protected float mx, my;
        protected ItemStack tooltip = ItemStack.EMPTY;

        Base(String title) { super(Component.literal(title)); }

        abstract float designW();
        abstract float designH();
        abstract void paint(GuiGraphics g);
        abstract void clicked(String id);
        boolean dim() { return true; }
        // [기능:파티던전 시작]
        /** 화면에서 차지하는 비율 (입장창 = 화면 가득, 수락 창 = 세로 62%). 최대 배율은 기존 창 기준 2.5. */
        float fill() { return 0.94f; }
        float maxScale() { return 2.5f; }
        /** 클릭 판정 등록 (그림을 직접 그리는 버튼용). */
        void hit(String id, float x, float y, float w, float h, boolean enabled) { hits.add(new Hit(id, x, y, w, h, enabled)); }
        // [기능:파티던전 끝]

        @Override public boolean isPauseScreen() { return false; }

        @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
            if (dim()) Art.ui(g, "panels/screen-dimmer", 0, 0, width, height, 0xFFFFFFFF);
        }

        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            renderBackground(g, mouseX, mouseY, delta);
            scale = Math.min(Math.min(width * fill() / designW(), height * fill() / designH()), maxScale());
            ox = (width - designW() * scale) / 2;
            oy = (height - designH() * scale) / 2;
            mx = (mouseX - ox) / scale;
            my = (mouseY - oy) / scale;
            hits.clear();
            tooltip = ItemStack.EMPTY;
            tooltipExtra = List.of();
            var pose = g.pose();
            pose.pushPose();
            pose.translate(ox, oy, 0);
            pose.scale(scale, scale, 1);
            paint(g);
            pose.popPose();
            g.flush();
            if (!tooltip.isEmpty()) {
                // [기능:보상수량 시작] 마법도감과 같은 아이템 설명창 (없으면 기본 툴팁)
                var codexLines = new ArrayList<Component>(Screen.getTooltipFromItem(Minecraft.getInstance(), tooltip));
                codexLines.addAll(tooltipExtra);
                if (codexTooltip(g, tooltip, mouseX, mouseY, codexLines)) return;
                // [기능:보상수량 끝]
                if (tooltipExtra.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
                else {
                    var lines = new ArrayList<Component>(Screen.getTooltipFromItem(Minecraft.getInstance(), tooltip));
                    lines.addAll(tooltipExtra);
                    g.renderTooltip(font, lines, tooltip.getTooltipImage(), mouseX, mouseY);
                }
            }
        }

        // [기능:보상수량 시작]
        private static java.lang.reflect.Method codexTip;
        private static boolean codexTipLooked;

        /** 마법도감(MagicCodex) ItemTooltipRenderer.render 로 그리기. 그렸으면 true. */
        private boolean codexTooltip(GuiGraphics g, ItemStack stack, int x, int y, List<Component> lines) {
            if (!codexTipLooked) {
                codexTipLooked = true;
                try {
                    codexTip = Class.forName("school.magiccodex.client.ItemTooltipRenderer")
                            .getMethod("render", GuiGraphics.class, net.minecraft.client.gui.Font.class, ItemStack.class, int.class, int.class, List.class);
                } catch (Throwable ignored) { codexTip = null; }
            }
            if (codexTip == null) return false;
            try { return (Boolean) codexTip.invoke(null, g, font, stack, x, y, lines); } catch (Throwable ignored) { return false; }
        }
        // [기능:보상수량 끝]

        boolean over(float x, float y, float w, float h) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }

        /** style: primary · secondary · danger · disabled. */
        void button(GuiGraphics g, String id, String style, float x, float y, float w, float h, String label) {
            boolean enabled = !style.equals("disabled");
            boolean hover = enabled && over(x, y, w, h);
            String tex = style.equals("primary") && hover ? "buttons/primary-hover" : "buttons/" + (style.equals("primary") ? "primary-idle" : style);
            int tint = hover && !style.equals("primary") ? 0xFFFFFFFF : style.equals("primary") ? 0xFFFFFFFF : 0xE8E2E2E2;
            Art.hslice(g, tex, x, y, w, h, 48, tint);
            Art.textCenter(g, Art.fit(g, label, h * 0.42f, w - 12), x + w / 2, y + h / 2 + .5f, h * 0.42f, enabled ? WHITE : MUTED);
            hits.add(new Hit(id, x, y, w, h, enabled));
        }

        void close(GuiGraphics g, float x, float y, float s) {
            boolean hover = over(x, y, s, s);
            Art.ui(g, "icons/close", x, y, s, s, hover ? 0xFFFFFFFF : 0xCCFFFFFF);
            hits.add(new Hit("close", x, y, s, s, true));
        }

        void divider(GuiGraphics g, float x, float y, float w) { Art.hslice(g, "panels/section-divider", x, y - 2, w, 4, 48, 0xCCFFFFFF); }

        /** 보상 칸 격자. 칸 안에 실제 아이템 렌더러, 마우스를 올리면 아이템 툴팁(이름·수량·설명). 돌려준 값 = 전체 줄 수. */
        int slots(GuiGraphics g, List<ItemStack> items, float x, float y, int cols, int rows, int scrollRow, float size) {
            float gap = 2;
            int total = (items.size() + cols - 1) / cols;
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) {
                    float sx = x + c * (size + gap), sy = y + r * (size + gap);
                    Art.ui(g, "panels/reward-slot", sx, sy, size, size, 0xFFFFFFFF);
                }
            g.flush();
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) {
                    int i = (r + scrollRow) * cols + c;
                    if (i >= items.size()) continue;
                    float sx = x + c * (size + gap), sy = y + r * (size + gap);
                    var pose = g.pose();
                    pose.pushPose();
                    float k = (size - 4) / 16f;
                    pose.translate(sx + 2, sy + 2, 0);
                    pose.scale(k, k, 1);
                    g.renderItem(items.get(i), 0, 0);
                    g.renderItemDecorations(font, items.get(i), 0, 0);
                    pose.popPose();
                    if (over(sx, sy, size, size)) tooltip = items.get(i);
                }
            return total;
        }

        /** 툴팁에 덧붙일 줄 (확률 보상의 확률 등). 아이템 툴팁 아래에 붙는다. */
        protected List<Component> tooltipExtra = List.of();

        /**
         * 보상 한 줄: 칸 cols 개, offset 번째 아이템부터. chances(만분율)가 있으면 칸 오른쪽 아래에 확률, dim 이면 칸을 어둡게(이미 받음).
         */
        void rowSlots(GuiGraphics g, List<ItemStack> items, float x, float y, int cols, int offset, float size, List<Integer> chances, boolean dim) {
            float gap = 2;
            for (int c = 0; c < cols; c++) Art.ui(g, "panels/reward-slot", x + c * (size + gap), y, size, size, dim ? 0x99FFFFFF : 0xFFFFFFFF);
            g.flush();
            var pose = g.pose();
            for (int c = 0; c < cols; c++) {
                int i = offset + c;
                if (i >= items.size()) continue;
                float sx = x + c * (size + gap);
                pose.pushPose();
                float k = (size - 4) / 16f;
                pose.translate(sx + 2, y + 2, 0);
                pose.scale(k, k, 1);
                g.renderItem(items.get(i), 0, 0);
                g.renderItemDecorations(font, items.get(i), 0, 0);
                pose.popPose();
                if (over(sx, y, size, size)) {
                    tooltip = items.get(i);
                    tooltipExtra = i < chances.size() ? List.of(Component.literal("확률 " + percent(chances.get(i))).withColor(GOLD & 0xFFFFFF))
                            : dim ? List.of(Component.literal("이미 받은 보상").withColor(MUTED & 0xFFFFFF)) : List.of();
                }
            }
            g.flush();
            // 아이템 위에 그리는 표시 (확률·받음): 아이템보다 앞으로
            pose.pushPose();
            pose.translate(0, 0, 300);
            for (int c = 0; c < cols; c++) {
                int i = offset + c;
                if (i >= items.size()) continue;
                float sx = x + c * (size + gap);
                if (dim) Art.ui(g, "panels/panel-fill", sx + 1, y + 1, size - 2, size - 2, 0xAA000000 | 0xFFFFFF);
                if (i < chances.size()) {
                    String t = percent(chances.get(i));
                    Art.textRight(g, t, sx + size - 1.2f, y + size - 3.6f, 5.4f, GOLD);
                }
            }
            pose.popPose();
        }

        static String percent(int basis) {
            if (basis % 100 == 0) return basis / 100 + "%";
            return String.format(java.util.Locale.ROOT, basis % 10 == 0 ? "%.1f%%" : "%.2f%%", basis / 100.0);
        }

        void titleLine(GuiGraphics g, DungeonProtocol.Holder h, float x, float cy, float size) {
            float at = x;
            if (!h.prefix().name().isBlank()) at += Art.text(g, h.prefix().name(), at, cy, size, 0xFF000000 | h.prefix().color()) + size * 0.4f;
            if (!h.suffix().name().isBlank()) Art.text(g, h.suffix().name(), at, cy, size, 0xFF000000 | h.suffix().color());
        }

        @Override public boolean mouseClicked(double x, double y, int button) {
            if (button != 0) return super.mouseClicked(x, y, button);
            float lx = (float) ((x - ox) / scale), ly = (float) ((y - oy) / scale);
            for (var h : hits) if (h.enabled() && lx >= h.x() && lx <= h.x() + h.w() && ly >= h.y() && ly <= h.y() + h.h()) { pressed = h.id(); return true; }
            return super.mouseClicked(x, y, button);
        }

        @Override public boolean mouseReleased(double x, double y, int button) {
            if (button == 0 && pressed != null) {
                String id = pressed;
                pressed = null;
                float lx = (float) ((x - ox) / scale), ly = (float) ((y - oy) / scale);
                for (var h : hits)
                    if (h.id().equals(id) && h.enabled() && lx >= h.x() && lx <= h.x() + h.w() && ly >= h.y() && ly <= h.y() + h.h()) {
                        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
                        if (id.equals("close")) onClose(); else clicked(id);
                        return true;
                    }
                return true;
            }
            return super.mouseReleased(x, y, button);
        }
    }

    // ------------------------------------------------------------ 입장창
    // [기능:파티던전 시작] 오너 레퍼런스(dungeonui-ref) 시안 그대로: 캔버스 1672×941 = 시안 픽셀 좌표, 그림은 textures/gui/ref/

    static final class EntryScreen extends Base {
        private static final int CREAM = 0xFFF4EAD8, LABEL = 0xFFE4E8EE, RGOLD = 0xFFF3CD6E, SUBTLE = 0xFFA9B3C2, WARN = 0xFFF2A7A7,
                RECORD = 0xFFF1E3C2, TIP_GOLD = 0xFFF0C766, BTN_TEXT = 0xFFFBEBD0;
        /** 보상 칸 순서 = 반복 보상 → 확률 보상 → 최초 클리어. */
        private static final int[] KINDS = {DungeonProtocol.R_BASE, DungeonProtocol.R_RANDOM, DungeonProtocol.R_FIRST};
        private static final String[] KIND_LABEL = {"반복 보상", "확률 보상", "최초 클리어"};
        private static final float[] SLOT_X = {1204, 1348, 1492};
        private static final float SLOT_Y = 538, SLOT_W = 118, SLOT_H = 116, CX = 1413, L = 1226, R = 1604;
        /** 보상 목록 툴팁: 보상 줄 왼쪽에 고정 (시안 916~1168), 줄 5개까지 보이고 나머지는 휠. */
        private static final float TIP_X = 916, TIP_W = 252, TIP_HEAD = 50, TIP_ROW = 63, TIP_PAD = 13;
        private static final int TIP_ROWS = 5;
        private record Reward(ItemStack stack, int chance) {}
        private final int session;
        private List<DungeonProtocol.RewardGroup> source;
        private final DungeonProtocol.RewardGroup[] groups = new DungeonProtocol.RewardGroup[3];
        private final List<List<Reward>> rewards = new ArrayList<>();
        private int tip = -1, tipScroll, hoverSlot = -1;
        private long hoverSince;

        EntryScreen(int session) { super("던전 입장"); this.session = session; }
        int session() { return session; }
        @Override float designW() { return 1672; }
        @Override float designH() { return 941; }
        @Override float fill() { return 1f; }
        @Override float maxScale() { return 8f; }

        private DungeonProtocol.Entry e() { return DungeonClient.entry; }

        /** 묶음 → 칸 3개. 아이템 바이트를 하나씩 읽어 확률(만분율)과 짝을 지킨다 (못 읽은 아이템만 빠짐). */
        private void load(List<DungeonProtocol.RewardGroup> list) {
            source = list;
            rewards.clear();
            for (int i = 0; i < 3; i++) {
                groups[i] = null;
                for (var group : list) if (group.kind() == KINDS[i]) { groups[i] = group; break; }
                var out = new ArrayList<Reward>();
                if (groups[i] != null)
                    for (int k = 0; k < groups[i].items().size(); k++) {
                        var one = Net.items(List.of(groups[i].items().get(k)));
                        if (!one.isEmpty()) out.add(new Reward(one.get(0), k < groups[i].chances().size() ? groups[i].chances().get(k) : -1));
                    }
                rewards.add(out);
            }
            tipScroll = 0;
        }

        @Override void paint(GuiGraphics g) {
            var e = e();
            if (e == null) return;
            if (source != e.rewards()) load(e.rewards());
            // 바탕 그림 · 오른쪽 패널 · 금테 · 닫기
            // 배경: 던전별 그림 textures/gui/art/<art>.png (없거나 빈 값이면 기본 = 리치 연구실), 화면 비율이 달라도 가득 (cover)
            String art = e.art() == null || e.art().isBlank() || !e.art().matches("[a-z0-9_./-]+") ? "lich" : e.art();
            Art.cover(g, Art.ui("art/" + art).isPresent() ? "art/" + art : "art/lich", 0, 0, 1672, 941, WHITE);
            Art.ui(g, "ref/right_panel", 984, 0, 688, 941, WHITE);
            Art.ui(g, "ref/entry_outer_frame", 0, 0, 1672, 941, WHITE);
            close(g, 1584, 26, 62);

            // 제목 (가운데, 양옆 장식선)
            float ts = Art.shrink(g, Art.Face.SERIF, e.name(), 66, 600, 36);
            String title = Art.fit(g, Art.Face.SERIF, e.name(), ts, 600);
            float tw = Art.width(g, Art.Face.SERIF, title, ts);
            Art.textCenter(g, Art.Face.SERIF, title, 836, 112, ts, CREAM);
            ornament(g, 836 - tw / 2 - 28, 113, 120, false, true);
            ornament(g, 836 + tw / 2 + 28, 113, 120, true, true);

            // 인원: 파티 = 사람 3 + "파티 n / 3", 1인 = 사람 1 + "1인 입장"
            boolean party = e.partySize() > 1;
            float py = 181, ls = 26, gw;
            String count = Math.max(1, e.partyCount()) + " / " + e.partySize();
            int people = Math.min(3, e.partySize());
            if (party) gw = people * 26 + 10 + Art.width(g, Art.Face.MEDIUM, "파티 ", ls) + Art.width(g, Art.Face.SERIF, count, ls);
            else gw = 38 + Art.width(g, Art.Face.MEDIUM, "1인 입장", ls);
            float x = 836 - gw / 2;
            ornament(g, x - 18, py, 86, false, false);
            ornament(g, x + gw + 18, py, 98, true, false);
            if (party) {
                for (int i = 0; i < people; i++) Art.icon(g, "icons/solo-person", x + 13 + i * 26, py, 48, i < e.partyCount() ? WHITE : 0x66FFFFFF);
                x += people * 26 + 10;
                x += Art.text(g, Art.Face.MEDIUM, "파티", x, py, ls, CREAM) + Art.width(g, Art.Face.MEDIUM, " ", ls);
                Art.text(g, Art.Face.SERIF, count, x, py + 1, ls, CREAM);
            } else {
                Art.icon(g, "icons/solo-person", x + 13, py, 48, WHITE);
                Art.text(g, Art.Face.MEDIUM, "1인 입장", x + 38, py, ls, CREAM);
            }

            // 내 기록 두 줄 (패널 구분선 157 ~ 281 사이)
            Art.text(g, Art.Face.MEDIUM, "클리어 여부", L, 196, 23, LABEL);
            Art.textRight(g, e.cleared() ? "클리어" : "미클리어", R, 196, 23, e.cleared() ? RGOLD : WHITE);
            Art.rect(g, 1208, 222, 412, 1, 0x30FFFFFF);
            Art.text(g, Art.Face.MEDIUM, "내 최고 기록", L, 251, 23, LABEL);
            Art.textRight(g, Net.time(e.personalBestMs()), R, 251, 23, e.personalBestMs() >= 0 ? RGOLD : WHITE);

            // 서버 최단 기록 (월계수 · 트로피 · 시간 · 닉네임 · 칭호)
            Art.ui(g, "ref/laurel_left", 1218, 333, 75, 125, WHITE);
            Art.ui(g, "ref/laurel_right", 1537, 333, 75, 125, WHITE);
            String label = "서버 최단 기록";
            float lw = 30 + Art.width(g, Art.Face.MEDIUM, label, 21);
            Art.icon(g, "icons/trophy", CX - lw / 2 + 13, 325, 44, WHITE);
            Art.text(g, Art.Face.MEDIUM, label, CX - lw / 2 + 30, 326, 21, RECORD);
            var best = e.best();
            if (best.ms() >= 0) {
                Art.textCenter(g, Art.Face.SERIF, Net.time(best.ms()), CX, 378, 58, RGOLD);
                if (!best.name().isBlank()) Art.textCenter(g, Art.fit(g, best.name(), 30, 290), CX, 430, 30, WHITE);
                titleCenter(g, best, CX, 461, 19, 290);
            } else Art.textCenter(g, Art.Face.SERIF, "기록 없음", CX, 395, 40, SUBTLE);

            // 보상 칸 3개: 묶음의 첫 아이템, 마우스를 올리면 왼쪽에 전체 목록
            updateTip();
            for (int i = 0; i < 3; i++) {
                boolean done = groups[i] != null && groups[i].done();
                Art.textCenter(g, KIND_LABEL[i], SLOT_X[i] + SLOT_W / 2, 520, 20, done ? SUBTLE : WHITE);
                Art.ui(g, "ref/reward_slot", SLOT_X[i], SLOT_Y, SLOT_W, SLOT_H, done ? 0xFF9A9A9A : WHITE);
                if (i == tip) Art.rect(g, SLOT_X[i] + 5, SLOT_Y + 5, SLOT_W - 10, SLOT_H - 10, 0x24FFD27A);
            }
            g.flush();
            var pose = g.pose();
            for (int i = 0; i < 3; i++) {
                if (rewards.get(i).isEmpty()) continue;
                pose.pushPose();
                pose.translate(SLOT_X[i] + 59 - 34, SLOT_Y + 57 - 34, 0);
                pose.scale(68 / 16f, 68 / 16f, 1);
                g.renderItem(rewards.get(i).get(0).stack(), 0, 0);
                pose.popPose();
            }
            g.flush();
            pose.pushPose();
            pose.translate(0, 0, 300);
            // [기능:보상수량 시작] 큰 보상 칸에 첫 아이템 수량 (2개 이상일 때)
            for (int i = 0; i < 3; i++) {
                if (rewards.get(i).isEmpty()) continue;
                int amount = rewards.get(i).get(0).stack().getCount();
                if (amount > 1) Art.textRight(g, "×" + amount, SLOT_X[i] + SLOT_W - 12, SLOT_Y + SLOT_H - 18, 24, WHITE);
            }
            // [기능:보상수량 끝]
            for (int i = 0; i < 3; i++) {
                if (groups[i] == null || !groups[i].done()) continue;
                Art.rect(g, SLOT_X[i] + 6, SLOT_Y + 6, SLOT_W - 12, SLOT_H - 12, 0xA60A0E16);
                float mw = 28 + Art.width(g, "받음", 21);
                Art.icon(g, "icons/check", SLOT_X[i] + 59 - mw / 2 + 11, SLOT_Y + 57, 34, WHITE);
                Art.text(g, "받음", SLOT_X[i] + 59 - mw / 2 + 28, SLOT_Y + 57, 21, RGOLD);
            }
            pose.popPose();

            // 진행 중 칸 · 입장권 (패널 구분선 709 아래)
            String progress = "진행 중 " + e.active() + " / " + e.max();
            float pw = 34 + Art.width(g, progress, 22);
            Art.icon(g, "icons/exit", CX - pw / 2 + 13, 734, 40, WHITE);
            Art.text(g, progress, CX - pw / 2 + 34, 735, 22, e.active() >= e.max() ? RGOLD : WHITE);
            boolean shortTicket = e.ticketNeed() > 0 && !e.ticketFree() && e.ticketHave() < e.ticketNeed();
            String ticketName = e.ticketName().isBlank() ? "입장권" : e.ticketName();
            String ticket = e.ticketNeed() == 0 ? "입장권 필요 없음" : e.ticketFree() ? "첫 입장 무료 · 이후 " + e.ticketNeed() + "장"
                    : ticketName + " " + e.ticketNeed() + "장 필요 · 보유 " + e.ticketHave();
            float tsz = Art.shrink(g, Art.Face.MEDIUM, ticket, 22, 340, 15);
            float tlw = 36 + Art.width(g, Art.Face.MEDIUM, ticket, tsz);
            Art.icon(g, "icons/ticket", CX - tlw / 2 + 14, 768, 42, WHITE);
            Art.text(g, Art.Face.MEDIUM, ticket, CX - tlw / 2 + 36, 769, tsz, WHITE);
            if (e.ticketNeed() > 0) {
                String check = shortTicket ? "입장권이 " + (e.ticketNeed() - e.ticketHave()) + "장 부족해요" : "입장권 확인 완료";
                float kw = 28 + Art.width(g, Art.Face.MEDIUM, check, 17);
                Art.icon(g, "icons/ticket", CX - kw / 2 + 11, 797, 32, shortTicket ? 0xB3F2A7A7 : 0x8CFFFFFF);
                Art.text(g, Art.Face.MEDIUM, check, CX - kw / 2 + 28, 797, 17, shortTicket ? WARN : SUBTLE);
            }

            // 금색 입장 버튼 (상태별 글자는 기존 뜻 그대로)
            var q = DungeonClient.queue;
            switch (e.button()) {
                case DungeonProtocol.BTN_ENTER -> goldButton(g, shortTicket ? "none" : "enter", !shortTicket, shortTicket ? "입장권이 부족해요" : party ? "파티 입장" : "입장하기");
                case DungeonProtocol.BTN_QUEUE -> goldButton(g, shortTicket ? "none" : "queue", !shortTicket, shortTicket ? "입장권이 부족해요" : "대기열 등록");
                case DungeonProtocol.BTN_QUEUED -> goldButton(g, "open-queue", true, "대기 중" + (q.position() > 0 ? " · " + q.position() + "번째" : ""));
                case DungeonProtocol.BTN_READY -> goldButton(g, "enter", true, party ? "파티 입장" : "입장하기");
                case DungeonProtocol.BTN_IN_RUN -> goldButton(g, "none", false, "도전 중이에요");
                case DungeonProtocol.BTN_ACCEPTING -> goldButton(g, "none", false, "파티원 수락 대기 중");
                default -> goldButton(g, "none", false, e.lockReason().isBlank() ? "입장할 수 없어요" : e.lockReason());
            }

            if (tip >= 0) drawTip(g, tip);
        }

        /** 툴팁 대상: 칸 위 = 그 칸 (다른 칸으로 옮길 때는 0.18초 머물러야 바뀜: 목록으로 가는 길에 지나는 칸 무시), 목록 위 = 유지. */
        private void updateTip() {
            int hover = -1;
            for (int i = 0; i < 3; i++) if (over(SLOT_X[i], SLOT_Y, SLOT_W, SLOT_H)) hover = i;
            long now = DungeonClient.now();
            if (hover != hoverSlot) { hoverSlot = hover; hoverSince = now; }
            if (hover >= 0) {
                if (tip < 0 || (hover != tip && now - hoverSince >= 180)) { tip = hover; tipScroll = 0; }
                return;
            }
            if (tip < 0) return;
            float top = SLOT_Y - 82, bottom = Math.max(top + tipHeight(tip), SLOT_Y + SLOT_H);
            if (!over(TIP_X, top, SLOT_X[tip] + SLOT_W - TIP_X, bottom - top)) tip = -1;
        }

        private float tipHeight(int i) { return TIP_HEAD + Math.max(1, Math.min(TIP_ROWS, rewards.get(i).size())) * TIP_ROW + TIP_PAD; }

        private void drawTip(GuiGraphics g, int i) {
            var list = rewards.get(i);
            float top = SLOT_Y - 82, h = tipHeight(i), k = TIP_W / 384f, cw = 412 * k;
            var pose = g.pose();
            pose.pushPose();
            pose.translate(0, 0, 400);
            // 판: 위(모서리·말풍선 꼬리)·아래 모서리는 그대로, 가운데만 세로로 늘림
            Art.ui("ref/reward_tooltip").ifPresent(t -> {
                float capT = 150 * k, capB = 40 * k, vT = 150f / 585, vB = 545f / 585;
                Art.quad(g, t, TIP_X, top, TIP_X + cw, top + capT, 0, 0, 1, vT, WHITE);
                Art.quad(g, t, TIP_X, top + capT, TIP_X + cw, top + h - capB, 0, vT, 1, vB, WHITE);
                Art.quad(g, t, TIP_X, top + h - capB, TIP_X + cw, top + h, 0, vB, 1, 1, WHITE);
            });
            Art.text(g, KIND_LABEL[i], TIP_X + 19, top + 28, 22, TIP_GOLD);
            float right = TIP_X + TIP_W - 18;
            if (groups[i] != null && groups[i].done()) Art.textRight(g, "받음", right, top + 28, 16, SUBTLE);
            else if (list.size() > TIP_ROWS) Art.textRight(g, (tipScroll + 1) + "-" + (tipScroll + TIP_ROWS) + " / " + list.size(), right, top + 28, 15, SUBTLE);
            if (list.isEmpty()) Art.text(g, Art.Face.MEDIUM, "설정된 보상이 없어요", TIP_X + 19, top + TIP_HEAD + 30, 19, SUBTLE);
            int n = Math.min(TIP_ROWS, list.size() - tipScroll);
            for (int r = 0; r < n; r++) {
                var row = list.get(tipScroll + r);
                float ry = top + TIP_HEAD + r * TIP_ROW, fx = TIP_X + 19, fy = ry + 3;
                if (r > 0) Art.rect(g, TIP_X + 14, ry - 3, TIP_W - 28, 1, 0x40C9A45C);
                Art.rect(g, fx, fy, 55, 55, 0xFF0B1424);
                Art.rect(g, fx, fy, 55, 1, 0x99C9A45C); Art.rect(g, fx, fy + 54, 55, 1, 0x99C9A45C);
                Art.rect(g, fx, fy, 1, 55, 0x99C9A45C); Art.rect(g, fx + 54, fy, 1, 55, 0x99C9A45C);
                float pctW = 0;
                if (row.chance() >= 0) {
                    String p = percent(row.chance());
                    pctW = Art.width(g, p, 18) + 10;
                    Art.textRight(g, p, right, ry + 30.5f, 18, TIP_GOLD);
                }
                int amount = row.stack().getCount();
                String name = row.stack().getHoverName().getString() + (amount > 1 ? " ×" + amount : "");
                Art.text(g, Art.Face.MEDIUM, Art.fit(g, Art.Face.MEDIUM, name, 20, right - pctW - (TIP_X + 89)), TIP_X + 89, ry + 30.5f, 20, WHITE);
                if (over(TIP_X + 14, ry, TIP_W - 28, TIP_ROW - 3)) {
                    tooltip = row.stack();
                    tooltipExtra = row.chance() >= 0 ? List.of(Component.literal("확률 " + percent(row.chance())).withColor(GOLD & 0xFFFFFF)) : List.of();
                }
            }
            g.flush();
            for (int r = 0; r < n; r++) {
                float ry = top + TIP_HEAD + r * TIP_ROW;
                pose.pushPose();
                pose.translate(TIP_X + 26, ry + 10, 0);
                pose.scale(41 / 16f, 41 / 16f, 1);
                g.renderItem(list.get(tipScroll + r).stack(), 0, 0);
                pose.popPose();
            }
            g.flush();
            pose.popPose();
        }

        /** 금색 큰 버튼 (button_gold). 글자는 마루부리, 길면 줄여서. */
        private void goldButton(GuiGraphics g, String id, boolean enabled, String label) {
            float bx = 1183, by = 812, bw = 445, bh = bw * 178 / 1024f;
            boolean hover = enabled && over(bx + 14, by, bw - 28, bh);
            Art.ui(g, "ref/button_gold", bx, by, bw, bh, !enabled ? 0xFF7C7C7C : hover ? WHITE : 0xFFE6E6E6);
            float size = Art.shrink(g, Art.Face.SERIF, label, 34, bw - 100, 18);
            Art.textCenter(g, Art.Face.SERIF, label, bx + bw / 2, by + bh / 2, size, enabled ? BTN_TEXT : SUBTLE);
            hit(id, bx + 14, by, bw - 28, bh, enabled);
        }

        /** 칭호 한 줄 가운데 정렬 (접두·접미 각자 색). */
        private static void titleCenter(GuiGraphics g, DungeonProtocol.Holder h, float cx, float cy, float size, float maxW) {
            String pre = h.prefix().name(), suf = h.suffix().name();
            float pw = pre.isBlank() ? 0 : Art.width(g, Art.Face.MEDIUM, pre, size), sw = suf.isBlank() ? 0 : Art.width(g, Art.Face.MEDIUM, suf, size);
            float gap = pw > 0 && sw > 0 ? size * 0.4f : 0, total = pw + gap + sw;
            if (total <= 0) return;
            if (total > maxW) { size *= maxW / total; pw *= maxW / total; gap *= maxW / total; total = maxW; }
            float x = cx - total / 2;
            if (pw > 0) Art.text(g, Art.Face.MEDIUM, pre, x, cy, size, 0xFF000000 | h.prefix().color());
            if (sw > 0) Art.text(g, Art.Face.MEDIUM, suf, x + pw + gap, cy, size, 0xFF000000 | h.suffix().color());
        }

        /** 장식선 (오른쪽 패널 구분선의 왼쪽 절반 그림): 마름모 가운데 dx, 선 길이 len, mirror = 오른쪽으로 뻗음. diamond=false 면 선만. */
        private static void ornament(GuiGraphics g, float dx, float y, float len, boolean mirror, boolean diamond) {
            Art.ui("ref/divider_half").ifPresent(t -> {
                float d = 15, tw = 238, ul = (tw - d) / tw, y0 = y - 8, y1 = y + 8;
                if (!diamond) {
                    float ue = 200 / tw;
                    if (mirror) Art.quad(g, t, dx, y0, dx + len, y1, ue, 0, 0, 1, WHITE);
                    else Art.quad(g, t, dx - len, y0, dx, y1, 0, 0, ue, 1, WHITE);
                    return;
                }
                Art.quad(g, t, dx - d, y0, dx, y1, ul, 0, 1, 1, WHITE);
                Art.quad(g, t, dx, y0, dx + d, y1, 1, 0, ul, 1, WHITE);
                if (mirror) Art.quad(g, t, dx + d, y0, dx + len, y1, ul, 0, 0, 1, WHITE);
                else Art.quad(g, t, dx - len, y0, dx - d, y1, 0, 0, ul, 1, WHITE);
            });
        }

        @Override void clicked(String id) {
            var e = e();
            if (e == null) return;
            switch (id) {
                case "enter" -> Net.send(DungeonProtocol.C_ENTER, e.session());
                case "queue" -> Net.send(DungeonProtocol.C_QUEUE_JOIN, e.session());
                case "open-queue" -> DungeonClient.open(new QueueScreen());
                default -> { }
            }
        }

        @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
            if (tip >= 0) {
                int n = rewards.get(tip).size();
                if (n > TIP_ROWS) tipScroll = Math.max(0, Math.min(n - TIP_ROWS, tipScroll - (int) Math.signum(sy)));
            }
            return true;
        }
    }
    // [기능:파티던전 끝]

    // ------------------------------------------------------------ 대기 / 입장 준비

    static final class QueueScreen extends Base {
        QueueScreen() { super("던전 대기"); }
        @Override float designW() { return 230; }
        @Override float designH() { return 196; }

        @Override void paint(GuiGraphics g) {
            var q = DungeonClient.queue;
            Art.nine(g, "panels/modal-panel", 0, 0, 230, 196, 28, 10, 0xFFFFFFFF);
            close(g, 206, 10, 13);
            Art.textCenter(g, Art.fit(g, q.name(), 11, 190), 115, 24, 11, WHITE);
            divider(g, 18, 38, 194);
            boolean ready = q.state() == DungeonProtocol.Q_READY;
            Art.ui(g, ready ? "icons/ticket" : "icons/solo-person", 101, 48, 28, 28, ready ? 0xFFFFFFFF : 0xFFE3E8EF);
            if (ready) {
                long left = Math.max(0, q.offerLeftMs() - (DungeonClient.now() - DungeonClient.queueAt));
                Art.textCenter(g, "입장 준비 완료", 115, 92, 13, GOLD);
                Art.textCenter(g, "남은 시간 " + (left + 999) / 1000 + "초 · 지나면 다음 사람에게 넘어가요", 115, 109, 7.5f, SILVER);
                Art.textCenter(g, "입장할 때 입장권을 확인해요", 115, 121, 7, MUTED);
                button(g, "enter", "primary", 22, 138, 186, 22, "입장하기");
                button(g, "cancel", "secondary", 22, 164, 186, 18, "대기 취소");
            } else if (q.state() == DungeonProtocol.Q_WAITING) {
                Art.textCenter(g, "내 순서 " + q.position() + "번째", 115, 92, 13, WHITE);
                Art.textCenter(g, "진행 중 " + q.active() + " / " + q.max() + " · 대기 " + q.queued() + "명", 115, 109, 8, SILVER);
                Art.textCenter(g, "창을 닫아도 대기는 유지돼요. 차례가 오면 알려 드려요.", 115, 123, 7, MUTED);
                button(g, "cancel", "secondary", 22, 150, 186, 22, "대기 취소");
            } else {
                Art.textCenter(g, "대기 중이 아니에요", 115, 100, 10, MUTED);
                button(g, "close", "secondary", 22, 150, 186, 22, "닫기");
            }
        }

        @Override void clicked(String id) {
            switch (id) {
                case "enter" -> Net.send(DungeonProtocol.C_ENTER, DungeonClient.queue.session());
                case "cancel" -> { Net.send(DungeonProtocol.C_QUEUE_LEAVE, 0); onClose(); }
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------ 파티 입장 수락
    // [기능:파티던전 시작] acceptance_panel 그림 (캔버스 = 잘라낸 그림 1215×938 의 절반), 줄 3개 = 파티원, 10초 타이머, 수락·거절

    static final class AcceptScreen extends Base {
        private static final int CREAM = 0xFFF4EAD8, SUBTLE = 0xFFA9B3C2, OK = 0xFF9BE3A6, NO = 0xFFF0938F, RGOLD = 0xFFF3CD6E;
        private static final float W = 607.5f, H = 469, ROW_X0 = 34, ROW_X1 = 573;
        private static final float[] ROW_CY = {168.75f, 251.25f, 332.75f};
        private final int session;
        private boolean answered;

        AcceptScreen(int session) { super("파티 입장 수락"); this.session = session; }
        int session() { return session; }
        @Override float designW() { return W; }
        @Override float designH() { return H; }
        @Override float fill() { return 0.62f; }
        @Override float maxScale() { return 4f; }

        @Override void paint(GuiGraphics g) {
            var a = DungeonClient.accept;
            if (a == null || a.session() != session) { onClose(); return; }
            var mc = Minecraft.getInstance();
            var me = mc.player == null ? null : mc.player.getUUID();
            for (var m : a.members()) if (m.id().equals(me) && m.state() != 0) answered = true;
            Art.ui(g, "ref/acceptance_panel", 0, 0, W, H, WHITE);

            // 제목 · 타이머 (받은 남은 시간에서 흐른 만큼 뺀다)
            String title = a.name() + " 입장 수락";
            float ts = Art.shrink(g, Art.Face.SERIF, title, 30, 500, 18);
            Art.textCenter(g, Art.Face.SERIF, title, W / 2, 52, ts, CREAM);
            long left = Math.max(0, a.leftMs() - (DungeonClient.now() - DungeonClient.acceptAt));
            if (DungeonClient.now() - DungeonClient.acceptAt > a.leftMs() + 3000) { onClose(); return; } // 서버 답이 없을 때 안전장치
            String sec = (left + 999) / 1000 + "초";
            float sw = 30 + Art.width(g, Art.Face.SERIF, sec, 26);
            Art.icon(g, "icons/hourglass", W / 2 - sw / 2 + 11, 92, 34, WHITE);
            Art.text(g, Art.Face.SERIF, sec, W / 2 - sw / 2 + 30, 93, 26, RGOLD);
            float total = Math.max(1, DungeonClient.acceptTotal), bw = 300;
            Art.rect(g, W / 2 - bw / 2, 114, bw, 3, 0x40FFFFFF);
            Art.rect(g, W / 2 - bw / 2, 114, bw * Math.min(1, left / total), 3, 0xFFE9C46A);

            // 파티원 줄: 얼굴 · 닉네임 · 상태
            g.flush();
            for (int i = 0; i < Math.min(3, a.members().size()); i++) {
                var m = a.members().get(i);
                float cy = ROW_CY[i];
                face(g, mc, m.id(), ROW_X0 + 22, cy - 23, 46);
                Art.text(g, Art.fit(g, m.name(), 22, 250), ROW_X0 + 84, cy, 22, m.id().equals(me) ? RGOLD : WHITE);
                String state = m.state() == 1 ? "수락" : m.state() == 2 ? "거절" : "기다리는 중";
                String icon = m.state() == 1 ? "icons/check" : m.state() == 2 ? "icons/close" : "icons/hourglass";
                int color = m.state() == 1 ? OK : m.state() == 2 ? NO : SUBTLE;
                float stw = Art.width(g, Art.Face.MEDIUM, state, 20);
                Art.textRight(g, Art.Face.MEDIUM, state, ROW_X1 - 24, cy, 20, color);
                Art.icon(g, icon, ROW_X1 - 24 - stw - 18, cy, 32, m.state() == 0 ? 0xB3FFFFFF : WHITE);
            }

            // 수락 · 거절 (답한 뒤에는 둘 다 잠금)
            boolean on = !answered;
            float bw2 = 205, ax = W / 2 - 8 - bw2, rx = W / 2 + 8, ah = bw2 * 161 / 768f, rh = bw2 * 150 / 768f, cy = 412;
            boolean ha = on && over(ax, cy - ah / 2, bw2, ah), hr = on && over(rx, cy - rh / 2, bw2, rh);
            Art.ui(g, "ref/button_accept", ax, cy - ah / 2, bw2, ah, !on ? 0xFF7C7C7C : ha ? WHITE : 0xFFE6E6E6);
            Art.ui(g, "ref/button_reject", rx, cy - rh / 2, bw2, rh, !on ? 0xFF7C7C7C : hr ? WHITE : 0xFFE6E6E6);
            Art.textCenter(g, Art.Face.SERIF, "수락", ax + bw2 / 2, cy, 22, on ? WHITE : SUBTLE);
            Art.textCenter(g, Art.Face.SERIF, "거절", rx + bw2 / 2, cy, 22, on ? WHITE : SUBTLE);
            hit("accept", ax, cy - ah / 2, bw2, ah, on);
            hit("reject", rx, cy - rh / 2, bw2, rh, on);
            if (answered) Art.textCenter(g, Art.Face.MEDIUM, "다른 파티원의 답을 기다리고 있어요", W / 2, 446, 14, SUBTLE);
        }

        /** 플레이어 얼굴 (접속 목록의 스킨, 없으면 기본 스킨). */
        private static void face(GuiGraphics g, Minecraft mc, java.util.UUID id, float x, float y, float size) {
            var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
            var skin = info != null ? info.getSkin() : net.minecraft.client.resources.DefaultPlayerSkin.get(id);
            Art.rect(g, x - 2, y - 2, size + 4, size + 4, 0xFFC9A45C);
            g.flush();
            var pose = g.pose();
            pose.pushPose();
            pose.translate(x, y, 0);
            pose.scale(size / 8f, size / 8f, 1);
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, skin, 0, 0, 8);
            pose.popPose();
            g.flush();
        }

        @Override void clicked(String id) {
            if (answered) return;
            switch (id) {
                case "accept" -> { answered = true; Net.send(DungeonProtocol.C_ACCEPT, session); }
                case "reject" -> { answered = true; Net.send(DungeonProtocol.C_REJECT, session); }
                default -> { }
            }
        }

        /** 10초짜리 창: Esc 로 닫지 않고 수락·거절로 답한다 (시간이 지나면 서버가 닫는다). */
        @Override public boolean shouldCloseOnEsc() { return false; }
    }
    // [기능:파티던전 끝]

    // ------------------------------------------------------------ 나가기 확인

    static final class ExitConfirm extends Base {
        ExitConfirm() { super("던전 나가기"); }
        @Override float designW() { return 230; }
        @Override float designH() { return 160; }

        @Override void paint(GuiGraphics g) {
            if (!DungeonClient.run.active()) { onClose(); return; }
            Art.nine(g, "panels/modal-panel", 0, 0, 230, 160, 28, 10, 0xFFFFFFFF);
            Art.ui(g, "icons/exit", 101, 16, 28, 28, 0xFFFFFFFF);
            // [기능:파티던전 시작] 파티: 파티장 = 모두 나가기(전원 실패), 파티원 = 혼자 빠짐(남은 파티원은 계속)
            var run = DungeonClient.run;
            String head = !run.party() ? "던전에서 나갈까요?" : run.leader() ? "모두 던전에서 나갈까요?" : "파티에서 빠져 마을로 갈까요?";
            String sub = !run.party() ? "지금 나가면 실패로 처리되고 기록이 남지 않아요." : run.leader() ? "파티원 전원이 실패로 처리돼요" : "남은 파티원은 계속 진행해요";
            Art.textCenter(g, Art.fit(g, head, 12, 206), 115, 58, 12, WHITE);
            Art.textCenter(g, Art.fit(g, sub, 7.5f, 206), 115, 75, 7.5f, SILVER);
            // [기능:파티던전 끝]
            Art.ui(g, "icons/hourglass", 82, 84, 10, 10, 0xFFFFFFFF);
            Art.text(g, Net.time(DungeonClient.elapsed()), 95, 89.5f, 8, GOLD);
            button(g, "stay", "secondary", 18, 112, 94, 24, "계속 도전");
            button(g, "exit", "danger", 118, 112, 94, 24, "나가기");
        }

        @Override void clicked(String id) {
            if (id.equals("exit")) Net.send(DungeonProtocol.C_EXIT, 0);
            onClose();
        }
    }

    // ------------------------------------------------------------ 결과

    static final class ResultScreen extends Base {
        private final DungeonProtocol.Result r;
        private final List<ItemStack> given;
        private int scroll;

        ResultScreen(DungeonProtocol.Result r) { super("던전 결과"); this.r = r; this.given = Net.items(r.given()); }
        @Override float designW() { return 270; }
        @Override float designH() { return 268; }

        @Override void paint(GuiGraphics g) {
            Art.nine(g, "panels/modal-panel", 0, 0, 270, 268, 28, 10, 0xFFFFFFFF);
            Art.ui(g, r.cleared() ? "icons/laurel" : "icons/hourglass", 117, 12, 36, 36, r.cleared() ? 0xFFFFFFFF : 0xFFB9C2CE);
            Art.textCenter(g, r.cleared() ? "클리어!" : "도전 실패", 135, 60, 15, r.cleared() ? GOLD : 0xFFE8B0B0);
            Art.textCenter(g, Art.fit(g, r.name() + (r.cleared() ? "" : " · " + r.reason()), 8, 240), 135, 76, 8, SILVER);
            divider(g, 18, 86, 234);
            float x = 24;
            Art.text(g, "이번 기록", x, 98, 7.5f, MUTED);
            Art.text(g, r.cleared() ? Net.time(r.timeMs()) : "—", x + 76, 98, 10, r.cleared() ? GOLD : MUTED);
            if (r.newPersonal()) { Art.nine(g, "panels/record-badge", x + 150, 91, 72, 14, 20, 5, 0xFFFFFFFF); Art.textCenter(g, r.firstClear() ? "첫 클리어" : "개인 최고 갱신", x + 186, 98.5f, 7, GOLD); }
            Art.text(g, "이전 개인 기록", x, 113, 7.5f, MUTED);
            Art.text(g, r.previousBestMs() < 0 ? "기록 없음" : Net.time(r.previousBestMs()), x + 76, 113, 8.5f, WHITE);
            Art.ui(g, "icons/trophy", x - 2, 121, 11, 11, 0xFFFFFFFF);
            Art.text(g, "서버 최고 기록", x + 12, 127, 7.5f, MUTED);
            var best = r.best();
            Art.text(g, best.ms() < 0 ? "기록 없음" : Net.time(best.ms()), x + 76, 127, 8.5f, GOLD);
            if (r.newServer()) Art.text(g, "서버 최고 기록!", x + 150, 127, 7.5f, GOLD);
            Art.text(g, best.ms() < 0 || best.name().isBlank() ? "—" : Art.fit(g, best.name(), 8.5f, 200), x + 12, 140, 8.5f, WHITE);
            titleLine(g, best, x + 12, 151, 7);
            divider(g, 18, 161, 234);
            Art.text(g, r.cleared() ? (r.firstClear() ? "받은 보상 (첫 클리어)" : "받은 보상") : "보상 없음", x, 171, 7.5f, SILVER);
            if (r.cleared()) {
                int rows = slots(g, given, x, 178, 9, 2, scroll, 22.5f);
                if (rows > 2) Art.textRight(g, (scroll + 1) + "-" + Math.min(rows, scroll + 2) + " / " + rows + "줄", 246, 171, 6.5f, MUTED);
                Art.textCenter(g, Art.fit(g, r.rewardNote(), 7, 230), 135, 229, 7, MUTED);
            }
            button(g, "ok", "primary", 60, 238, 150, 20, "확인");
        }

        @Override void clicked(String id) { onClose(); }

        @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
            int rows = (given.size() + 8) / 9;
            if (rows > 2) scroll = Math.max(0, Math.min(rows - 2, scroll - (int) Math.signum(sy)));
            return true;
        }
    }
}
