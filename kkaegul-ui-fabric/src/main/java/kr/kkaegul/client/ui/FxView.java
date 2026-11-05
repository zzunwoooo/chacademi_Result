package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/** 화면 가운데 큰 연출: 상자 오픈, 피버 시작, 초대장, 졸업 축하. 한 번에 하나씩 순서대로 */
public final class FxView {
    private record Fx(String id, JsonObject d, long start, long life) {}
    private final Deque<Fx> queue = new ArrayDeque<>();
    private Fx cur;
    private int soundMask;

    public void add(String id, JsonObject d, long now) {
        long life = switch (id) {
            case "box_open" -> 2300 + Math.min(8, J.objs(d, "rewards").size()) * 230L + 2300;
            case "fever_diamond", "fever_triple", "fever_box" -> 3600;
            case "invite_letter" -> 6800;
            case "graduate" -> 5200;
            default -> 0;
        };
        if (life == 0) return;
        if (queue.size() >= 6) queue.pollFirst();
        queue.addLast(new Fx(id, d, 0, life));
        if (cur == null) next(now);
    }

    public boolean handles(String id) {
        return switch (id) {
            case "box_open", "fever_diamond", "fever_triple", "fever_box", "invite_letter", "graduate" -> true;
            default -> false;
        };
    }

    private void next(long now) {
        Fx f = queue.pollFirst();
        cur = f == null ? null : new Fx(f.id(), f.d(), now, f.life());
        soundMask = 0;
    }

    public boolean active() { return cur != null; }

    /** 클릭으로 넘기기 */
    public void skip(long now) { if (cur != null) next(now); }

    private void sound(Host h, int bit, String id, float pitch) {
        if ((soundMask & bit) != 0) return;
        soundMask |= bit;
        h.sound(id, pitch, .8f);
    }

    public void render(Canvas c, long now, Host host) {
        if (cur == null) return;
        long e = now - cur.start();
        if (e > cur.life()) { next(now); if (cur == null) return; e = 0; }
        float a = Anim.fade(now, cur.start(), cur.life(), 150, 450);
        switch (cur.id()) {
            case "box_open" -> box(c, e, a, host);
            case "fever_diamond", "fever_triple", "fever_box" -> fever(c, e, a, host);
            case "invite_letter" -> invite(c, e, a, host);
            case "graduate" -> graduate(c, e, a, host);
            default -> { }
        }
    }

    // ───────── 상자 오픈 (레퍼런스: 열린 상자 위로 빛 → 보상 카드) ─────────
    private void box(Canvas c, long e, float a, Host host) {
        JsonObject d = cur.d();
        float W = c.width(), H = c.height();
        String box = J.str(d, "box");
        String kind = switch (box) { case "premium" -> "advanced"; case "lucky" -> "lucky"; default -> "normal"; };
        c.fill(0, 0, W, H, 0, Anim.alpha(0x66000000, a));
        float cx = W / 2, cy = H * .72f, ch = 290;
        int tint = Anim.white(a);
        String state;
        float shake = 0, sc = 1;
        if (e < 800) { state = "closed"; shake = Anim.shake(e, 3 + e / 120f, 9); sc = .7f + .3f * Anim.outBack(e / 320f); }
        else if (e < 1150) { state = "ajar"; shake = Anim.shake(e, 9, 14); }
        else { state = "open"; sc = 1 + .08f * (1 - Anim.outCubic((e - 1150) / 300f)); }
        if (e >= 800) sound(host, 1, "minecraft:block.chest.open", .9f);
        if (e >= 1150) sound(host, 2, "minecraft:entity.player.levelup", 1.4f);
        List<JsonObject> rewards = J.objs(d, "rewards");
        int n = Math.min(8, rewards.size());
        // 빛줄기: 상자 → 카드
        if (state.equals("open")) {
            float bt = Anim.outCubic((e - 1150) / 400f);
            float pulse = .7f + .3f * (float) Math.sin(e / 180.0);
            c.push();
            c.translate(cx, cy - ch * .25f);
            c.scale(1, -1);
            c.imageAt("chest/" + kind + "_beam", 0, ch * .55f * bt, ch * 1.2f * bt, 1, 0, Anim.white(a * pulse * .85f));
            c.pop();
        }
        c.imageAt("chest/" + kind + "_" + state, cx + shake, cy, ch, sc, shake * .4f, tint);
        if (J.bool(d, "fever")) {
            c.imageAt("reward/chest_fever_badge", cx + ch * .62f, cy - ch * .4f, 84, 1, 0, tint);
            c.text("1+1", cx + ch * .62f, cy - ch * .4f, 28, Anim.alpha(0xFFE0564C, a), Canvas.CENTER, Canvas.BOLD);
        }
        c.textFit(J.str(d, "label", "상자"), cx, cy + ch * .6f, 30, 600, Anim.alpha(Theme.WHITE, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        if (n == 0 && e > 1300) c.text("꽝!", cx, H * .34f, 60, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);

        // 보상 카드: 1장이면 크게, 여러 장이면 줄지어
        float size = n <= 1 ? 290 : n <= 3 ? 250 : n <= 5 ? 220 : 180, gap = 26;
        int perRow = n <= 5 ? n : (n + 1) / 2;
        for (int i = 0; i < n; i++) {
            long st = 1250 + i * 230L;
            if (e < st) continue;
            float t = Anim.outBack(Anim.t(e, st, 420));
            int row = i / Math.max(1, perRow), col = i % Math.max(1, perRow);
            int inRow = Math.min(perRow, n - row * perRow);
            float tx = cx + (col - (inRow - 1) / 2f) * (size + gap);
            float ty = n <= 5 ? H * .3f : H * .18f + row * (size * .86f + 20);
            float x = Anim.lerp(cx, tx, t), y = Anim.lerp(cy - 60, ty, t);
            JsonObject r = rewards.get(i);
            boolean rare = J.bool(r, "rare");
            if (rare) sound(host, 4 << i, "minecraft:entity.experience_orb.pickup", 1.6f);
            c.push();
            c.translate(x, y);
            c.scale(.4f + .6f * t, .4f + .6f * t);
            rewardCard(c, kind, rare, J.str(r, "id"), J.str(r, "label"), size, a, e);
            c.pop();
        }
    }

    /** 보상 카드 (일반=크림, 고급=보라+별 3개, 행운=금테+메달, 희귀=메달) */
    private static void rewardCard(Canvas c, String kind, boolean rare, String id, String label, float s, float a, long e) {
        int border, fill;
        switch (kind) {
            case "advanced" -> { border = 0xFFC9B2E6; fill = 0xFFF1E9FA; }
            case "lucky" -> { border = 0xFFE8C255; fill = 0xFFFDF6E3; }
            default -> { border = 0xFFE6D6B6; fill = 0xFFFBF5E8; }
        }
        float h = s * .86f;
        HudView.leaf(c, -s * .36f, -h / 2 + 4, s * .07f, true);
        HudView.leaf(c, s * .36f, -h / 2 + 4, s * .07f, false);
        if (kind.equals("advanced"))
            for (int k = -1; k <= 1; k++) c.imageAt("gacha/confetti_star", k * s * .14f, -h / 2 - s * (k == 0 ? .1f : .06f), s * (k == 0 ? .14f : .1f), 1, 0, Anim.white(a));
        MenuPanel.box(c, -s / 2, -h / 2, s, h, s * .16f, Anim.alpha(border, a), Anim.alpha(fill, a), s * .025f);
        MenuPanel.box(c, -s * .22f, -h / 2 + s * .1f, s * .44f, s * .4f, s * .08f, Anim.alpha(0xFFE6D9C2, a), Anim.alpha(0xFFFFFBF2, a), s * .012f);
        c.imageFit(icon(id, label), 0, -h / 2 + s * .3f, s * .3f, Anim.white(a));
        MenuPanel.box(c, -s * .4f, h / 2 - s * .27f, s * .8f, s * .17f, s * .085f, Anim.alpha(0xFFE6D9C2, a), Anim.alpha(0xFFFFFBF2, a), s * .01f);
        c.textFit(label, 0, h / 2 - s * .185f, Math.max(19, s * .085f), s * .68f, Anim.alpha(Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (rare || kind.equals("lucky")) {
            float mx = s * .42f, my = -h / 2 + s * .06f;
            c.imageAt("menu/medal_gold", mx, my + s * .06f, s * .28f, 1, 8, Anim.white(a));
        }
    }

    /** 보상 이름으로 아이콘 고르기 */
    static String icon(String id, String label) {
        String s = (id + " " + label).toLowerCase();
        if (s.contains("다이아") || s.contains("diamond")) return "hud/diamond";
        if (s.contains("코인") || s.contains("coin")) return "hud/frog_coin";
        if (s.contains("상자") || s.contains("box")) return "chest/normal_closed";
        if (s.contains("방지") || s.contains("protect")) return "gacha_machine/protection_ticket";
        if (s.contains("텔레포트") || s.contains("텔포") || s.contains("teleport")) return "user_select/teleport_ticket";
        if (s.contains("게이지") || s.contains("gauge")) return "gacha_machine/luck_clover";
        if (s.contains("콜라") || s.contains("cola") || s.contains("음식") || s.contains("food")) return "reward/event_buff";
        if (s.contains("꽝") || s.contains("fail")) return "menu/icon_losing_paper";
        if (s.contains("곡괭이") || s.contains("pickaxe")) return "gacha_machine/pickaxe";
        if (s.contains("인벤") || s.contains("수선") || s.contains("mending")) return "menu/icon_mending_book";
        return "reward/event_diamond";
    }

    // ───────── 피버 시작 (레퍼런스: 테두리 카드 · 왼쪽 깨굴이 · 가로지르는 리본 · 위 아이콘 3개 · 오른쪽 메달) ─────────
    private void fever(Canvas c, long e, float a, Host host) {
        String id = cur.id();
        String k = id.equals("fever_triple") ? "triple" : id.equals("fever_box") ? "chest" : "diamond";
        String title = switch (k) { case "triple" -> "3배 피버 터짐!!"; case "chest" -> "상자 피버!"; default -> "다이아 증감 피버!"; };
        String sub = switch (k) { case "triple" -> "룰렛 결과 1+2"; case "chest" -> "상자 내용물 1+1"; default -> "룰렛이 빨라지고 1+1"; };
        long sec = J.num(cur.d(), "seconds");
        if (sec > 0) sub = Fmt.time(sec) + " 동안 " + sub;
        int accent = switch (k) { case "triple" -> 0xFF9B4FCF; case "chest" -> 0xFF3C8FD6; default -> 0xFFE0564C; };
        sound(host, 1, "minecraft:entity.firework_rocket.blast", 1f);
        if (e > 300) sound(host, 2, "minecraft:entity.player.levelup", 1.2f);
        float W = c.width(), H = c.height();
        float w = Math.min(860, W - 160);
        int[] fs = c.size("fever/frame_" + k);
        float h = fs == null ? w * .5f : w * fs[1] / fs[0];
        float pop = .8f + .2f * Anim.outBack(Anim.t(e, 0, 380));
        float x = -w / 2, y = -h / 2;
        int tint = Anim.white(a);
        c.push();
        c.translate(W / 2, H * .42f);
        c.scale(pop, pop);
        // 테두리 없이 안쪽 구성만 (깨굴이 · 리본 · 아이콘 · 메달)
        // 위 아이콘 3개 (가운데 크게)
        String deco = switch (k) { case "triple" -> "fever/lightning"; case "chest" -> "fever/gift"; default -> "fever/flame"; };
        for (int i = -1; i <= 1; i++) {
            float bt = Anim.outBack(Anim.t(e, 200 + (i + 1) * 90L, 350));
            float s = (i == 0 ? h * .3f : h * .2f) * bt;
            c.imageAt(deco, i * w * .13f + w * .04f, y + h * (i == 0 ? .2f : .26f) + Anim.shake(e, 2, 3 + i), s, 1, i * 10, tint);
        }
        // 리본 (살짝 기울게)
        float bw = w * .78f;
        int[] bs = c.size("fever/banner_" + k);
        float bh = bs == null ? bw * .18f : bw * bs[1] / bs[0];
        float slide = (1 - Anim.outCubic(Anim.t(e, 100, 400))) * w * .3f;
        c.push();
        c.translate(w * .07f + slide, h * .05f);
        c.rotate(-5);
        c.image("fever/banner_" + k, -bw / 2, -bh / 2, bw, bh, tint);
        c.textFit(title, -bw * .02f, -bh * .04f, h * .1f, bw * .6f, Anim.alpha(accent, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.pop();
        // 오른쪽 메달 (3배 / 상자)
        if (!k.equals("diamond")) {
            float mt = Anim.outBack(Anim.t(e, 500, 400));
            c.imageAt("reward/chest_fever_badge", x + w * .86f, h * .02f, h * .3f * mt, 1, 0, tint);
            c.text(k.equals("triple") ? "x3" : "1+1", x + w * .86f, h * .02f, h * .1f * mt, Anim.alpha(accent, a), Canvas.CENTER, Canvas.BOLD);
        }
        // 왼쪽 깨굴이
        String frog = switch (k) { case "triple" -> "fever/frog_megaphones"; case "chest" -> "fever/frog_boxes"; default -> "fever/frog_drummer"; };
        float bounce = Math.abs((float) Math.sin(e / 130.0)) * 10;
        c.imageAt(frog, x + w * .17f, y + h * .64f - bounce, h * .56f, 1, 0, tint);
        c.textFit(sub, w * .1f, y + h * .86f, h * .065f, w * .6f, Anim.alpha(Theme.WHITE, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        c.pop();
    }

    // ───────── 초대장 (레퍼런스: 봉인된 봉투 → 봉인 깨짐 → 봉투에서 카드가 올라옴 · 오른쪽 집사 깨굴이) ─────────
    private void invite(Canvas c, long e, float a, Host host) {
        JsonObject d = cur.d();
        float W = c.width(), H = c.height();
        int tint = Anim.white(a);
        c.fill(0, 0, W, H, 0, Anim.alpha(0x88000000, a));
        float ew = Math.min(560, W * .35f);
        float cx = W / 2 - ew * .12f, bottom = H * .9f;
        if (e < 1150) {
            float drop = Anim.outBounce(Anim.t(e, 0, 650));
            float eh = ew * 545 / 1024f;
            float y = Anim.lerp(-eh, bottom - eh, drop);
            if (e > 300) sound(host, 1, "minecraft:item.book.page_turn", .8f);
            c.image("invite/envelope_closed", cx - ew / 2, y, ew, eh, tint);
            float sh = e > 650 ? Anim.shake(e, 5, 12) : 0;
            c.imageAt("invite/wax_seal", cx + sh, y + eh * .52f, eh * .4f, 1, sh * 2, tint);
            return;
        }
        float bh = ew * 797 / 1024f, fh = ew * 514 / 1024f;
        float envTop = bottom - bh;
        c.image("invite/envelope_back", cx - ew / 2, envTop, ew, bh, tint);
        // 카드가 올라온다
        float cardW = ew * .78f, cardH = cardW * 1024 / 707f;
        float rise = Anim.outCubic(Anim.t(e, 1300, 900));
        float cardBottom = Anim.lerp(bottom - 30, bottom - fh * .42f, rise);
        float cy = cardBottom - cardH / 2;
        c.push();
        c.translate(cx, cy);
        if (rise > .98f) {
            // 올라온 뒤 위쪽 봉인이 반짝
            float glow = .5f + .5f * (float) Math.sin(e / 200.0);
            c.fill(-cardW * .1f, -cardH / 2 - cardW * .02f, cardW * .2f, cardW * .2f, cardW * .1f, Anim.alpha(0x66B6F07A, a * glow));
        }
        c.image("invite/invitation_card", -cardW / 2, -cardH / 2, cardW, cardH, tint);
        float ta = a * Anim.t(e, 2100, 300);
        c.textFit(J.str(d, "title", "깨굴이의 위험한 초대"), 0, -cardH / 2 + cardH * .125f, cardW * .055f, cardW * .6f, Anim.alpha(0xFF6B4A9C, ta), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.textFit("To. " + J.str(d, "to"), 0, -cardH / 2 + cardH * .38f, cardW * .075f, cardW * .78f, Anim.alpha(Theme.INK, ta), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        String line2 = d.has("from") ? "From. " + J.str(d, "from") : "장소 : " + J.str(d, "place", "비밀");
        c.textFit(line2, 0, -cardH / 2 + cardH * .655f, cardW * .065f, cardW * .78f, Anim.alpha(Theme.INK, ta), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (e > 2900) {
            float st = Anim.t(e, 2900, 260);
            sound(host, 4, "minecraft:entity.iron_golem.attack", .7f);
            float ss = 2.2f - 1.2f * Anim.outCubic(st);
            float sy = -cardH / 2 + cardH * .89f, sz = cardW * .4f;
            c.imageAt("invite/stamp_red", 0, sy, sz, ss, -12, Anim.white(a * st));
            c.push(); c.translate(0, sy); c.rotate(-12); c.scale(ss, ss);
            c.text("거부", 0, -sz * .14f, sz * .22f, Anim.alpha(0xFFD8423B, a * st), Canvas.CENTER, Canvas.BOLD);
            c.text("불가", 0, sz * .14f, sz * .22f, Anim.alpha(0xFFD8423B, a * st), Canvas.CENTER, Canvas.BOLD);
            c.pop();
        }
        c.pop();
        c.image("invite/envelope_front_overlay", cx - ew / 2, bottom - fh, ew, fh, tint);
        // 봉인 조각이 떨어진다
        if (e < 1900) {
            float t = Anim.t(e, 1150, 750);
            sound(host, 2, "minecraft:block.glass.break", 1.4f);
            c.imageAt("invite/seal_fragment_a", cx - 40 - 30 * t, bottom - fh * .3f + 160 * t * t, ew * .09f, 1, -50 * t, Anim.white(a * (1 - t)));
            c.imageAt("invite/seal_fragment_b", cx + 30 + 30 * t, bottom - fh * .25f + 180 * t * t, ew * .09f, 1, 60 * t, Anim.white(a * (1 - t)));
        }
        if (e > 2300) {
            float bt = Anim.outBack(Anim.t(e, 2300, 450));
            c.imageAt("invite/frog_butler", cx + ew * .5f + ew * .32f, bottom - ew * .3f + (1 - bt) * 60, ew * .6f, 1, 0, Anim.white(a * Anim.clamp(bt)));
        }
    }

    // ───────── 졸업 ─────────
    private void graduate(Canvas c, long e, float a, Host host) {
        float W = c.width(), H = c.height();
        sound(host, 1, "minecraft:ui.toast.challenge_complete", 1f);
        c.fill(0, 0, W, H, 0, Anim.alpha(0x66000000, a));
        confetti(c, e, a, cur.start());
        float t = Anim.outBack(Anim.t(e, 0, 500));
        c.imageAt("gacha/jackpot_sunburst", W / 2, H * .45f, 560, 1 + .05f * (float) Math.sin(e / 300.0), e / 40f, Anim.white(a * .8f));
        c.imageAt("gacha/frog_jackpot", W / 2, H * .47f, 330 * t, 1, 0, Anim.white(a));
        float rw = 760, rh = rw * 247 / 1024f;
        c.image("gacha/jackpot_ribbon", W / 2 - rw / 2, H * .66f, rw, rh, Anim.white(a));
        c.text("졸업 축하해요!", W / 2, H * .66f + rh * .42f, 44, Anim.alpha(0xFFE0564C, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        String name = J.str(cur.d(), "name");
        if (!name.isEmpty()) c.text(name, W / 2, H * .2f, 40, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
    }

    private static final String[] CONFETTI = {"gacha/confetti_blue", "gacha/confetti_diamond", "gacha/confetti_green", "gacha/confetti_purple", "gacha/confetti_red", "gacha/confetti_star"};

    /** 위에서 쏟아지는 색종이 (seed 고정이라 프레임마다 같은 궤적) */
    public static void confetti(Canvas c, long e, float a, long seed) {
        Random r = new Random(seed);
        float W = c.width(), H = c.height();
        for (int i = 0; i < 26; i++) {
            float x0 = r.nextFloat() * W, delay = r.nextFloat() * 900, vy = 260 + r.nextFloat() * 300, sway = 30 + r.nextFloat() * 50;
            float spin = (r.nextFloat() - .5f) * 400, size = 46 + r.nextFloat() * 44;
            String img = CONFETTI[r.nextInt(CONFETTI.length)];
            float t = (e - delay) / 1000f;
            if (t < 0) continue;
            float y = -60 + vy * t + 40 * t * t;
            if (y > H + 80) continue;
            float x = x0 + (float) Math.sin(t * 3 + i) * sway;
            c.imageAt(img, x, y, size, 1, spin * t, Anim.white(a));
        }
    }
}
