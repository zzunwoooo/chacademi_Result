package kr.kkaegul.client.ui;

import com.google.gson.JsonObject;

/**
 * 창 없이 화면 위에 뜨는 것들 (받은 그림 묶음 layout.json 1920×1080 좌표 그대로):
 *  - 깜빵: 운동 카운터 + 진행 바 (screen "jail"), 다이아 증가 슬롯 (fx "jail_game")
 *  - 거탐 퀴즈: 문제 카드 + 분류 꼬리표 + 안내 띠 + 타이머 (screen "quiz"), 정답/오답 도장 (update quiz {result})
 *  - 깨굴 운세 카드 (screen "fortune", 몇 초 뒤 사라짐)
 *  - 작은 카드: 시작 장비 뽑기 (fx "armor_roll"), 수선 뽑기 (fx "mending_draw")
 */
public final class ExtraView {
    private static final int TXT = Canvas.BOLD | Canvas.LIGHT;
    private static final int INK = 0xFF4A2D16;

    private JsonObject jail;
    private JsonObject slot;
    private long slotAt;
    private JsonObject quiz;
    private long quizAt, quizResultAt, quizCloseAt;
    private String quizResult = "";
    private JsonObject fortune;
    private long fortuneAt;
    private JsonObject card;
    private String cardKind = "";
    private long cardAt;

    // 감옥 돌림판 8칸 (×0 / 0.2 / 0.5 / 1 / 2 / 3 / 5 / 10). 칸 그림은 5종을 나눠 씀
    private static final String[] TILES = {"slot_tile_lose", "slot_tile_x1", "slot_tile_x1", "slot_tile_x1", "slot_tile_x2", "slot_tile_x3", "slot_tile_x5", "slot_tile_x5"};
    private static final double[] MULTS = {0, 0.2, 0.5, 1, 2, 3, 5, 10};

    private static String multText(double m) { return m == 0 ? "꽝" : "X" + (m == Math.floor(m) ? String.valueOf((long) m) : String.valueOf(m)); }
    private static final long SPIN = 2800, SLOT_LIFE = 6000, FORTUNE_LIFE = 7000, CARD_LIFE = 4200;

    public boolean handles(String screen) { return screen.equals("jail") || screen.equals("quiz") || screen.equals("fortune"); }

    public boolean handlesFx(String id) {
        return switch (id) {
            case "jail_game", "armor_roll", "mending_draw", "title_unlock", "submit_normal", "random_submit", "gacha_destroy", "gacha_protect" -> true;
            default -> false;
        };
    }

    public void open(String screen, JsonObject d, long now) {
        switch (screen) {
            case "jail" -> jail = d;
            case "quiz" -> { quiz = d; quizAt = now; quizResult = ""; quizCloseAt = 0; }
            case "fortune" -> { fortune = d; fortuneAt = now; }
            default -> { }
        }
    }

    public void update(String screen, JsonObject d, long now) {
        switch (screen) {
            case "jail" -> jail = jail == null ? d : J.merge(jail, d);
            case "quiz" -> {
                if (quiz == null) return;
                quiz = J.merge(quiz, d);
                String r = J.str(d, "result");
                if (!r.isEmpty()) { quizResult = r; quizResultAt = now; }
            }
            default -> { }
        }
    }

    public void close(String screen, long now) {
        switch (screen) {
            case "jail" -> jail = null;
            case "quiz" -> { if (quiz != null) quizCloseAt = now; }
            case "fortune" -> fortune = null;
            default -> { }
        }
    }

    public void fx(String id, JsonObject d, long now) {
        if (id.equals("jail_game")) { slot = d; slotAt = now; }
        else { card = d; cardKind = id; cardAt = now; }
    }

    /** 화면 가운데 큰 연출이 떠 있는지 (퀴즈·슬롯·작은 카드) */
    public boolean busy(long now) {
        return (quiz != null && (quizCloseAt == 0 || now - quizCloseAt <= 1600))
            || (slot != null && now - slotAt <= SLOT_LIFE) || (card != null && now - cardAt <= CARD_LIFE);
    }

    // ───────── 그리기 ─────────
    public void render(Canvas c, long now) {
        if (quiz != null && quizCloseAt > 0 && now - quizCloseAt > 1600) quiz = null;
        if (slot != null && now - slotAt > SLOT_LIFE) slot = null;
        if (fortune != null && now - fortuneAt > FORTUNE_LIFE) fortune = null;
        if (card != null && now - cardAt > CARD_LIFE) card = null;
        if (jail == null && slot == null && quiz == null && fortune == null && card == null) return;
        float W = c.width(), H = c.height();
        float k = Math.min(W / 1920f, H / 1080f);
        c.push();
        c.translate((W - 1920 * k) / 2, 0);
        c.scale(k, k);
        if (jail != null && (!jail.has("inJail") || J.bool(jail, "inJail"))) jailCounter(c);
        if (slot != null) slotMachine(c, now);
        if (quiz != null) quizCard(c, now);
        if (fortune != null) fortuneCard(c, now);
        if (card != null) smallCard(c, now);
        c.pop();
    }

    /** 운동 카운터 (1500,190) 400×110, 숫자칸 (125,42.5) 245×50 / 바 (1520,310) 360×40, 채움 (1528,318) 344×24 */
    private void jailCounter(Canvas c) {
        long done = J.num(jail, "done"), req = Math.max(1, J.num(jail, "required", 1));
        c.image("jail/exercise_counter", 1500, 190, 400, 110, 0xFFFFFFFF);
        boolean ready = J.bool(jail, "exitReady");
        c.textFit(ready ? "운동 완료!" : done + " / " + req, 1736, 259, 40, 245, ready ? 0xFF3F7F31 : INK, Canvas.CENTER, TXT);
        float f = Math.min(1, (float) done / req);
        c.image("jail/exercise_bar_empty", 1520, 310, 360, 40, 0xFFFFFFFF);
        // 채움 막대가 빈 막대 안쪽 홈(x 15~705, y 21~64 / 720×80)에 딱 맞게, 오른쪽 둥근 끝은 잘리지 않게 3조각으로
        if (f > 0) c.imageNine("jail/exercise_bar_fill", 1525.6f, 319.3f, 348.9f * f, 23.9f, 44 / 720f, 0, 13.1f, 0, 0xFFFFFFFF);
        c.text(ready ? "출구 NPC 를 눌러 나가요" : "웅크리기(Shift)로 운동", 1700, 372, 20, 0xFFFFFFFF, Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
    }

    /**
     * 다이아 증가 게임: 판 (660,300) 600×400 제목칸 (175,77.5) 255×37.5 / 결과칸 (160,305) 280×50,
     * 칸 5개 (710+100i, 450) 90×110 (아이템칸 (20,12.5) 50 / 배수칸 (10,72.5) 70×25), 도장 (860,720) 200×80
     */
    private void slotMachine(Canvas c, long now) {
        long e = now - slotAt;
        double mult = J.dbl(slot, "multiplier", 0);
        int target = 0;
        for (int i = 0; i < MULTS.length; i++) if (Math.abs(MULTS[i] - mult) < 1e-6) target = i;
        float a = e < 200 ? e / 200f : e > SLOT_LIFE - 500 ? (SLOT_LIFE - e) / 500f : 1;
        c.image("jail/slot_machine_base", 660, 300, 600, 400, Anim.white(a));
        c.textFit("다이아 증가 게임", 960, 300 + 85, 32, 255, Anim.alpha(INK, a), Canvas.CENTER, TXT);   // 리본 판 안쪽 y 127~213px (1200×800) 가운데
        // 돌아가는 동안 불빛이 칸을 옮겨 다니다가 결과 칸에서 멈춤 (점점 느려짐)
        boolean spinning = e < SPIN;
        int lit;
        if (spinning) {
            float t = e / (float) SPIN;
            int steps = 18 + target;                          // 마지막에 target 에 닿게
            lit = (int) Math.floor(steps * (1 - (1 - t) * (1 - t))) % TILES.length;
        } else lit = target;
        // 8칸: 칸 58×71, 64 간격. 판 안쪽 홈 (화면 700~1220, 453~559 = 그림 80~1120, 306~518px) 안에 다 들어가게, 세로 가운데
        float tw = 58, th = 71, step = 64, x0 = 960 - (step * (TILES.length - 1) + tw) / 2;
        for (int i = 0; i < TILES.length; i++) {
            float x = x0 + step * i, y = 506 - th / 2;
            boolean on = i == lit;
            float lift = on ? (spinning ? 4 : 8) : 0;
            c.image("jail/" + TILES[i], x, y - lift, tw, th, on ? Anim.white(a) : Anim.white(a * .55f));
            if (i > 0) c.imageFit("hud/diamond", x + tw / 2, y - lift + th * .34f, 26, on ? Anim.white(a) : Anim.white(a * .55f));
            else c.imageFit("hud/diamond", x + tw / 2, y - lift + th * .34f, 26, Anim.white((on ? a : a * .55f) * .25f));   // 꽝: 흐린 다이아
            c.textFit(multText(MULTS[i]), x + tw / 2, y - lift + th * .77f, 17, tw - 8, Anim.alpha(INK, on ? a : a * .6f), Canvas.CENTER, TXT);
        }
        if (!spinning) {
            long amount = J.num(slot, "amount");
            c.textFit(mult == 0 ? "꽝! 다이아가 그대로예요" : "목표 다이아 +" + Fmt.num(amount), 960, 300 + 305 + 25, 30, 280, Anim.alpha(INK, a), Canvas.CENTER, TXT);
            float pop = Anim.outBack(Math.min(1, (e - SPIN) / 260f));
            c.push();
            c.translate(960, 760);
            c.scale(.6f + .4f * pop, .6f + .4f * pop);
            c.translate(-960, -760);
            if (mult == 0) {
                c.image("jail/stamp_fail", 860, 720, 200, 80, Anim.white(a));
                c.textFit("꽝", 860 + 35 + 65, 720 + 22.5f + 15, 30, 130, Anim.alpha(0xFF6B6B6B, a), Canvas.CENTER, TXT);
            } else {
                c.image("jail/stamp_win", 860, 720, 200, 80, Anim.white(a));
                c.textFit(multText(mult) + " 당첨", 860 + 47.5f + 52.5f, 720 + 20 + 15, 28, 105, Anim.alpha(0xFF8A5A00, a), Canvas.CENTER, TXT);
            }
            c.pop();
        }
    }

    /**
     * 거탐 퀴즈: 문제 카드 (560,140) 800×260 문제칸 (50,107.5) 700×120 / 꼬리표 (600,150) 200×50 글자칸 (17.5,10) 165×30
     * 안내 띠 (700,410) 520×50 글자칸 (30,10) 460×30 / 타이머 (1280,110) 100 / 도장 (760,520) 400×160 글자칸 (130,82.5) 225×47.5
     */
    private void quizCard(Canvas c, long now) {
        float a = quizCloseAt > 0 ? Math.max(0, 1 - (now - quizCloseAt) / 1600f) : Math.min(1, (now - quizAt) / 200f);
        c.image("quiz/question_card", 560, 140, 800, 260, Anim.white(a));
        c.image("quiz/category_tag", 600, 150, 200, 50, Anim.white(a));
        c.textFit(J.str(quiz, "category", "상식퀴즈"), 600 + 17.5f + 82.5f, 150 + 10 + 15, 24, 165, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        // 문제 2줄까지
        String q = J.str(quiz, "question");
        java.util.List<String> lines = TextRaster.wrap(q, 690, 30, TXT);
        if (lines.size() > 2) lines = lines.subList(0, 2);
        float cy = 140 + 107.5f + 60;
        for (int i = 0; i < lines.size(); i++)
            c.textFit(lines.get(i), 960, cy + (i - (lines.size() - 1) / 2f) * 42, 30, 700, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        c.image("quiz/instruction_strip", 700, 410, 520, 50, Anim.white(a));
        c.textFit("채팅으로 정답을 입력하세요", 700 + 30 + 230, 410 + 10 + 15, 22, 460, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        // 타이머 (미니게임 B 재사용, 100 크기)
        long total = Math.max(1, J.num(quiz, "seconds", 20)) * 1000, left = Math.max(0, total - (now - quizAt));
        if (quizResult.isEmpty() && quizCloseAt == 0) {
            float s = 100 / 140f;
            c.push();
            c.translate(1374, 150);   // 문제 카드(560~1360) 오른쪽 밖: 카드 귀퉁이 장식을 가리지 않게
            c.scale(s, s);
            c.translate(-1710, -45);
            timer(c, left, total);
            c.pop();
        }
        if (!quizResult.isEmpty()) {
            boolean ok = quizResult.equals("correct");
            float pop = Anim.outBack(Math.min(1, (now - quizResultAt) / 260f));
            c.push();
            c.translate(960, 600);
            c.scale(.6f + .4f * pop, .6f + .4f * pop);
            c.translate(-960, -600);
            c.image(ok ? "quiz/quiz_correct" : "quiz/quiz_wrong", 760, 520, 400, 160, Anim.white(a));
            c.textFit(ok ? "정답!" : "오답!", 760 + 130 + 112.5f, 520 + 82.5f + 23.75f, 36, 225, Anim.alpha(ok ? 0xFF3F7F31 : 0xFFD9443A, a), Canvas.CENTER, TXT);
            String sub = J.str(quiz, "rewardText");
            if (!sub.isEmpty()) c.textFit(sub, 960, 700, 22, 500, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
            c.pop();
        }
    }

    /** 미니게임 B 타이머 (1710,45) 140×160 기준으로 그림 */
    private static void timer(Canvas c, long left, long total) {
        float x = 1710, y = 45;
        c.image("minigame/b_timer_empty", x, y, 140, 160, 0xFFFFFFFF);
        float f = (float) left / Math.max(1, total);
        float cx = x + 69.5f, cy = y + 94, r = 63.5f;
        c.fill(cx - r + 4, cy - r + 4, (r - 4) * 2, (r - 4) * 2, r - 4, 0xF2FFF8EE);   // 링 안쪽 채움 (숫자 잘 보이게)
        String ring; float rr, ox, oy;
        if (f > .5f) { ring = "b_timer_green"; rr = 116; ox = 140; oy = 159; }
        else if (f > .2f) { ring = "b_timer_orange"; rr = 135; ox = 140; oy = 140; }
        else { ring = "b_timer_red"; rr = 135; ox = 140; oy = 140; }
        float S = r * 280 / rr;
        c.image("minigame/" + ring, cx - ox * S / 280, cy - oy * S / 280, S, S, 0xFFFFFFFF);
        c.image("minigame/b_timer_knob", x, y, 140, 160, 0xFFFFFFFF);   // 시계 머리는 색 링 위에
        long s = (left + 999) / 1000;
        c.textFit(String.valueOf(s), cx, cy, 40, 80, f > .2f ? INK : 0xFFE0564C, Canvas.CENTER, TXT);
    }

    /**
     * 운세: 카드 (660,160) 600×700 제목칸 (120,45) 360×50 / 깨굴이 (810,350) 300×250 / 문장 (720,622) 480×100 글자칸 (24,15) 432×70
     * 효과 (740,740) 440×60 글자칸 (20,10) 400×40 / 남은 횟수 (1100,810) 140×40 글자칸 (12,8) 116×24
     */
    private void fortuneCard(Canvas c, long now) {
        long e = now - fortuneAt;
        float a = e < 250 ? e / 250f : e > FORTUNE_LIFE - 600 ? (FORTUNE_LIFE - e) / 600f : 1;
        boolean good = J.bool(fortune, "good");
        float pop = Anim.outBack(Math.min(1, e / 320f));
        c.push();
        c.translate(960, 510);
        c.scale(.7f + .3f * pop, .7f + .3f * pop);
        c.translate(-960, -510);
        c.image(good ? "fortune/fortune_panel_good" : "fortune/fortune_panel_bad", 660, 160, 600, 700, Anim.white(a));
        c.textFit("오늘의 깨굴 운세", 960, 160 + (good ? 91.75f : 86.75f), 32, 330,   // 리본 판 안쪽 130~237px (재서 맞춤)
                Anim.alpha(INK, a), Canvas.CENTER, TXT);
        c.image(good ? "fortune/fortune_frog_good" : "fortune/fortune_frog_bad", 810, 350, 300, 250, Anim.white(a));
        c.image("fortune/fortune_sentence", 720, 610, 480, 100, Anim.white(a));
        java.util.List<String> lines = TextRaster.wrap(Fmt.strip(J.str(fortune, "message")), 425, 24, TXT);
        if (lines.size() > 2) lines = lines.subList(0, 2);
        for (int i = 0; i < lines.size(); i++)
            c.textFit(lines.get(i), 960, 660 + (i - (lines.size() - 1) / 2f) * 30, 24, 432, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        c.image("fortune/fortune_effect", 740, 722, 440, 60, Anim.white(a));
        long amt = J.num(fortune, "amount"), sec = J.num(fortune, "seconds");
        String stat = J.str(fortune, "stat").equals("fortune") ? "행운" : "효율";
        String eff = amt == 0 ? "효과 없음 (이미 최대)" : stat + " " + (amt > 0 ? "+" : "") + amt + " · " + (sec >= 60 ? sec / 60 + "분" : sec + "초");
        long gd = J.num(fortune, "gaugeDelta");
        if (gd != 0) eff += " · 게이지 " + (gd > 0 ? "+" : "") + gd;
        c.textFit(eff, 960, 752, 24, 400, Anim.alpha(good ? 0xFF3F7F31 : 0xFF7A3FA0, a), Canvas.CENTER, TXT);
        c.image("fortune/fortune_remaining", 896, 790, 128, 36, Anim.white(a));   // 판 안쪽(아래 끝 835) 가운데: 오른쪽 아래 보석·테두리와 겹치지 않게
        c.textFit("남은 " + J.num(fortune, "usesLeft") + "회", 960, 808, 17, 106, Anim.alpha(INK, a), Canvas.CENTER, TXT);
        c.pop();
    }

    /**
     * 작은 카드 460×520 (가운데에 띄움): 제목칸 (90,45) 280×45, 그림칸 (105,130) 250×250, 아래 글자칸 (100,420) 260×50
     */
    private void smallCard(Canvas c, long now) {
        long e = now - cardAt;
        float a = e < 200 ? e / 200f : e > CARD_LIFE - 500 ? (CARD_LIFE - e) / 500f : 1;
        float pop = Anim.outBack(Math.min(1, e / 320f));
        float x = 730, y = 260;
        c.push();
        c.translate(960, 520);
        c.scale(.6f + .4f * pop, .6f + .4f * pop);
        c.translate(-960, -520);
        if (cardKind.equals("armor_roll")) {
            String tier = J.str(card, "tier");
            String art = switch (tier) {
                case "diamond" -> "diamond"; case "gold" -> "gold"; case "netherite" -> "netherite";
                case "iron", "chainmail", "copper" -> "iron"; default -> "wood";
            };
            c.image("cards/equipment_" + art, x, y, 460, 520, Anim.white(a));
            c.textFit("시작 장비 뽑기", x + 230, y + 81.25f, 28, 260,   // 리본 판 113~212px
                Anim.alpha(INK, a), Canvas.CENTER, TXT);
            String prefix = J.str(card, "prefix").toLowerCase();
            if (prefix.equals("copper")) prefix = "leather";
            boolean drew = !prefix.isEmpty() && c.item("minecraft:" + prefix + "_chestplate", x + 130, y + 155, 200);
            if (!drew) c.text("?", x + 230, y + 255, 120, Anim.alpha(0xFF9A8470, a), Canvas.CENTER, Canvas.BOLD);
            c.textFit(J.str(card, "name", tier), x + 230, y + (art.equals("wood") ? 459.75f : 447.5f), 30, 230,   // 아래 판: 나무 869~970px, 나머지 850~940px
                Anim.alpha(INK, a), Canvas.CENTER, TXT);
            String msg = Fmt.strip(J.str(card, "message"));
            if (!msg.isEmpty()) c.textFit(msg, 960, y + 560, 26, 700, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        } else if (!cardKind.equals("mending_draw")) {
            // 묶음 3 카드: 리본 판 / 아래 판 가운데는 그림마다 재서 맞춤
            String art, title, bottom, sub = "";
            float ty, by;
            int bc = INK;
            switch (cardKind) {
                case "title_unlock" -> { art = "title_card"; title = "칭호 획득!"; bottom = Fmt.strip(J.str(card, "name")); ty = 106; by = 454.75f; bc = 0xFF7A4FB0; }
                case "submit_normal" -> { art = "submit_normal"; title = "제출 완료!"; bottom = "다이아 " + Fmt.num(J.num(card, "credited")) + "개 제출";
                    sub = "남은 다이아 " + Fmt.num(J.num(card, "debt")) + "개"; ty = 106.25f; by = 454.75f; bc = 0xFF2F7FB0; }
                case "random_submit" -> { art = "submit_random"; title = "랜덤 제출!"; bottom = Fmt.num(J.num(card, "used")) + "개 → " + Fmt.num(J.num(card, "credited")) + "개 인정";
                    sub = Fmt.strip(J.str(card, "message")) + "  ·  남은 다이아 " + Fmt.num(J.num(card, "debt")) + "개"; ty = 110.75f; by = 455.25f; bc = 0xFF2F7FB0; }
                case "gacha_destroy" -> { art = "pickaxe_broken"; title = "곡괭이 파괴!"; bottom = "곡괭이가 부서졌어요"; sub = "깨굴이가 웃음을 참고 있어요"; ty = 113.5f; by = 449.25f; bc = 0xFFC0392B; }
                default -> { art = "pickaxe_protected"; title = "파괴 방지!"; bottom = "파괴 방지권 발동"; sub = "곡괭이를 지켰어요"; ty = 106.5f; by = 448.5f; bc = 0xFF3F7F31; }
            }
            c.image("cards/" + art, x, y, 460, 520, Anim.white(a));
            c.textFit(title, x + 230, y + ty, 30, 250, Anim.alpha(INK, a), Canvas.CENTER, TXT);
            c.textFit(bottom, x + 230, y + by, 26, 330, Anim.alpha(bc, a), Canvas.CENTER, TXT);
            if (cardKind.equals("title_unlock") && J.bool(card, "serverFirst")) {
                float tp = Anim.outBack(Anim.t(now, cardAt + 350, 300));
                c.push();
                c.translate(x + 250 + 90, y + 160 + 24);
                c.scale(tp, tp);
                c.rotate(8);
                c.image("cards/first_tag", -90, -24, 180, 48, Anim.white(a));
                c.textFit("서버 최초", 0, 0, 18, 104, Anim.alpha(0xFF7A4A00, a), Canvas.CENTER, TXT);
                c.pop();
            }
            if (!sub.isEmpty()) c.textFit(sub, 960, y + 560, 24, 700, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        } else {
            boolean win = J.bool(card, "win");
            c.image(win ? "cards/mending_win" : "cards/mending_fail", x, y, 460, 520, Anim.white(a));
            c.textFit("수선 뽑기", x + 230, y + (win ? 92.75f : 99.25f), 28, 260,   // 당첨 130~241px / 꽝 147~250px
                  // 리본 판 130~241px
                Anim.alpha(INK, a), Canvas.CENTER, TXT);
            c.textFit(win ? "수선 당첨!" : "꽝…", x + 230, y + (win ? 427.25f : 441f), 32, 240,   // 당첨 793~916px / 꽝 809~955px
                Anim.alpha(win ? 0xFF2F7FB0 : 0xFF6B6B6B, a), Canvas.CENTER, TXT);
            c.textFit(win ? "수선 책을 받았어요" : "꽝 종이 1장 (5장 모으면 고급상자)", 960, y + 560, 24, 700, Anim.alpha(0xFFFFFFFF, a), Canvas.CENTER, Canvas.BOLD | Canvas.OUTLINE);
        }
        c.pop();
    }
}
