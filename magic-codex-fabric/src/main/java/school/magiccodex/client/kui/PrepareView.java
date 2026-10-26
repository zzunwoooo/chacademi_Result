package school.magiccodex.client.kui;

import java.util.List;
import java.util.UUID;
import school.magiccodex.client.kui.Canvas.Face;

/**
 * 경기 준비 화면 (prepare_layout, reference 58ad6006; 1672×941). Online dots are replaced by 준비 완료 / 세팅 중 (README).
 * The centre panel shows my six match spells; clicking it opens the spell picker.
 */
public final class PrepareView {
    private PrepareView() {}
    public static final float W = 1672, H = 941;

    public record Row(UUID id, String nick, String skin, boolean ready, int house, String broomId, String broomName, int rating) {}
    public record Spell(String icon, String name, String key) {}
    public record Data(int leftMs, List<Row> mine, List<Row> enemy, List<Spell> spells, boolean ready, boolean canReady) {}

    public static final String READY = "ready", SPELLS = "spells";

    public static void paint(Pen p, Data d) {
        p.image("prepare/header", 577, 15, 517, 187);
        p.text("경기 준비", 836, 112, 54, Theme.GOLD, Face.TITLE, Canvas.CENTER);
        p.text("경기가 곧 시작됩니다", 836, 167, 20, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        p.image("prepare/countdown", 703, 199, 265, 103);
        p.text(Theme.time(d.leftMs()), 836, 250, 50, Theme.GOLD, Face.TITLE, Canvas.CENTER);

        team(p, "우리 팀", 23, 278, 619, 529, d.mine(), 0);
        team(p, "상대 팀", 1030, 278, 620, 530, d.enemy(), 1007);

        // spell panel
        p.image("prepare/spell_panel", 635, 319, 404, 449);
        p.text("마법 세팅", 808, 381, 30, Theme.CREAM, Face.TITLE, Canvas.LEFT);
        boolean overPanel = p.over(650, 420, 374, 330);
        p.hit(SPELLS, 650, 420, 374, 330);
        for (int i = 0; i < 6; i++) {
            float x = 666 + (i % 3) * 117, y = 432 + (i / 3) * 153;
            p.image(overPanel ? "prepare/spell_slot_selected" : "prepare/spell_slot", x, y, 109, 112);
            Spell s = i < d.spells().size() ? d.spells().get(i) : null;
            if (s == null || s.icon() == null) continue;
            p.c.texture(s.icon(), x + 14, y + 14, 81, 81, 1);
            p.fit(s.name(), x + 54.5f, y + 124, 17, 112, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        }
        // ready button
        p.button(READY, d.ready() ? "prepare/ready_button_pressed" : "prepare/ready_button", 622, 794, 428, 117, d.canReady());
        p.text(d.ready() ? "준비 취소" : "준비 완료", 874, 848, 42, d.canReady() ? Theme.CREAM : Theme.MUTED, Face.TITLE, Canvas.CENTER);
    }

    private static void team(Pen p, String title, float x, float y, float w, float h, List<Row> rows, float dx) {
        p.image(dx == 0 ? "prepare/team_left" : "prepare/team_right", x, y, w, h);
        p.text(title, 138 + dx, 343, 36, Theme.CREAM, Face.TITLE, Canvas.LEFT);
        for (int i = 0; i < 3; i++) {
            float ry = 396 + i * 132, rx = 52 + dx;
            Row r = i < rows.size() ? rows.get(i) : null;
            if (r == null) continue;
            p.head(r.id(), r.skin(), rx + 21, ry + 14, 82);
            p.fit(r.nick(), rx + 126, ry + 42, 26, 100, Theme.WHITE, Face.SERIF, Canvas.LEFT);
            p.image(r.ready() ? "status/ready_check" : "status/setting_hourglass", rx + 118, ry + 63, r.ready() ? 30 : 26, r.ready() ? 30 : 30);
            p.text(r.ready() ? "준비 완료" : "세팅 중", rx + 146, ry + 78, 17, r.ready() ? Theme.GREEN : 0xFFE9C46A, Face.SERIF, Canvas.LEFT);
            Theme.crest(p, r.house(), rx + 258, ry + 59, 64);
            if (r.broomId() != null && !r.broomId().isEmpty()) p.c.broom(r.broomId(), rx + 373, ry + 36, 130, 40);
            p.fit(r.broomName(), rx + 376, ry + 82, 17, 150, Theme.CREAM, Face.SERIF, Canvas.CENTER);
            Theme.icon(p, "icons/crossed_swords", rx + 506.5f, ry + 39, 22, 24);
            p.text(String.valueOf(r.rating()), rx + 506, ry + 72, 24, Theme.GOLD, Face.SERIF, Canvas.CENTER);
        }
    }
}
