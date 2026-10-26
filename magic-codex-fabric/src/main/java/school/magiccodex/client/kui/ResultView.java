package school.magiccodex.client.kui;

import java.util.List;
import java.util.UUID;
import school.magiccodex.client.kui.Canvas.Face;

/**
 * 결과 창 (result_layout, reference e4af0ac1; 1672×941). Team banners stay plain (README rule 8: no lion / eagle).
 * MVP: icons/crown before the name.
 */
public final class ResultView {
    private ResultView() {}
    public static final float W = 1672, H = 941;

    public record Line(UUID id, String nick, String skin, int goals, int kills, int snitch, boolean mvp, int deaths) {
        public Line(UUID id, String nick, String skin, int goals, int kills, int snitch, boolean mvp) { this(id, nick, skin, goals, kills, snitch, mvp, 0); }
    }
    /** outcome 0 패배 1 승리 2 무승부 3 무효. duel: 1:1 대전 (score = rounds won; columns 처치 · 데스, no 골 / 스니치 / MVP). */
    public record Data(int outcome, String myLabel, String enemyLabel, int myScore, int enemyScore, List<Line> mine, List<Line> enemy,
                       int before, int after, boolean rated, boolean duel) {
        public Data(int outcome, String myLabel, String enemyLabel, int myScore, int enemyScore, List<Line> mine, List<Line> enemy, int before, int after, boolean rated) {
            this(outcome, myLabel, enemyLabel, myScore, enemyScore, mine, enemy, before, after, rated, false);
        }
    }

    public static final String CONFIRM = "confirm";

    public static void paint(Pen p, Data d) {
        p.image("result/main", 227, 29, 1219, 878);
        String title = switch (d.outcome()) { case 1 -> "승리"; case 2 -> "무승부"; case 3 -> "무효"; default -> "패배"; };
        p.text(title, 836, 172, 82, d.outcome() == 0 ? 0xFFC9D2DE : Theme.GOLD, Face.TITLE, Canvas.CENTER);
        p.image("result/banner_blue", 393, 245, 106, 120);
        p.image("result/banner_red", 1176, 245, 106, 120);
        p.text(d.myLabel(), 600, 298, 34, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        p.text(String.valueOf(d.myScore()), 738, 298, 72, Theme.GOLD, Face.TITLE, Canvas.CENTER);
        p.text(":", 836, 296, 60, Theme.CREAM, Face.TITLE, Canvas.CENTER);
        p.text(String.valueOf(d.enemyScore()), 933, 298, 72, Theme.WHITE, Face.TITLE, Canvas.CENTER);
        p.text(d.enemyLabel(), 1070, 298, 34, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        if (d.duel()) p.text("라운드 승리", 836, 345, 18, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        table(p, d.myLabel(), d.mine(), 0, true, d.duel());
        table(p, d.enemyLabel(), d.enemy(), 583, false, d.duel());
        // my rating
        p.text("내 레이팅", 798, 743, 22, Theme.CREAM, Face.SERIF, Canvas.LEFT);
        if (d.rated()) {
            int delta = d.after() - d.before();
            String a = d.before() + " → ", b = String.valueOf(d.after()), c = "(" + (delta >= 0 ? "+" : "") + delta + ")";
            float wa = p.width(a, 44, Face.TITLE), wb = p.width(b, 44, Face.TITLE), wc = p.width(c, 32, Face.TITLE);
            float left = 836 - (wa + wb + 14 + wc) / 2;
            p.text(a, left, 777, 44, Theme.WHITE, Face.TITLE, Canvas.LEFT);
            p.text(b, left + wa, 777, 44, Theme.GOLD, Face.TITLE, Canvas.LEFT);
            p.text(c, left + wa + wb + 14, 779, 32, delta >= 0 ? Theme.TEAL : Theme.LOSS, Face.TITLE, Canvas.LEFT);
        } else p.text("레이팅 변동 없음", 836, 777, 28, Theme.MUTED, Face.SERIF, Canvas.CENTER);
        p.button(CONFIRM, "result/confirm_button", 672, 811, 329, 73, true);
        p.text("확인", 836, 846, 26, Theme.CREAM, Face.SERIF, Canvas.CENTER);
    }

    private static void table(Pen p, String label, List<Line> lines, float dx, boolean blue, boolean duel) {
        p.image(blue ? "result/team_a" : "result/team_b", 257 + dx, 362, 575, 358);
        p.text(label, 388 + dx - (blue ? 0 : 23), 398, 26, Theme.CREAM, Face.SERIF, Canvas.LEFT);
        float[] cols = {602, 681, 762};
        String[] heads = duel ? new String[]{"처치", "데스", ""} : new String[]{"골", "처치", "스니치"};
        p.text("닉네임", 450 + dx, 438, 18, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        for (int i = 0; i < 3; i++) p.text(heads[i], cols[i] + dx, 438, 18, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        for (int r = 0; r < 3 && r < lines.size(); r++) {
            var l = lines.get(r);
            float y = 464 + r * 77.5f, cy = y + 38;
            p.head(l.id(), l.skin(), 283 + dx, y + 5, 66);
            float nx = 371 + dx;
            if (l.mvp() && !duel) { Theme.icon(p, "icons/crown", nx + 45, cy - 7, 26, 22); nx += 72; }
            p.fit(l.nick(), nx, cy, 22, 560 + dx - nx, Theme.WHITE, Face.SERIF, Canvas.LEFT);
            if (duel) {
                p.text(String.valueOf(l.kills()), cols[0] + dx, cy, 22, Theme.WHITE, Face.SERIF, Canvas.CENTER);
                p.text(String.valueOf(l.deaths()), cols[1] + dx, cy, 22, Theme.WHITE, Face.SERIF, Canvas.CENTER);
                continue;
            }
            p.text(String.valueOf(l.goals()), cols[0] + dx, cy, 22, Theme.WHITE, Face.SERIF, Canvas.CENTER);
            p.text(String.valueOf(l.kills()), cols[1] + dx, cy, 22, Theme.WHITE, Face.SERIF, Canvas.CENTER);
            p.text(String.valueOf(l.snitch()), cols[2] + dx, cy, 22, Theme.WHITE, Face.SERIF, Canvas.CENTER);
        }
    }
}
