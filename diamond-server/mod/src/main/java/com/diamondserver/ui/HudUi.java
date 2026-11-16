package com.diamondserver.ui;

import com.diamondserver.client.St;

/**
 * 항상 떠 있는 화면: 왼쪽 위 한 줄 HUD · 걸린 효과 · 이벤트 대기열 · 획득 알림,
 * 위쪽 가운데 후원 알림·알림 글, 대상 룰렛(얼굴 칸) · 1000개 룰렛(원판) · 발표 창 · 즉사 연출.
 * 그림은 오너가 준 것만 쓰고, 좌표는 오너가 승인한 배치 미리보기와 같다 (1920×1080 기준).
 */
public final class HudUi {
    private HudUi() { }

    /** 시작할 때 0.15초에 걸쳐 나타나고, 끝나기 0.4초 전부터 사라진다 */
    private static double fade(long age, long life) {
        if (age < 0 || age > life) return 0;
        return Math.max(0, Math.min(1, Math.min(age / 150.0, (life - age) / 400.0)));
    }

    private static int a(int argb, double alpha) {
        int al = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    /** 화면 표시를 숨긴 상태(F1)에서도 정전은 가린다 */
    public static void blackoutOnly(G g) { blackout(g, System.currentTimeMillis()); }

    /** 평소 화면 전체 */
    public static void draw(G g) {
        under(g, "");
        over(g, false);
    }

    /** 창(상점·우편함·닉네임) 아래에 깔리는 부분: 왼쪽 위 HUD 묶음. open = 열려 있는 창 ("shop" / "mail" / "") */
    public static void under(G g, String open) {
        long now = System.currentTimeMillis();
        blackout(g, now);
        mini(g, open);
        double y = slots(g, now);
        queue(g, y);
        gain(g, now);
    }

    /**
     * 창 위에도 보여야 하는 부분: 위쪽 가운데 알림, 룰렛, 발표 창, 즉사.
     * compact = 창이 열려 있을 때. 알림을 가장 새것 하나만 보여 준다 (창의 탭·내용을 가리지 않게).
     */
    public static void over(G g, boolean compact) {
        long now = System.currentTimeMillis();
        double ox = (g.vw() - 1920) / 2;
        double top = 24;
        if (compact) {
            St.Toast last = St.toasts.isEmpty() ? null : St.toasts.get(St.toasts.size() - 1);
            if (St.banner != null && (last == null || St.banner.at() >= last.at())) top = banner(g, ox, top, now);
            else if (last != null) {
                double al = fade(now - last.at(), St.TOAST_MS);
                if (al > 0) { frame(g, ox, top, last.ok() ? "neutral" : "hinder", "icons/diamond", null, last.text(), al); top += 118; }
            }
        } else {
            top = banner(g, ox, top, now);
            top = toasts(g, ox, top, now, St.wheel != null ? 2 : 3);
        }
        if (St.pick != null) pick(g, ox, top, now);
        else if (St.wheel != null) wheel(g, ox, top, now);
        else if (St.announce != null) announce(g, ox, Math.max(150, top + 8), now);
        death(g, now);
    }

    /** 정전: 화면 전체를 까맣게 (0.4초에 걸쳐 어두워지고 밝아진다) */
    private static void blackout(G g, long now) {
        if (now >= St.blackoutUntil) return;
        double in = Math.min(1, (now - St.blackoutFrom) / 400.0), out = Math.min(1, (St.blackoutUntil - now) / 400.0);
        g.rect(0, 0, g.vw(), g.vh(), a(0xFA000000, Math.max(0, Math.min(in, out))));
    }

    // ── 왼쪽 위 한 줄 HUD (받은 layout.json 좌표 그대로) ────────────────────
    /** open = 지금 열려 있는 창 ("shop" / "mail" / ""): 그 아이콘 뒤에 선택 표시를 깐다 */
    public static void mini(G g, String open) {
        g.img("hud/mini_hud", 24, 16, 566, 50, 1);
        g.img("hud/goal_icon", 36, 25, 33, 33, 1);
        g.fit("남은 목표 " + (St.done ? "달성" : G.n(St.goal)), 76, 41, 17, 0, G.TEXT, 130);
        g.img("hud/wallet_icon", 224, 25, 33, 33, 1);
        g.fit("보유 " + G.n(St.held), 264, 41, 18, 0, G.TEXT, 136);
        if (open.equals("shop")) g.img("hud/nav_selected", 426, 22, 50, 38, 1);
        g.img("hud/shop", 433, 24, 35, 35, 1);
        if (open.equals("mail")) g.img("hud/nav_selected", 519, 22, 50, 38, 1);
        g.img("hud/mail", 526, 23, 36, 36, 1);
        // 받지 않은 우편 수: 우편함 아이콘 오른쪽 위에 겹쳐서 (HUD 틀 안쪽)
        if (!St.mails.isEmpty()) g.shadow(St.mails.size() > 99 ? "99+" : String.valueOf(St.mails.size()), 567, 29, 16, 1, G.TEXT);
    }

    /** 걸려 있는 효과: HUD 아래 한 줄. 칸 72x72 + 아래 남은 시간 막대. 다음 줄이 시작할 y 를 돌려준다 */
    private static double slots(G g, long now) {
        double x = 24, y = 78;
        int shown = 0;
        for (St.Effect e : St.effects) {
            if (e.endsAt() <= now || shown >= 6) continue;
            double left = e.endsAt() - now;
            g.img("hud/event_slot", x, y, 72, 72, 1);
            g.img(e.icon(), x + 12, y + 12, 48, 48, 1);
            g.img("hud/event_time_empty", x, y + 76, 72, 10, 1);
            g.imgClip("hud/event_time_fill", x, y + 76, 72, 10, 0, Math.max(0, Math.min(1, left / e.total())));
            g.shadow((long) Math.ceil(left / 1000.0) + "초", x + 36, y + 99, 15, 1, G.TEXT);
            x += 84;
            shown++;
        }
        return shown > 0 ? 196 : 78;
    }

    /** 이벤트 대기열: 한 줄 360x56, 맨 위가 진행 중. 4줄을 넘으면 마지막 줄에 "외 N건" */
    private static void queue(G g, double y) {
        int n = St.queue.size(), lines = Math.min(4, n);
        for (int i = 0; i < lines; i++) {
            St.Row r = St.queue.get(i);
            g.img("hud/queue_row", 24, y, 360, 56, 1);
            g.img(r.icon(), 24 + 9, y + 11, 34, 34, 1);
            g.fit(r.text(), 24 + 62, y + 28, 18, 0, i == 0 ? G.TEXT : G.DIM, 284);
            y += 60;
        }
        if (n > 4) {
            g.img("hud/queue_row", 24, y, 360, 56, 1);
            g.text("외 " + (n - 4) + "건", 24 + 62, y + 28, 18, 0, G.DIM);
        }
    }

    /** 보유 다이아가 바뀌면 HUD 오른쪽에 잠깐 +N / -N */
    private static void gain(G g, long now) {
        long age = now - St.heldChangedAt;
        if (St.heldDelta == 0 || age < 0 || age > St.GAIN_MS) return;
        double al = fade(age, St.GAIN_MS);
        g.img("toasts/gain", 602, 13, 220, 56, al);
        g.img("icons/diamond", 602 + 14, 13 + 11, 34, 34, al);
        g.fit((St.heldDelta > 0 ? "+" : "-") + G.n(Math.abs(St.heldDelta)), 602 + 64, 41, 22, 0, a(G.TEXT, al), 140);
    }

    // ── 위쪽 가운데: 후원 알림 · 알림 글 (640x110 한 장씩 아래로 쌓인다) ────────────
    private static void frame(G g, double ox, double y, String kind, String icon, String top, String bottom, double al) {
        double x = ox + 640;
        g.img("toasts/donation_" + kind, x, y, 640, 110, al);
        g.img(icon, x + 22, y + 25, 60, 60, al);
        if (top == null || top.isEmpty()) {
            if (g.textW(bottom, 23) <= 492) g.text(bottom, x + 124, y + 55, 23, 0, a(G.TEXT, al));
            else {                                      // 한 줄에 안 들어가면 띄어쓰기에서 두 줄로 나눈다
                String[] two = wrap(g, bottom, 20, 492);
                g.fit(two[0], x + 124, y + 39, 20, 0, a(G.TEXT, al), 492);
                g.fit(two[1], x + 124, y + 71, 20, 0, a(G.TEXT, al), 492);
            }
        } else {
            g.fit(top, x + 124, y + 37, 19, 0, a(G.DIM, al), 492);
            g.fit(bottom, x + 124, y + 72, 25, 0, a(G.TEXT, al), 492);
        }
    }

    /** 글을 두 줄로 나눈다: 첫 줄에 maxW 안에서 들어가는 만큼, 나머지는 둘째 줄 */
    private static String[] wrap(G g, String s, double px, double maxW) {
        String[] words = s.split(" ");
        StringBuilder first = new StringBuilder();
        int i = 0;
        for (; i < words.length; i++) {
            String next = first.length() == 0 ? words[i] : first + " " + words[i];
            if (first.length() > 0 && g.textW(next, px) > maxW) break;
            first.setLength(0);
            first.append(next);
        }
        StringBuilder rest = new StringBuilder();
        for (; i < words.length; i++) rest.append(rest.length() == 0 ? "" : " ").append(words[i]);
        return new String[]{first.toString(), rest.toString()};
    }

    private static String kind(String k) { return k.equals("good") ? "help" : k.equals("bad") ? "hinder" : "neutral"; }

    private static double banner(G g, double ox, double y, long now) {
        St.Banner b = St.banner;
        if (b == null) return y;
        double al = fade(now - b.at(), St.BANNER_MS);
        if (al <= 0) return y;
        frame(g, ox, y, kind(b.kind()), b.icon(), b.donor() + "님 · 별풍선 " + G.n(b.count()) + "개",
                b.target().isEmpty() ? b.name() : b.name() + " → " + b.target(), al);
        return y + 118;
    }

    private static double toasts(G g, double ox, double y, long now, int max) {
        int skip = Math.max(0, St.toasts.size() - max);
        for (int i = skip; i < St.toasts.size(); i++) {
            St.Toast t = St.toasts.get(i);
            double al = fade(now - t.at(), St.TOAST_MS);
            if (al <= 0) continue;
            frame(g, ox, y, t.ok() ? "neutral" : "hinder", "icons/diamond", null, t.text(), al);
            y += 118;
        }
        return y;
    }

    // ── 대상 룰렛: 얼굴 칸들 사이를 불빛이 돌다가 한 명에서 멈춘다 ─────────────────
    private static void pick(G g, double ox, double y, long now) {
        St.Pick p = St.pick;
        long age = now - p.at();
        boolean done = age >= p.ms();
        frame(g, ox, y, "neutral", p.icon(), done ? "대상이 정해졌습니다" : "대상을 뽑는 중", p.title(), 1);
        int n = p.names().size();
        double t = Math.min(1, age / (double) p.ms()), ease = 1 - Math.pow(1 - t, 3);
        int lit = done ? p.winner() : (int) Math.floor(ease * (n * 4 + p.winner())) % n;      // 처음엔 빠르게, 끝으로 갈수록 느리게

        int perRow = Math.min(n, 6), rows = (n + perRow - 1) / perRow;
        double fy = y + 180;
        for (int r = 0; r < rows; r++) {
            int inRow = Math.min(perRow, n - r * perRow);
            double x = ox + (1920 - (inRow * 160 + (inRow - 1) * 28)) / 2.0;
            for (int c = 0; c < inRow; c++) {
                int i = r * perRow + c;
                String state = i == lit ? (done ? "winner" : "lit") : "normal";
                g.face(p.ids().get(i), x + 28, fy + 28, 104);
                g.img("events/face_" + state, x, fy, 160, 160, 1);
                g.shadow(p.names().get(i), x + 80, fy + 186, 21, 1, G.TEXT);
                x += 188;
            }
            fy += 220;
        }
    }

    // ── 1000개 룰렛: 원판이 돌고 바늘은 고정. 상품 아이콘·이름은 똑바로 선 채 같이 돈다 ───────────
    private static void wheel(G g, double ox, double y, long now) {
        St.Wheel w = St.wheel;
        long age = now - w.at();
        boolean done = age >= w.ms();
        frame(g, ox, y, "neutral", "icons/roulette", done ? "룰렛 결과" : "룰렛이 도는 중", w.title(), 1);
        int n = w.labels().size();
        double sector = 360.0 / n;
        double t = Math.min(1, age / (double) w.ms()), ease = 1 - Math.pow(1 - t, 3);
        double rot = -(sector / 2 + sector * w.winner()) + (1 - ease) * 360 * 4;       // 끝나면 당첨 칸이 바늘 아래(12시)에 온다

        double size = 666, cx = ox + 960, cy = Math.max(610, y + 118 + 16 + size / 2 + 30);
        g.imgRot("events/wheel_eight", cx, cy, size, size, rot);
        for (int i = 0; i < n; i++) {
            double ang = Math.toRadians(-90 + sector / 2 + sector * i + rot), r = size * 0.5 * 0.62;
            double ix = cx + Math.cos(ang) * r, iy = cy + Math.sin(ang) * r;
            g.img(w.icons().get(i), ix - 42, iy - 52, 84, 84, 1);
            g.fit(w.labels().get(i), ix, iy + 46, 16, 1, G.TEXT, 150);
        }
        g.img("events/wheel_hub", cx - 39, cy - 39, 78, 78, 1);
        g.img("events/wheel_pointer", cx - 31, cy - size / 2 - 36, 62, 84, 1);
    }

    // ── 발표 창 900x300 (아이콘·제목·설명 자리는 받은 dynamic_regions 그대로) ────────────
    private static void announce(G g, double ox, double y, long now) {
        St.Announce an = St.announce;
        double al = fade(now - an.at(), St.ANNOUNCE_MS);
        if (al <= 0) return;
        double x = ox + 510;
        g.img("alerts/announcement", x, y, 900, 300, al);
        g.img(an.icon(), x + 402, y + 31, 96, 96, al);
        g.fit(an.title(), x + 450, y + 175, 36, 1, a(G.TEXT, al), 760);
        g.fit(an.desc(), x + 450, y + 246, 22, 1, a(G.DIM, al), 760);
    }

    // ── 즉사: 화면 가장자리 붉은 효과 → 제목 띠 → 글자 ─────────────────────────
    private static void death(G g, long now) {
        St.Death d = St.death;
        if (d == null) return;
        double al = fade(now - d.at(), St.DEATH_MS);
        if (al <= 0) return;
        double ox = (g.vw() - 1920) / 2, oy = (g.vh() - 1080) / 2;
        g.img("alerts/instant_death_vignette", 0, 0, g.vw(), g.vh(), al);
        g.img("alerts/instant_death_title", ox + 360, oy + 420, 1200, 160, al);
        String s = d.name() + "님 즉사";
        double px = 54;
        while (px > 34 && g.textW(s, px) > 820) px -= 2;
        double start = ox + 960 - (96 + 24 + g.textW(s, px)) / 2;        // 아이콘과 글자를 한 덩어리로 가운데에
        g.img("icons/instant_death", start, oy + 452, 96, 96, al);
        g.text(s, start + 120, oy + 498, px, 0, a(G.TEXT, al));
    }
}
