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
            var pose = g.pose();
            pose.pushPose();
            pose.translate(ox, oy, 0);
            pose.scale(scale, scale, 1);
            paint(g);
            pose.popPose();
            g.flush();
            if (!tooltip.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
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
        private final int session;
        private int scroll;
        private List<ItemStack> rewards = List.of();
        private List<byte[]> rewardSource;

        EntryScreen(int session) { super("던전 입장"); this.session = session; }
        int session() { return session; }
        @Override float designW() { return 480; }
        @Override float designH() { return 270; }

        private DungeonProtocol.Entry e() {
            var e = DungeonClient.entry;
            return e;
        }

        @Override void paint(GuiGraphics g) {
            var e = e();
            if (e == null) return;
            if (rewardSource != e.rewards()) { rewardSource = e.rewards(); rewards = Net.items(e.rewards()); }
            Art.ui(g, "panels/panel-fill", 0, 0, 480, 270, 0xFFFFFFFF);
            Art.cover(g, "illustration/lich-laboratory", 6, 6, 250, 258, 0xFFFFFFFF);
            Art.nine(g, "panels/entry-frame", 0, 0, 480, 270, 28, 10, 0xFFFFFFFF);
            close(g, 456, 10, 14);
            float x = 268, w = 200;
            Art.text(g, Art.fit(g, e.name(), 15, w - 20), x, 26, 15, WHITE);
            Art.text(g, Art.fit(g, e.subtitle(), 7.5f, w), x, 42, 7.5f, MUTED);
            Art.ui(g, "icons/solo-person", x, 50, 10, 10, 0xFFFFFFFF);
            Art.text(g, "1인 전용 · 혼자서만 입장할 수 있어요", x + 13, 55.5f, 7.5f, SILVER);
            divider(g, x, 66, w);
            // 기록
            Art.text(g, "클리어", x, 78, 7, MUTED);
            if (e.cleared()) { Art.ui(g, "icons/check", x + 70, 73, 10, 10, 0xFFFFFFFF); Art.text(g, "클리어함", x + 83, 78, 8, GOLD); }
            else Art.text(g, "아직 클리어하지 않았어요", x + 70, 78, 8, WHITE);
            Art.text(g, "내 최고 기록", x, 93, 7, MUTED);
            Art.nine(g, "panels/record-badge", x + 68, 86, 70, 14, 20, 5, 0xFFFFFFFF);
            Art.textCenter(g, e.personalBestMs() < 0 ? "기록 없음" : Net.time(e.personalBestMs()), x + 103, 93.5f, 8, e.personalBestMs() < 0 ? MUTED : WHITE);
            Art.ui(g, "icons/trophy", x, 104, 11, 11, 0xFFFFFFFF);
            Art.text(g, "서버 최고 기록", x + 14, 110, 7, MUTED);
            var best = e.best();
            Art.text(g, best.ms() < 0 ? "기록 없음" : Net.time(best.ms()), x + 82, 110, 9, best.ms() < 0 ? MUTED : GOLD);
            Art.text(g, best.ms() < 0 || best.name().isBlank() ? "—" : Art.fit(g, best.name(), 9, w - 14), x + 14, 123, 9, WHITE);
            titleLine(g, best, x + 14, 134, 7);
            divider(g, x, 144, w);
            // 보상 (많으면 칸 안에서 휠로 넘김)
            Art.text(g, e.rewardLabel(), x, 153, 7.5f, SILVER);
            if (rewards.isEmpty()) Art.text(g, "설정된 보상이 없어요", x, 172, 7.5f, MUTED);
            int rows = slots(g, rewards, x, 160, 8, 2, scroll, 22.5f);
            if (rows > 2) Art.textRight(g, (scroll + 1) + "-" + Math.min(rows, scroll + 2) + " / " + rows + "줄", x + w, 153, 6.5f, MUTED);
            // 인스턴스·입장권
            Art.ui(g, "icons/circle-node", x, 212, 10, 10, 0xFFFFFFFF);
            Art.text(g, "진행 중 " + e.active() + " / " + e.max(), x + 13, 217, 8, e.active() >= e.max() ? GOLD : WHITE);
            Art.ui(g, "icons/ticket", x + 92, 211, 12, 12, 0xFFFFFFFF);
            String ticket = e.ticketNeed() == 0 ? "입장권 없이 입장 (첫 클리어 전)" : Art.fit(g, e.ticketName(), 7.5f, 60) + " " + e.ticketNeed() + "장 (보유 " + e.ticketHave() + ")";
            Art.text(g, Art.fit(g, ticket, 7.5f, w - 106), x + 106, 217, 7.5f, e.ticketNeed() > e.ticketHave() ? 0xFFF0A8A8 : WHITE);
            // 버튼
            var q = DungeonClient.queue;
            switch (e.button()) {
                case DungeonProtocol.BTN_ENTER -> {
                    if (e.ticketNeed() > e.ticketHave()) button(g, "none", "disabled", x, 232, w, 22, "입장권이 부족해요");
                    else button(g, "enter", "primary", x, 232, w, 22, "입장하기");
                }
                case DungeonProtocol.BTN_QUEUE -> {
                    if (e.ticketNeed() > e.ticketHave()) button(g, "none", "disabled", x, 232, w, 22, "입장권이 부족해요");
                    else button(g, "queue", "primary", x, 232, w, 22, "대기열 등록");
                }
                case DungeonProtocol.BTN_QUEUED -> button(g, "open-queue", "secondary", x, 232, w, 22, "대기 중" + (q.position() > 0 ? " · " + q.position() + "번째" : "") + " — 대기 창 보기");
                case DungeonProtocol.BTN_READY -> button(g, "enter", "primary", x, 232, w, 22, "입장하기 (입장 준비 완료)");
                case DungeonProtocol.BTN_IN_RUN -> button(g, "none", "disabled", x, 232, w, 22, "도전 중이에요");
                default -> button(g, "none", "disabled", x, 232, w, 22, e.lockReason().isBlank() ? "입장할 수 없어요" : e.lockReason());
            }
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
            int rows = (rewards.size() + 7) / 8;
            if (rows > 2) scroll = Math.max(0, Math.min(rows - 2, scroll - (int) Math.signum(sy)));
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
