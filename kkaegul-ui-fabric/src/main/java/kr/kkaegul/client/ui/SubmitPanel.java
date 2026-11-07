package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 다이아 제출소 창 (GPT 시안 layout.json, 패널 1000x680 좌표).
 * 일반 제출: 고른 개수만큼 그대로 남은 다이아에서 깎임 → submit.normal amount=N
 * 랜덤 제출: 운에 따라 그대로 / 2배 인정 / 일부 기부(덜 깎임) → submit.random amount=N
 */
public final class SubmitPanel implements Panel {
    private final Host host;
    private JsonObject data;
    private long amount = -1;           // -1 = 아직 안 정함(최대값으로)
    private String typed;               // 입력칸에 직접 치는 중이면 문자열
    private final long opened;
    private long waitingSince;
    private String fxId;
    private JsonObject fx;
    private long fxStart;
    private String pressed;
    private long pressedAt;
    private record R(String key, float x, float y, float w, float h) {}
    private final List<R> hits = new ArrayList<>();
    private float k = 1, ox, oy;
    private static final long DICE_MS = 1100, RESULT_MS = 2600;

    public SubmitPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
    }

    @Override public boolean textInput() { return true; }

    @Override public String screen() { return "submit"; }

    private long max() { return Math.max(0, J.num(data, "max")); }

    private long amount() {
        long m = max();
        if (typed != null) {
            try { return Math.min(m, Math.max(0, Long.parseLong(typed.isEmpty() ? "0" : typed))); } catch (NumberFormatException e) { return 0; }
        }
        return amount < 0 ? m : Math.min(amount, m);
    }

    @Override public void update(JsonObject d, long now) {
        if (d == null) return;
        data = J.merge(data, d);
        if (amount > max()) amount = max();
        waitingSince = 0;   // 갱신이 오면 대기 끝 (성공은 fx 가 먼저 옴, 거절돼도 바로 풀리게)
    }

    @Override public boolean fx(String id, JsonObject d, long now) {
        if (!id.equals("submit_normal") && !id.equals("random_submit")) return false;
        fxId = id;
        fx = d;
        fxStart = now;
        waitingSince = 0;
        typed = null;
        amount = -1;
        host.sound(id.equals("random_submit") ? "minecraft:entity.item.pickup" : "minecraft:entity.experience_orb.pickup", 1.2f, .8f);
        return true;
    }

    private long fxLife() { return fxId == null ? 0 : fxId.equals("random_submit") ? DICE_MS + RESULT_MS : RESULT_MS; }

    private boolean busy(long now) {
        if (waitingSince > 0 && now - waitingSince > 5000) waitingSince = 0;
        return waitingSince > 0 || (fx != null && now - fxStart < fxLife());
    }

    @Override public void render(Canvas c, long now, float mx, float my) {
        hits.clear();
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x77000000);
        float pop = .93f + .07f * Anim.outBack(Anim.t(now, opened, 280));
        k = Math.min(W * .72f / 1000f, H * .8f / 680f) * pop;
        ox = W / 2 - 500 * k;
        oy = H / 2 - 340 * k;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        mx = (mx - ox) / k;
        my = (my - oy) / k;

        c.image("submit/panel_base", 0, 0, 1000, 680, 0xFFFFFFFF);
        c.textFit("다이아 제출소", 499, 125, 38, 340, Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        boolean hc = Anim.in(mx, my, 899, 113, 67, 66);
        if (hc) c.fill(899, 113, 67, 66, 18, 0x22FFFFFF);
        hits.add(new R("close", 899, 113, 67, 66));

        // 정보칸: 남은 다이아 / 낼 수 있는 다이아
        long debt = J.num(data, "debt"), held = J.num(data, "held"), inv = J.num(data, "inventory");
        c.image("submit/info_remaining", 60, 215, 420, 110, 0xFFFFFFFF);
        c.text("남은 다이아", 190, 247, 21, 0xFF4E7FA8, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        c.textFit(Fmt.num(debt) + "개", 190, 290, 38, 270, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        c.image("submit/info_owned", 520, 215, 420, 110, 0xFFFFFFFF);
        c.text("낼 수 있는 다이아", 625, 247, 21, 0xFF4F7F45, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        c.textFit(Fmt.num(held + inv) + "개", 625, 290, 38, 180, Theme.INK, Canvas.LEFT, Canvas.BOLD | Canvas.LIGHT);
        c.textFit("§7보유 " + Fmt.num(held), 920, 278, 17, 120, Theme.MUTED, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);
        c.textFit("§7인벤 " + Fmt.num(inv), 920, 302, 17, 120, Theme.MUTED, Canvas.RIGHT, Canvas.BOLD | Canvas.LIGHT);

        // 개수 고르기
        long amt = amount(), max = max();
        boolean down = pressed != null && now - pressedAt < 150;
        c.image(down && "minus".equals(pressed) ? "submit/minus_pressed" : "submit/minus_default", 80, 380, 90, 90, amt > 0 ? 0xFFFFFFFF : 0x88FFFFFF);
        hits.add(new R("minus", 80, 380, 90, 90));
        c.image("submit/quantity_input", 195, 380, 360, 90, 0xFFFFFFFF);
        String shown = typed != null ? (typed.isEmpty() ? "0" : typed) : Fmt.num(amt);
        c.textFit(shown, 375, 425, 40, 300, max == 0 ? Theme.MUTED : Theme.INK, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        if (typed != null && (now / 530) % 2 == 0) {
            float tw = c.textWidth(shown, 40, Canvas.BOLD);
            c.fill(375 + tw / 2 + 4, 405, 3, 40, 1.5f, 0xFF7CB342);
        }
        c.text("최대 " + Fmt.num(max), 375, 492, 17, Theme.MUTED, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        hits.add(new R("input", 195, 380, 360, 90));
        c.image(down && "plus".equals(pressed) ? "submit/plus_pressed" : "submit/plus_default", 580, 380, 90, 90, amt < max ? 0xFFFFFFFF : 0x88FFFFFF);
        hits.add(new R("plus", 580, 380, 90, 90));
        c.image(down && "all".equals(pressed) ? "submit/all_pressed" : "submit/all_default", 710, 390, 160, 70, 0xFFFFFFFF);
        c.text("전부", 790, 425, 26, 0xFF3F7F31, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        hits.add(new R("all", 710, 390, 160, 70));

        // 제출 버튼
        boolean can = amt > 0 && !busy(now);
        boolean hn = can && Anim.in(mx, my, 70, 535, 400, 110), hr = can && Anim.in(mx, my, 530, 535, 400, 110);
        c.image(!can ? "submit/submit_normal_disabled" : hn ? "submit/submit_normal_hover" : "submit/submit_normal_default", 70, 535, 400, 110, 0xFFFFFFFF);
        c.text("일반 제출", 300, 574, 32, can ? 0xFF2F6E9E : 0xFF9AA7B0, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.textFit("남은 다이아 -" + Fmt.num(amt), 300, 612, 18, 250, can ? 0xFF4E7FA8 : 0xFF9AA7B0, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        hits.add(new R("normal", 70, 535, 400, 110));
        c.image(!can ? "submit/submit_random_disabled" : hr ? "submit/submit_random_hover" : "submit/submit_random_default", 530, 535, 400, 110, 0xFFFFFFFF);
        c.text("랜덤 제출", 760, 574, 32, can ? 0xFF6B44A0 : 0xFFA9A0B4, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.textFit("2배 인정 or 일부 기부", 760, 612, 18, 250, can ? 0xFF8A66B8 : 0xFFA9A0B4, Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        hits.add(new R("random", 530, 535, 400, 110));

        if (fx != null && now - fxStart < fxLife()) drawFx(c, now - fxStart);
        c.pop();

        // 랜덤 제출 설명 툴팁
        if (Anim.in(mx, my, 530, 535, 400, 110) && (fx == null || now - fxStart >= fxLife())) {
            StringBuilder t = new StringBuilder("&d랜덤 제출\n&f고른 개수를 내고, 결과는 운빨!");
            JsonObject r = J.objOrEmpty(data, "random");
            for (var e : r.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject o = e.getValue().getAsJsonObject();
                double f = J.dbl(o, "factor", 1);
                String what = f > 1 ? "&e" + trim(f) + "배로 인정" : f < 1 ? "&d" + Math.round((1 - f) * 100) + "%는 기부" : "&a그대로 인정";
                t.append("\n&7").append(trim(J.dbl(o, "chance", 0))).append("% → ").append(what);
            }
            Theme.tooltip(c, t.toString(), mx * k + ox, my * k + oy);
        }
    }

    private static String trim(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d); }

    /** 결과 연출: 일반 = 상자 받는 깨굴이 + 배너 / 랜덤 = 주사위 굴림 → 결과 배너 */
    private void drawFx(Canvas c, long e) {
        c.fill(28, 186, 944, 474, 30, Anim.alpha(0xFAFDF6EA, Anim.fade(e, 0, fxLife(), 120, 400)));
        float a = Anim.fade(e, 0, fxLife(), 120, 400);
        if (fxId.equals("random_submit") && e < DICE_MS) {
            String[] dice = {"submit/die_01", "submit/die_02", "submit/die_03"};
            float t = e / (float) DICE_MS;
            float hop = Math.abs((float) Math.sin(t * Math.PI * 3)) * 70 * (1 - t);
            c.imageAt(dice[(int) (e / 90) % 3], 500, 420 - hop, 190, 1, e * .6f, Anim.white(a));
            c.imageAt("submit/frog_toss_dice", 230, 470, 260, 1, 0, Anim.white(a));
            if ((e / 90) % 3 == 0 && e % 90 < 20) host.sound("minecraft:block.wood.hit", 1.5f, .4f);
            return;
        }
        long r = fxId.equals("random_submit") ? e - DICE_MS : e;
        float t = Anim.outBack(Anim.t(r, 0, 380));
        long used = J.num(fx, "used"), cred = J.num(fx, "credited"), donated = J.num(fx, "donated");
        String banner, title;
        int color;
        if (fxId.equals("submit_normal")) { banner = "submit/banner_normal"; title = Fmt.num(cred) + "개 제출 완료!"; color = Theme.INK; }
        else {
            double f = J.dbl(fx, "factor", 1);
            if (f > 1) { banner = "submit/banner_jackpot"; title = "운이 좋아요! " + trim(f) + "배 인정"; color = 0xFFB4720F; }
            else if (f < 1) { banner = "submit/banner_miss"; title = "다이아 기부 감사합니다"; color = 0xFF6B6B6B; }
            else { banner = "submit/banner_normal"; title = "그대로 제출 완료!"; color = Theme.INK; }
        }
        c.push();
        c.translate(500, 300);
        c.scale(t, t);
        c.image(banner, -350, -80, 700, 160, Anim.white(a));
        c.textFit(title, 0, -4, 44, 480, Anim.alpha(color, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        c.pop();
        String sub = fxId.equals("submit_normal") ? "남은 다이아 " + Fmt.num(J.num(fx, "debt")) + "개"
                : Fmt.num(used) + "개 냄 → " + Fmt.num(cred) + "개 인정" + (donated > 0 ? " · " + Fmt.num(donated) + "개 기부" : "") + " · 남은 " + Fmt.num(J.num(fx, "debt"));
        c.textFit(sub, 500, 410, 26, 760, Anim.alpha(Theme.INK, a), Canvas.CENTER, Canvas.BOLD | Canvas.LIGHT);
        String frog = fxId.equals("submit_normal") ? "submit/frog_receive_diamonds" : "submit/frog_toss_dice";
        c.imageAt(frog, 500, 540 - 10 * (float) Math.abs(Math.sin(r / 200.0)), 200, 1, 0, Anim.white(a));
        if (donated > 0) {
            float ft = Anim.t(r, 300, 1500);
            c.imageAt("submit/donation_diamond", 700 + 60 * ft, 520 - 220 * ft, 90, 1, 0, Anim.white(a * (1 - ft * .7f)));
        }
    }

    private void press(String key, long now) { pressed = key; pressedAt = now; }

    @Override public boolean click(float mx, float my, long now) {
        mx = (mx - ox) / k;
        my = (my - oy) / k;
        if (fx != null && now - fxStart < fxLife()) { fx = null; return true; } // 연출 클릭하면 넘김
        for (R r : hits) {
            if (!Anim.in(mx, my, r.x(), r.y(), r.w(), r.h())) continue;
            long amt = amount();
            switch (r.key()) {
                case "close" -> host.closePanel(this);
                case "minus" -> { amount = Math.max(0, amt - step()); typed = null; press("minus", now); host.sound("minecraft:ui.button.click", 1.4f, .4f); }
                case "plus" -> { amount = Math.min(max(), amt + step()); typed = null; press("plus", now); host.sound("minecraft:ui.button.click", 1.5f, .4f); }
                case "all" -> { amount = max(); typed = null; press("all", now); host.sound("minecraft:ui.button.click", 1.2f, .4f); }
                case "input" -> typed = "";
                case "normal", "random" -> {
                    if (amt <= 0 || busy(now)) { host.sound("minecraft:block.note_block.bass", .6f, .6f); return true; }
                    waitingSince = now;
                    host.send("submit." + r.key(), "amount", String.valueOf(amt));
                    host.sound("minecraft:block.stone_button.click_on", 1, 1);
                }
                default -> { }
            }
            return true;
        }
        if (typed != null) { amount = amount(); typed = null; }
        return false;
    }

    private int stepMods;
    private long step() { return (stepMods & 1) != 0 ? 10 : (stepMods & 2) != 0 ? 100 : 1; }

    /** 쉬프트 = 10개씩, 컨트롤 = 100개씩 (게임 쪽에서 눌린 키를 알려줌) */
    public void modifiers(int mods) { stepMods = mods; }

    @Override public boolean scroll(float mx, float my, double v) {
        amount = Math.max(0, Math.min(max(), amount() + (v > 0 ? step() : -step())));
        typed = null;
        return true;
    }

    @Override public boolean typed(char ch, long now) {
        if (ch < '0' || ch > '9') return true;
        if (typed == null) typed = "";
        if (typed.length() < 9) typed = (typed.equals("0") ? "" : typed) + ch;
        return true;
    }

    @Override public boolean key(int key, int mods, long now) {
        stepMods = mods;
        if (key == 259) { // BACKSPACE
            if (typed == null) typed = String.valueOf(amount());
            if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1);
            return true;
        }
        if (key == 257 || key == 335) { amount = amount(); typed = null; return true; } // ENTER
        return false;
    }
}
