package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Util;
import school.magiccodex.protocol.DrawBoardProtocol;

/**
 * 「행운의 뽑기판」 등록 관리 창 (/뽑기판 관리, §44). 상자 관리 창(LootBoxAdminScreen)과 같은 판·글꼴·버튼·인벤토리 고르기 창.
 * 왼쪽 10 × 10 = 등록된 아이템(등록 순서), 오른쪽 = 고른 아이템·삭제. 「인벤토리에서 등록」 → 내 인벤토리 칸 고르기 → 확인.
 * 등록은 "내 인벤토리 몇 번 칸"만 보내고 서버가 실제 칸을 다시 읽어 원본 그대로(개수 포함) 공유 DB 에 저장한다. 아이템은 소모하지 않는다.
 */
public final class DrawBoardAdminScreen extends SocialScreen implements DrawBoardClient.Viewer {
    private static final int SLOT = 60, SLOT_GAP = 6, LIST_X = 50, LIST_Y = 200, PICK = 76, PICK_GAP = 8, PICK_X = 296;
    private final long session;
    private DrawBoardProtocol.State state;
    private int selected = -1, pickSlot = -1;
    private boolean picking;
    private long waiting, waitingSince;
    private int confirmAction = -1;
    private String confirmText = "", confirmDetail = "", confirmId = "", pickItem = "";

    DrawBoardAdminScreen(long session) { super("행운의 뽑기판 관리"); this.session = session; }

    @Override public long session() { return session; }

    @Override SocialLayout.Fit fit() { float s = Math.min(width * .86f / 1340, height * .86f / 900); return new SocialLayout.Fit((width - 1340 * s) / 2, (height - 900 * s) / 2, s); }

    @Override public void receive(DrawBoardProtocol.State s) {
        state = s;
        if (s.sequence() != 0 && s.sequence() == waiting) { waiting = 0; busy = false; }
        if (!s.message().isEmpty()) notice(s.message());
        if (selected >= s.registered()) selected = -1;
    }

    private int count() { return state == null ? 0 : Math.min(state.registered(), DrawBoardClient.catalogFor(state.itemsRevision()) ? DrawBoardClient.catalogSize() : 0); }
    private ItemStack stack(int i) { return state == null ? ItemStack.EMPTY : DrawBoardClient.item(state.itemsRevision(), i); }
    private String name(ItemStack s) { return s.isEmpty() ? "(그림 불러오는 중)" : ItemTooltipRenderer.localName(s, s.getName()).getString() + (s.getCount() > 1 ? " ×" + s.getCount() : ""); }

    private void send(int action, int slot, String slotItem, String id) {
        if (busy) return;
        long seq = DrawBoardClient.send(session, action, 0, -1, false, slot, slotItem, id);
        if (seq == 0) { notice("서버 연결을 확인하세요"); return; }
        if (action != DrawBoardProtocol.CLOSE) { waiting = seq; waitingSince = Util.getMeasuringTimeMs(); busy = true; }
    }

    private void confirm(int action, String text, String detail, String id) { confirmAction = action; confirmText = text; confirmDetail = detail; confirmId = id; }

    private void pick(int slot) {
        var stack = client.player.getInventory().getStack(slot);
        if (stack.isEmpty()) return;
        picking = false; pickSlot = slot; pickItem = Registries.ITEM.getId(stack.getItem()).toString();
        confirm(DrawBoardProtocol.ADD, "이 아이템을 등록할까요?", name(stack) + " · " + (slot < 9 ? "핫바 " + (slot + 1) : "인벤토리 " + (slot - 8)) + "번 칸 · 원본 그대로 (소모 안 함)", "");
    }

    private static float slotX(int i) { return LIST_X + (i % 10) * (SLOT + SLOT_GAP); }
    private static float slotY(int i) { return LIST_Y + (i / 10) * (SLOT + SLOT_GAP); }
    private static int pickX(int slot) { return PICK_X + (slot % 9) * (PICK + PICK_GAP); }
    private static int pickY(int slot) { return slot < 9 ? 568 : 300 + (slot / 9 - 1) * (PICK + PICK_GAP); }

    private void icon(DrawContext c, ItemStack item, float x, float y, float scale) {
        c.getMatrices().push(); c.getMatrices().translate(x, y, 0); c.getMatrices().scale(scale, scale, 1);
        c.drawItem(item, 0, 0); c.drawStackOverlay(textRenderer, item, 0, 0); c.getMatrices().pop();
    }

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        if (busy && Util.getMeasuringTimeMs() - waitingSince > 15000) { busy = false; waiting = 0; notice("응답이 늦습니다. 새로고침으로 결과를 확인하세요."); }
        var f = fit(); double mx = f.x(mouseX), my = f.y(mouseY);
        ItemStack hover = ItemStack.EMPTY;
        boolean modal = picking || confirmAction >= 0;
        int n = count();
        start(c);
        try {
            chamfer(c, 8, 8, 1324, 884, 22, 0xFFD0BD88); chamfer(c, 11, 11, 1318, 878, 20, 0xF00D2033);
            label(c, "행운의 뽑기판 관리", 52, 62, 34, WHITE, true);
            button(c, "닫기", 1190, 36, 100, 46, !modal && in(mx, my, 1190, 36, 100, 46));
            String sub = state == null ? "불러오는 중" : "운영자 · 공유 DB · 등록 " + state.registered() + "/100"
                    + (state.ready() ? " · " + state.round() + "회차 · 공개 " + state.revealed().size() + "칸" : " · 100개가 되면 섞어서 판 시작");
            label(c, sub, 52, 99, 18, MUTED, false);
            button(c, "새로고침", 50, 124, 170, 48, !modal && !busy && in(mx, my, 50, 124, 170, 48));
            button(c, "인벤토리에서 등록", 234, 124, 240, 48, !modal && !busy && in(mx, my, 234, 124, 240, 48));
            button(c, "새로 섞기…", 488, 124, 170, 48, !modal && !busy && in(mx, my, 488, 124, 170, 48));
            button(c, "뽑기판 열기", 672, 124, 170, 48, !modal && in(mx, my, 672, 124, 170, 48));
            for (int i = 0; i < 100; i++) {
                float x = slotX(i), y = slotY(i);
                boolean has = i < n, h = !modal && has && in(mx, my, x, y, SLOT, SLOT);
                box(c, (int) x, (int) y, SLOT, SLOT, h || i == selected);
                if (!has) continue;
                var s = stack(i);
                if (!s.isEmpty()) { icon(c, s, x + 10, y + 10, 2.5f); if (h) hover = s; }
                else center(c, Integer.toString(i + 1), x + SLOT / 2f, y + SLOT / 2f, 16, MUTED);
            }
            if (n == 0) center(c, state != null && state.registered() > 0 ? "목록 불러오는 중…" : "등록된 아이템이 없습니다. 「인벤토리에서 등록」", 50 + 327, 530, 21, MUTED);
            // 오른쪽: 고른 아이템
            label(c, selected < 0 ? "아이템을 누르면 여기 보여요" : (selected + 1) + "번 아이템", 760, 230, 26, WHITE, true);
            if (selected >= 0 && selected < n) {
                var s = stack(selected);
                box(c, 760, 260, 110, 110, false);
                if (!s.isEmpty()) { icon(c, s, 779, 279, 4.5f); if (!modal && in(mx, my, 760, 260, 110, 110)) hover = s; }
                fitted(c, name(s), 890, 300, 22, 400, GOLD, true);
                label(c, "판에는 이 모습 그대로 보여요", 890, 336, 17, MUTED, false);
                button(c, "이 아이템 삭제…", 760, 392, 300, 50, !modal && !busy && in(mx, my, 760, 392, 300, 50));
            }
            label(c, "· 정확히 100개가 모이면 자동으로 섞어 판이 시작돼요", 760, 500, 18, MUTED, false);
            label(c, "· 등록·삭제하면 지금 판은 새로 섞여요 (공개 칸 초기화)", 760, 530, 18, MUTED, false);
            label(c, "· 같은 아이템을 여러 번 등록해도 돼요", 760, 560, 18, MUTED, false);
            label(c, "· 아이템 지급·소모 없음 (보여 주기만)", 760, 590, 18, MUTED, false);
            label(c, busy ? "처리 중…" : "", 760, 640, 20, CYAN, false);
            toast(c, 50, 870 - 54 - 10, 1240);

            if (picking) {
                c.draw(); c.getMatrices().push(); c.getMatrices().translate(0, 0, 400);
                HudMesh.quad(c, 0, 0, 1340, 900, 0xCF07131E, 0xCF07131E); box(c, 250, 180, 840, 600, false);
                label(c, "뽑기판에 넣을 아이템 고르기", 290, 226, 27, WHITE, true);
                label(c, "내 인벤토리 · 고른 칸의 아이템을 원본 그대로(개수 포함) 등록해요 (소모 안 함)", 290, 262, 18, MUTED, false);
                var inv = client.player.getInventory();
                for (int slot = 0; slot < 36; slot++) {
                    int x = pickX(slot), y = pickY(slot); var stack = inv.getStack(slot);
                    boolean h = !stack.isEmpty() && in(mx, my, x, y, PICK, PICK);
                    box(c, x, y, PICK, PICK, h || slot == pickSlot);
                    if (!stack.isEmpty()) { icon(c, stack, x + 14, y + 14, 3f); if (h) hover = stack; }
                }
                label(c, "핫바", PICK_X, 556, 16, MUTED, false);
                button(c, "취소", 545, 672, 250, 52, in(mx, my, 545, 672, 250, 52));
                c.draw(); c.getMatrices().pop();
            }
            if (confirmAction >= 0) {
                c.draw(); c.getMatrices().push(); c.getMatrices().translate(0, 0, 400);
                HudMesh.quad(c, 0, 0, 1340, 900, 0xCF07131E, 0xCF07131E); box(c, 310, 280, 720, 340, false);
                center(c, confirmText, 670, 340, 29, WHITE);
                fitted(c, confirmDetail, 355, 410, 20, 630, MUTED, false);
                label(c, "모든 서버(학교·야생·경기장)의 같은 판에 바로 반영돼요.", 355, 456, 18, MUTED, false);
                button(c, "취소", 355, 535, 250, 52, in(mx, my, 355, 535, 250, 52));
                button(c, "확인", 735, 535, 250, 52, in(mx, my, 735, 535, 250, 52));
                c.draw(); c.getMatrices().pop();
            }
        } finally { end(c); }
        if (confirmAction < 0 && !hover.isEmpty() && entrance.ready()) {
            if (!ItemTooltipRenderer.render(c, textRenderer, hover, mouseX, mouseY)) c.drawItemTooltip(textRenderer, hover, mouseX, mouseY);
        }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return super.mouseClicked(x, y, button);
        var f = fit(); double mx = f.x(x), my = f.y(y);
        if (confirmAction >= 0) {
            if (in(mx, my, 355, 535, 250, 52)) { confirmAction = -1; pickSlot = -1; }
            else if (in(mx, my, 735, 535, 250, 52) && !busy) {
                int a = confirmAction; confirmAction = -1;
                if (a == DrawBoardProtocol.ADD) send(a, pickSlot, pickItem, "");
                else send(a, -1, "", confirmId);
                pickSlot = -1;
            }
            return true;
        }
        if (picking) {
            if (in(mx, my, 545, 672, 250, 52)) { picking = false; return true; }
            for (int slot = 0; slot < 36; slot++) if (in(mx, my, pickX(slot), pickY(slot), PICK, PICK)) { pick(slot); break; }
            return true;
        }
        if (in(mx, my, 1190, 36, 100, 46)) { close(); return true; }
        if (in(mx, my, 672, 124, 170, 48)) { if (client.player != null) client.player.networkHandler.sendChatCommand("뽑기판"); return true; }
        if (busy || state == null) return true;
        int n = count();
        if (in(mx, my, 50, 124, 170, 48)) send(DrawBoardProtocol.REFRESH, -1, "", "");
        else if (in(mx, my, 234, 124, 240, 48)) {
            if (state.registered() >= 100) notice("이미 100개가 등록돼 있어요. 먼저 하나를 지워 주세요");
            else picking = true;
        }
        else if (in(mx, my, 488, 124, 170, 48)) confirm(DrawBoardProtocol.RESHUFFLE, "판을 새로 섞을까요?", "모든 칸이 다시 가려지고 다음 회차가 시작돼요", "");
        else if (selected >= 0 && selected < n && in(mx, my, 760, 392, 300, 50)) {
            String id = DrawBoardClient.id(selected);
            if (id != null) confirm(DrawBoardProtocol.REMOVE, selected + 1 + "번 아이템을 지울까요?", name(stack(selected)) + " · 지우면 99개 이하라 판이 멈춰요", id);
        }
        else for (int i = 0; i < n; i++) if (in(mx, my, slotX(i), slotY(i), SLOT, SLOT)) { selected = i; break; }
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (confirmAction >= 0) { if (key == 256) { confirmAction = -1; pickSlot = -1; } return true; }
        if (picking) { if (key == 256) picking = false; return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override public void close() { client.setScreen(null); }

    @Override public void removed() { DrawBoardClient.close(session); super.removed(); }
}
