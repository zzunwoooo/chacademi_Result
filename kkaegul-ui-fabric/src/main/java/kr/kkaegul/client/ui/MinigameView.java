package kr.kkaegul.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 미니게임 진행 중 화면 위 표시 (창을 열지 않음 → 그대로 움직이며 플레이).
 * 그림·배치: 받은 "운빨깨굴랜드 미니게임 HUD" 묶음 (textures/ui/minigame, layout.json 1920×1080 좌표 그대로).
 *  - screen "minigame": {game, title, seconds, participants:[{name,account,state:wait|success|out}], partner:{name,account},
 *                        progress, target, broken(빼빼로) / chests, left(보물찾기)}
 *  - screen "wasd": {keys:"[W, A, S, D]", seconds, index, title} → 키 입력 판 / 끝: update {done, success, index}
 *  - fx "minigame_result": {success, title, sub, rewards:[{label,item,premium}]} 개구리 + 리본 + 보상 카드
 *  - fx "treasure_found": {label, item, premium} 열린 상자 + 보상 카드
 */
public final class MinigameView {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final int INK = 0xFF4A2D16;
    private static final String P = "minigame/";

    private JsonObject game;
    private long gameAt;

    private List<Character> keys = List.of();
    private int keyIndex;
    private long wasdAt, wasdSec, wasdEnd;
    private boolean wasdOn, wasdDone, wasdOk;
    private String wasdTitle = "";

    private JsonObject result, found;
    private long resultAt, foundAt;
    private long bangs, bangAt;   // 러시안 룰렛 "탕!" (bangs 가 늘면 연출)

    public boolean handles(String screen) { return screen.equals("minigame") || screen.equals("wasd"); }

    public boolean handlesFx(String id) { return id.equals("minigame_result") || id.equals("treasure_found"); }

    /** 게임 표시 중이면 HUD 위쪽 게이지를 가린다 (제목 리본과 겹침) */
    /** WASD 판·결과 개구리·보물 발견처럼 화면 가운데 큰 연출 */
    public boolean busy(long now) {
        return wasdOn || (wasdEnd > 0 && now - wasdEnd < 1400) || (result != null && now - resultAt < 3800) || (found != null && now - foundAt < 2600);
    }

    public boolean active(long now) { return game != null || wasdOn || (wasdEnd > 0 && now - wasdEnd < 1400); }

    public void open(String screen, JsonObject d, long now) {
        if (screen.equals("wasd")) {
            List<Character> k = new ArrayList<>();
            for (char ch : J.str(d, "keys").toCharArray()) if (ch == 'W' || ch == 'A' || ch == 'S' || ch == 'D') k.add(ch);
            keys = k;
            keyIndex = (int) J.num(d, "index");
            wasdSec = Math.max(1, J.num(d, "seconds", 7));
            wasdTitle = J.str(d, "title");
            wasdAt = now;
            wasdOn = true;
            wasdDone = false;
            wasdEnd = 0;
        } else { game = d; gameAt = now; bangCheck(d, now); }
    }

    private void bangCheck(JsonObject d, long now) {
        long b = J.num(d, "bangs", bangs);
        if (b > bangs) bangAt = now;
        bangs = b;
    }

    public void update(String screen, JsonObject d, long now) {
        if (screen.equals("wasd")) {
            keyIndex = (int) J.num(d, "index", keyIndex);
            if (d.has("seconds") && !J.bool(d, "done")) { wasdSec = Math.max(1, J.num(d, "seconds", wasdSec)); wasdAt = now; }   // 키마다 제한시간 다시
            if (J.bool(d, "done")) { wasdDone = true; wasdOk = J.bool(d, "success"); wasdEnd = now; }
        } else if (game != null) { game = J.merge(game, d); bangCheck(d, now); }
    }

    public void close(String screen, long now) {
        if (screen.equals("wasd")) { if (wasdOn && wasdEnd == 0) wasdEnd = now; wasdOn = false; }
        else if (screen.equals("minigame") || screen.isEmpty()) game = null;
    }

    public void fx(String id, JsonObject d, long now) {
        if (id.equals("minigame_result")) { result = d; resultAt = now; }
        else { found = d; foundAt = now; }
    }

    // ───────── 그리기 (1920×1080 기준 좌표, 화면에 맞춰 줄임) ─────────
    public void render(Canvas c, long now) {
        boolean wasdShow = wasdOn || (wasdEnd > 0 && now - wasdEnd < 1400);
        boolean res = result != null && now - resultAt < 3800;
        boolean fnd = found != null && now - foundAt < 2600;
        if (game == null && !wasdShow && !res && !fnd) return;
        float W = c.width(), H = c.height();
        float k = Math.min(W / 1920f, H / 1080f);
        c.push();
        c.translate((W - 1920 * k) / 2, 0);
        c.scale(k, k);

        String title = game != null ? J.str(game, "title", name(J.str(game, "game"))) : !wasdTitle.isEmpty() ? wasdTitle : res ? J.str(result, "title") : "";
        if (!title.isEmpty() && !res) titleRibbon(c, title);
        // 남은 시간
        long left = -1, total = 1;
        if (wasdShow && !wasdDone) { total = wasdSec * 1000; left = Math.max(0, total - (now - wasdAt)); }
        else if (game != null && J.num(game, "seconds") > 0) { total = J.num(game, "seconds") * 1000; left = Math.max(0, total - (now - gameAt)); }
        // 타이머: 오른쪽 위는 미니맵과 겹쳐서 화면 아래 가운데로. 다른 판보다 나중에 그려서 가려지지 않게
        boolean showTimer = left >= 0 && !res;

        if (game != null && !res) {
            JsonObject partner = J.objOrEmpty(game, "partner");
            if (!J.str(partner, "name").isEmpty()) partner(c, partner);
            participants(c, game);
            String g = J.str(game, "game");
            if (g.equals("pepero")) pepero(c, game);
            if (game.has("chests")) chests(c, game);
            if (game.has("chambers")) cylinder(c, game, now);
        }
        if (wasdShow && !res) keyPanel(c, now);
        if (showTimer && wasdShow) timer(c, left, total, 890.5f, 236);   // 236~396: 리본(~220)과 화살표(416~) 사이
        else if (showTimer) {
            boolean pep = game != null && J.str(game, "game").equals("pepero");
            timer(c, left, total, 890.5f, pep ? 520 : H / k - 330);   // 빼빼로: 막대(690~) 바로 위
        }
        if (fnd && !res) foundCard(c, now);
        if (bangAt > 0 && now - bangAt < 1400 && !res) bang(c, now);
        if (res) resultView(c, now);
        c.pop();
    }

    /**
     * 러시안 룰렛 실린더 (rroulette/cylinder_frame 300×300, 구멍 6개 지름 64: 맨 위부터 시계 방향 HOLE 좌표, 맨 위 = 빨간 표시 칸).
     * chambers 6글자 E 빈 칸 · B 총알 · U 아직, cursor = 지금 칸 (반짝이는 고리)
     */
    private static final float[][] HOLE = {{148.9f, 72.5f}, {221.7f, 113.7f}, {221f, 193.8f}, {148.1f, 230.5f}, {75.6f, 193.6f}, {75.3f, 113.6f}};

    private void cylinder(Canvas c, JsonObject g, long now) {
        String ch = J.str(g, "chambers");
        float S = 260, x = 1600, y = 360, k = S / 300f;
        c.image("rroulette/cylinder_frame", x, y, S, S, 0xFFFFFFFF);
        int cur = (int) J.num(g, "cursor");
        for (int i = 0; i < 6 && i < ch.length(); i++) {
            float cx = x + HOLE[i][0] * k, cy = y + HOLE[i][1] * k, d = 64 * k;   // 구멍 지름 63~64px 그대로
            String id = switch (ch.charAt(i)) { case 'E' -> "chamber_empty"; case 'B' -> "chamber_bullet"; default -> "chamber_unknown"; };
            if (i == cur && ch.charAt(i) == 'U') c.fill(cx - d / 2 - 5, cy - d / 2 - 5, d + 10, d + 10, d / 2 + 5, Anim.alpha(0xFFFFE27A, .55f + .35f * (float) Math.sin(now / 150.0)));
            c.image("rroulette/" + id, cx - d / 2, cy - d / 2, d, d, 0xFFFFFFFF);
        }
        c.textFit(J.num(g, "round") + "라운드 · 판돈 " + J.num(g, "pot") + "다이아", x + S / 2, y + S + 24, 22, 320, 0xFFFFFFFF, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
    }

    /** "탕!" 연출 (rroulette/bang 800×400): 튀어나와 흔들리다 사라짐 */
    private void bang(Canvas c, long now) {
        long e = now - bangAt;
        float t = Anim.outBack(Anim.clamp(e / 260f)), a = e < 1000 ? 1 : 1 - (e - 1000) / 400f;
        c.imageAt("rroulette/bang", 960 + Anim.shake(now, 6, 10) * (e < 500 ? 1 : 0), 430, 380 * t, 1, -6 + 6 * t, Anim.white(Anim.clamp(a)));
    }

    private static String name(String id) {
        return switch (id) { case "pepero" -> "빼빼로 게임"; case "treasure" -> "보물찾기"; case "invite" -> "깨굴이의 위험한 초대"; default -> "미니게임"; };
    }

    /** A 제목 리본 (640,20) 640×200, 글자칸 (110,112) 420×55 */
    private void titleRibbon(Canvas c, String t) {
        c.image(P + "a_title", 640, 20, 640, 200, 0xFFFFFFFF);
        c.textFit(t, 960, 20 + 137, 40, 420, INK, Canvas.CENTER, TXT);   // 리본 판 안쪽 y 213~335px (1280×400) 가운데
    }

    /** B 타이머 (1710,45) 140×160. 링 가운데 (69.5, 94) 반지름 63.5 (재서 맞춤), 남은 비율로 초록→주황→빨강 */
    private void timer(Canvas c, long left, long total, float x, float y) {
        c.image(P + "b_timer_empty", x, y, 140, 160, 0xFFFFFFFF);
        float f = (float) left / Math.max(1, total);
        float cx = x + 69.5f, cy = y + 94, r = 63.5f;
        // 링 안쪽을 채워서 뒤 배경(맵)이 비치지 않게 → 숫자가 잘 보임
        c.fill(cx - r + 4, cy - r + 4, (r - 4) * 2, (r - 4) * 2, r - 4, 0xF2FFF8EE);
        // 색 링: 그림마다 링 크기가 달라서 바깥 반지름이 같아지게 맞춤 (px: 초록 r116 중심(140,159) / 주황·빨강 r135 중심(140,140), 280px 그림)
        String ring; float rr, ox, oy;
        if (f > .5f) { ring = "b_timer_green"; rr = 116; ox = 140; oy = 159; }
        else if (f > .2f) { ring = "b_timer_orange"; rr = 135; ox = 140; oy = 140; }
        else { ring = "b_timer_red"; rr = 135; ox = 140; oy = 140; }
        float S = r * 280 / rr;
        c.image(P + ring, cx - ox * S / 280, cy - oy * S / 280, S, S, 0xFFFFFFFF);
        c.image(P + "b_timer_knob", x, y, 140, 160, 0xFFFFFFFF);   // 시계 머리는 색 링 위에
        long s = (left + 999) / 1000;
        String t = s >= 60 ? s / 60 + ":" + String.format("%02d", s % 60) : String.valueOf(s);
        c.textFit(t, cx, cy, s >= 60 ? 30 : 40, 80, f > .2f ? INK : 0xFFE0564C, Canvas.CENTER, TXT);
    }

    /** D 파트너 (60,80) 160×160, 얼굴칸 (30,30) 100×100 */
    private void partner(Canvas c, JsonObject pt) {
        float x = 60, y = 80;
        if (!c.face(J.str(pt, "account"), x + 21.75f, y + 27.75f, 114)) c.imageFit("hud/frog_smile", x + 78.75f, y + 84.75f, 100, 0xFFFFFFFF);   // 틀 구멍 x 40~275, y 52~287px(320) 가운데, 구멍보다 살짝 크게 → 가장자리는 틀 아래
        c.image(P + "d_partner_frame", x, y, 160, 160, 0xFFFFFFFF);
        c.text("파트너", x + 176, y + 58, 22, 0xFFFFFFFF, Canvas.LEFT, Canvas.BOLD | Canvas.OUTLINE);
        c.textFit(J.str(pt, "name"), x + 176, y + 100, 32, 240, 0xFFFFFFFF, Canvas.LEFT, Canvas.BOLD | Canvas.OUTLINE);
    }

    /** F 참가자 줄 (60, 250+82i) 360×72, 얼굴칸 안쪽 (18,21) 28 (그림 36~93, 42~97px 잼), 글자칸 (85,14) 220×44, 상태 뱃지는 오른쪽 */
    private void participants(Canvas c, JsonObject g) {
        JsonArray arr = g.has("participants") && g.get("participants").isJsonArray() ? g.getAsJsonArray("participants") : null;
        if (arr == null || arr.isEmpty()) return;
        int n = Math.min(5, arr.size());
        for (int i = 0; i < n; i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            float x = 60, y = 250 + 82 * i;
            boolean last = i == 4 && arr.size() > 5;
            c.image(P + "f_participant_row", x, y, 360, 72, 0xFFFFFFFF);
            if (last) { c.textFit("외 " + (arr.size() - 4) + "명", x + 195, y + 36, 24, 220, INK, Canvas.CENTER, TXT); continue; }
            if (!c.face(J.str(o, "account"), x + 18.25f, y + 20.75f, 28)) c.imageFit("hud/frog_smile", x + 32.25f, y + 34.75f, 26, 0xFFFFFFFF);
            c.textFit(J.str(o, "name"), x + 85, y + 36, 24, 220, INK, Canvas.LEFT, TXT);
            String st = J.str(o, "state", "wait");
            String badge = switch (st) { case "success" -> "f_badge_success"; case "out" -> "f_badge_out"; default -> "f_badge_wait"; };
            c.image(P + badge, x + 312, y + 20, 32, 32, 0xFFFFFFFF);
        }
    }

    /** C 빼빼로 (710,690) 500×90 + E 게이지 (700,800) 520×44, 채움 (710,807) 500×30 */
    private void pepero(Canvas c, JsonObject g) {
        long tgt = Math.max(1, J.num(g, "target", 60)), pr = Math.min(tgt, J.num(g, "progress"));
        float f = (float) pr / tgt;
        String st = J.bool(g, "broken") ? "c_pepero_broken" : f >= .4f ? "c_pepero_cracked" : "c_pepero_intact";
        c.image(P + st, 710, 690, 500, 90, 0xFFFFFFFF);
        gauge(c, "e", 700, 800, f);
        c.text(pr + " / " + tgt, 1236, 822, 30, 0xFFFFFFFF, Canvas.LEFT, Canvas.BOLD | Canvas.OUTLINE);
        c.text("빼빼로", 688, 822, 26, 0xFFFFFFFF, Canvas.RIGHT, Canvas.BOLD | Canvas.OUTLINE);
    }

    private void gauge(Canvas c, String k, float x, float y, float f) {
        f = Math.max(0, Math.min(1, f));
        c.image(P + k + "_gauge_empty", x, y, 520, 44, 0xFFFFFFFF);
        // 채움 그림 오른쪽 둥근 끝이 잘리지 않게 3조각(왼끝·가운데 늘림·오른끝)으로 (끝 = 그림 1000px 중 55px)
        if (f > 0) c.imageNine(P + k + "_gauge_fill", x + 10, y + 7, 500 * f, 30, .055f, 0, 27.5f, 0, 0xFFFFFFFF);
    }

    /** H 남은 상자 (1450,250) 420×90, 글자칸 (120,20) 275×50 */
    private void chests(Canvas c, JsonObject g) {
        long total = J.num(g, "chests"), left = g.has("left") ? J.num(g, "left") : total;
        c.image(P + "h_remaining_chests", 1450, 250, 420, 90, 0xFFFFFFFF);
        c.textFit("남은 상자  " + left + " / " + total, 1450 + 120 + 137.5f, 250 + 45, 34, 275, INK, Canvas.CENTER, TXT);
        String finder = J.str(g, "finder");
        if (!finder.isEmpty()) c.textFit(finder + " 님이 찾았어요!", 1660, 362, 22, 400, 0xFFFFFFFF, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
    }

    /** K 키 판 (570,460) 780×160, L 키캡 96×96 (610+116i, 491), J 화살표 54×54 위, M 게이지 (700,730) */
    private void keyPanel(Canvas c, long now) {
        int n = keys.size();
        if (n == 0) return;
        c.image(P + "k_key_panel", 570, 460, 780, 160, 0xFFFFFFFF);
        float step = Math.min(116, 700f / n), size = Math.min(96, step - 12);
        float x0 = 960 - (step * (n - 1) + size) / 2, y = 491 + (96 - size) / 2;
        for (int i = 0; i < n; i++) {
            String cap = i < keyIndex ? "l_keycap_correct" : (wasdDone && !wasdOk && i == keyIndex) ? "l_keycap_wrong" : "l_keycap_default";
            float x = x0 + i * step;
            float bump = (!wasdDone && i == keyIndex) ? (float) Math.sin(now / 160.0) * 3 : 0;
            c.image(P + cap, x, y + bump, size, size, 0xFFFFFFFF);
            char kc = keys.get(i);
            String glyph = switch (kc) { case 'W' -> "↑"; case 'A' -> "←"; case 'S' -> "↓"; default -> "→"; };
            int col = cap.equals("l_keycap_default") ? INK : 0xFFFFFFFF;
            c.text(glyph, x + size / 2, y + bump + size / 2 - size * .06f, size * .46f, col, Canvas.CENTER, Canvas.BOLD);
            c.text(String.valueOf(kc), x + size / 2, y + bump + size * .80f, size * .17f, cap.equals("l_keycap_default") ? 0xFF9A8470 : 0xEEFFFFFF, Canvas.CENTER, Canvas.BOLD);
        }
        if (!wasdDone && keyIndex < n) {
            float ax = x0 + keyIndex * step + size / 2 - 27;
            c.image(P + "j_current_arrow", ax, 420 + (float) Math.sin(now / 200.0) * 4, 54, 54, 0xFFFFFFFF);
        }
        gauge(c, "m", 700, 730, (float) keyIndex / n);
        c.text("진행도", 688, 752, 26, 0xFFFFFFFF, Canvas.RIGHT, Canvas.BOLD | Canvas.OUTLINE);
        c.text(keyIndex + " / " + n, 1236, 752, 30, 0xFFFFFFFF, Canvas.LEFT, Canvas.BOLD | Canvas.OUTLINE);
    }

    /** 상자 찾음: I 열린 상자 + J 보상 카드 */
    private void foundCard(Canvas c, long now) {
        float t = (now - foundAt) / 2600f;
        float a = t < .1f ? t / .1f : t > .8f ? (1 - t) / .2f : 1;
        float pop = Anim.outBack(Math.min(1, t / .18f));
        c.push();
        c.translate(960, 540);
        c.scale(.6f + .4f * pop, .6f + .4f * pop);
        c.translate(-960, -540);
        c.image(P + "i_chest_open", 700, 400, 260, 260, Anim.white(a));
        card(c, found, 990, 420, 220, 260, a);
        c.pop();
    }

    /** J 보상 카드: 아이템칸 (40,35) 140×135, 글자칸 (35,185) 150×45 (220×260 기준) */
    private void card(Canvas c, JsonObject r, float x, float y, float w, float h, float a) {
        float s = w / 220f;
        c.image(P + (J.bool(r, "premium") ? "j_reward_card_premium" : "j_reward_card_normal"), x, y, w, h, Anim.white(a));
        float ix = x + 40 * s, iy = y + 35 * s, iw = 140 * s, ih = 135 * s;
        float isz = Math.min(iw, ih) * .7f;
        String item = J.str(r, "item");
        if (item.isEmpty() || !c.item(item, ix + (iw - isz) / 2, iy + (ih - isz) / 2, isz)) c.imageFit("hud/diamond", ix + iw / 2, iy + ih / 2, isz, Anim.white(a));
        c.textFit(J.str(r, "label"), x + (35 + 75) * s, y + (185 + 22.5f) * s, 26 * s, 150 * s, Anim.alpha(INK, a), Canvas.CENTER, TXT);
    }

    /** 결과: G 개구리 (760,250|236) 400×400 + 리본 (610,625) 700×160 글자칸 (145,45) 410×70 + 작은 보상 카드 132×156 */
    private void resultView(Canvas c, long now) {
        float t = (now - resultAt) / 3800f;
        float a = t < .08f ? t / .08f : t > .85f ? (1 - t) / .15f : 1;
        boolean ok = J.bool(result, "success");
        float pop = Anim.outBack(Math.min(1, t / .14f));
        c.push();
        c.translate(960, 600);
        c.scale(.7f + .3f * pop, .7f + .3f * pop);
        c.translate(-960, -600);
        c.image(P + (ok ? "g_frog_success" : "g_frog_failure"), 760, ok ? 250 : 236, 400, 400, Anim.white(a));   // 발끝(그림 789px)이 리본 윗선(성공 31px·실패 4px)에 올라서게: 실패 리본이 더 높아 발이 묻히던 것
        c.image(P + (ok ? "result_ribbon_success" : "result_ribbon_failure"), 610, 625, 700, 160, Anim.white(a));
        String head = J.str(result, "head", ok ? "성공!" : "실패…");
        c.textFit(head, 610 + 145 + 205, 625 + 45 + 35, 52, 410, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        String sub = J.str(result, "sub");
        if (!sub.isEmpty()) c.textFit(sub, 960, ok ? 352 : 338, 28, 900, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        JsonArray rw = result.has("rewards") && result.get("rewards").isJsonArray() ? result.getAsJsonArray("rewards") : null;
        if (rw != null && !rw.isEmpty()) {
            int n = Math.min(4, rw.size());
            float gap = 168, x0 = 960 - (gap * (n - 1) + 132) / 2;
            for (int i = 0; i < n; i++) if (rw.get(i).isJsonObject()) card(c, rw.get(i).getAsJsonObject(), x0 + i * gap, 792, 132, 156, a);   // 리본 꼬리 아래끝(783) 밑으로
        }
        c.pop();
    }
}
