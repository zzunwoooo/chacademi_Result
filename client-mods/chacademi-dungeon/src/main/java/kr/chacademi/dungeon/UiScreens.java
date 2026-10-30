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
        private ItemStack tooltip = ItemStack.EMPTY;

        Base(String title) { super(Component.literal(title)); }

        abstract float designW();
        abstract float designH();
        abstract void paint(GuiGraphics g);
        abstract void clicked(String id);
        boolean dim() { return true; }

        @Override public boolean isPauseScreen() { return false; }

        @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
            if (dim()) Art.ui(g, "panels/screen-dimmer", 0, 0, width, height, 0xFFFFFFFF);
        }

        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            renderBackground(g, mouseX, mouseY, delta);
            scale = Math.min(Math.min(width * 0.94f / designW(), height * 0.94f / designH()), 2.5f);
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
                if (tooltipExtra.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
                else {
                    var lines = new ArrayList<Component>(Screen.getTooltipFromItem(Minecraft.getInstance(), tooltip));
                    lines.addAll(tooltipExtra);
                    g.renderTooltip(font, lines, tooltip.getTooltipImage(), mouseX, mouseY);
                }
            }
        }

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

    static final class EntryScreen extends Base {
        private static final int TITLE = 0xFFEFA9FF, TITLE_GLOW = 0x669B3BFF, WARN = 0xFFF2A7A7, LABEL = 0xFF9AA6B8;
        private final int session;
        private List<DungeonProtocol.RewardGroup> source;
        private final List<List<ItemStack>> rewardItems = new ArrayList<>();
        private int[] offsets = new int[0];
        private static final float RX = 270, RW = 196, ROW_Y = 141, ROW_H = 22, SLOT = 18;
        private static final int COLS = 7;

        EntryScreen(int session) { super("던전 입장"); this.session = session; }
        int session() { return session; }
        @Override float designW() { return 480; }
        @Override float designH() { return 270; }

        private DungeonProtocol.Entry e() { return DungeonClient.entry; }

        @Override void paint(GuiGraphics g) {
            var e = e();
            if (e == null) return;
            if (source != e.rewards()) {
                source = e.rewards();
                rewardItems.clear();
                for (var group : source) rewardItems.add(Net.items(group.items()));
                offsets = new int[source.size()];
            }
            // 바탕 · 일러스트(왼쪽, 오른쪽 끝을 바탕색으로 흐리게) · 금테
            Art.ui(g, "panels/panel-fill", 0, 0, 480, 270, 0xFFFFFFFF);
            Art.cover(g, "illustration/lich-laboratory", 6, 6, 252, 258, 0xFFFFFFFF);
            Art.ui(g, "panels/panel-fill", 226, 6, 32, 258, 0x99FFFFFF);
            Art.nine(g, "panels/entry-frame", 0, 0, 480, 270, 28, 10, 0xFFFFFFFF);
            close(g, 456, 10, 14);
            float x = RX, w = RW;

            // 제목: 크게, 보랏빛 + 은은한 번짐 / 부제는 작게 바로 아래
            String title = Art.fit(g, e.name(), 17, w - 20);
            for (float[] o : new float[][]{{-.8f, 0}, {.8f, 0}, {0, -.8f}, {0, .8f}}) Art.text(g, title, x + o[0], 27 + o[1], 17, TITLE_GLOW);
            Art.text(g, title, x, 27, 17, TITLE);
            Art.text(g, Art.fit(g, e.subtitle(), 6.6f, w), x + .5f, 40, 6.6f, LABEL);

            // 규칙 칩: 1인 전용 · 체력 자동 회복 없음
            float cx = chip(g, x, 47, "icons/solo-person", "1인 전용", SILVER);
            if (e.noRegen()) chip(g, cx + 4, 47, "icons/hourglass", "체력 자동 회복 없음", WARN);
            divider(g, x, 65, w);

            // 내 기록: 클리어 여부와 최고 기록을 한 줄로
            Art.text(g, "내 기록", x, 76, 6.8f, LABEL);
            float vx = x + 56;
            if (e.cleared()) {
                Art.ui(g, "icons/check", vx, 71, 10, 10, 0xFFFFFFFF);
                float at = vx + 12 + Art.text(g, "클리어", vx + 12, 76, 8, GOLD);
                Art.text(g, " ·  " + Net.time(e.personalBestMs()), at, 76, 8, WHITE);
            } else Art.text(g, "미클리어  ·  기록 없음", vx, 76, 8, MUTED);

            // 서버 최고 기록: 이름 자리에 이름(없으면 "기록 없음" 같은 크기·자리), 칭호는 있을 때만
            var best = e.best();
            Art.ui(g, "icons/trophy", x - 1, 84, 10, 10, 0xFFFFFFFF);
            Art.text(g, "서버 최고 기록", x + 12, 89.5f, 6.8f, LABEL);
            if (best.ms() >= 0) Art.textRight(g, Net.time(best.ms()), x + w, 89.5f, 8.5f, GOLD);
            boolean hasRecord = best.ms() >= 0 && !best.name().isBlank();
            Art.text(g, hasRecord ? Art.fit(g, best.name(), 9.5f, w - 14) : "기록 없음", x + 12, 103, 9.5f, hasRecord ? WHITE : MUTED);
            if (hasRecord && (!best.prefix().name().isBlank() || !best.suffix().name().isBlank())) titleLine(g, best, x + 12, 115, 6.8f);
            divider(g, x, 125, w);

            // 보상: 기본 / 첫 클리어 / 확률 — 줄마다 칸 7개, 더 있으면 그 줄에서 휠
            Art.text(g, "보상", x, 134, 7.5f, SILVER);
            if (source.isEmpty()) Art.text(g, "설정된 보상이 없어요", x, 152, 7.5f, MUTED);
            for (int i = 0; i < Math.min(3, source.size()); i++) {
                var group = source.get(i);
                float y = ROW_Y + i * ROW_H;
                int color = group.kind() == DungeonProtocol.R_RANDOM ? GOLD : group.kind() == DungeonProtocol.R_FIRST ? 0xFFF4C7FF : SILVER;
                Art.text(g, shortLabel(group.kind()), x, y + SLOT / 2f - (group.done() ? 3.2f : 0), 7, group.done() ? MUTED : color);
                if (group.done()) Art.text(g, "받음", x, y + SLOT / 2f + 4.2f, 5.6f, MUTED);
                var items = rewardItems.get(i);
                rowSlots(g, items, x + 46, y, COLS, offsets[i], SLOT, group.chances(), group.done());
                if (items.size() > COLS) Art.textRight(g, (offsets[i] + 1) + "-" + Math.min(items.size(), offsets[i] + COLS) + "/" + items.size(), x + w, y - 2.5f, 5.2f, MUTED);
            }

            // 진행 중 칸 · 입장권
            float fy = 216;
            Art.ui(g, "icons/circle-node", x, fy - 5, 10, 10, 0xFFFFFFFF);
            Art.text(g, "진행 중 " + e.active() + " / " + e.max(), x + 13, fy, 7.5f, e.active() >= e.max() ? GOLD : WHITE);
            String ticket;
            int ticketColor = WHITE;
            if (e.ticketNeed() == 0) ticket = "입장권 필요 없음";
            else if (e.ticketFree()) { ticket = "첫 입장 무료 · 이후 " + e.ticketNeed() + "장"; ticketColor = GOLD; }
            else {
                ticket = e.ticketName() + " " + e.ticketNeed() + "장 필요 · 보유 " + e.ticketHave();
                if (e.ticketHave() < e.ticketNeed()) ticketColor = WARN;
            }
            float tw = Art.width(g, ticket, 7.2f);
            Art.ui(g, "icons/ticket", x + w - tw - 14, fy - 6, 12, 12, 0xFFFFFFFF);
            Art.textRight(g, ticket, x + w, fy, 7.2f, ticketColor);

            boolean shortTicket = e.ticketNeed() > 0 && !e.ticketFree() && e.ticketHave() < e.ticketNeed();
            var q = DungeonClient.queue;
            float by = 229, bh = 25;
            switch (e.button()) {
                case DungeonProtocol.BTN_ENTER -> button(g, shortTicket ? "none" : "enter", shortTicket ? "disabled" : "primary", x, by, w, bh, shortTicket ? "입장권이 부족해요" : "입장하기");
                case DungeonProtocol.BTN_QUEUE -> button(g, shortTicket ? "none" : "queue", shortTicket ? "disabled" : "primary", x, by, w, bh, shortTicket ? "입장권이 부족해요" : "대기열 등록");
                case DungeonProtocol.BTN_QUEUED -> button(g, "open-queue", "secondary", x, by, w, bh, "대기 중" + (q.position() > 0 ? " · " + q.position() + "번째" : "") + " — 대기 창 보기");
                case DungeonProtocol.BTN_READY -> button(g, "enter", "primary", x, by, w, bh, "입장하기 (입장 준비 완료)");
                case DungeonProtocol.BTN_IN_RUN -> button(g, "none", "disabled", x, by, w, bh, "도전 중이에요");
                default -> button(g, "none", "disabled", x, by, w, bh, e.lockReason().isBlank() ? "입장할 수 없어요" : e.lockReason());
            }
        }

        private static String shortLabel(int kind) {
            return kind == DungeonProtocol.R_RANDOM ? "확률" : kind == DungeonProtocol.R_FIRST ? "첫 클리어" : "기본";
        }

        /** 작은 칩 (record-badge 9-slice + 아이콘 + 글자). 돌려준 값 = 칩 오른쪽 끝 x. */
        private float chip(GuiGraphics g, float x, float y, String icon, String text, int color) {
            float tw = Art.width(g, text, 6.6f), w = tw + 21, h = 13;
            Art.nine(g, "panels/record-badge", x, y, w, h, 20, 4.5f, 0xE6FFFFFF);
            Art.ui(g, icon, x + 5, y + 2.5f, 8, 8, 0xFFFFFFFF);
            Art.text(g, text, x + 15, y + h / 2 + .4f, 6.6f, color);
            return x + w;
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
            float ly = (float) ((y - oy) / scale);
            int row = (int) Math.floor((ly - ROW_Y) / ROW_H);
            if (row < 0 || row >= Math.min(3, rewardItems.size())) return true;
            int n = rewardItems.get(row).size();
            if (n > COLS) offsets[row] = Math.max(0, Math.min(n - COLS, offsets[row] - (int) Math.signum(sy)));
            return true;
        }
    }

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

    // ------------------------------------------------------------ 나가기 확인

    static final class ExitConfirm extends Base {
        ExitConfirm() { super("던전 나가기"); }
        @Override float designW() { return 230; }
        @Override float designH() { return 160; }

        @Override void paint(GuiGraphics g) {
            if (!DungeonClient.run.active()) { onClose(); return; }
            Art.nine(g, "panels/modal-panel", 0, 0, 230, 160, 28, 10, 0xFFFFFFFF);
            Art.ui(g, "icons/exit", 101, 16, 28, 28, 0xFFFFFFFF);
            Art.textCenter(g, "던전에서 나갈까요?", 115, 58, 12, WHITE);
            Art.textCenter(g, "지금 나가면 실패로 처리되고 기록이 남지 않아요.", 115, 75, 7.5f, SILVER);
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
