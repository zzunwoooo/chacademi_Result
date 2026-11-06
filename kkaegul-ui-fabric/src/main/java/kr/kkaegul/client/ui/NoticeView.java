package kr.kkaegul.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 알림 카드 — 서버의 "center"(멘트)와 "toast"를 모두 화면 오른쪽 아래 작은 카드로 통일.
 * 최대 3개, 카드 사이 8px, 오른쪽에서 부드럽게 들어오고 흐려지며 사라짐. 같은 알림이 연달아 오면 합쳐서 ×2 로.
 * (잭팟·상자 같은 큰 연출은 FxView 가 화면 가운데에 따로 그림)
 */
public final class NoticeView {
    private static final class Card {
        final String text, sub, kind;
        long start, life;
        int count = 1;
        float y = -1;        // 지금 그려지는 위치 (부드럽게 따라감)
        Card(String text, String sub, String kind, long start, long life) { this.text = text; this.sub = sub; this.kind = kind; this.start = start; this.life = life; }
    }

    private final List<Card> cards = new ArrayList<>();
    private static final int MAX = 3;
    private static final float W = 340, GAP = 8, MARGIN = 12;
    private static final long IN = 220, OUT = 420;

    public void center(String text, String sub, long ms, long now) {
        add(text, sub == null ? "" : sub, Math.max(1800, ms) + 600, now);
    }

    public void toast(String text, long now) {
        add(text, "", 2800, now);
    }

    private void add(String text, String sub, long life, long now) {
        for (Card c : cards) {
            if (c.text.equals(text) && c.sub.equals(sub) && now - c.start < c.life) {
                c.count++;
                c.life = now - c.start + life;        // 시간만 연장
                return;
            }
        }
        cards.add(new Card(text, sub, kind(text + " " + sub), now, life));
        while (cards.size() > MAX) cards.remove(0);
    }

    /** 색코드/단어로 종류 고르기 */
    static String kind(String s) {
        String plain = Fmt.strip(s);
        if (plain.contains("잭팟") || plain.contains("대박") || plain.contains("JACKPOT") || plain.contains("졸업")) return "jackpot";
        String a = Fmt.amp(s);
        int i = a.indexOf('§');
        char code = 0;
        while (i >= 0 && i + 1 < a.length()) {
            char k = Character.toLowerCase(a.charAt(i + 1));
            if ("0123456789abcdef".indexOf(k) >= 0) { code = k; break; }
            i = a.indexOf('§', i + 2);
        }
        if (code == 'c' || code == '4') return "failure";
        if (code == 'a' || code == '2') return "success";
        return "neutral";
    }

    private static int accent(String kind) {
        return switch (kind) {
            case "failure" -> 0xFFE9584F;
            case "success" -> 0xFF7CC24A;
            case "jackpot" -> 0xFFF2C230;
            default -> 0xFFC9A77A;
        };
    }

    private static float height(Card c) { return c.sub.isBlank() ? 46 : 66; }

    public void render(Canvas c, long now) {
        float SW = c.width(), SH = c.height();
        cards.removeIf(k -> now - k.start > k.life);
        float bottom = SH - MARGIN;
        // 최근 것이 맨 아래
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card k = cards.get(i);
            float h = height(k);
            float target = bottom - h;
            k.y = k.y < 0 ? target : k.y + (target - k.y) * 0.25f;   // 위 카드가 사라지면 부드럽게 내려옴
            long e = now - k.start;
            float in = Anim.outCubic(Math.min(1, e / (float) IN));
            float out = e > k.life - OUT ? Math.max(0, (k.life - e) / (float) OUT) : 1;
            float a = Math.min(in, out);
            float x = SW - MARGIN - W + (1 - in) * 40;
            draw(c, k, x, k.y, h, a);
            bottom = target - GAP;
        }
    }

    private void draw(Canvas c, Card k, float x, float y, float h, float a) {
        int acc = accent(k.kind);
        c.fill(x + 2, y + 3, W, h, 14, Anim.alpha(0x40000000, a));                 // 그림자
        MenuPanel.box(c, x, y, W, h, 14, Anim.alpha(0xFF8A6A48, a), Anim.alpha(0xF2FFF7EA, a), 2);
        c.fill(x + 6, y + 8, 5, h - 16, 2.5f, Anim.alpha(acc, a));                  // 왼쪽 색 띠
        float face = h - 18;
        c.imageFit("notice/face_" + k.kind, x + 18 + face / 2, y + h / 2, face, Anim.white(a));
        float tx = x + 26 + face, tw = W - (tx - x) - (k.count > 1 ? 50 : 14);
        if (k.sub.isBlank()) {
            c.textFit(k.text, tx, y + h / 2, 21, tw, Anim.alpha(Theme.INK, a), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        } else {
            c.textFit(k.text, tx, y + 22, 21, tw, Anim.alpha(Theme.INK, a), Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
            c.textFit(k.sub, tx, y + 46, 17, tw, Anim.alpha(Theme.MUTED, a), Canvas.LEFT, Canvas.LIGHT);
        }
        if (k.count > 1) {
            c.fill(x + W - 46, y + h / 2 - 13, 36, 26, 13, Anim.alpha(acc, a));
            c.text("×" + k.count, x + W - 28, y + h / 2, 17, Anim.alpha(Theme.WHITE, a), Canvas.CENTER, Canvas.BOLD);
        }
    }
}
