package school.magiccodex.client.kui;

import java.util.List;
import java.util.UUID;
import school.magiccodex.client.kui.Canvas.Face;

/**
 * 큐디치 대시보드 (dashboard_*_layout, references e404802e / caeb9f6b / 60b1c202; 1672×941).
 * Each tab has its own whole-window frame from the owner (dashboard/frame_live · frame_ranking · frame_record, canvas
 * coordinates, tab row baked with that tab selected); content is drawn at the reference coordinates.
 * frame_ranking's baked scroll thumb was cut out into dashboard/rank_thumb so it can move. Close X: dashboard/close_button.
 */
public final class DashboardView {
    private DashboardView() {}
    public static final float W = 1672, H = 941;

    public enum Tab { LIVE, RANKING, RECORD }
    public record Who(UUID id, String nick, String skin) {}
    public record Live(String arena, int phase, int scoreA, int scoreB, int timeLeftMs, int spectators, List<Who> teamA, List<Who> teamB) {}
    public record Rank(int rank, UUID id, String nick, String skin, int rating, int tier, int games, int wins, int losses) {}
    public record Recent(int outcome, int team, int scoreA, int scoreB, int goals, int kills, int deaths) {}
    public record PlayerRec(UUID id, String nick, String skin, int rating, int tier, int rank, int games, int wins, int losses, int draws,
                         int goals, int kills, int deaths, float damage, float heal, int snitch, int deflectHits, int mvp, List<Recent> recent) {}
    public record Data(Tab tab, int waitingParties, List<Live> live, int liveScroll, List<Rank> ranking, Rank mine, int rankScroll,
                       PlayerRec record, boolean recordLoaded, String search, boolean searchFocused, boolean caret, String notice) {}

    public static final String CLOSE = "close", SEARCH = "search", SEARCH_FIELD = "search_field";
    public static String tabId(Tab t) { return "tab:" + t.ordinal(); }
    public static String spectateId(String arena) { return "spectate:" + arena; }
    public static final int LIVE_VISIBLE = 3;

    /** Per tab: frame, tab rectangles, title / close positions — each from that tab's layout. */
    record Chrome(String frame, float[][] tabs, float titleY, float titleSize, float[] close, float tabText) {}
    static final Chrome LIVE_CHROME = new Chrome("dashboard/frame_live",
            new float[][]{{325, 255, 335, 65}, {668, 255, 337, 65}, {1015, 255, 333, 65}}, 172, 80, new float[]{1466, 120, 47, 45}, 28);
    static final Chrome RANKING_CHROME = new Chrome("dashboard/frame_ranking",
            new float[][]{{322, 178, 331, 55}, {663, 178, 344, 55}, {1016, 178, 332, 55}}, 128, 72, new float[]{1409, 89, 47, 45}, 25);
    static final Chrome RECORD_CHROME = new Chrome("dashboard/frame_record",
            new float[][]{{496, 128, 221, 43}, {720, 128, 233, 43}, {955, 128, 227, 43}}, 90, 56, new float[]{1438, 81, 47, 45}, 20);
    /** dashboard/close_button (X art 31 px inside 96) drawn at the old X size (34 px) around (cx, cy); hit = the X. */
    static void close(Pen p, float cx, float cy) {
        float k = 34f / 31f, hx = cx - 24, hy = cy - 24;
        boolean over = p.over(hx, hy, 48, 48);
        String part = over && CLOSE.equals(p.pressed) ? "dashboard/close_button_pressed" : over ? "dashboard/close_button_hover" : "dashboard/close_button";
        p.image(part, cx - 49.5f * k, cy - 51.5f * k, 96 * k, 96 * k);
        p.hit(CLOSE, hx, hy, 48, 48);
    }
    static Chrome chromeOf(Tab t) { return t == Tab.LIVE ? LIVE_CHROME : t == Tab.RANKING ? RANKING_CHROME : RECORD_CHROME; }

    public static void paint(Pen p, Data d) {
        Chrome ch = chromeOf(d.tab());
        p.image(ch.frame(), 0, 0, W, H);
        p.text("큐디치", 836, ch.titleY(), ch.titleSize(), Theme.GOLD, Face.TITLE, Canvas.CENTER);
        close(p, ch.close()[0] + 21, ch.close()[1] + 21.5f);
        if (d.tab() == Tab.LIVE) {
            // 대기 중인 파티 pill (reference 1235..1470 × 166..214; the pill art is longer, so it is a little wider and lower)
            p.image("dashboard/waiting_party_pill", 1168, 168, 297, 44);
            Theme.icon(p, "icons/party", 1205, 190, 34, 24);
            p.text("대기 중인 파티", 1232, 190, 21, Theme.CREAM, Face.SERIF, Canvas.LEFT);
            p.text(String.valueOf(d.waitingParties()), 1432, 190, 30, Theme.WHITE, Face.TITLE, Canvas.CENTER);
        }
        String[] names = {"경기 현황", "랭킹", "플레이어 기록"};
        for (Tab t : Tab.values()) {
            int i = t.ordinal(); boolean on = t == d.tab();
            float[] r = ch.tabs()[i];
            p.hit(tabId(t), r[0], r[1], r[2], r[3]);
            boolean over = !on && p.over(r[0], r[1], r[2], r[3]);
            p.text(names[i], r[0] + r[2] / 2, r[1] + r[3] / 2, ch.tabText(), on ? Theme.DARK_TEXT : over ? Theme.GOLD : Theme.CREAM, Face.SERIF, Canvas.CENTER);
        }
        switch (d.tab()) {
            case LIVE -> live(p, d);
            case RANKING -> ranking(p, d);
            case RECORD -> record(p, d);
        }
        if (d.notice() != null && !d.notice().isEmpty()) p.text(d.notice(), 836, d.tab() == Tab.LIVE ? 325 : 16, 17, 0xFFFFB0A8, Face.SANS, Canvas.CENTER);
    }

    // ================================================================== 경기 현황
    static void live(Pen p, Data d) {
        if (d.live().isEmpty()) { p.text("지금 진행 중인 경기가 없어요.", 836, 600, 26, Theme.MUTED, Face.SERIF, Canvas.CENTER); return; }
        for (int i = 0; i < LIVE_VISIBLE; i++) {
            int idx = d.liveScroll() + i;
            if (idx >= d.live().size()) break;
            var l = d.live().get(idx);
            float ct = 335 + i * 178;
            p.image("dashboard/match_card", 149, ct, 1372, 174);
            side(p, l.teamA(), 183, 290, ct, true);
            side(p, l.teamB(), 803, 908, ct, false);
            boolean prep = l.phase() == 0 || l.phase() == 1;
            if (prep) {
                header(p, "준비 중", ct + 45);
                p.c.fill(518, ct + 97, 33, 1.5f, 0x99E8D3A0); p.c.fill(730, ct + 97, 33, 1.5f, 0x99E8D3A0);
                Theme.icon(p, "icons/hourglass", 588, ct + 97.5f, 22, 29);
                p.text("준비 " + Theme.time(l.timeLeftMs()), 613, ct + 98, 24, Theme.CREAM, Face.SERIF, Canvas.LEFT);
            } else {
                header(p, l.phase() == 3 ? "골!" : "진행 중", ct + 45);
                p.text(String.valueOf(l.scoreA()), 580, ct + 86, 56, Theme.GOLD, Face.TITLE, Canvas.CENTER);
                p.text(":", 642, ct + 86, 56, Theme.GOLD, Face.TITLE, Canvas.CENTER);
                p.text(String.valueOf(l.scoreB()), 704, ct + 86, 56, 0xFFDDE3F5, Face.TITLE, Canvas.CENTER);
                Theme.icon(p, "icons_line/clock", 564, ct + 136, 25, 25);
                p.text("남은 시간", 586, ct + 137, 20, Theme.CREAM, Face.SERIF, Canvas.LEFT);
                p.text(Theme.time2(l.timeLeftMs()), 676, ct + 137, 24, Theme.WHITE, Face.SERIF, Canvas.LEFT);
            }
            p.text("경기장", 1189, ct + 47, 15, Theme.GOLD, Face.SERIF, Canvas.CENTER);
            Theme.icon(p, "icons/arena", 1189.5f, ct + 85, 55, 40);
            p.fit(l.arena(), 1189, ct + 129, 21, 150, Theme.WHITE, Face.SERIF, Canvas.CENTER);
            boolean full = l.spectators() >= 20;
            p.button(spectateId(l.arena()), "dashboard/spectate_button", 1297, ct + 51, 195, 82, !full);
            p.text(full ? "가득 참" : "관전", 1394, ct + 92, 28, full ? Theme.MUTED : Theme.CREAM, Face.TITLE, Canvas.CENTER);
        }
        if (d.live().size() > LIVE_VISIBLE)
            p.text((d.liveScroll() + 1) + "–" + Math.min(d.live().size(), d.liveScroll() + LIVE_VISIBLE) + " / " + d.live().size() + "  (휠)", 1500, 880, 15, Theme.MUTED, Face.SANS, Canvas.RIGHT);
    }

    /** "◇ 진행 중 ◇" with short lines either side (the diamonds and lines are baked into match_card). */
    private static void header(Pen p, String text, float y) { p.text(text, 642, y, 19, Theme.GOLD, Face.SERIF, Canvas.CENTER); }

    private static void side(Pen p, List<Who> team, float bannerX, float nameX, float ct, boolean blue) {
        p.image(blue ? "result/banner_blue" : "result/banner_red", bannerX, ct + 28, 80, 91);
        String title = team.isEmpty() ? "" : team.get(0).nick() + " 파티";
        p.fit(title, nameX, ct + 48, 27, 200, Theme.WHITE, Face.SERIF, Canvas.LEFT);
        for (int k = 0; k < team.size() && k < 3; k++) p.head(team.get(k).id(), team.get(k).skin(), nameX + 2 + k * 63, ct + 80, 52);
    }

    // ================================================================== 랭킹 (ranking reference coordinates)
    public static final int RANK_ROWS_TOP = 3, RANK_ROWS_NORMAL = 4, RANK_ROWS_SCROLLED = 8;
    static void ranking(Pen p, Data d) {
        float[] cols = {339, 497, 651, 715, 1068, 1304};
        p.text("순위", 311, 272, 20, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        p.text("티어", 497, 272, 20, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        p.text("닉네임", 809, 272, 20, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        p.text("레이팅", 1068, 272, 20, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        p.text("승률", 1300, 272, 20, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        var rows = d.ranking();
        if (rows.isEmpty()) p.text("아직 랭킹이 없어요.", 836, 520, 28, Theme.MUTED, Face.SERIF, Canvas.CENTER);
        // top of the list: 1·2·3위 in the tall gold rows + four normal rows; scrolled: eight even normal rows
        boolean top = d.rankScroll() == 0;
        int slots = top ? RANK_ROWS_TOP + RANK_ROWS_NORMAL : RANK_ROWS_SCROLLED;
        for (int i = 0; i < slots && d.rankScroll() + i < rows.size(); i++) {
            var r = rows.get(d.rankScroll() + i);
            if (top && i < RANK_ROWS_TOP) rankRow(p, r, 291 + i * 85.5f, r.rank() >= 1 && r.rank() <= 3, cols);
            else rankRow(p, r, top ? 550 + (i - RANK_ROWS_TOP) * 60 : 292 + i * 61.5f, false, cols);
        }
        // scroll bar: track baked into the frame (287..770), thumb = dashboard/rank_thumb (art rows 1..120 of 124)
        int visible = RANK_ROWS_TOP + RANK_ROWS_NORMAL, maxScroll = maxRankScroll(rows.size());
        float track = 483, len = maxScroll > 0 ? Math.max(40, track * visible / rows.size()) : track;
        float at = maxScroll > 0 ? (track - len) * d.rankScroll() / maxScroll : 0;
        float k = len / 119f;
        p.image("dashboard/rank_thumb", 1431, 287 + Math.min(at, track - len) - k, 21, 124 * k);
        // my row (inside the list panel, under the rows)
        p.image("dashboard/my_rank_row", 222, 795, 1228, 84.5f);
        var m = d.mine();
        p.text("내 순위", 328, 837, 24, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        if (m == null) { p.text("아직 랭크 경기 기록이 없어요", 836, 837, 22, Theme.MUTED, Face.SERIF, Canvas.CENTER); return; }
        p.text(m.rank() > 0 ? m.rank() + "위" : "-", 492, 837, 34, Theme.TEAL, Face.TITLE, Canvas.CENTER);
        p.image(Theme.tier(m.tier()), 612, 800, 74, 74);
        p.head(m.id(), m.skin(), 726, 809, 56);
        p.fit(m.nick(), 803, 837, 24, 200, Theme.WHITE, Face.SERIF, Canvas.LEFT);
        p.text(String.valueOf(m.rating()), 1068, 837, 30, Theme.TEAL, Face.TITLE, Canvas.CENTER);
        p.text(rate(m.wins(), m.games()), 1310, 837, 30, Theme.TEAL, Face.TITLE, Canvas.CENTER);
    }

    public static int maxRankScroll(int rows) { return rows <= RANK_ROWS_TOP + RANK_ROWS_NORMAL ? 0 : Math.max(1, rows - RANK_ROWS_SCROLLED); }

    private static void rankRow(Pen p, Rank r, float y, boolean gold, float[] cols) {
        float h = gold ? 89 : 62, cy = y + h / 2;
        p.image(gold ? "dashboard/top_rank_row" : "dashboard/normal_rank_row", 223, y, 1205, h);
        if (gold) Theme.icon(p, "medals/rank_" + r.rank(), 338, cy, 116, 80);
        else p.text(String.valueOf(r.rank()), cols[0], cy, 28, Theme.GOLD, Face.TITLE, Canvas.CENTER);
        float ts = gold ? 80 : 58;
        p.image(Theme.tier(r.tier()), cols[1] - ts / 2, cy - ts / 2, ts, ts);
        float hs = gold ? 58 : 50;
        p.head(r.id(), r.skin(), cols[2] - hs / 2, cy - hs / 2, hs);
        p.fit(r.nick(), cols[3], cy, gold ? 24 : 22, 300, Theme.WHITE, Face.SERIF, Canvas.LEFT);
        p.text(String.valueOf(r.rating()), cols[4], cy, gold ? 30 : 24, gold ? Theme.GOLD : Theme.WHITE, Face.TITLE, Canvas.CENTER);
        p.text(rate(r.wins(), r.games()), cols[5], cy, gold ? 30 : 24, gold ? Theme.GOLD : Theme.WHITE, Face.TITLE, Canvas.CENTER);
    }
    static String rate(int wins, int games) { return games <= 0 ? "-" : Math.round(100.0 * wins / games) + "%"; }

    // ================================================================== 플레이어 기록 (record reference coordinates)
    static void record(Pen p, Data d) {
        Theme.icon(p, "icons/search", 238, 213, 26, 26);
        p.hit(SEARCH_FIELD, 208, 189, 472, 49);
        String s = d.search();
        if (s.isEmpty() && !d.searchFocused()) p.text("닉네임 검색", 262, 213, 19, Theme.MUTED, Face.SERIF, Canvas.LEFT);
        else {
            p.fit(s, 262, 213, 21, 400, Theme.WHITE, Face.SERIF, Canvas.LEFT);
            if (d.searchFocused() && d.caret()) { float cx = 262 + Math.min(400, p.width(s, 21, Face.SERIF)) + 2; p.c.fill(cx, 201, 2, 24, 0xFFF2D28A); }
        }
        if (p.over(686, 193, 118, 41)) p.image("dashboard/search_button_hover", 680, 187, 131, 53);
        p.hit(SEARCH, 686, 193, 118, 41);
        p.text("검색", 745, 213, 21, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        var r = d.record();
        float[] sx = {705, 893, 1088, 1290}; float[] sw = {176, 182, 189, 194};
        p.text("최근 10경기", 270, 498, 22, Theme.CREAM, Face.SERIF, Canvas.LEFT);
        String[] heads = {"결과", "점수", "내 골", "킬", "데스"};
        float[] hc = {325, 578, 843, 1089, 1345};
        for (int i = 0; i < 5; i++) p.text(heads[i], hc[i], 541, 19, Theme.CREAM, Face.SERIF, Canvas.CENTER);
        if (r == null) {
            p.text(d.recordLoaded() ? "기록이 없어요." : "불러오는 중…", 445, 360, 24, Theme.MUTED, Face.SERIF, Canvas.CENTER);
            return;
        }
        // profile
        p.head(r.id(), r.skin(), 219, 268, 112);
        p.fit(r.nick(), 362, 281, 34, 310, Theme.WHITE, Face.TITLE, Canvas.LEFT);
        p.image(Theme.tier(r.tier()), 368, 307, 84, 84); // owner 2026-10-17: inside the profile box (between the two lines)
        p.text(Theme.TIER_NAMES[Math.clamp(r.tier(), 0, 8)] + (r.rank() > 0 ? "  ·  " + r.rank() + "위" : ""), 490, 333, 22, Theme.CREAM, Face.SERIF, Canvas.LEFT);
        p.text(String.valueOf(r.rating()), 490, 369, 38, Theme.WHITE, Face.TITLE, Canvas.LEFT);
        String[] labels = {"경기", "승", "패", "무", "승률"};
        String[] values = {"" + r.games(), "" + r.wins(), "" + r.losses(), "" + r.draws(), rate(r.wins(), r.games())};
        float[] lx = {258, 351, 446, 537, 630};
        for (int i = 0; i < 5; i++) {
            p.text(labels[i], lx[i], 416, 20, Theme.CREAM, Face.SERIF, Canvas.CENTER);
            p.text(values[i], lx[i], 446, 30, Theme.WHITE, Face.TITLE, Canvas.CENTER);
        }
        // stats
        String[] icons = {"goal", "kill", "death", "damage", "heal", "snitch", "bludger", "mvp"};
        String[] names = {"골", "킬", "데스", "딜량", "파티원 힐량", "스니치 포획", "블러저 쳐내기 명중", "MVP 횟수"};
        String[] vals = {"" + r.goals(), "" + r.kills(), "" + r.deaths(), Theme.number(Math.round(r.damage())), Theme.number(Math.round(r.heal())),
                "" + r.snitch(), "" + r.deflectHits(), "" + r.mvp()};
        for (int i = 0; i < 8; i++) {
            float x = sx[i % 4], y = i < 4 ? 241 : 358, w = sw[i % 4];
            int[] size = Parts.size("statistics/" + icons[i]);
            float ih = 58, iw = ih * size[0] / size[1];
            p.image("statistics/" + icons[i], x + 50 - iw / 2, y + 58 - ih / 2, iw, ih);
            float cx = x + 66 + (w - 66) / 2;
            p.fit(names[i], cx, y + 37, 16, w - 70, Theme.CREAM, Face.SERIF, Canvas.CENTER);
            p.fit(vals[i], cx, y + 74, 30, w - 96, Theme.WHITE, Face.TITLE, Canvas.CENTER);
        }
        // recent games
        for (int i = 0; i < r.recent().size() && i < 10; i++) {
            var g = r.recent().get(i);
            float cy = 574 + i * 29.6f;
            String badge = g.outcome() == 1 ? "win_badge" : g.outcome() == 2 ? "draw_badge" : "loss_badge";
            p.image("dashboard/" + badge, 275, cy - 14, 102, 28);
            p.text(g.outcome() == 1 ? "승리" : g.outcome() == 2 ? "무승부" : "패배", 326, cy, 16, Theme.WHITE, Face.SERIF, Canvas.CENTER);
            int mine = g.team() == 0 ? g.scoreA() : g.scoreB(), them = g.team() == 0 ? g.scoreB() : g.scoreA();
            p.text(mine + " : " + them, 578, cy, 19, Theme.WHITE, Face.SERIF, Canvas.CENTER);
            p.text(String.valueOf(g.goals()), 843, cy, 19, Theme.WHITE, Face.SERIF, Canvas.CENTER);
            p.text(String.valueOf(g.kills()), 1089, cy, 19, Theme.WHITE, Face.SERIF, Canvas.CENTER);
            p.text(String.valueOf(g.deaths()), 1345, cy, 19, Theme.WHITE, Face.SERIF, Canvas.CENTER);
        }
        if (r.recent().isEmpty()) p.text("아직 경기 기록이 없어요.", 836, 700, 22, Theme.MUTED, Face.SERIF, Canvas.CENTER);
    }
}
