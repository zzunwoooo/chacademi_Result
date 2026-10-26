package school.magiccodex.client.kui;

import java.util.List;
import java.util.UUID;
import school.magiccodex.client.kui.Canvas.Face;

/**
 * 큐디치 신청 창 + 매칭 대기 중 카드 (application_layout, reference 00fe2cb4; 1672×941).
 * Icons from the owner's EXTRA_PNG (icons/*) and LINE_BUTTON_PNG (icons_line/*), placed at their reference positions.
 */
public final class ApplicationView {
    private ApplicationView() {}
    public static final float W = 1672, H = 941;

    public record Member(UUID id, String nick, String skin, int house, int rating, boolean leader) {}
    /** members: up to three (party) or just me (random team). */
    public record Data(int myRating, int wins, int losses, int baseRating, List<Member> members, boolean canStart, String note,
                       boolean queued) {}

    public static final String START = "start";

    public static void paint(Pen p, Data d) {
        p.image("application/main", 373, 59, 928, 826);
        p.text("큐디치 신청", 512, 140, 50, Theme.GOLD, Face.TITLE, Canvas.LEFT);
        // summary band
        p.image("application/summary_band", 421, 207, 831, 109);
        Theme.icon(p, "icons/bar_chart", 545.5f, 260, 39, 38);
        Theme.icon(p, "icons_line/crossed_swords_silver", 957.5f, 259.5f, 40, 39);
        p.text("내 레이팅", 605, 242, 21, Theme.CREAM, Face.SERIF, Canvas.LEFT);
        p.text(String.valueOf(d.myRating()), 606, 281, 38, Theme.GOLD, Face.SERIF, Canvas.LEFT);
        p.text("전적", 1016, 242, 21, Theme.CREAM, Face.SERIF, Canvas.LEFT);
        p.text(d.wins() + "승 / " + d.losses() + "패", 1016, 278, 32, Theme.WHITE, Face.SERIF, Canvas.LEFT);
        // member cards
        for (int i = 0; i < 3; i++) {
            float dx = i * 280;
            Member m = i < d.members().size() ? d.members().get(i) : null;
            boolean lead = m != null && m.leader();
            if (lead) p.image("application/member_leader", 422 + dx, 328, 270, 347);
            else p.image("application/member_normal", 422 + dx, 327, 271, 349);
            float cx = 557 + dx;
            if (m == null) {
                p.text("랜덤 팀원", cx, 427, 22, Theme.MUTED, Face.SERIF, Canvas.CENTER);
                p.text("매칭되면 함께해요", cx, 508, 17, Theme.MUTED, Face.SANS, Canvas.CENTER);
                continue;
            }
            if (lead) Theme.icon(p, "icons/crown", 522 + dx, 354, 26, 20);
            if (lead) p.text("파티장", cx + 23, 354, 17, Theme.GOLD, Face.SERIF, Canvas.CENTER);
            p.head(m.id(), m.skin(), 505 + dx, 376, 104);
            p.fit(m.nick(), cx, 508, 28, 220, Theme.WHITE, Face.SERIF, Canvas.CENTER);
            if (m.house() >= 0 && m.house() < 4) {
                float nameW = p.width(Theme.HOUSE_NAMES[m.house()], 22, Face.SERIF);
                float total = 52 + 12 + nameW, left = cx - total / 2;
                Theme.crest(p, m.house(), left + 26, 567, 60);
                p.text(Theme.HOUSE_NAMES[m.house()], left + 64, 567, 22, Theme.HOUSE_COLORS[m.house()], Face.SERIF, Canvas.LEFT);
            }
            p.image("application/rating_field", 449 + dx, 604, 218, 51);
            Theme.icon(p, "icons_line/bar_chart_silver", 481 + dx, 631, 25, 20);
            p.text("레이팅", 517 + dx, 630, 19, Theme.CREAM, Face.SERIF, Canvas.LEFT);
            p.text(String.valueOf(m.rating()), 650 + dx, 630, 26, Theme.GOLD, Face.SERIF, Canvas.RIGHT);
        }
        // base rating and start
        String label = "매칭 기준 레이팅", value = String.valueOf(d.baseRating());
        float lw = p.width(label, 23, Face.SERIF), vw = p.width(value, 30, Face.SERIF), left = 836 - (lw + 20 + vw) / 2;
        p.text(label, left, 707, 23, Theme.CREAM, Face.SERIF, Canvas.LEFT);
        p.text(value, left + lw + 20, 707, 30, Theme.GOLD, Face.SERIF, Canvas.LEFT);
        boolean enabled = d.canStart() && !d.queued();
        p.button(START, "application/match_start", 593, 737, 487, 90, enabled);
        p.text(d.queued() ? "매칭 중" : "매칭 시작", 836, 780, 38, enabled ? Theme.DARK_TEXT : 0xFF6B5B3E, Face.TITLE, Canvas.CENTER);
        p.text(d.note(), 836, 842, 17, Theme.MUTED, Face.SANS, Canvas.CENTER);
    }

    // ================================================================== 매칭 대기 중 (anchor: top right)
    public static final String CANCEL = "cancel";
    public record Queue(int waitedSec, String range, boolean offer) {}

    public static void queue(Pen p, Queue q, long nowMs) {
        p.image("application/queue_panel", 1307, 35, 344, 313);
        spinner(p, 1377, 101, nowMs);
        p.text("매칭 대기 중", 1427, 100, 28, Theme.WHITE, Face.SERIF, Canvas.LEFT);
        Theme.icon(p, "icons_line/clock", 1367, 173.5f, 26, 26);
        p.text("대기", 1400, 173, 21, Theme.CREAM, Face.SERIF, Canvas.LEFT);
        p.text(q.waitedSec() / 60 + ":" + String.format("%02d", q.waitedSec() % 60), 1455, 173, 30, Theme.GOLD, Face.SERIF, Canvas.LEFT);
        Theme.icon(p, "icons_line/range_slider", 1367, 218, 26, 22);
        p.text("범위", 1400, 218, 21, Theme.CREAM, Face.SERIF, Canvas.LEFT);
        p.text(q.range(), 1455, 218, 25, Theme.WHITE, Face.SERIF, Canvas.LEFT);
        p.button(CANCEL, "application/queue_cancel", 1341, 252, 267, 61, true);
        Theme.icon(p, "icons/cancel", 1443.5f, 281.5f, 25, 25);
        p.text("취소", 1495, 282, 24, 0xFFFF9C9C, Face.SERIF, Canvas.CENTER);
    }

    /** Loading ring of dots (the README asks for the waiting animation in code). */
    static void spinner(Pen p, float cx, float cy, long nowMs) {
        int head = (int) ((nowMs / 90) % 12);
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 * i / 12;
            int age = (head - i + 12) % 12;
            int alpha = Math.max(40, 255 - age * 20);
            float x = cx + (float) Math.sin(a) * 18, y = cy - (float) Math.cos(a) * 18;
            p.c.arc(x, y, 0, 2.6f, 0, 1, (alpha << 24) | 0xF2EEE3);
        }
    }

    public static String range(int waitedSec) { return waitedSec < 30 ? "±100" : waitedSec < 60 ? "±250" : "제한 없음"; }
}
