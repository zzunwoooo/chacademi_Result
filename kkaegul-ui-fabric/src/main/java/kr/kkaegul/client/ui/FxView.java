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

    // ───────── 상자 오픈 ─────────
    private void box(Canvas c, long e, float a, Host host) {
        JsonObject d = cur.d();
        float W = c.width(), H = c.height();
        String kind = switch (J.str(d, "box")) { case "premium" -> "advanced"; case "lucky" -> "lucky"; default -> "normal"; };
        c.fill(0, 0, W, H, 0, Anim.alpha(0x88000000, a));
        float cx = W / 2, cy = H * .64f, ch = 240;
        int tint = Anim.white(a);
        String state;
        float shake = 0, sc = 1;
        if (e < 800) { state = "closed"; shake = Anim.shake(e, 3 + e / 120f, 9); sc = .7f + .3f * Anim.outBack(e / 320f); }
        else if (e < 1150) { state = "ajar"; shake = Anim.shake(e, 9, 14); }
        else { state = "open"; sc = 1 + .08f * (1 - Anim.outCubic((e - 1150) / 300f)); }
        if (e >= 800) sound(host, 1, "minecraft:block.chest.open", .9f);
        if (e >= 1150) sound(host, 2, "minecraft:entity.player.levelup", 1.4f);
        if (state.equals("open")) {
            float bt = Anim.outCubic((e - 1150) / 400f);
            float pulse = .75f + .25f * (float) Math.sin(e / 180.0);
            c.imageAt("chest/" + kind + "_beam", cx, cy - ch * .75f, ch * 1.5f * bt, 1, 0, Anim.white(a * pulse * .9f));
        }
        c.imageAt("chest/" + kind + "_" + state, cx + shake, cy, ch, sc, shake * .4f, tint);
        if (J.bool(d, "fever")) {
            c.imageAt("reward/chest_fever_badge", cx + ch * .62f, cy - ch * .45f, 92, 1, 0, tint);
            c.text("1+1", cx + ch * .62f, cy - ch * .45f, 30, Anim.alpha(0xFFE0564C, a), Canvas.CENTER, Canvas.BOLD);
        }
        c.textFit(J.str(d, "label", "상자"), cx, cy + ch * .62f, 30, 600, Anim.alpha(Theme.WHITE, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);

        // 보상 카드: 상자에서 튀어나와 위쪽에 늘어선다
        List<JsonObject> rewards = J.objs(d, "rewards");
        int n = Math.min(8, rewards.size());
        if (n == 0 && e > 1300) c.text("꽝!", cx, H * .3f, 54, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        int perRow = n <= 4 ? n : (n + 1) / 2;
        float cardH = n <= 4 ? 210 : 170, cardW = cardH * 454 / 512f, gap = 22;
        for (int i = 0; i < n; i++) {
            long st = 1250 + i * 230L;
            if (e < st) continue;
            float t = Anim.outBack(Anim.t(cur.start() + e, cur.start() + st, 420));
            int row = i / Math.max(1, perRow), col = i % Math.max(1, perRow);
            int inRow = Math.min(perRow, n - row * perRow);
            float tx = cx + (col - (inRow - 1) / 2f) * (cardW + gap);
            float ty = (n <= 4 ? H * .3f : H * .2f + row * (cardH + 18));
            float x = Anim.lerp(cx, tx, t), y = Anim.lerp(cy - 40, ty, t);
            JsonObject r = rewards.get(i);
            boolean rare = J.bool(r, "rare");
            if (rare) sound(host, 4 << i, "minecraft:entity.experience_orb.pickup", 1.6f);
            float s = .4f + .6f * t;
            c.push();
            c.translate(x, y);
            c.scale(s, s);
            String card = rare ? "reward/reward_card_rare" : "reward/reward_card_normal";
            float w = rare ? cardH * 512 / 466f : cardW;
            c.image(card, -w / 2, -cardH / 2, w, cardH, tint);
            c.imageFit(icon(J.str(r, "id"), J.str(r, "label")), 0, -cardH * .08f, cardH * .38f, tint);
            c.textFit(J.str(r, "label"), 0, cardH * (rare ? .29f : .3f), 19, cardW * .72f, Anim.alpha(Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            c.pop();
        }
        if (e > 1250 + n * 230L + 300) {
            float ft = Anim.outBack(Anim.t(e, 1250 + n * 230L + 300, 400));
            c.imageFit("reward/frog_waving", cx - ch * 1.05f, cy + 10 + (1 - ft) * 80, 190, Anim.white(a * ft));
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

    // ───────── 피버 시작 ─────────
    private void fever(Canvas c, long e, float a, Host host) {
        String id = cur.id();
        String k = id.equals("fever_triple") ? "triple" : id.equals("fever_box") ? "chest" : "diamond";
        String title = switch (k) { case "triple" -> "3배 피버 터짐!!"; case "chest" -> "상자 피버!"; default -> "다이아 증감 피버!"; };
        String sub = switch (k) {
            case "triple" -> "룰렛 결과 1+2";
            case "chest" -> "상자 내용물 1+1";
            default -> "룰렛이 빨라지고 1+1";
        };
        long sec = J.num(cur.d(), "seconds");
        if (sec > 0) sub = Fmt.time(sec) + " 동안 " + sub;
        int accent = switch (k) { case "triple" -> 0xFFB04FD0; case "chest" -> 0xFFD9951A; default -> 0xFFE0564C; };
        sound(host, 1, "minecraft:entity.firework_rocket.blast", 1f);
        if (e > 300) sound(host, 2, "minecraft:entity.player.levelup", 1.2f);
        float W = c.width(), H = c.height();
        float slide = (1 - Anim.outBack(Anim.t(e, 0, 450))) * -W * .6f;
        float w = Math.min(680, W - 120);
        int[] fs = c.size("fever/frame_" + k);
        float h = fs == null ? w * .52f : w * fs[1] / fs[0];
        float x = (W - w) / 2 + slide, y = H * .4f - h / 2;
        int tint = Anim.white(a);
        c.fill(x + w * .02f, y + h * .03f, w * .96f, h * .94f, 26, Anim.alpha(0xE6FFF6E6, a));
        // 속도선이 흘러간다
        for (int i = 0; i < 3; i++) {
            float p = ((e / 600f) + i / 3f) % 1f;
            float lx = x + w * (1.1f - p * 1.4f);
            c.imageW("fever/speed_lines", lx - w * .35f, y + h * (.18f + i * .22f), w * .5f, Anim.white(a * .35f * (float) Math.sin(p * Math.PI)));
        }
        c.image("fever/frame_" + k, x, y, w, h, tint);
        String frog = switch (k) { case "triple" -> "fever/frog_megaphones"; case "chest" -> "fever/frog_boxes"; default -> "fever/frog_drummer"; };
        float bounce = Math.abs((float) Math.sin(e / 130.0)) * 10;
        c.imageFit(frog, x + w * .5f, y + h * .58f - bounce, h * .62f, tint);
        String deco = switch (k) { case "triple" -> "fever/lightning"; case "chest" -> "fever/gift"; default -> "fever/flame"; };
        c.imageAt(deco, x + w * .14f, y + h * .6f, h * .38f, 1, Anim.shake(e, 8, 3), tint);
        c.imageAt(deco, x + w * .86f, y + h * .6f, h * .38f, 1, -Anim.shake(e, 8, 3), tint);
        float bw = w * 1.08f;
        int[] bs = c.size("fever/banner_" + k);
        float bh = bs == null ? bw * .18f : bw * bs[1] / bs[0];
        float bsScale = .6f + .4f * Anim.outBack(Anim.t(e, 150, 400));
        c.push();
        c.translate(x + w / 2, y + 4);
        c.scale(bsScale, bsScale);
        c.image("fever/banner_" + k, -bw / 2, -bh / 2, bw, bh, tint);
        c.textFit(title, 0, -bh * .02f, 38, bw * .66f, Anim.alpha(accent, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.pop();
        c.textFit(sub, x + w / 2, y + h * .92f, 26, w * .9f, Anim.alpha(Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
    }

    // ───────── 초대장 ─────────
    private void invite(Canvas c, long e, float a, Host host) {
        JsonObject d = cur.d();
        float W = c.width(), H = c.height();
        int tint = Anim.white(a);
        c.fill(0, 0, W, H, 0, Anim.alpha(0x99000000, a));
        float cx = W / 2, cy = H * .52f;
        float ew = Math.min(620, W - 200);
        if (e < 1150) {
            // 봉투가 떨어지고 봉인이 흔들린다
            float drop = Anim.outBounce(Anim.t(e, 0, 650));
            float y = Anim.lerp(-H * .4f, cy, drop);
            float eh = ew * 545 / 1024f;
            if (e > 300) sound(host, 1, "minecraft:item.book.page_turn", .8f);
            c.image("invite/envelope_closed", cx - ew / 2, y - eh / 2, ew, eh, tint);
            float sh = e > 650 ? Anim.shake(e, 6, 12) : 0;
            c.imageAt("invite/wax_seal", cx + sh, y + eh * .05f, eh * .42f, 1, sh * 2, tint);
            return;
        }
        // 봉인이 깨져 날아간다
        if (e < 1700) {
            float t = Anim.t(e, 1150, 550);
            sound(host, 2, "minecraft:block.glass.break", 1.4f);
            float eh = ew * 545 / 1024f;
            c.imageAt("invite/seal_fragment_a", cx - 60 * t * 3, cy - 20 + 200 * t * t, eh * .42f, 1, -60 * t, Anim.white(a * (1 - t)));
            c.imageAt("invite/seal_fragment_b", cx + 60 * t * 3, cy - 10 + 220 * t * t, eh * .42f, 1, 70 * t, Anim.white(a * (1 - t)));
        }
        // 열린 봉투에서 초대장이 올라온다
        float cardH = Math.min(620, H * .66f), cardW = cardH * 707 / 1024f;
        float rise = Anim.outCubic(Anim.t(e, 1250, 800));
        float toCenter = Anim.inOut(Anim.t(e, 2150, 550));
        float envA = 1 - toCenter;
        float bh = ew * 797 / 1024f, fh = ew * 514 / 1024f;
        float envTop = cy - bh * .2f;
        if (envA > 0) c.image("invite/envelope_back", cx - ew / 2, envTop, ew, bh, Anim.white(a * envA));
        float s0 = .55f;
        float cardCy = Anim.lerp(envTop + bh * .55f - rise * cardH * .48f, H * .5f, toCenter);
        float cs = Anim.lerp(s0, 1, toCenter);
        c.push();
        c.translate(cx - (e > 2700 ? 120 : 120 * toCenter), cardCy);
        c.scale(cs, cs);
        c.image("invite/invitation_card", -cardW / 2, -cardH / 2, cardW, cardH, tint);
        float ta = a * Anim.t(e, 2400, 300);
        c.textFit(J.str(d, "title", "깨굴이의 위험한 초대"), 0, -cardH / 2 + cardH * .125f, 24, cardW * .6f, Anim.alpha(0xFF6B4A9C, ta), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.textFit("To. " + J.str(d, "to"), 0, -cardH / 2 + cardH * .38f, 32, cardW * .78f, Anim.alpha(Theme.INK, ta), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        String line2 = d.has("from") ? "From. " + J.str(d, "from") : "장소 : " + J.str(d, "place", "비밀");
        c.textFit(line2, 0, -cardH / 2 + cardH * .655f, 28, cardW * .78f, Anim.alpha(Theme.INK, ta), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        // 거부 불가 도장 쾅
        if (e > 3000) {
            float st = Anim.t(e, 3000, 260);
            sound(host, 4, "minecraft:entity.iron_golem.attack", .7f);
            float ss = 2.2f - 1.2f * Anim.outCubic(st);
            float sy = -cardH / 2 + cardH * .89f, sz = cardW * .42f;
            c.imageAt("invite/stamp_red", 0, sy, sz, ss, -12, Anim.white(a * st));
            c.push();
            c.translate(0, sy);
            c.rotate(-12);
            c.scale(ss, ss);
            c.text("거부", 0, -sz * .14f, sz * .22f, Anim.alpha(0xFFD8423B, a * st), Canvas.CENTER, Canvas.BOLD);
            c.text("불가", 0, sz * .14f, sz * .22f, Anim.alpha(0xFFD8423B, a * st), Canvas.CENTER, Canvas.BOLD);
            c.pop();
        }
        c.pop();
        if (envA > 0) c.image("invite/envelope_front_overlay", cx - ew / 2, envTop + bh - fh, ew, fh, Anim.white(a * envA));
        if (e > 2500) {
            float bt = Anim.outBack(Anim.t(e, 2500, 450));
            c.imageFit("invite/frog_butler", cx + cardW * .5f + 60, H * .58f + (1 - bt) * 60, 330, Anim.white(a * Anim.clamp(bt)));
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
