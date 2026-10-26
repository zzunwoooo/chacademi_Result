package school.magiccodex.client.kui;

import school.magiccodex.client.kui.Canvas.Face;

/** 경기 수락 팝업 (match_accept_popup; 1586×992). Six circles fill with the ready check as people accept. */
public final class AcceptView {
    private AcceptView() {}
    public static final float W = 1586, H = 992;
    public record Data(int leftSec, int accepted, int total, boolean iAccepted) {}
    public static final String ACCEPT = "accept", DECLINE = "decline";

    public static void paint(Pen p, Data d) {
        p.image("matching/panel", 273, 203, 1044, 586);
        p.text("경기를 찾았어요!", 794, 318, 40, Theme.GOLD, Face.TITLE, Canvas.CENTER);
        int total = Math.max(1, Math.min(6, d.total()));
        for (int i = 0; i < total; i++) {
            float cx = 534 + i * 103.4f + (6 - total) * 51.7f, cy = 428;
            p.image("matching/readiness_empty", cx - 34.5f, cy - 34, 69, 68);
            if (i < d.accepted()) p.image("status/ready_check", cx - 24, cy - 24, 48, 48);
        }
        p.text(d.iAccepted() ? "수락했어요 · 다른 사람을 기다리는 중" : "남은 시간 " + Math.max(0, d.leftSec()) + "초",
                794, 554, 30, d.leftSec() <= 3 && !d.iAccepted() ? 0xFFFF9C9C : Theme.CREAM, Face.SERIF, Canvas.CENTER);
        p.button(ACCEPT, "matching/accept", 337, 626, 446, 108, !d.iAccepted());
        p.text("수락", 560, 680, 38, d.iAccepted() ? 0xFF6B5B3E : Theme.DARK_TEXT, Face.TITLE, Canvas.CENTER);
        p.button(DECLINE, "matching/decline", 806, 626, 448, 108, !d.iAccepted());
        p.text("거절", 1030, 680, 38, d.iAccepted() ? Theme.MUTED : 0xFFFFD6D6, Face.TITLE, Canvas.CENTER);
    }
}
