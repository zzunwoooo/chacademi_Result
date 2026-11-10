package kr.kkaegul.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 오목 1:1 창 (1920×1080 기준). 서버 OmokGame 이 board(225자 0/1/2)·turn·me·last·forbidden·black/white·result 를 보냄.
 * 오목판 그림 board 1000×1000 의 연두 테두리 안 나무 칸 (160,210)~(840,890) 에 15줄 격자를 코드로 그림 (그림엔 줄이 없음).
 * 왼쪽 흑 / 오른쪽 백 이름판 480×140 (얼굴 칸 (24,24) 92, 글자 칸 x 129~392), 위 차례 리본 520×100 (글자 칸 x 35~455, 가운데 y 43).
 * 내 차례에 빈 칸 클릭 → omok.place x y. 흑 차례엔 쌍삼 금지 자리에 X 표시. 끝나면 승리/패배 그림 → 서버가 창을 닫음.
 */
public final class OmokPanel implements Panel {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT, N = 15;
    private static final String P = "omok/";
    private static final float BX = 560, BY = 150, BS = 800, BK = BS / 1000f;            // 판 위치·크기
    private static final float GX = BX + 160 * BK, GY = BY + 210 * BK, GW = 680 * BK, GH = 680 * BK;   // 연두 테두리 안 나무 칸
    private static final float CW = GW / N, CH = GH / N;

    private final Host host;
    private JsonObject data;
    private final long opened;
    private long turnAt, resultAt;
    private String sig = "";
    private float k = 1, ox, oy;

    public OmokPanel(Host host, JsonObject data, long now) {
        this.host = host;
        this.data = data == null ? new JsonObject() : data;
        this.opened = now;
        this.turnAt = now;
        this.sig = sig();
    }

    @Override public String screen() { return "omok"; }
    @Override public boolean closable() { return false; }

    @Override public void update(JsonObject d, long now) {
        if (d == null) return;
        data = J.merge(data, d);
        String s = sig();
        if (!s.equals(sig)) { sig = s; turnAt = now; }   // 판이나 차례가 바뀌면 시간 다시
        if (d.has("result") && resultAt == 0) {
            resultAt = now;
            host.sound(J.str(data, "result").equals("win") ? "minecraft:ui.toast.challenge_complete" : "minecraft:entity.villager.no", 1f, .7f);
        }
    }

    private String sig() { return J.str(data, "board") + "/" + J.num(data, "turn"); }

    private int cell(int i) {
        String b = J.str(data, "board");
        return i < b.length() ? b.charAt(i) - '0' : 0;
    }

    private boolean myTurn() { return !J.bool(data, "done") && J.num(data, "turn") == J.num(data, "me") && J.num(data, "me") != 0; }

    private static float px(int x) { return GX + CW * (x + .5f); }
    private static float py(int y) { return GY + CH * (y + .5f); }

    /** 마우스 → 칸 (없으면 -1) */
    private int hit(float lx, float ly) {
        int x = (int) Math.floor((lx - GX) / CW), y = (int) Math.floor((ly - GY) / CH);
        return x < 0 || y < 0 || x >= N || y >= N ? -1 : y * N + x;
    }

    private boolean forbidden(int i) {
        JsonArray a = data.has("forbidden") && data.get("forbidden").isJsonArray() ? data.getAsJsonArray("forbidden") : null;
        if (a == null) return false;
        for (JsonElement e : a) if (e.getAsInt() == i) return true;
        return false;
    }

    @Override public void render(Canvas c, long now, float mx, float my) {
        float W = c.width(), H = c.height();
        c.fill(0, 0, W, H, 0, 0x66000000);
        k = Math.min(W / 1920f, H / 1080f);
        float rise = (1 - Anim.outCubic(Anim.t(now, opened, 280))) * 30;
        ox = (W - 1920 * k) / 2;
        oy = (H - 1080 * k) / 2 + rise;
        c.push();
        c.translate(ox, oy);
        c.scale(k, k);
        float lx = (mx - ox) / k, ly = (my - oy) / k;
        long turn = J.num(data, "turn"), me = J.num(data, "me");
        boolean mine = myTurn();
        long left = Math.max(0, J.num(data, "seconds", 20) - (now - turnAt) / 1000);

        // 차례 리본
        c.image(P + "turn_plate", 700, 40, 520, 100, 0xFFFFFFFF);
        String who = J.bool(data, "done") ? "대국 끝" : mine ? "내 차례" : "상대 차례";
        c.textFit(who + (J.bool(data, "done") ? "" : " · " + left + "초"), 960, 83, 30, 400, mine ? 0xFF2F6A1C : 0xFF5A4A3A, Canvas.CENTER, TXT);

        // 판 + 격자
        c.image(P + "board", BX, BY, BS, BS, 0xFFFFFFFF);
        int line = 0xFF6B4A26;
        for (int i = 0; i < N; i++) {
            c.fill(px(0), py(i) - .9f, px(N - 1) - px(0), 1.8f, 0, line);
            c.fill(px(i) - .9f, py(0), 1.8f, py(N - 1) - py(0), 0, line);
        }
        for (int[] s : new int[][]{{3, 3}, {3, 11}, {7, 7}, {11, 3}, {11, 11}}) c.fill(px(s[0]) - 4, py(s[1]) - 4, 8, 8, 4, line);

        // 돌
        float st = Math.min(CW, CH) * .92f;
        int last = (int) J.num(data, "last", -1);
        for (int i = 0; i < N * N; i++) {
            int v = cell(i);
            if (v == 0) continue;
            float cx = px(i % N), cy = py(i / N);
            float pop = i == last ? 1 + .25f * (1 - Anim.outBack(Anim.t(now, turnAt, 220))) : 1;
            c.image(P + (v == 1 ? "stone_black" : "stone_white"), cx - st * pop / 2, cy - st * pop / 2, st * pop, st * pop, 0xFFFFFFFF);
            if (i == last) c.fill(cx - 5, cy - 5, 10, 10, 5, 0xFFE9584F);   // 마지막 수
        }
        // 금지 자리 (흑 차례, 내가 흑일 때)
        if (mine && me == 1) for (int i = 0; i < N * N; i++) if (cell(i) == 0 && forbidden(i)) {
            float f = st * .7f;
            c.image(P + "forbidden_mark", px(i % N) - f / 2, py(i / N) - f / 2, f, f, Anim.white(.85f));
        }
        // 놓을 자리 미리 보기
        int h = hit(lx, ly);
        if (mine && h >= 0 && cell(h) == 0 && !forbidden(h))
            c.image(P + (me == 1 ? "stone_black" : "stone_white"), px(h % N) - st / 2, py(h / N) - st / 2, st, st, Anim.white(.45f));

        // 이름판
        plate(c, "player_black", 40, 330, J.objOrEmpty(data, "black"), turn == 1 && !J.bool(data, "done"), me == 1, left, now);
        plate(c, "player_white", 1400, 330, J.objOrEmpty(data, "white"), turn == 2 && !J.bool(data, "done"), me == 2, left, now);
        c.textFit("이기면 상대 다이아 " + J.num(data, "stake", 50) + "개 · 흑은 쌍삼 금지", 960, 975, 22, 760, 0xFFF6EBD6, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);

        // 결과
        if (resultAt > 0) {
            String r = J.str(data, "result");
            float t = Anim.outBack(Anim.t(now, resultAt, 420)), a = Anim.clamp((now - resultAt) / 200f);
            c.fill(0, -oy / k, 1920, H / k, 0, Anim.alpha(0x55000000, a));
            if (!r.equals("draw")) c.imageAt(P + (r.equals("win") ? "result_win" : "result_lose"), 960, 520, 400 * t, 1, 0, Anim.white(a));
            else c.text("무승부", 960, 520, 72, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            c.textFit(J.str(data, "resultSub"), 960, 760, 30, 900, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        }
        c.pop();
    }

    /** 이름판: 얼굴 · 이름 · (차례면) 남은 초 · 시간 초과 횟수 */
    private void plate(Canvas c, String id, float x, float y, JsonObject p, boolean active, boolean me, long left, long now) {
        float lift = active ? (float) Math.sin(now / 160.0) * 2 - 4 : 0;
        c.fill(x + 30, y + 28 + lift, 93, 82, 14, 0xFFF6EAD3);   // 얼굴 칸은 그림에서 뚫린 구멍(x 36~117, y 34~104px) → 얼굴·글자를 먼저 깔고 틀을 위에
        if (!c.face(J.str(p, "account"), x + 35.5f, y + 28 + lift, 82)) c.textFit(J.str(p, "name").isEmpty() ? "?" : J.str(p, "name").substring(0, 1), x + 76.5f, y + 69 + lift, 40, 72, 0xFF6B4A26, Canvas.CENTER, TXT);
        c.image(P + id, x, y + lift, 480, 140, 0xFFFFFFFF);
        c.textFit(J.str(p, "name") + (me ? " (나)" : ""), x + 260, y + 52 + lift, 28, 250, 0xFF4A2D16, Canvas.CENTER, TXT);
        long s = J.num(p, "strikes");
        String sub = active ? left + "초 남음" : "대기";
        if (s > 0) sub += " · 시간 초과 " + s;
        c.textFit(sub, x + 260, y + 92 + lift, 20, 250, active ? 0xFFB8452E : 0xFF8A7A66, Canvas.CENTER, TXT);
    }

    @Override public boolean click(float mx, float my, long now) {
        float lx = (mx - ox) / k, ly = (my - oy) / k;
        if (!myTurn()) return true;
        int h = hit(lx, ly);
        if (h < 0 || cell(h) != 0) return true;
        if (forbidden(h)) { host.sound("minecraft:entity.villager.no", 1f, .5f); return true; }
        host.send("omok.place", "x", String.valueOf(h % N), "y", String.valueOf(h / N));
        host.sound("minecraft:block.wood.place", 1.3f, .6f);
        return true;
    }
}
