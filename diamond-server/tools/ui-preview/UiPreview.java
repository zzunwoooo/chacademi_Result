import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 나니 다이아서버 UI 미리보기: 모드(diamond-ui)의 그리기 코드와 같은 좌표·색으로 화면을 그림 파일로 뽑는다.
 * 게임 화면을 찍은 것이 아니라 같은 배치를 다시 그린 것이다 (글자 모양·아이템 그림은 게임과 다르다).
 * 사용: java UiPreview.java <출력폴더>
 * 모드의 Hud.java · ShopScreen.java 를 고치면 여기도 같이 고친다.
 */
public class UiPreview {
    static final int S = 3;                       // 확대 배율 (게임 GUI 3배율 = 1920x1080 에서 640x360)
    static final int SW = 640, SH = 360;
    static Graphics2D g;

    // ── 게임의 그리기 흉내 ───────────────────────────────────
    static void fill(int x1, int y1, int x2, int y2, int argb) {
        g.setColor(new Color(argb, true));
        g.fillRect(x1 * S, y1 * S, (x2 - x1) * S, (y2 - y1) * S);
    }

    /** 게임 글꼴의 글자 너비 (한글 9, 영문·숫자 대부분 6) */
    static int cw(char c) {
        if (c == ' ') return 4;
        if ("i!.,:;'|".indexOf(c) >= 0) return 2;
        if (c == 'l') return 3;
        if ("tI()[]*".indexOf(c) >= 0) return 4;
        if ("fk".indexOf(c) >= 0) return 5;
        if (c == '·') return 4;
        return c < 0x250 ? 6 : 9;
    }

    static int width(String s) { int w = 0; for (char c : s.toCharArray()) w += cw(c); return w; }

    static String trim(String s, int max) {
        int w = 0;
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) { if (w + cw(c) > max) break; w += cw(c); b.append(c); }
        return b.toString();
    }

    static final Font KO = new Font("Malgun Gothic", Font.PLAIN, 8 * S);
    static final Font EN = new Font("Consolas", Font.BOLD, 8 * S);

    static void text(String s, int x, int y, int argb) {
        g.setColor(new Color(argb, true));
        int px = x * S;
        for (char c : s.toCharArray()) {
            boolean wide = c >= 0x250;
            g.setFont(wide ? KO : EN);
            if (!wide && cw(c) < 6 && c != ' ') {            // 좁은 글자(i , . 등)는 옆 글자와 겹치지 않게 좁혀서 그린다
                AffineTransform old = g.getTransform();
                g.translate(px, (y + 7) * S);
                g.scale(cw(c) / 6.0, 1);
                g.drawString(String.valueOf(c), 0, 0);
                g.setTransform(old);
            } else {
                g.drawString(String.valueOf(c), px, (y + 7) * S);
            }
            px += cw(c) * S;
        }
    }

    static void textShadow(String s, int x, int y, int argb) {
        text(s, x + 1, y + 1, ((argb >>> 24) << 24) | 0x202020);
        text(s, x, y, argb);
    }

    static int alpha(int argb, float a) {
        int al = Math.max(0, Math.min(255, Math.round(((argb >>> 24) & 0xFF) * a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    static void box(int x, int y, int w, int h, int bg, int border) {
        fill(x, y, x + w, y + h, bg);
        fill(x, y, x + w, y + 1, border);
        fill(x, y + h - 1, x + w, y + h, border);
        fill(x, y + 1, x + 1, y + h - 1, border);
        fill(x + w - 1, y + 1, x + w, y + h - 1, border);
    }

    static String n(long v) { return String.format("%,d", v); }

    // ── 아이템 그림 (게임에서는 진짜 아이템 그림이 나온다. 여기는 알아볼 수 있게만) ──
    static void px(int x, int y, int w, int h, int rgb) { fill(x, y, x + w, y + h, 0xFF000000 | rgb); }

    static void item(String id, int x, int y) {
        if (id.equals("diamond")) {
            px(x + 5, y + 3, 6, 1, 0xB8F4FF); px(x + 3, y + 4, 10, 2, 0x7FE3F7); px(x + 2, y + 6, 12, 2, 0x4FC3E8);
            px(x + 3, y + 8, 10, 2, 0x35B4DC); px(x + 5, y + 10, 6, 2, 0x2A9BC8); px(x + 7, y + 12, 2, 1, 0x1F86B4);
        } else if (id.endsWith("_pickaxe")) {
            int head = id.startsWith("wooden") ? 0x9A6B3A : id.startsWith("stone") ? 0x8E8E8E : id.startsWith("iron") ? 0xE0E0E0
                    : id.startsWith("golden") ? 0xF5D442 : id.startsWith("diamond") ? 0x4FE0E0 : 0x4A3F45;
            for (int i = 0; i < 9; i++) px(x + 3 + i, y + 12 - i, 2, 2, 0x6B4A2A);           // 자루
            px(x + 4, y + 2, 8, 2, head); px(x + 2, y + 3, 3, 2, head); px(x + 11, y + 3, 2, 3, head);
            px(x + 12, y + 5, 2, 4, head); px(x + 1, y + 5, 2, 1, head);
        } else if (id.equals("enchanted_book")) {
            px(x + 3, y + 2, 10, 12, 0x7A3FB0); px(x + 4, y + 3, 8, 10, 0x9A5AD6); px(x + 11, y + 2, 2, 12, 0xE8DCC0);
            px(x + 6, y + 6, 4, 4, 0xF2D34F);
        } else if (id.equals("bread")) {
            px(x + 2, y + 6, 12, 6, 0xC98B3A); px(x + 3, y + 5, 10, 1, 0xE0A858); px(x + 4, y + 7, 2, 1, 0x8A5A20); px(x + 8, y + 7, 2, 1, 0x8A5A20);
        } else if (id.equals("paper")) {
            px(x + 3, y + 2, 10, 12, 0xF2F2F2); px(x + 5, y + 5, 6, 1, 0xB0B0B0); px(x + 5, y + 8, 6, 1, 0xB0B0B0); px(x + 5, y + 11, 4, 1, 0xB0B0B0);
        } else {
            int c = switch (id) {
                case "coal" -> 0x2B2B2B; case "raw_iron" -> 0xD8AF93; case "iron_ingot" -> 0xE0E0E0; case "raw_gold" -> 0xF5C542;
                case "gold_ingot" -> 0xFDE047; case "redstone" -> 0xD21F1F; case "lapis_lazuli" -> 0x2B4FC0; case "emerald" -> 0x2ECC71;
                case "raw_copper" -> 0xC87C5A; default -> 0x9A9A9A;
            };
            px(x + 4, y + 4, 8, 8, c); px(x + 5, y + 3, 6, 1, c); px(x + 5, y + 12, 6, 1, c); px(x + 5, y + 5, 2, 2, 0xFFFFFF & (c + 0x303030));
        }
    }

    // ── Hud.java 와 같은 값 ──────────────────────────────────
    static final int BG = 0xC8101826, BORDER = 0xFF2B3D63, WHITE = 0xFFFFFFFF, GRAY = 0xFFA8B3C7,
            AQUA = 0xFF5CE1F5, GREEN = 0xFF63E6A0, RED = 0xFFFF6B6B;
    static final int PX = 6, PY = 6, PW = 134, PH = 34;
    static final int TOAST_Y = 46, TOAST_H = 18, TOAST_GAP = 3, BANNER_Y = 46, BANNER_H = 32;

    static long goal, held, goalDelta, heldDelta;
    static boolean done;
    static List<String> queue = new ArrayList<>();
    static List<String[]> effects = new ArrayList<>();     // 이름, 남은 초
    static String[] banner;                                // 후원자, 개수, 이름, 종류, 대상
    static List<Object[]> toasts = new ArrayList<>();      // 글, 성공 여부

    static void drawPanel() {
        box(PX, PY, PW, PH, BG, BORDER);
        item("diamond", PX + 5, PY + 9);
        int right = PX + PW - 7;
        text("남은 목표", PX + 26, PY + 6, GRAY);
        String gs = done ? "달성" : n(goal);
        text(gs, right - width(gs), PY + 6, done ? GREEN : WHITE);
        text("보유", PX + 26, PY + 19, GRAY);
        String hs = n(held);
        text(hs, right - width(hs), PY + 19, AQUA);
        if (goalDelta != 0) textShadow((goalDelta > 0 ? "+" : "-") + n(Math.abs(goalDelta)), PX + PW + 5, PY + 6, goalDelta > 0 ? RED : GREEN);
        if (heldDelta != 0) textShadow((heldDelta > 0 ? "+" : "-") + n(Math.abs(heldDelta)), PX + PW + 5, PY + 19, heldDelta > 0 ? GREEN : RED);
    }

    static int queueBottom() {
        if (queue.isEmpty()) return PY + PH;
        int lines = Math.min(4, queue.size()) + (queue.size() > 4 ? 1 : 0);
        return PY + PH + 3 + 5 + 11 * lines + 2;
    }

    static int leftBottom() {
        return effects.isEmpty() ? queueBottom() : queueBottom() + 3 + 5 + 11 * effects.size() + 2;
    }

    static void drawQueue() {
        if (queue.isEmpty()) return;
        int lines = Math.min(4, queue.size());
        boolean more = queue.size() > 4;
        int y = PY + PH + 3, h = 5 + 11 * (lines + (more ? 1 : 0)) + 2;
        box(PX, y, PW, h, BG, BORDER);
        for (int i = 0; i < lines; i++) {
            String s = trim(queue.get(i), PW - 20);
            fill(PX + 6, y + 8 + i * 11, PX + 9, y + 11 + i * 11, i == 0 ? AQUA : 0xFF6B7890);
            text(s, PX + 13, y + 5 + i * 11, i == 0 ? WHITE : GRAY);
        }
        if (more) text("외 " + (queue.size() - 4) + "건", PX + 13, y + 5 + lines * 11, 0xFF6B7890);
    }

    static void drawEffects() {
        if (effects.isEmpty()) return;
        int y = queueBottom() + 3, h = 5 + 11 * effects.size() + 2;
        box(PX, y, PW, h, BG, RED);
        int i = 0;
        for (String[] e : effects) {
            String left = e[1] + "초";
            int ly = y + 5 + i * 11;
            text(left, PX + PW - 7 - width(left), ly, WHITE);
            text(trim(e[0], PW - 20 - width(left)), PX + 7, ly, RED);
            i++;
        }
    }

    static int drawBanner() {
        if (banner == null) return -1;
        int color = banner[3].equals("good") ? GREEN : banner[3].equals("bad") ? RED : AQUA;
        int max = SW - 40;
        String top = trim(banner[0] + "님 별풍선 " + n(Long.parseLong(banner[1])) + "개", max);
        String bottom = trim(banner[4].isEmpty() ? banner[2] : banner[2] + " → " + banner[4], max);
        int w = Math.max(width(top), width(bottom)) + 32;
        int x = (SW - w) / 2;
        int y = x < PX + PW + 4 ? Math.max(BANNER_Y, leftBottom() + 4) : BANNER_Y;
        box(x, y, w, BANNER_H, 0xE60D1424, color);
        fill(x + 1, y + 1, x + w - 1, y + 3, color);
        text(top, x + (w - width(top)) / 2, y + 8, GRAY);
        text(bottom, x + (w - width(bottom)) / 2, y + 19, color);
        return y + BANNER_H;
    }

    static void drawToasts(int bannerBottom) {
        int y = bannerBottom > 0 ? bannerBottom + TOAST_GAP : TOAST_Y;
        for (Object[] t : toasts) {
            String s = (String) t[0];
            boolean ok = (Boolean) t[1];
            int w = Math.min(SW - 8, width(s) + 20);
            int x = (SW - w) / 2;
            box(x, y, w, TOAST_H, 0xE0101826, BORDER);
            fill(x + 1, y + 1, x + 4, y + TOAST_H - 1, ok ? GREEN : RED);
            text(s, x + 10, y + 5, WHITE);
            y += TOAST_H + TOAST_GAP;
        }
    }

    static void drawPick(String title, List<String> names, int lit, boolean doneBlink) {
        int nn = names.size();
        final int bw = 92, bh = 22, gap = 6;
        int perRow = Math.max(1, Math.min(nn, (SW - 20 + gap) / (bw + gap)));
        int rows = (nn + perRow - 1) / perRow;
        int top = Math.max(84, SH / 2 - 44 - rows * (bh + gap));
        String t = trim(title, SW - 60);
        int tw = width(t) + 24;
        box((SW - tw) / 2, top, tw, 18, 0xE60D1424, AQUA);
        text(t, (SW - width(t)) / 2, top + 5, WHITE);
        for (int i = 0; i < nn; i++) {
            int row = i / perRow, inRow = Math.min(perRow, nn - row * perRow);
            int rowW = inRow * bw + (inRow - 1) * gap;
            int x = (SW - rowW) / 2 + (i % perRow) * (bw + gap), y = top + 24 + row * (bh + gap);
            boolean on = i == lit;
            box(x, y, bw, bh, on ? (doneBlink ? 0xFF8A2A2A : 0xFF5A1F1F) : 0xE0101826, on ? RED : BORDER);
            String name = trim(names.get(i), bw - 10);
            text(name, x + (bw - width(name)) / 2, y + 7, on ? WHITE : GRAY);
        }
    }

    // ── ShopScreen.java 와 같은 값 ───────────────────────────
    static final int W = 340, H = 214, COLS = 2, ROWS = 4, CARD_W = 124, CARD_H = 26;
    static final int WIN = 0xF00D1424, CARD = 0xFF182238, CARD_HOVER = 0xFF22304E, LINE = 0xFF2B3D63, DIM = 0xFF6B7890;
    static int x0, y0;

    record Item(String id, String cat, String name, long price, int amount, String icon) { }

    static int[] tabRect(int i) { return new int[]{x0 + 10 + i * 48, y0 + 26, 44, 16}; }
    static int[] catRect(int i) { return new int[]{x0 + 6, y0 + 50 + i * 20, 66, 18}; }
    static int[] cardRect(int i) { return new int[]{x0 + 80 + (i % COLS) * 128, y0 + 50 + (i / COLS) * 30, CARD_W, CARD_H}; }
    static int[] sellCardRect(int i) { return new int[]{x0 + 44 + (i % COLS) * 128, y0 + 50 + (i / COLS) * 30, CARD_W, CARD_H}; }
    static int[] actionRect() { return new int[]{x0 + W - 8 - 58, y0 + 185, 58, 18}; }

    static void card(int[] r, boolean hover, boolean on, String icon, String name, String sub, int subColor) {
        box(r[0], r[1], r[2], r[3], hover ? CARD_HOVER : CARD, on ? AQUA : LINE);
        item(icon, r[0] + 5, r[1] + 5);
        text(name, r[0] + 26, r[1] + 4, WHITE);
        text(sub, r[0] + 26, r[1] + 15, subColor);
    }

    static void button(int[] r, String label, boolean enabled, boolean hover) {
        box(r[0], r[1], r[2], r[3], !enabled ? CARD : hover ? 0xFF2E86B8 : 0xFF236A94, enabled ? AQUA : LINE);
        text(label, r[0] + (r[2] - width(label)) / 2, r[1] + (r[3] - 8) / 2, enabled ? WHITE : DIM);
    }

    static void shopFrame(boolean sellTab) {
        x0 = (SW - W) / 2; y0 = (SH - H) / 2;
        fill(0, 0, SW, SH, 0x90000000);
        box(x0, y0, W, H, WIN, LINE);
        text("상점", x0 + 10, y0 + 8, WHITE);
        text("X", x0 + W - 20 + 3, y0 + 6 + 2, DIM);
        String hs = n(held);
        int chipW = 22 + width(hs) + 8, chipX = x0 + W - 26 - chipW;
        box(chipX, y0 + 3, chipW, 18, CARD, LINE);
        item("diamond", chipX + 2, y0 + 4);
        text(hs, chipX + 22, y0 + 8, AQUA);
        String[] tabs = {"구매", "판매"};
        for (int i = 0; i < 2; i++) {
            int[] r = tabRect(i);
            boolean on = (i == 1) == sellTab;
            text(tabs[i], r[0] + (r[2] - width(tabs[i])) / 2, r[1] + 4, on ? WHITE : DIM);
            if (on) fill(r[0], r[1] + r[3], r[0] + r[2], r[1] + r[3] + 1, AQUA);
        }
        fill(x0 + 1, y0 + 45, x0 + W - 1, y0 + 46, LINE);
        fill(x0 + 1, y0 + 178, x0 + W - 1, y0 + 179, LINE);
    }

    static void shopBuy(String[] cats, int catOn, List<Item> list, int sel, int hover, int qty) {
        shopFrame(false);
        for (int i = 0; i < cats.length; i++) {
            int[] r = catRect(i);
            boolean on = i == catOn;
            if (on) { fill(r[0], r[1], r[0] + r[2], r[1] + r[3], CARD_HOVER); fill(r[0], r[1], r[0] + 2, r[1] + r[3], AQUA); }
            text(cats[i], r[0] + 8, r[1] + 5, on ? WHITE : GRAY);
        }
        fill(x0 + 75, y0 + 46, x0 + 76, y0 + 178, LINE);
        for (int v = 0; v < COLS * ROWS && v < list.size(); v++) {
            Item it = list.get(v);
            String name = it.amount() > 1 ? it.name() + " ×" + it.amount() : it.name();
            card(cardRect(v), v == hover, v == sel, it.icon(), name, "다이아 " + n(it.price()) + "개", held >= it.price() ? AQUA : RED);
        }
        Item s = sel >= 0 ? list.get(sel) : null;
        if (s == null) text("살 물건을 고르세요", x0 + 10, y0 + 190, DIM);
        else {
            text(s.name(), x0 + 10, y0 + 190, WHITE);
            button(new int[]{x0 + 150, y0 + 186, 16, 16}, "-", qty > 1, false);
            button(new int[]{x0 + 190, y0 + 186, 16, 16}, "+", qty < 64, false);
            String q = String.valueOf(qty);
            text(q, x0 + 178 - width(q) / 2, y0 + 190, WHITE);
            long total = s.price() * qty;
            text("합계 " + n(total), x0 + 212, y0 + 190, held >= total ? AQUA : RED);
        }
        button(actionRect(), "구매", s != null, false);
    }

    static void shopSell(List<Object[]> holds) {      // 이름, 그림, 개수, 값
        shopFrame(true);
        int[] a = actionRect();
        a = new int[]{a[0] - 14, a[1], a[2] + 14, a[3]};
        double sum = 0;
        for (Object[] h : holds) sum += (Double) h[3];
        for (int v = 0; v < COLS * ROWS && v < holds.size(); v++) {
            Object[] h = holds.get(v);
            card(sellCardRect(v), v == 1, false, (String) h[1], h[0] + " ×" + h[2], "다이아 +" + String.format("%.2f", (Double) h[3]), GREEN);
        }
        text("눌러서 한 종류만 판매 · 예상 +" + String.format("%.2f", sum), x0 + 10, y0 + 190, GRAY);
        button(a, "전부 판매", true, false);
    }

    // ── 장면 ───────────────────────────────────────────────
    /** 게임 화면 대신 깔아 두는 배경 (동굴 느낌의 단색 블럭) */
    static void backdrop() {
        Random r = new Random(7);
        for (int y = 0; y < SH; y += 16) for (int x = 0; x < SW; x += 16) {
            int v = 58 + r.nextInt(14) - (y > 200 ? 10 : 0);
            px(x, y, 16, 16, (v << 16) | (v << 8) | (v + 4));
            if (r.nextInt(23) == 0) { px(x + 4, y + 5, 3, 3, 0x4FE0E0); px(x + 9, y + 9, 3, 2, 0x4FE0E0); }
        }
        fill(SW / 2 - 4, SH / 2, SW / 2 + 5, SH / 2 + 1, 0xC0FFFFFF);          // 조준점
        fill(SW / 2, SH / 2 - 4, SW / 2 + 1, SH / 2 + 5, 0xC0FFFFFF);
        fill(SW / 2 - 91, SH - 22, SW / 2 + 91, SH, 0xA0202020);               // 핫바 자리
        for (int i = 0; i < 9; i++) box(SW / 2 - 91 + i * 20 + 1, SH - 21, 20, 20, 0x60000000, 0xFF8A8A8A);
    }

    static void save(File dir, String name, Runnable scene) throws Exception {
        BufferedImage im = new BufferedImage(SW * S, SH * S, BufferedImage.TYPE_INT_RGB);
        g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        backdrop();
        scene.run();
        g.dispose();
        ImageIO.write(im, "png", new File(dir, name));
    }

    static void reset() {
        goal = 1247; held = 86; goalDelta = 0; heldDelta = 0; done = false;
        queue = new ArrayList<>(); effects = new ArrayList<>(); banner = null; toasts = new ArrayList<>();
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        dir.mkdirs();

        // 1. 평소 화면: 다이아를 캐는 중
        reset(); heldDelta = 3;
        save(dir, "1_평소화면.png", () -> { drawPanel(); });

        // 2. 후원이 몰렸을 때: 배너 + 대기열 + 걸린 효과 + 알림
        reset(); goalDelta = 37;
        queue.add("목표 증가 10회 → Nani"); queue.add("장난 랜덤"); queue.add("밥 3개 → Rinko"); queue.add("목표 감소 → Nani"); queue.add("즉사 → Zzunwoo"); queue.add("룰렛");
        effects.add(new String[]{"저주받은 곡괭이", "172"}); effects.add(new String[]{"거꾸로 세상", "24"});
        banner = new String[]{"별풍선왕", "500", "목표 증가 10회", "bad", "Nani"};
        toasts.add(new Object[]{"Nani님의 남은 목표 +312 (10회)", false});
        toasts.add(new Object[]{"Rinko님이 밥 3개를 받았습니다.", true});
        save(dir, "2_후원알림.png", () -> { drawPanel(); drawQueue(); drawEffects(); drawToasts(drawBanner()); });

        // 3. 대상 룰렛: 1명 대상 장난이 누구에게 갈지 뽑는 중
        reset();
        queue.add("장난 랜덤");
        save(dir, "3_대상룰렛.png", () -> { drawPanel(); drawQueue(); drawPick("개미화", List.of("Nani", "Rinko", "Zzunwoo", "Kkaegul"), 2, true); });

        // 4. 상점 구매 탭
        reset();
        List<Item> picks = List.of(
                new Item("wooden_pickaxe", "pickaxe", "나무 곡괭이", 1, 1, "wooden_pickaxe"), new Item("stone_pickaxe", "pickaxe", "돌 곡괭이", 3, 1, "stone_pickaxe"),
                new Item("iron_pickaxe", "pickaxe", "철 곡괭이", 10, 1, "iron_pickaxe"), new Item("golden_pickaxe", "pickaxe", "금 곡괭이", 8, 1, "golden_pickaxe"),
                new Item("diamond_pickaxe", "pickaxe", "다이아 곡괭이", 40, 1, "diamond_pickaxe"), new Item("netherite_pickaxe", "pickaxe", "네더라이트 곡괭이", 150, 1, "netherite_pickaxe"));
        save(dir, "4_상점_구매.png", () -> { drawPanel(); shopBuy(new String[]{"곡괭이", "인챈트북", "아이템"}, 0, picks, 4, 2, 2); });

        // 5. 상점 구매 탭: 인챈트북 (가장 긴 목록) + 다이아가 모자랄 때
        reset(); held = 25;
        List<Item> books = List.of(
                new Item("e3", "enchant", "효율 III", 20, 1, "enchanted_book"), new Item("e5", "enchant", "효율 V", 60, 1, "enchanted_book"),
                new Item("f1", "enchant", "행운 I", 30, 1, "enchanted_book"), new Item("f2", "enchant", "행운 II", 80, 1, "enchanted_book"),
                new Item("f3", "enchant", "행운 III", 200, 1, "enchanted_book"), new Item("u3", "enchant", "내구성 III", 30, 1, "enchanted_book"),
                new Item("m", "enchant", "수선", 150, 1, "enchanted_book"));
        save(dir, "5_상점_인챈트북.png", () -> {
            drawPanel(); shopBuy(new String[]{"곡괭이", "인챈트북", "아이템"}, 1, books, 4, -1, 64);
            toasts.add(new Object[]{"보유 다이아가 부족합니다. (필요 12,800개)", false});
            drawToasts(-1);
        });

        // 6. 상점 구매 탭: 아이템
        reset();
        List<Item> things = List.of(new Item("food", "item", "밥", 1, 8, "bread"), new Item("inv", "item", "인벤세이브권", 15, 1, "paper"),
                new Item("ret", "item", "귀환권", 5, 1, "paper"), new Item("tp", "item", "텔레포트권", 5, 1, "paper"));
        save(dir, "6_상점_아이템.png", () -> { drawPanel(); shopBuy(new String[]{"곡괭이", "인챈트북", "아이템"}, 2, things, -1, 0, 1); });

        // 7. 상점 판매 탭
        reset();
        List<Object[]> holds = List.of(new Object[]{"석탄", "coal", 128, 1.92}, new Object[]{"철 주괴", "iron_ingot", 48, 2.40},
                new Object[]{"금 원석", "raw_gold", 12, 0.96}, new Object[]{"레드스톤", "redstone", 200, 1.60},
                new Object[]{"청금석", "lapis_lazuli", 64, 0.96}, new Object[]{"에메랄드", "emerald", 3, 1.50});
        save(dir, "7_상점_판매.png", () -> { drawPanel(); shopSell(holds); });
        System.out.println("ok");
    }
}
