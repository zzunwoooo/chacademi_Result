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
        List<String> lines, subLines;   // 줄 나눈 글자 (폭이 바뀌면 다시)
        float linesW = -1;
        boolean pill;        // 한 줄짜리 = 알약 카드(toast_cream), 아니면 깨굴이 금테 카드(banner_*)
        Card(String text, String sub, String kind, long start, long life) { this.text = text; this.sub = sub; this.kind = kind; this.start = start; this.life = life; }
    }

    private final List<Card> cards = new ArrayList<>();
    private static final int MAX = 3;
    private static final float W = 340, GAP = 8, MARGIN = 12;
    private static final long IN = 220, OUT = 420;
    private static final float SCALE = 1.7f;   // 카드 전체 크기 배율

    public void center(String text, String sub, long ms, long now) {
        add(text, sub == null ? "" : sub, Math.max(1800, ms) + 600, now);
    }

    public void toast(String text, long now) {
        add(text, "", 2800, now);
    }

    /** kind 가 정해져 온 알림 (error = 빨간 느낌표) */
    public void toast(String text, String kind, long now) {
        if (kind == null || kind.isEmpty()) { toast(text, now); return; }
        add(text, "", 2800, now, kind);
    }

    private void add(String text, String sub, long life, long now) { add(text, sub, life, now, null); }

    private void add(String text, String sub, long life, long now, String forced) {
        for (Card c : cards) {
            if (c.text.equals(text) && c.sub.equals(sub) && now - c.start < c.life) {
                c.count++;
                c.life = now - c.start + life;        // 시간만 연장
                return;
            }
        }
        cards.add(new Card(text, sub, forced != null ? forced : kind(text + " " + sub), now, life));
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
            case "failure", "error" -> 0xFFE9584F;
            case "success" -> 0xFF7CC24A;
            case "jackpot" -> 0xFFF2C230;
            default -> 0xFFC9A77A;
        };
    }

    private static final float T = 18, S = 15, TL = 22, SL = 19, FACE = 36;   // 제목·부제 글자 크기 / 줄 높이, 깨굴이 얼굴
    private static final int TSTYLE = Canvas.BOLD | Canvas.LIGHT, SSTYLE = Canvas.LIGHT, MAXL = 3;

    /** 글자 칸 폭: 얼굴과 ×N 자리(없으면 오른쪽 아래 클로버 자리)를 뺀 나머지 */
    private static float textW(Card k) { return W - 26 - FACE - (k.count > 1 ? 50 : 24); }

    /** 알약 카드(notice/toast_cream, 1024×160) 안 칸: 왼쪽 동그라미 가운데 x, 글자 판 x0~x1 (그림 폭 비율) */
    private static final float PILL_FACE = .135f, PILL_X0 = .2f, PILL_X1 = .9f, PILL_H = W * 160 / 1024f;
    /** 금테 카드 위로 튀어나온 깨굴이 높이 (그림 세로 31% × 배율) — 카드 사이를 이만큼 더 띄움 */
    private static final float FROG_TOP = 242 * .31f * W / 1024f;
    private static float pillW(Card k) { return W * (PILL_X1 - PILL_X0) - 8 - (k.count > 1 ? 44 : 0); }

    /** 종류별 카드 그림 (notice/banner_*: 위에 깨굴이가 걸터앉은 금테 판) */
    private static String banner(String kind) {
        return switch (kind) {
            case "failure", "error" -> "notice/banner_failure";
            case "success" -> "notice/banner_success";
            case "jackpot" -> "notice/banner_jackpot";
            default -> "notice/banner_neutral";
        };
    }

    /** 카드 폭에 맞춰 줄 나누기 (최대 3줄, 넘치면 …). 색 코드는 다음 줄로 이어 감 */
    static List<String> wrap(String s, float size, int style, float maxW) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        s = Fmt.amp(s);
        StringBuilder line = new StringBuilder();
        String carry = "";
        for (String word : s.split(" ")) {
            String test = line.length() == 0 ? carry + word : line + " " + word;
            if (TextRaster.width(test, size, style) <= maxW) { line.setLength(0); line.append(test); continue; }
            if (line.length() > 0) { out.add(line.toString()); carry = codes(carry + line); line.setLength(0); }
            String w = carry + word;
            while (TextRaster.width(w, size, style) > maxW && w.length() > 1) {   // 한 낱말이 너무 길면 글자 단위로 자름
                int cut = w.length() - 1;
                while (cut > 1 && TextRaster.width(w.substring(0, cut), size, style) > maxW) cut--;
                out.add(w.substring(0, cut));
                carry = codes(carry + w.substring(0, cut));
                w = carry + w.substring(cut);
            }
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        if (out.size() > MAXL) {
            List<String> cut = new ArrayList<>(out.subList(0, MAXL));
            String last = cut.get(MAXL - 1);
            while (last.length() > 1 && TextRaster.width(last + "…", size, style) > maxW) last = last.substring(0, last.length() - 1);
            cut.set(MAXL - 1, last + "…");
            out = cut;
        }
        return out;
    }

    /** 문자열 끝까지 적용된 색·꾸밈 코드 */
    private static String codes(String s) {
        StringBuilder c = new StringBuilder();
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) != '§') continue;
            char k = Character.toLowerCase(s.charAt(i + 1));
            if ("0123456789abcdefr".indexOf(k) >= 0) c.setLength(0);
            if (k != 'r') c.append('§').append(k);
            i++;
        }
        return c.toString();
    }

    private static void layout(Card k) {
        float tw = textW(k);
        if (k.lines != null && k.linesW == tw) return;
        k.linesW = tw;
        // 부제 없이 한 줄에 들어가면 알약 카드, 아니면 금테 카드
        List<String> one = k.sub.isBlank() ? wrap(k.text, T, TSTYLE, pillW(k)) : null;
        k.pill = one != null && one.size() == 1;
        k.lines = k.pill ? one : wrap(k.text, T, TSTYLE, tw);
        k.subLines = k.pill ? List.of() : wrap(k.sub, S, SSTYLE, tw);
    }

    private static float height(Card k) {
        layout(k);
        if (k.pill) return PILL_H;
        float h = 24 + k.lines.size() * TL + k.subLines.size() * SL;
        return Math.max(52, h);
    }

    public void render(Canvas c, long now) {
        cards.removeIf(k -> now - k.start > k.life);
        if (cards.isEmpty()) return;
        float SW = c.width() / SCALE, SH = c.height() / SCALE;
        c.push();
        c.scale(SCALE, SCALE);
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
            bottom = target - GAP - (k.pill ? 0 : FROG_TOP);
        }
        c.pop();
    }

    private void draw(Canvas c, Card k, float x, float y, float h, float a) {
        int acc = accent(k.kind);
        layout(k);
        if (k.pill) {
            // 알약 카드: 왼쪽 동그라미에 얼굴, 오른쪽 판에 글자 한 줄
            c.image("notice/toast_cream", x, y, W, h, Anim.white(a));
            c.imageFit("notice/face_" + k.kind, x + W * PILL_FACE, y + h / 2, FACE * .9f, Anim.white(a));
            c.text(k.lines.get(0), x + W * PILL_X0 + 8, y + h / 2, T, Anim.alpha(Theme.INK, a), Canvas.LEFT, TSTYLE);
            if (k.count > 1) badge(c, k, x + W * PILL_X1 - 40, y + h / 2, acc, a);
            return;
        }
        // 금테 카드 그림: 판(그림 세로 31%~98%)이 카드 칸 (x,y,W,h)에 오게, 위로 튀어나온 깨굴이는 카드 위로. 높이는 9조각으로 늘림
        String img = banner(k.kind);
        int[] sz = c.size(img);
        float iw = sz == null ? 1024 : sz[0], ih = sz == null ? 240 : sz[1], s = W / iw;
        float top = ih * .31f * s, bot = ih * .02f * s;
        c.imageNine(img, x, y - top, W, h + top + bot, .22f, .45f, iw * .22f * s, ih * .45f * s, Anim.white(a));
        c.imageFit("notice/face_" + k.kind, x + 18 + FACE / 2, y + h / 2, FACE, Anim.white(a));   // error = 땀 흘리는 깨굴이 (묶음 5)
        float tx = x + 26 + FACE;
        float ty = y + h / 2 - (k.lines.size() * TL + k.subLines.size() * SL) / 2;   // 글자 덩어리를 세로 가운데로
        for (String l : k.lines) { c.text(l, tx, ty + TL / 2, T, Anim.alpha(Theme.INK, a), Canvas.LEFT, TSTYLE); ty += TL; }
        for (String l : k.subLines) { c.text(l, tx, ty + SL / 2, S, Anim.alpha(Theme.MUTED, a), Canvas.LEFT, SSTYLE); ty += SL; }
        if (k.count > 1) badge(c, k, x + W - 46, y + h / 2, acc, a);
    }

    /** ×N 알약 (x = 왼쪽, cy = 세로 가운데) */
    private static void badge(Canvas c, Card k, float x, float cy, int acc, float a) {
        c.fill(x, cy - 13, 36, 26, 13, Anim.alpha(acc, a));
        c.text("×" + k.count, x + 18, cy, 17, Anim.alpha(Theme.WHITE, a), Canvas.CENTER, Canvas.BOLD);
    }
}
