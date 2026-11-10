package kr.kkaegul.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 괴롭히기 룰렛 (fx "harass_roulette", 받은 묶음 3 룰렛).
 * 판 (560,260) 800×520. 제목 판 안쪽 가운데 (400,86), 결과 판(초록 띠 y 400~478) 가운데 (400,440) — 그림에서 잼.
 * 칸 110×140 (y 185), 5칸이면 간격 147.5 / 6칸이면 118, 칸 이름 가운데 y 117.
 * 도장 400×140 (760,805), 글자 가운데 (240,70).
 */
public final class HarassView {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final int INK = 0xFF4A2D16;

    private JsonObject data;
    private long at;
    private int lastStep = -1;
    private boolean stamped;

    public boolean busy() { return data != null; }

    public boolean handlesFx(String id) { return id.equals("harass_roulette"); }

    public void fx(String id, JsonObject d, long now) { data = d; at = now; lastStep = -1; stamped = false; }

    private static String art(String id) {
        return switch (id) {
            case "monsters" -> "monsters"; case "mining_fatigue" -> "fatigue"; case "tp_random_user" -> "teleport";
            case "freeze" -> "ice"; case "delete_slot" -> "trash";
            case "quiz" -> "quiz"; case "wasd" -> "keyboard"; case "sky" -> "sky"; case "fever" -> "fever";
            case "all_jail" -> "jail"; case "lobby_kick" -> "invite";
            default -> "blank";
        };
    }

    private static String name(String id) {
        return switch (id) {
            case "monsters" -> "몬스터 소환"; case "mining_fatigue" -> "채광 피로"; case "tp_random_user" -> "텔레포트";
            case "freeze" -> "3초 얼음"; case "delete_slot" -> "한 칸 삭제"; case "launch" -> "높이 날리기";
            case "quiz" -> "퀴즈"; case "wasd" -> "WASD"; case "sky" -> "하늘 이동"; case "fever" -> "피버";
            case "all_jail" -> "전원 감옥"; case "lobby_kick" -> "로비 초대";
            default -> id;
        };
    }

    /** 감속 곡선: 빠르게 돌다가 천천히 멈춤 */
    private static float ease(float t) { t = Anim.clamp(t); float u = 1 - t; return 1 - u * u * u * u; }

    public void render(Canvas c, long now, Host host) {
        if (data == null) return;
        long spin = Math.max(1200, J.num(data, "spinMs", 2800));
        long e = now - at, life = spin + 2600;
        if (e > life) { data = null; return; }
        boolean owner = "owner".equals(J.str(data, "mode"));
        JsonArray arr = data.has("tiles") && data.get("tiles").isJsonArray() ? data.getAsJsonArray("tiles") : new JsonArray();
        int n = Math.max(1, arr.size());
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) ids[i] = arr.size() > i ? arr.get(i).getAsString() : "blank";
        String result = J.str(data, "result");
        int ri = 0;
        for (int i = 0; i < n; i++) if (ids[i].equals(result)) ri = i;

        float W = c.width(), H = c.height(), k = Math.min(W / 1920f, H / 1080f);
        float in = Anim.outBack(Anim.t(now, at, 300)), out = e > life - 300 ? (life - e) / 300f : 1;
        float a = Anim.clamp(Math.min(Anim.t(now, at, 200), out));
        c.push();
        c.translate((W - 1920 * k) / 2, (H - 1080 * k) / 2);
        c.scale(k, k);
        c.translate(960, 520);
        float s = .9f + .1f * in;
        c.scale(s, s);
        c.translate(-400, -260);    // 판 기준 좌표

        c.image("harass/a_panel", 0, 0, 800, 520, Anim.white(a));
        c.textFit(owner ? "섭주 괴롭히기" : "괴롭히기 룰렛", 400, 86, 30, 300, Anim.alpha(INK, a), Canvas.CENTER, TXT);

        // 불빛 위치: 몇 바퀴 돌고 결과 칸에 멈춤
        int total = n * 4 + ri;
        float st = Anim.clamp((e - 250) / (float) spin);
        int step = Math.round(ease(st) * total);
        int lit = step % n;
        if (step != lastStep && e > 250 && st < 1) { lastStep = step; if (host != null) host.sound("minecraft:block.note_block.hat", 1.4f + .4f * st, .5f); }
        boolean done = st >= 1;

        float gap = n <= 1 ? 0 : (700 - 110) / (float) (n - 1);
        for (int i = 0; i < n; i++) {
            float x = 50 + gap * i, y = 200;
            boolean on = i == lit;
            float pop = done && i == ri ? 1 + .06f * (float) Math.sin(Math.min(1, (e - 250 - spin) / 200f) * Math.PI) : 1;
            float dim = done && i != ri ? .55f : 1;
            c.push();
            c.translate(x + 55, y + 70);
            c.scale(pop, pop);
            c.translate(-55, -70);
            float glow = done ? .75f + .25f * (float) Math.sin(e / 120.0) : 1;
            if (on) {
                // 빨간 강조는 칸 뒤에 둥근 빛으로 (v3 칸은 테두리에 클로버·보석·왕관이 있어서 위에 빨간 테두리를 겹쳐 그리면 장식이 번져 보였음)
                c.fill(-9, -9, 128, 158, 24, Anim.alpha(0x66FF2A2A, a * glow));
                c.fill(-4, -4, 118, 148, 18, Anim.alpha(0xEEFF2A2A, a * glow));
            }
            c.image("harass/b_" + art(ids[i]), 0, 0, 110, 140, Anim.alpha(0xFFFFFFFF, a * dim));
            if (ids[i].equals("wasd")) {
                String[] keys = {"W", "A", "S", "D"};
                float[][] kp = {{56, 47}, {27, 74}, {55, 74}, {84, 74}};
                for (int q = 0; q < 4; q++) c.text(keys[q], kp[q][0], kp[q][1], 13, Anim.alpha(INK, a * dim), Canvas.CENTER, Canvas.BOLD);
            }
            // 이름: v3 칸 아래 모서리 장식(왼 왕관·오른 보석) 사이 빈 띠 (그림 실측 y 108~116, x 25~90)
            c.textFit(name(ids[i]), 57, 111, 14, 64, Anim.alpha(INK, a * dim), Canvas.CENTER, TXT);
            c.pop();
        }

        // 결과
        float rt = Anim.t(now, at + 250 + spin, 260);
        if (done) {
            c.textFit(J.str(data, "resultText", name(result)) + "!", 400, 440, 30, 520, Anim.alpha(INK, a * rt), Canvas.CENTER, TXT);
            // 도장: 위에서 크게 찍힘
            float dt = Anim.t(now, at + 250 + spin + 180, 260);
            if (dt > 0) {
                if (!stamped) { stamped = true; if (host != null) host.sound(owner ? "minecraft:block.amethyst_block.hit" : "minecraft:entity.player.attack.knockback", owner ? .8f : 1f, .8f); }
                float ds = 1.5f - .5f * Anim.outBack(dt);
                c.push();
                c.translate(400, 545 + 70);
                c.scale(ds, ds);
                c.rotate(-4);
                c.translate(-200, -70);
                c.image(owner ? "harass/d_owner" : "harass/d_personal", 0, 0, 400, 140, Anim.alpha(0xFFFFFFFF, a * Anim.clamp(dt * 2)));
                c.textFit(owner ? "섭주 벌칙" : "개인 벌칙", 240, 70, 34, 200, Anim.alpha(owner ? 0xFF7A2FB0 : 0xFFD8352E, a * Anim.clamp(dt * 2)), Canvas.CENTER, TXT);
                c.pop();
            }
        }
        c.pop();
    }
}
