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
        played = 0;
    }

    /** 상자 연출은 빨리 감기: 기본 1.6배, 뒤에 상자가 더 밀려 있으면 3배 (연출 안의 진행 시간을 따로 셈) */
    private double played;
    private long lastFrame;
    private double boxSpeed() {
        for (Fx f : queue) if (f.id().equals("box_open")) return 3.0;
        return 1.6;
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
        long dt = lastFrame == 0 ? 0 : Math.min(200, now - lastFrame);
        lastFrame = now;
        if (cur == null) return;
        long e;
        if (cur.id().equals("box_open")) { played += dt * boxSpeed(); e = (long) played; }
        else e = now - cur.start();
        if (e > cur.life()) { next(now); if (cur == null) return; e = 0; }
        float a = Anim.fade(cur.start() + e, cur.start(), cur.life(), 150, 450);
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
            // 그림대로 좁은 쪽이 상자 입구, 넓은 쪽이 위 (예전엔 뒤집어 그려서 넓은 쪽 평평한 끝이 상자 옆으로 삐져나왔음)
            float bh = ch * 1.2f * bt;
            c.imageAt("chest/" + kind + "_beam", cx, cy - ch * .1f - bh / 2, bh, 1, 0, Anim.white(a * pulse * .85f));
        }
        c.imageAt("chest/" + kind + "_" + state, cx + shake, cy, ch, sc, shake * .4f, tint);
        if (J.bool(d, "fever")) {
            c.imageAt("reward/chest_fever_badge", cx + ch * .62f, cy - ch * .4f, 84, 1, 0, tint);
            c.text("1+1", cx + ch * .62f, cy - ch * .4f, 28, Anim.alpha(0xFFE0564C, a), Canvas.CENTER, Canvas.BOLD);
        }
        // 상자 이름표 (reward/event_banner: 왼쪽 네모 칸에 상자 아이콘, 오른쪽 판에 이름)
        String label = J.str(d, "label", "상자");
        float lh = 64, lk = lh / 166f, ly = cy + ch * .6f;
        float lw = Math.min(680, Math.max(360, c.textWidth(label, 30, Canvas.BOLD | Canvas.LIGHT) + 200));
        float lx = cx - lw / 2;
        c.imageNine("reward/event_banner", lx, ly - lh / 2, lw, lh, .22f, .45f, 1024 * .22f * lk, lh * .45f, tint);
        c.imageFit("chest/" + kind + "_closed", lx + 1024 * .14f * lk, ly, lh * .62f, tint);
        float tl = lx + 1024 * .21f * lk, tr = lx + lw - 1024 * .08f * lk;
        c.textFit(label, (tl + tr) / 2, ly, 30, tr - tl - 16, Anim.alpha(Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (n == 0 && e > 1300) {
            // 꽝: 손 흔드는 깨굴이 + 글자
            float wt = Anim.outBack(Anim.t(e, 1300, 400)), wave = (float) Math.sin(e / 160.0) * 6;
            c.imageAt("reward/frog_waving", cx - 160, H * .34f, 220 * wt, 1, wave, tint);
            c.text("꽝!", cx, H * .34f, 60, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        }

        // 보상 카드: 1장이면 크게, 여러 장이면 줄지어
        float size = n <= 1 ? 290 : n <= 3 ? 250 : n <= 5 ? 220 : 180, gap = 26;
        int perRow = n <= 5 ? n : (n + 1) / 2;
        // 카드 간격은 실제 카드 그림 폭으로 (별 카드는 별 끝이 옆으로 나와서 옆 카드와 겹쳤음), 한 줄이 화면을 넘으면 줄임
        float wf = .9f;
        for (int i = 0; i < n; i++) wf = Math.max(wf, cardW(c, kind, J.bool(rewards.get(i), "rare"), 1));
        if (perRow > 0 && perRow * (size * wf + gap) - gap > W - 120) size = (W - 120 - (perRow - 1) * gap) / (perRow * wf);
        float step = size * wf + gap;
        for (int i = 0; i < n; i++) {
            long st = 1250 + i * 230L;
            if (e < st) continue;
            float t = Anim.outBack(Anim.t(e, st, 420));
            int row = i / Math.max(1, perRow), col = i % Math.max(1, perRow);
            int inRow = Math.min(perRow, n - row * perRow);
            float tx = cx + (col - (inRow - 1) / 2f) * step;
            float ty = n <= 5 ? H * .3f : H * .17f + row * (size * CARD_H * 1.02f + 14);
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

    /** 카드 그림 폭 (rewardCard 와 같은 계산) */
    private static float cardW(Canvas c, String kind, boolean rare, float s) {
        boolean star = rare || kind.equals("advanced");
        int[] sz = c.size(star ? "reward/reward_card_rare" : "reward/reward_card_normal");
        float h = s * CARD_H * (star ? 1.02f : 1);
        return sz == null ? h * .89f : h * sz[0] / sz[1];
    }

    /** 카드 높이 = 카드 크기(s) × 이 값 (위아래 줄 간격 계산에도 씀) */
    static final float CARD_H = 1.1f;

    /**
     * 보상 카드 그림 (reward/reward_card_*). 일반 = 크림 카드, 고급 상자·희귀 보상 = 보라 별 카드, 행운·희귀 = 금메달.
     * 그림 안 동그라미 칸에 아이콘, 아래 이름표 칸에 이름 (칸 위치는 그림 비율로 잰 값)
     */
    private static void rewardCard(Canvas c, String kind, boolean rare, String id, String label, float s, float a, long e) {
        boolean star = rare || kind.equals("advanced");
        String img = star ? "reward/reward_card_rare" : "reward/reward_card_normal";
        int[] sz = c.size(img);
        float h = s * CARD_H * (star ? 1.02f : 1), w = sz == null ? h * .89f : h * sz[0] / sz[1];
        float x = -w / 2, y = -h / 2;
        c.image(img, x, y, w, h, Anim.white(a));
        // {동그라미 가운데 y, 동그라미 지름(폭 대비), 이름표 가운데 y, 이름표 폭(폭 대비)}
        float[] m = star ? new float[]{.515f, .46f, .82f, .46f} : new float[]{.455f, .62f, .805f, .66f};
        c.imageFit(icon(id, label), 0, y + h * m[0], w * m[1] * .66f, Anim.white(a));
        c.textFit(label, 0, y + h * m[2], Math.max(19, s * .085f), w * m[3], Anim.alpha(Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (rare || kind.equals("lucky")) {
            float mx = star ? w * .3f : w * .4f, my = y + h * (star ? .24f : .12f);
            c.imageAt("menu/medal_gold", mx, my, s * .28f, 1, 8, Anim.white(a));
        }
    }

    /** 보상 이름으로 아이콘 고르기 */
    static String icon(String id, String label) {
        String s = (id + " " + label).toLowerCase();
        if (s.contains("다이아") || s.contains("diamond")) return "hud/diamond";
        if (s.contains("코인") || s.contains("coin")) return "reward/event_frog_coin";
        if (s.contains("얼음") || s.contains("빙결") || s.contains("freeze")) return "reward/event_freeze";
        if (s.contains("피로") || s.contains("fatigue")) return "reward/event_mining_fatigue";
        if (s.contains("디버프") || s.contains("저주") || s.contains("debuff")) return "reward/event_debuff";
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
        String title = switch (k) { case "triple" -> "3배 피버 터짐!!"; case "chest" -> "상자 피버!"; default -> "2배 피버!"; };
        String sub = switch (k) { case "triple" -> "룰렛 범위 3배"; case "chest" -> "상자 내용물 1+1"; default -> "룰렛이 빨라지고 범위 2배"; };
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
        // 가로 배치 (w 비율): 리본 가운데 BXC, 깨굴이는 리본 왼쪽 꼬리 끝에 살짝만 걸치고, 메달은 리본 오른쪽 꼬리 밖에
        // (예전엔 깨굴이·메달이 리본 양쪽 꼬리 장식을 덮었음)
        final float BXC = .1f;
        // 위 아이콘 3개 (가운데 크게)
        String deco = switch (k) { case "triple" -> "fever/lightning"; case "chest" -> "fever/gift"; default -> "fever/flame"; };
        for (int i = -1; i <= 1; i++) {
            float bt = Anim.outBack(Anim.t(e, 200 + (i + 1) * 90L, 350));
            float s = (i == 0 ? h * .3f : h * .2f) * bt;
            c.imageAt(deco, i * w * .13f + w * BXC, y + h * (i == 0 ? .2f : .26f) + Anim.shake(e, 2, 3 + i), s, 1, i * 10, tint);
        }
        // 리본 (살짝 기울게)
        float bw = w * .78f;
        int[] bs = c.size("fever/banner_" + k);
        float bh = bs == null ? bw * .18f : bw * bs[1] / bs[0];
        float slide = (1 - Anim.outCubic(Anim.t(e, 100, 400))) * w * .3f;
        c.push();
        c.translate(w * BXC + slide, h * .05f);
        c.rotate(-5);
        // 속도선: 리본이 오른쪽에서 날아드는 동안 뒤(오른쪽)에 남았다가 사라짐
        float sl = 1 - Anim.t(e, 350, 450);
        if (sl > 0) {
            float lw = bw * .45f, lh = lw * 464 / 1024f;
            c.image("fever/speed_lines", bw * .42f + slide * .3f, -lh / 2, lw, lh, Anim.white(a * sl * .9f));
        }
        c.image("fever/banner_" + k, -bw / 2, -bh / 2, bw, bh, tint);
        float pc = switch (k) { case "triple" -> .054f; case "chest" -> -.033f; default -> -.043f; };   // 리본 안쪽 판 세로 중심 (그림 측정값)
        float ph = switch (k) { case "triple" -> .483f; case "chest" -> .574f; default -> .654f; };
        c.textFit(title, -bw * .02f, bh * pc, bh * ph * .6f, bw * .74f, Anim.alpha(accent, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.pop();
        // 오른쪽 메달 (3배 / 상자)
        if (!k.equals("diamond")) {
            float mt = Anim.outBack(Anim.t(e, 500, 400)), ms = h * .3f;
            float mx = w * BXC + bw / 2 + ms * .5f, my = h * .05f - (mx - w * BXC) * (float) Math.sin(Math.toRadians(5));
            c.imageAt(k.equals("triple") ? "fever/badge_triple" : "fever/badge_chest", mx, my, ms * mt, 1, 0, tint);
            c.text(k.equals("triple") ? "x3" : "1+1", mx, my, h * .1f * mt, Anim.alpha(accent, a), Canvas.CENTER, Canvas.BOLD);
        }
        // 왼쪽 깨굴이
        String frog = switch (k) { case "triple" -> "fever/frog_megaphones"; case "chest" -> "fever/frog_boxes"; default -> "fever/frog_drummer"; };
        float bounce = Math.abs((float) Math.sin(e / 130.0)) * 10;
        int[] fz = c.size(frog);
        float fh = h * .56f, fw = fz == null ? fh : fh * fz[0] / fz[1];
        c.imageAt(frog, w * BXC - bw / 2 + w * .04f - fw / 2, y + h * .64f - bounce, fh, 1, 0, tint);
        c.textFit(sub, w * BXC, y + h * .86f, h * .065f, w * .6f, Anim.alpha(Theme.WHITE, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
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
        if (e < 1300) {
            // 봉인이 깨진 직후: 뚜껑이 열린 봉투 (invite/envelope_open) → 카드가 올라오기 시작하면 앞·뒤 겹친 그림으로
            float op = 1 + .05f * (1 - Anim.outCubic(Anim.t(e, 1150, 150)));
            float oh = ew * 892 / 1024f;
            c.push(); c.translate(cx, bottom); c.scale(op, op);
            c.image("invite/envelope_open", -ew / 2, -oh, ew, oh, tint);
            c.pop();
        } else {
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
                // 봉인 그림 가운데(카드 높이 .082)에 맞춘 원
                c.fill(-cardW * .15f, -cardH / 2 + cardH * .082f - cardW * .15f, cardW * .3f, cardW * .3f, cardW * .15f, Anim.alpha(0x66B6F07A, a * glow));
            }
            c.image("invite/invitation_card", -cardW / 2, -cardH / 2, cardW, cardH, tint);
            float ta = a * Anim.t(e, 2100, 300);
            // 제목은 리본 위, 가운데 깨굴이 봉인(그림 .006~.157) 아래 보이는 띠(.157~.19)에 (예전엔 봉인 그림 위에 글자가 겹쳤음)
            c.textFit(J.str(d, "title", "깨굴이의 위험한 초대"), 0, -cardH / 2 + cardH * .174f, cardH * .03f, cardW * .58f, Anim.alpha(0xFF6B4A9C, ta), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            c.textFit("To. " + J.str(d, "to"), 0, -cardH / 2 + cardH * .39f, cardW * .075f, cardW * .78f, Anim.alpha(Theme.INK, ta), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            String line2 = d.has("from") ? "From. " + J.str(d, "from") : "장소 : " + J.str(d, "place", "비밀");
            c.textFit(line2, 0, -cardH / 2 + cardH * .655f, cardW * .065f, cardW * .78f, Anim.alpha(Theme.INK, ta), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
            c.pop();
            c.image("invite/envelope_front_overlay", cx - ew / 2, bottom - fh, ew, fh, tint);
            // 도장은 봉투 앞판 위에 찍음 (예전엔 카드와 같이 앞판 뒤에 그려져 아래쪽이 봉투 V 에 가려졌음)
            if (e > 2900) {
                c.push();
                c.translate(cx, cy);
                float st = Anim.t(e, 2900, 260);
                sound(host, 4, "minecraft:entity.iron_golem.attack", .7f);
                float ss = 2.2f - 1.2f * Anim.outCubic(st);
                float sy = -cardH / 2 + cardH * .89f, sz = cardW * .4f;
                c.imageAt("invite/stamp_red", 0, sy, sz, ss, -12, Anim.white(a * st));
                c.push(); c.translate(0, sy); c.rotate(-12); c.scale(ss, ss);
                c.text("거부", 0, -sz * .14f, sz * .22f, Anim.alpha(0xFFD8423B, a * st), Canvas.CENTER, Canvas.BOLD);
                c.text("불가", 0, sz * .14f, sz * .22f, Anim.alpha(0xFFD8423B, a * st), Canvas.CENTER, Canvas.BOLD);
                c.pop();
                c.pop();
            }
        }
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
        GachaPanel.artAt(c, "gacha/frog_jackpot", W / 2, H * .47f, 330 * t, 1, 0, Anim.white(a));
        float rw = 760, rh = rw * 247 / 1024f;
        c.image("gacha/jackpot_ribbon", W / 2 - rw / 2, H * .66f, rw, rh, Anim.white(a));
        c.textArc("졸업 축하해요!", W / 2, H * .66f + rh * Theme.RIBBON_CY, 44, Theme.ribbonR(rw), rw * .6f, Anim.alpha(0xFFE0564C, a), Canvas.BOLD | Canvas.LIGHT);
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
