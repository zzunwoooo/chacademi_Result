package school.magiccodex.client;

import java.io.ByteArrayInputStream;
import java.util.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import school.magiccodex.protocol.LootBoxProtocol;
import school.magiccodex.protocol.LootBoxProtocol.*;

/**
 * 뽑기 상자 운영자 관리 창 (/상자관리 관리). 상점 물품 관리 창(ShopAdminScreen)과 같은 판·글꼴·버튼·인벤토리 고르기 창을 그대로 쓴다.
 * 상자 고르기(‹ ›) → 보상 목록(아이템·개수·등급·가중치·확률 %) → 인벤토리에서 보상 추가 / 선택 보상 수정·삭제, 이름 변경·열쇠 다시 지정·상자 삭제.
 * 모든 변경은 서버가 loot-boxes.yml 에 저장한다. 아이템 바이트는 서버 → 클라로만 오고, 등록은 "내 인벤토리 몇 번 칸"만 보낸다.
 */
public final class LootBoxAdminScreen extends SocialScreen {
    private static final String[] GRADES = {"일반", "고급", "유일", "전설"};
    /** 결과 칸과 같은 등급색 (일반 은백 · 고급 청록 · 유일 보라 · 전설 금색). */
    static final int[] GRADE_COLORS = {0xFFDDE3EA, 0xFF5FE3D6, 0xFFB98CFF, 0xFFF2C75C};
    private static final int SLOT = 76, GAP = 8, GRID_X = 296;
    private Response data;
    private Input amount, weight, name;
    private int grade = 0, selected = -1;
    private boolean picking, rekey;
    private int pickSlot = -1;
    private String pickItem = "";
    private ItemStack pickStack = ItemStack.EMPTY;
    private Request confirmation;
    private String confirmText = "", confirmDetail = "";
    private Map<Integer, ItemStack> icons = Map.of();
    private ItemStack keyIcon = ItemStack.EMPTY;
    private final Deque<Integer> previous = new ArrayDeque<>();

    LootBoxAdminScreen() { super("뽑기 상자 관리"); }

    @Override SocialLayout.Fit fit() { float s = Math.min(width * .86f / 1340, height * .86f / 900); return new SocialLayout.Fit((width - 1340 * s) / 2, (height - 900 * s) / 2, s); }

    @Override protected void init() {
        super.init();
        amount = new Input("개수", 2, amount == null ? "1" : amount.text());
        weight = new Input("가중치", 7, weight == null ? "10" : weight.text());
        name = new Input("이름", 24, name == null ? "" : name.text());
    }

    private List<Input> inputs() { return List.of(amount, weight, name); }

    void receive(Response r) {
        String before = data == null ? "" : data.box();
        data = r; busy = false; confirmation = null; notice(r.message());
        var decoded = new HashMap<Integer, ItemStack>();
        for (var w : r.rewards()) decoded.put(w.index(), decode(w.preview()));
        icons = Map.copyOf(decoded);
        keyIcon = decode(r.keyPreview());
        if (!r.box().equals(before)) { name.widget.setText(r.title()); previous.clear(); }
        if (r.rewards().stream().noneMatch(w -> w.index() == selected)) newReward(false); else select(selected);
    }

    void failed(String m) { busy = false; confirmation = null; notice(m); }

    private ItemStack decode(byte[] b) {
        if (b.length == 0 || client == null || client.world == null) return ItemStack.EMPTY;
        try { return ItemStack.fromNbt(client.world.getRegistryManager(), NbtIo.readCompressed(new ByteArrayInputStream(b), NbtSizeTracker.of(2097152))).orElse(ItemStack.EMPTY); }
        catch (Exception e) { return ItemStack.EMPTY; }
    }

    private Reward reward(int index) { return data == null ? null : data.rewards().stream().filter(w -> w.index() == index).findFirst().orElse(null); }

    private String rewardName(Reward w) {
        var item = icons.getOrDefault(w.index(), ItemStack.EMPTY);
        return item.isEmpty() ? w.spec() : ItemTooltipRenderer.localName(item, item.getName()).getString();
    }

    private void select(int index) {
        var w = reward(index);
        if (w == null) { newReward(false); return; }
        selected = index; grade = w.grade(); clearPick(); confirmation = null;
        amount.widget.setText(Integer.toString(w.amount())); weight.widget.setText(Integer.toString(w.weight()));
    }

    private void newReward(boolean openPicker) {
        selected = -1; confirmation = null; clearPick();
        if (openPicker) { picking = true; rekey = false; for (var i : inputs()) i.focus(false); }
    }

    private void clearPick() { pickSlot = -1; pickItem = ""; pickStack = ItemStack.EMPTY; }

    private void pick(int slot) {
        var stack = client.player.getInventory().getStack(slot);
        if (stack.isEmpty()) return;
        picking = false;
        if (rekey) {
            confirm(new Request(LootBoxProtocol.REKEY, 1, data.session(), data.box(), data.revision(), -1, 0, 0, 0, slot, Registries.ITEM.getId(stack.getItem()).toString(), "", data.offset()),
                    "이 아이템을 열쇠로 다시 지정할까요?", ItemTooltipRenderer.localName(stack, stack.getName()).getString() + " · 이미 나눠 준 예전 열쇠는 더 이상 열리지 않아요");
            return;
        }
        pickSlot = slot; pickItem = Registries.ITEM.getId(stack.getItem()).toString(); pickStack = stack.copyWithCount(1);
        amount.widget.setText(Integer.toString(Math.max(1, Math.min(stack.getCount(), stack.getMaxCount()))));
        notice((slot < 9 ? "핫바 " + (slot + 1) : "인벤토리 " + (slot - 8)) + "번 칸을 골랐어요. 등급·개수·가중치를 넣고 추가 확인을 누르세요.");
    }

    private void list(String box, int offset) {
        if (data == null || busy) return;
        confirmation = null;
        busy = LootBoxClient.request(this, new Request(LootBoxProtocol.LIST, 1, data.session(), box, data.revision(), -1, 0, 0, 0, -1, "", "", offset));
        if (!busy) notice("서버 연결 또는 이전 요청을 확인하세요.");
    }

    private void confirm(Request r, String text, String detail) { confirmation = r; confirmText = text; confirmDetail = detail; for (var i : inputs()) i.focus(false); }

    private int number(Input in, String what, int min, int max) {
        String t = in.text().strip();
        if (!t.matches("[0-9]{1,7}")) throw new IllegalArgumentException(what + "은(는) 숫자로 넣어 주세요");
        int v = Integer.parseInt(t);
        if (v < min || v > max) throw new IllegalArgumentException(what + "은(는) " + min + "~" + max + " 사이로 넣어 주세요");
        return v;
    }

    private void save() {
        if (data == null || busy || data.box().isEmpty()) return;
        try {
            int n = number(amount, "개수", 1, 64), w = number(weight, "가중치", 1, 1_000_000);
            if (selected < 0) {
                if (pickSlot < 0) throw new IllegalArgumentException("「인벤토리에서 보상 추가」로 아이템을 먼저 고르세요.");
                confirm(new Request(LootBoxProtocol.ADD, 1, data.session(), data.box(), data.revision(), -1, grade, n, w, pickSlot, pickItem, "", data.offset()),
                        "이 보상을 추가할까요?", ItemTooltipRenderer.localName(pickStack, pickStack.getName()).getString() + " ×" + n + " · " + GRADES[grade] + " · 가중치 " + w + " (" + percent(w, data.totalWeight() + w) + ")");
            } else {
                var old = reward(selected);
                long total = data.totalWeight() - (old == null ? 0 : old.weight()) + w;
                confirm(new Request(LootBoxProtocol.EDIT, 1, data.session(), data.box(), data.revision(), selected, grade, n, w, -1, "", "", data.offset()),
                        (selected + 1) + "번 보상을 저장할까요?", (old == null ? "" : rewardName(old)) + " ×" + n + " · " + GRADES[grade] + " · 가중치 " + w + " (" + percent(w, total) + ")");
            }
        } catch (IllegalArgumentException e) { notice(e.getMessage()); }
    }

    private void send() {
        if (confirmation == null || busy) return;
        busy = LootBoxClient.request(this, confirmation);
        if (busy) confirmation = null; else notice("서버 연결 또는 이전 요청을 확인하세요.");
    }

    private void cycle(int step) {
        if (data == null || data.boxes().isEmpty()) return;
        int at = 0;
        for (int i = 0; i < data.boxes().size(); i++) if (data.boxes().get(i).id().equals(data.box())) at = i;
        var b = data.boxes().get(Math.floorMod(at + step, data.boxes().size()));
        selected = -1; clearPick(); previous.clear(); list(b.id(), 0);
    }

    static String percent(long w, long total) {
        if (total <= 0 || w <= 0) return "0%";
        String s = String.format(Locale.ROOT, "%.2f", w * 100.0 / total);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s + "%";
    }

    private void field(DrawContext c, Input input, int x, int y, int w, String hint) { box(c, x, y, w, 44, input.focus()); input.draw(c, x + 6, y + 2, w - 12, 40, hint); }

    private void icon(DrawContext c, ItemStack item, float x, float y, float scale) {
        String path = LootBoxScreen.rewardIcon(item); // 가구 보상: 가구 그림
        if (path != null && UiResources.images().drawCentered(c, path, x + 8 * scale, y + 8 * scale, Math.round(16 * scale), 0xFFFFFFFF)) return;
        c.getMatrices().push(); c.getMatrices().translate(x, y, 0); c.getMatrices().scale(scale, scale, 1);
        c.drawItem(item, 0, 0); c.drawStackOverlay(textRenderer, item, 0, 0); c.getMatrices().pop();
    }

    private static int slotX(int slot) { return GRID_X + (slot % 9) * (SLOT + GAP); }
    private static int slotY(int slot) { return slot < 9 ? 568 : 300 + (slot / 9 - 1) * (SLOT + GAP); }

    @Override public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        var f = fit(); double mx = f.x(mouseX), my = f.y(mouseY);
        ItemStack hover = ItemStack.EMPTY;
        boolean modal = picking || confirmation != null;
        start(c);
        try {
            chamfer(c, 8, 8, 1324, 884, 22, 0xFFD0BD88); chamfer(c, 11, 11, 1318, 878, 20, 0xF00D2033);
            label(c, "뽑기 상자 관리", 52, 62, 34, WHITE, true);
            button(c, "닫기", 1190, 36, 100, 46, !modal && in(mx, my, 1190, 36, 100, 46));
            label(c, "운영자 · loot-boxes.yml 에 저장 · 저장 전 확인", 52, 99, 18, MUTED, false);
            button(c, "‹", 50, 124, 54, 48, !modal && in(mx, my, 50, 124, 54, 48));
            button(c, "›", 706, 124, 54, 48, !modal && in(mx, my, 706, 124, 54, 48));
            String title = data == null ? "상자 불러오는 중" : data.box().isEmpty() ? "상자 없음 · /상자관리 생성 <이름>" : data.title() + " · " + data.box();
            fitted(c, title, 120, 148, 26, 570, GOLD, true);
            button(c, "새로고침", 820, 124, 200, 48, !modal && !busy && in(mx, my, 820, 124, 200, 48));
            button(c, "인벤토리에서 보상 추가", 1034, 124, 236, 48, !modal && !busy && in(mx, my, 1034, 124, 236, 48));
            if (data != null && !data.box().isEmpty()) {
                box(c, 50, 186, 710, 54, false);
                if (!keyIcon.isEmpty()) { icon(c, keyIcon, 58, 193, 2.5f); if (!modal && in(mx, my, 58, 193, 40, 40)) hover = keyIcon; }
                fitted(c, "열쇠 · " + data.key(), 110, 205, 19, 640, WHITE, false);
                long t = data.totalWeight(); long[] g = data.gradeWeights();
                fitted(c, "가중치 합 " + t + " · 일반 " + percent(g[0], t) + " · 고급 " + percent(g[1], t) + " · 유일 " + percent(g[2], t) + " · 전설 " + percent(g[3], t), 110, 228, 17, 640, MUTED, false);
                for (int row = 0; row < data.rewards().size(); row++) {
                    var w = data.rewards().get(row);
                    int y = 248 + row * 41;
                    boolean h = !modal && in(mx, my, 50, y, 710, 38);
                    box(c, 50, y, 710, 38, w.index() == selected || h);
                    var item = icons.getOrDefault(w.index(), ItemStack.EMPTY);
                    if (!item.isEmpty()) { icon(c, item, 58, y + 3, 2f); if (h) hover = item; }
                    fitted(c, (w.index() + 1) + ". " + rewardName(w), 100, y + 19, 19, 330, WHITE, false);
                    fitted(c, GRADES[w.grade()], 440, y + 19, 19, 60, GRADE_COLORS[w.grade()], true);
                    fitted(c, "×" + w.amount() + " · 가중치 " + w.weight() + " · " + percent(w.weight(), data.totalWeight()), 505, y + 19, 18, 245, MUTED, false);
                }
                if (data.rewards().isEmpty()) center(c, "보상이 없습니다. 「인벤토리에서 보상 추가」", 405, 470, 21, MUTED);
            }
            button(c, "이전 목록", 50, 748, 180, 46, !modal && !busy && !previous.isEmpty() && in(mx, my, 50, 748, 180, 46));
            button(c, "다음 목록", 580, 748, 180, 46, !modal && !busy && data != null && data.nextOffset() >= 0 && in(mx, my, 580, 748, 180, 46));
            center(c, data == null ? "" : (data.offset() + 1) + "번 보상부터", 405, 770, 18, MUTED);

            // 오른쪽: 보상 편집
            label(c, selected < 0 ? "새 보상 추가" : (selected + 1) + "번 보상 수정", 820, 211, 27, WHITE, true);
            if (selected < 0 && !pickStack.isEmpty()) { icon(c, pickStack, 1218, 190, 2.6f); if (!modal && in(mx, my, 1218, 190, 42, 42)) hover = pickStack; }
            label(c, selected < 0 ? (pickSlot < 0 ? "「인벤토리에서 보상 추가」로 아이템을 고르세요" : (pickSlot < 9 ? "핫바 " + (pickSlot + 1) : "인벤토리 " + (pickSlot - 8)) + "번 칸 · 원본 그대로 등록")
                    : "아이템은 그대로 · 등급·개수·가중치만 수정", 820, 249, 18, MUTED, false);
            label(c, "등급 (결과 칸 색·연출 빛)", 820, 284, 20, GOLD, false);
            for (int g = 0; g < 4; g++) {
                int x = 820 + g * 115;
                box(c, x, 300, 105, 44, g == grade || (!modal && in(mx, my, x, 300, 105, 44)));
                center(c, GRADES[g], x + 52.5f, 322, 21, g == grade ? GRADE_COLORS[g] : MUTED);
            }
            label(c, "개수 (1~64)", 820, 364, 20, GOLD, false); label(c, "가중치 (확률)", 1060, 364, 20, GOLD, false);
            field(c, amount, 820, 380, 210, "1"); field(c, weight, 1060, 380, 210, "10");
            String chance = "";
            if (data != null) try {
                int w = Integer.parseInt(weight.text().strip());
                var old = selected < 0 ? null : reward(selected);
                chance = "이 보상 확률 ≈ " + percent(w, data.totalWeight() - (old == null ? 0 : old.weight()) + w) + "  (가중치 ÷ 모든 가중치 합)";
            } catch (NumberFormatException ignored) { chance = "가중치는 숫자로 넣어 주세요"; }
            label(c, chance, 820, 446, 18, MUTED, false);
            button(c, busy ? "처리 중…" : selected < 0 ? "추가 확인" : "수정 확인", 820, 470, 450, 55, !modal && !busy && in(mx, my, 820, 470, 450, 55));
            button(c, selected < 0 ? "새 보상으로" : "보상 삭제…", 820, 536, 450, 46, !modal && !busy && in(mx, my, 820, 536, 450, 46));
            // 상자 자체
            label(c, "상자 이름 · 열쇠 · 삭제", 820, 616, 20, GOLD, false);
            field(c, name, 820, 632, 290, "띄어쓰기 없이 1~24자");
            button(c, "이름 변경", 1124, 632, 146, 44, !modal && !busy && in(mx, my, 1124, 632, 146, 44));
            button(c, "열쇠 다시 지정", 820, 690, 220, 46, !modal && !busy && in(mx, my, 820, 690, 220, 46));
            button(c, "상자 삭제…", 1050, 690, 220, 46, !modal && !busy && in(mx, my, 1050, 690, 220, 46));
            toast(c, 50, 808, 1220);

            if (picking) {
                c.draw(); c.getMatrices().push(); c.getMatrices().translate(0, 0, 400);
                HudMesh.quad(c, 0, 0, 1340, 900, 0xCF07131E, 0xCF07131E); box(c, 250, 180, 840, 600, false);
                label(c, rekey ? "열쇠로 쓸 아이템 고르기" : "보상으로 넣을 아이템 고르기", 290, 226, 27, WHITE, true);
                label(c, rekey ? "ItemsAdder 아이템은 id 로, 모델 데이터가 있으면 재질+모델로 판정해요 (소모 안 함)" : "내 인벤토리 · 고른 칸의 아이템을 원본 그대로 등록해요 (소모 안 함)", 290, 262, 18, MUTED, false);
                var inv = client.player.getInventory();
                for (int slot = 0; slot < 36; slot++) {
                    int x = slotX(slot), y = slotY(slot); var stack = inv.getStack(slot);
                    boolean h = !stack.isEmpty() && in(mx, my, x, y, SLOT, SLOT);
                    box(c, x, y, SLOT, SLOT, h || slot == pickSlot);
                    if (!stack.isEmpty()) { icon(c, stack, x + 14, y + 14, 3f); if (h) hover = stack; }
                }
                label(c, "핫바", GRID_X, 556, 16, MUTED, false);
                button(c, "취소", 545, 672, 250, 52, in(mx, my, 545, 672, 250, 52));
                c.draw(); c.getMatrices().pop();
            }
            if (confirmation != null) {
                c.draw(); c.getMatrices().push(); c.getMatrices().translate(0, 0, 400);
                HudMesh.quad(c, 0, 0, 1340, 900, 0xCF07131E, 0xCF07131E); box(c, 310, 280, 720, 340, false);
                center(c, confirmText, 670, 329, 29, WHITE);
                fitted(c, data == null ? "" : data.title(), 355, 388, 26, 630, GOLD, true);
                fitted(c, confirmDetail, 355, 442, 20, 630, MUTED, false);
                label(c, "다른 관리자의 변경이 먼저 있으면 저장되지 않습니다.", 355, 480, 18, MUTED, false);
                button(c, "취소", 355, 535, 250, 52, in(mx, my, 355, 535, 250, 52));
                button(c, "확인", 735, 535, 250, 52, in(mx, my, 735, 535, 250, 52));
                c.draw(); c.getMatrices().pop();
            }
        } finally { end(c); }
        if (confirmation == null && !hover.isEmpty() && entrance.ready()) {
            if (!ItemTooltipRenderer.render(c, textRenderer, hover, mouseX, mouseY)) c.drawItemTooltip(textRenderer, hover, mouseX, mouseY);
        }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return super.mouseClicked(x, y, button);
        var f = fit(); double mx = f.x(x), my = f.y(y);
        if (confirmation != null) { if (in(mx, my, 355, 535, 250, 52)) confirmation = null; else if (in(mx, my, 735, 535, 250, 52)) send(); return true; }
        if (picking) {
            if (in(mx, my, 545, 672, 250, 52)) { picking = false; return true; }
            for (int slot = 0; slot < 36; slot++) if (in(mx, my, slotX(slot), slotY(slot), SLOT, SLOT)) { pick(slot); break; }
            return true;
        }
        if (in(mx, my, 1190, 36, 100, 46)) { close(); return true; }
        if (busy) return true;
        boolean hasBox = data != null && !data.box().isEmpty();
        if (in(mx, my, 50, 124, 54, 48)) cycle(-1);
        else if (in(mx, my, 706, 124, 54, 48)) cycle(1);
        else if (in(mx, my, 820, 124, 200, 48)) { previous.clear(); list(data == null ? "" : data.box(), 0); }
        else if (in(mx, my, 1034, 124, 236, 48)) { if (hasBox) newReward(true); }
        else if (in(mx, my, 50, 748, 180, 46) && !previous.isEmpty()) list(data.box(), previous.pop());
        else if (in(mx, my, 580, 748, 180, 46) && data != null && data.nextOffset() >= 0) { previous.push(data.offset()); list(data.box(), data.nextOffset()); }
        else if (in(mx, my, 820, 470, 450, 55)) save();
        else if (in(mx, my, 820, 536, 450, 46) && hasBox) {
            if (selected < 0) newReward(false);
            else { var w = reward(selected); confirm(new Request(LootBoxProtocol.DELETE, 1, data.session(), data.box(), data.revision(), selected, 0, 0, 0, -1, "", "", data.offset()), (selected + 1) + "번 보상을 지울까요?", w == null ? "" : rewardName(w) + " ×" + w.amount()); }
        }
        else if (in(mx, my, 1124, 632, 146, 44) && hasBox) {
            String n = name.text().strip();
            if (n.isEmpty() || n.length() > 24 || n.contains(" ")) notice("이름은 띄어쓰기 없이 1~24자로 해 주세요");
            else confirm(new Request(LootBoxProtocol.RENAME, 1, data.session(), data.box(), data.revision(), -1, 0, 0, 0, -1, "", n, data.offset()), "상자 이름을 바꿀까요?", data.title() + " → " + n);
        }
        else if (in(mx, my, 820, 690, 220, 46) && hasBox) { picking = true; rekey = true; for (var i : inputs()) i.focus(false); }
        else if (in(mx, my, 1050, 690, 220, 46) && hasBox) confirm(new Request(LootBoxProtocol.DELETE_BOX, 1, data.session(), data.box(), data.revision(), -1, 0, 0, 0, -1, "", "", 0), "이 상자를 지울까요?", "보상 목록도 함께 지워져요. 이미 나눠 준 열쇠는 더 이상 열리지 않아요");
        else {
            boolean hit = false;
            for (int g = 0; g < 4; g++) if (in(mx, my, 820 + g * 115, 300, 105, 44)) { grade = g; hit = true; }
            if (!hit) {
                for (var i : inputs()) i.click(mx, my);
                if (data != null) for (int row = 0; row < data.rewards().size(); row++) if (in(mx, my, 50, 248 + row * 41, 710, 38)) { select(data.rewards().get(row).index()); break; }
            }
        }
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (confirmation != null) { if (key == 256) confirmation = null; return true; }
        if (picking) { if (key == 256) picking = false; return true; }
        if (busy) return key == 256 ? super.keyPressed(key, scan, mods) : true;
        for (var i : inputs()) if (i.focus() && i.key(key, scan, mods)) return true;
        return super.keyPressed(key, scan, mods);
    }

    @Override public boolean charTyped(char ch, int mods) {
        if (busy || confirmation != null || picking) return true;
        for (var i : inputs()) if (i.focus() && i.typed(ch, mods)) return true;
        return super.charTyped(ch, mods);
    }

    @Override public void close() {
        if (data != null && !busy) LootBoxClient.request(this, new Request(LootBoxProtocol.CLOSE, 1, data.session(), data.box(), 0, -1, 0, 0, 0, -1, "", "", 0));
        LootBoxClient.detach(this);
        client.setScreen(null);
    }
}
