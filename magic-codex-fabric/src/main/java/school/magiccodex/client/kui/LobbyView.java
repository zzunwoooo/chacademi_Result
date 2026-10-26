package school.magiccodex.client.kui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import school.magiccodex.client.kui.Canvas.Face;

/**
 * 결투장 로비 (owner references 2026-10-18, 1672×941): 매칭 · 진행 중인 경기 · 랭킹 · 전적, mode toggle 큐디치 / 1:1 대전.
 * Background: lobby/bg_kyudichi · bg_duel (blurred owner art), darker on the list tabs. Panels are drawn (rounded glass),
 * not images, so they stay crisp at any GUI scale. Coordinates follow the references.
 */
public final class LobbyView {
    private LobbyView() {}
    public static final float W = 1672, H = 941;
    public static final int MATCH = 0, LIVE = 1, RANKING = 2, RECORD = 3, KYUDICHI = 0, DUEL = 1;
    public static final int CARDS_PER_PAGE = 3, RECENT_ROWS = 4;

    // ------------------------------------------------------------------ data
    public record Mode(int season, int seasonLeftSec, int rating, int tier, int floor, int next, int wins, int losses, int draws,
                       int queued, boolean canStart, String note) {}
    public record Member(UUID id, String nick, String skin, boolean leader, boolean me) {}
    public record Spell(String icon, String key) {}
    /** Queue state of this player: 0 none 1 searching 2 offer 3 moving 4 playing; mode = the mode it is for. */
    public record Queue(int state, int mode, int waitedSec) {}
    public record Fighter(UUID id, String nick, String skin, int rating, int tier) {}
    public record Card(String arena, int phase, int scoreA, int scoreB, int timeLeftMs, int houseA, int houseB, String nameA, String nameB,
                       List<Fighter> a, List<Fighter> b, boolean full) {}
    public record Row(int rank, UUID id, String nick, String skin, int rating, int tier, int wins, int losses, int draws) {}
    public record Game(long endedAt, int outcome, int delta, int ratingAfter, int goals, int kills, int deaths, float damage, int myScore,
                       int theirScore, String opponent, String opponentSkin, int opponentHouse) {}
    public record Profile(UUID id, String nick, String skin, int rating, int tier, int rank, int games, int wins, int losses, int draws, int goals,
                          int kills, int deaths, int snitch, int deflectHits, float damage, float heal, List<Game> recent) {}
    /** One text field (검색): text, focused, caret blink on. */
    public record Field(String text, boolean focused, boolean caret) {}
    public record Data(int tab, int mode, UUID me, String myNick, String mySkin, Mode info, List<Member> party, boolean partyOpen, int menuFor,
                       boolean canInvite, List<Spell> spells, String specialKey, Queue queue,
                       List<Card> cards, int livePage, boolean liveLoaded,
                       List<Row> rows, Row mine, int page, int pages, int highlight, Field rankSearch, boolean ranksLoaded,
                       Profile profile, boolean profileLoaded, int recentScroll, Field recordSearch, String notice) {}

    // ------------------------------------------------------------------ hit ids
    public static final String PRACTICE = "practice", SPELLS = "spells", START = "start", PARTY_TOGGLE = "party_toggle", INVITE = "invite",
            RANK_SEARCH = "rank_search", RANK_SEARCH_GO = "rank_search_go", RECORD_SEARCH = "record_search", RECORD_SEARCH_GO = "record_search_go",
            PREV = "prev", NEXT = "next", ME_ROW = "me_row";
    public static String tabId(int t) { return "tab:" + t; }
    public static String modeId(int m) { return "mode:" + m; }
    public static String pageId(int p) { return "page:" + p; }
    public static String spectateId(String arena) { return "spectate:" + arena; }
    public static String menuId(int member) { return "menu:" + member; }
    /** Party menu actions on member i: promote / kick / leave. */
    public static String actionId(String action, int member) { return "act:" + action + ":" + member; }
    public static String rowId(int i) { return "row:" + i; }
    public static final String SPECIAL_KEY = "special_key";

    /**
     * Screen edges beyond the 1672 canvas (design px), set by the screen each frame: the menus / title / 마법 세팅 stick to the
     * screen's left edge and the season pill / right column to its right edge on wide windows (owner 2026-10-19).
     */
    static float edgeL, edgeR;
    public static void edges(float left, float right) { edgeL = Math.max(0, left); edgeR = Math.max(0, right); }
    /** The 특수 키 cap is waiting for a key press (set by the screen). */
    static boolean keyCapture;
    public static void keyCapture(boolean on) { keyCapture = on; }
    /** 티어 정보 popup open (set by the screen). */
    static boolean tierInfo;
    public static void tierInfo(boolean on) { tierInfo = on; }
    public static final String TIER_INFO = "tier_info";
    /**
     * Lowest rating of 실버 · 골드 · 플래티넘 · 다이아몬드 · 마스터: the defaults of the arena config rating.tiers and the proxy
     * kyudichi.properties tier-floors. Change them together with those if the owner changes the tiers.
     */
    public static final int[] TIER_FLOORS = {900, 1100, 1300, 1500, 1700};

    // ------------------------------------------------------------------ colours
    static final int CYAN = 0xFF2BD3EA, CYAN_DIM = 0x8022C5DD, TEXT = 0xFFF2F4F7, SOFT = 0xFFC8CED6, DIM = 0xFF8D96A3, GOLD = 0xFFF0C86A,
            WIN = 0xFF3CC8F0, LOSE = 0xFFFF5B5B, GAIN = 0xFF6EE7A0, GLASS = 0xA60C1420, GLASS_LINE = 0x2EFFFFFF, LINE = 0x1FFFFFFF;
    static final int[] TIER_COLORS = {0xFFD49A6A, 0xFFC5CBD3, 0xFFE9B949, 0xFF7FE0C8, 0xFF7FC4FF, 0xFFC59BFF, 0xFFFFD27F, 0xFFFFD27F, 0xFFFFD27F};
    static int tierColor(int t) { return TIER_COLORS[Math.clamp(t, 0, 8)]; }
    static String tierName(int t) { return t >= 6 ? "챌린저" : Theme.TIER_NAMES[Math.clamp(t, 0, 8)]; }

    // ================================================================== frame
    public static void paint(Pen p, Data d) { background(p, d.tab(), d.mode()); content(p, d); }

    /** Background art and its shading (the game draws it scaled to cover the whole screen). */
    public static void background(Pen p, int tab, int mode) {
        p.image(mode == DUEL ? "lobby/bg_duel" : "lobby/bg_kyudichi", 0, 0, W, H);
        if (tab == MATCH) {
            p.hgrad(0, 0, 760, H, 0xB0060A12, 0x00060A12);
            p.vgrad(0, 600, W, 341, 0x00060A12, 0xD0060A12);
            p.vgrad(0, 0, W, 150, 0x90060A12, 0x00060A12);
            p.hgrad(1100, 0, 572, H, 0x00060A12, 0x80060A12);
        } else {
            p.c.fill(0, 0, W, H, 0x8C060A12);
            p.vgrad(0, 0, W, 190, 0x80060A12, 0x00060A12);
            p.hgrad(0, 0, 700, H, 0x70060A12, 0x00060A12);
        }
    }

    public static void content(Pen p, Data d) {
        top(p, d);
        switch (d.tab()) {
            case MATCH -> match(p, d);
            case LIVE -> live(p, d);
            case RANKING -> ranking(p, d);
            default -> record(p, d);
        }
        if (d.notice() != null && !d.notice().isEmpty()) {
            float w = p.width(d.notice(), 19, Face.SANS) + 48;
            p.panel(836 - w / 2, 896, w, 36, 18, 0xE0101822, 0x40FF8A80);
            p.text(d.notice(), 836, 914, 19, 0xFFFFC2BC, Face.SANS, Canvas.CENTER);
        }
    }

    // ================================================================== top bar + mode toggle
    static final String[] TABS = {"매칭", "진행 중인 경기", "랭킹", "전적"};
    static final float[] TAB_X = {266, 397, 527, 618};
    static void top(Pen p, Data d) {
        p.push(-edgeL, 0, 1);
        p.text("결투장", 68, 54, 38, TEXT, Face.SERIF, Canvas.LEFT);
        p.c.fill(201, 40, 1.5f, 30, 0x50FFFFFF);
        p.c.fill(232, 77, 460, 1, 0x26FFFFFF);
        for (int t = 0; t < 4; t++) {
            float w = p.width(TABS[t], 21, Face.SANS_BOLD), x = TAB_X[t];
            boolean on = t == d.tab(), over = !on && p.over(x - w / 2 - 12, 30, w + 24, 46);
            p.text(TABS[t], x, 54, 21, on ? TEXT : over ? 0xFFE6EAF0 : 0xFFAEB6C1, on ? Face.SANS_BOLD : Face.SANS, Canvas.CENTER);
            if (on) { p.round(x - w / 2 - 6, 71, w + 12, 5, 2.5f, CYAN); p.c.fill(x - w / 2 - 6, 70, w + 12, 7, 0x2022D3EE); }
            p.hit(tabId(t), x - w / 2 - 12, 30, w + 24, 46);
        }
        // mode toggle
        float tx = 66, ty = 108, sw = 186, sh = 50;
        p.panel(tx, ty, sw * 2, sh, sh / 2, 0xA0101822, GLASS_LINE);
        String[] names = {"큐디치", "1:1 대전"};
        for (int m = 0; m < 2; m++) {
            float x = tx + m * sw;
            boolean on = m == d.mode(), over = !on && p.over(x, ty, sw, sh);
            if (on) {
                p.round(x + 3, ty + 3, sw - 6, sh - 6, (sh - 6) / 2, 0xE00E3550);
                p.outline(x + 2, ty + 2, sw - 4, sh - 4, (sh - 4) / 2, 2, CYAN);
                p.outline(x - 1, ty - 1, sw + 2, sh + 2, (sh + 2) / 2, 1.5f, 0x3022D3EE);
            }
            p.text(names[m], x + sw / 2, ty + sh / 2, 20, on ? TEXT : over ? 0xFFE6EAF0 : SOFT, on ? Face.SANS_BOLD : Face.SANS, Canvas.CENTER);
            p.hit(modeId(m), x, ty, sw, sh);
        }
        p.pop();
        // season (not on 전적: the search field is there), at the screen's right edge
        if (d.tab() != RECORD && d.info() != null) {
            String s = d.info().season() <= 0 ? "시즌 준비 중" : "시즌 " + d.info().season() + "  ·  " + left(d.info().seasonLeftSec());
            float w = p.width(s, 22, Face.SANS) + 52, r = 1626 + edgeR;
            p.panel(r - w, 24, w, 48, 24, 0xB00A111A, 0x33FFFFFF);
            p.text(s, r - w / 2, 48, 22, TEXT, Face.SANS, Canvas.CENTER);
        }
    }
    static String left(int sec) {
        if (sec <= 0) return "곧 종료";
        if (sec >= 86400) return sec / 86400 + "일 남음";
        if (sec >= 3600) return sec / 3600 + "시간 남음";
        return Math.max(1, sec / 60) + "분 남음";
    }

    // ================================================================== 매칭
    static void match(Pen p, Data d) {
        boolean duel = d.mode() == DUEL;
        p.push(-edgeL, 0, 1);
        // owner 2026-10-19: smaller title, 1:1 is "1:1 대전"
        p.text(duel ? "1:1 대전" : "큐디치", 70, 392, 78, TEXT, Face.TITLE, Canvas.LEFT);
        float linkY = 462;
        // 연습 경기장 입장 >
        float lw = p.width("연습 경기장 입장", 24, Face.SANS_BOLD);
        boolean lo = p.over(66, linkY - 24, lw + 50, 48);
        p.text("연습 경기장 입장", 74, linkY, 24, lo ? CYAN : TEXT, Face.SANS_BOLD, Canvas.LEFT);
        p.image("lobby/icon_chevron_right", 74 + lw + 10, linkY - 11, 22, 22);
        p.hit(PRACTICE, 66, linkY - 24, lw + 50, 48);

        // 마법 세팅: 70 % of the reference size, kept at the bottom-left corner (54, 900)
        p.push(54 * .3f, 900 * .3f, .7f);
        float panelY = 743, n = 6, slot = 92, gap = 113.5f, panelW = 708;
        float labelY = panelY - 24;
        boolean so = p.over(60, labelY - 20, 160, 40);
        p.text("마법 세팅", 66, labelY, 21, so ? CYAN : TEXT, Face.SANS_BOLD, Canvas.LEFT);
        p.image("lobby/icon_pencil", 66 + p.width("마법 세팅", 21, Face.SANS_BOLD) + 10, labelY - 10, 20, 20);
        p.hit(SPELLS, 60, labelY - 20, 160, 40);
        float totalW = panelW + (duel ? 0 : 160);
        p.panel(54, panelY, totalW, 150, 14, 0x8C0C1420, GLASS_LINE);
        for (int i = 0; i < n; i++) {
            float x = 70 + i * gap, y = panelY + 14;
            var s = i < d.spells().size() ? d.spells().get(i) : null;
            boolean over = p.over(x, y, slot, slot);
            p.round(x, y, slot, slot, 8, 0xCC0A121C);
            if (s != null && s.icon() != null && !s.icon().isEmpty()) p.c.texture(s.icon(), x + 2, y + 2, slot - 4, slot - 4, 1);
            p.outline(x, y, slot, slot, 8, 1.5f, over ? CYAN : 0x40FFFFFF);
            p.hit(SPELLS, x, y, slot, slot);
            String key = s == null || s.key() == null || s.key().isEmpty() ? String.valueOf(i + 1) : s.key();
            p.fit(key, x + slot / 2, y + slot + 17, 19, slot, SOFT, Face.SANS_BOLD, Canvas.CENTER);
        }
        if (!duel) {
            float fx = 54 + panelW; // F 특수 키
            p.c.fill(fx + 8, panelY + 26, 1.5f, 96, 0x33FFFFFF);
            // click the cap, then press a key: the 특수 키 (vanilla swap-hands) is set here
            boolean ko = p.over(fx + 30, panelY + 28, 62, 62);
            p.panel(fx + 36, panelY + 34, 50, 50, 8, keyCapture ? 0xE00E3550 : 0xCC0A121C, keyCapture || ko ? CYAN : 0x50FFFFFF);
            p.fit(keyCapture ? "?" : d.specialKey(), fx + 61, panelY + 59, 24, 42, keyCapture ? CYAN : TEXT, Face.SANS_BOLD, Canvas.CENTER);
            p.hit(SPECIAL_KEY, fx + 30, panelY + 28, 62, 62);
            p.image("hud/indicator_quaffle", fx + 98, panelY + 38, 42, 43);
            p.text(keyCapture ? "키를 누르세요" : "특수 키", fx + 89, panelY + 112, 20, keyCapture ? CYAN : SOFT, Face.SANS_BOLD, Canvas.CENTER);
        }
        p.pop();
        p.pop();

        // tier progress + right column: at the screen's right edge
        p.push(edgeR, 0, 1);
        var m = d.info();
        float bx = duel ? 940 : 1012, bw = duel ? 332 : 310, by = 882;
        // 티어 정보 (i) button left of the bar
        float ix = bx - 40, iy = by - 48;
        boolean io = tierInfo || p.over(ix - 20, iy - 20, 40, 40);
        p.c.arc(ix, iy, 0, 17, 0, 1, io ? 0xE00E3550 : 0xB00A111A);
        p.c.arc(ix, iy, 15.5f, 17, 0, 1, io ? CYAN : 0x80FFFFFF);
        p.text("i", ix, iy, 21, io ? CYAN : TEXT, Face.SERIF, Canvas.CENTER);
        p.hit(TIER_INFO, ix - 20, iy - 20, 40, 40);
        if (m != null) {
            int next = m.next();
            float frac = next <= m.floor() ? 1 : Math.clamp((m.rating() - m.floor()) / (float) (next - m.floor()), 0, 1);
            p.image(Theme.tier(m.tier()), bx + 2, by - 82, 50, 50);
            p.text(String.valueOf(m.floor()), bx + 27, by - 20, 19, TEXT, Face.SANS_BOLD, Canvas.CENTER);
            if (next > 0) {
                p.image(Theme.tier(Math.min(5, m.tier() + 1)), bx + bw - 50, by - 82, 50, 50);
                p.text(String.valueOf(next), bx + bw - 25, by - 18, 19, TEXT, Face.SANS_BOLD, Canvas.CENTER);
            } else p.text("최고 티어", bx + bw - 30, by - 18, 18, GOLD, Face.SANS_BOLD, Canvas.CENTER);
            p.round(bx, by, bw, 14, 7, 0xE00A1A2A);
            p.outline(bx, by, bw, 14, 7, 1.2f, 0x5022D3EE);
            if (frac > 0) { p.round(bx + 2, by + 2, Math.max(10, (bw - 4) * frac), 10, 5, CYAN); p.round(bx + 2, by + 2, Math.max(10, (bw - 4) * frac), 4, 2, 0x60FFFFFF); }
            float mx = bx + 2 + (bw - 4) * frac;
            p.c.fill(mx - 1.5f, by - 8, 3, 30, 0xFFF2C94C);
            // keep the current value clear of the floor / next labels under the icons
            p.text(String.valueOf(m.rating()), Math.clamp(mx, bx + 82, bx + bw - 82), by - 30, 20, CYAN, Face.SANS_BOLD, Canvas.CENTER);
        }

        // right column
        float profileY = duel ? 709 : 742;
        if (!duel) party(p, d);
        p.avatar(d.me(), d.mySkin(), duel ? 1370 : 1362, profileY, 34, 0x80FFFFFF);
        float nx = duel ? 1416 : 1414;
        p.fit(d.myNick(), nx, profileY - 16, 21, 200, TEXT, Face.SANS_BOLD, Canvas.LEFT);
        if (m != null) p.text(m.wins() + "승 " + m.losses() + "패 " + m.draws() + "무", nx, profileY + 19, 21, GOLD, Face.SANS_BOLD, Canvas.LEFT);

        // queue line + start button
        var q = d.queue();
        boolean mine = q != null && q.state() != 0 && q.mode() == d.mode(), other = q != null && q.state() != 0 && q.mode() != d.mode();
        float qy = duel ? 788 : 810, cx = duel ? 1482 : 1494;
        String line = mine && q.state() == 1 ? "매칭 중  " + clock(q.waitedSec()) + "  ·  대기 " + (m == null ? 0 : m.queued()) + "명" : "대기 중 " + (m == null ? 0 : m.queued()) + "명";
        float lw2 = p.width(line, 21, Face.SANS_BOLD) + 34;
        p.image("lobby/icon_people", cx - lw2 / 2 - 4, qy - 13, 26, 26);
        p.text(line, cx - lw2 / 2 + 30, qy, 21, mine ? CYAN : TEXT, Face.SANS_BOLD, Canvas.LEFT);
        float bx2 = cx - 146, by2 = duel ? 816 : 832, bw2 = 292, bh2 = 73;
        boolean enabled; String label;
        if (mine && q.state() == 1) { enabled = true; label = "매칭 취소"; }
        else if (mine && q.state() == 2) { enabled = false; label = "수락 대기 중"; }
        else if (mine) { enabled = false; label = q.state() == 4 ? "경기 중" : "이동 중"; }
        else if (other) { enabled = false; label = (q.mode() == DUEL ? "1:1 대전" : "큐디치") + " 매칭 중"; }
        else { enabled = m != null && m.canStart(); label = "매칭 시작"; }
        boolean over = p.button(START, "lobby/match_start", bx2, by2, bw2, bh2, enabled);
        p.text(label, cx, by2 + bh2 / 2, 30, enabled ? (over ? 0xFFFFFFFF : TEXT) : DIM, Face.SERIF, Canvas.CENTER);
        if (m != null && !enabled && !mine && !other && m.note() != null && !m.note().isEmpty())
            p.fit(m.note(), cx, by2 + bh2 + 15, 16, 330, DIM, Face.SANS, Canvas.CENTER);
        if (tierInfo) tierTable(p, m == null ? -1 : m.tier(), bx - 60, by - 96);
        p.pop();
    }

    /** Which rating is which tier, bottom-left corner at (x, bottom). The current tier row is lit. */
    static void tierTable(Pen p, int mine, float x, float bottom) {
        int rows = 7; float rh = 46, w = 360, h = 70 + rows * rh + 46, y = bottom - h;
        p.panel(x, y, w, h, 14, 0xF20A111A, 0x6022D3EE);
        p.text("티어 정보", x + 22, y + 32, 22, TEXT, Face.SANS_BOLD, Canvas.LEFT);
        p.text("레이팅", x + w - 22, y + 32, 17, DIM, Face.SANS, Canvas.RIGHT);
        p.c.fill(x + 16, y + 56, w - 32, 1, 0x30FFFFFF);
        int[] f = TIER_FLOORS;
        String[] ranges = {"~ " + (f[0] - 1), f[0] + " ~ " + (f[1] - 1), f[1] + " ~ " + (f[2] - 1), f[2] + " ~ " + (f[3] - 1), f[3] + " ~ " + (f[4] - 1), f[4] + " ~", f[4] + " 이상 1~3위"};
        int[] icon = {0, 1, 2, 3, 4, 5, 6};
        for (int i = 0; i < rows; i++) {
            float ry = y + 64 + i * rh, cy = ry + rh / 2;
            boolean on = i == Math.min(mine, 6);
            if (on) p.round(x + 10, ry + 2, w - 20, rh - 4, 8, 0x3022D3EE);
            p.image(Theme.tier(icon[i]), x + 22, cy - 18, 36, 36);
            p.text(i == 6 ? "챌린저" : Theme.TIER_NAMES[i], x + 70, cy, 19, on ? CYAN : tierColor(i), Face.SANS_BOLD, Canvas.LEFT);
            p.text(ranges[i], x + w - 22, cy, 18, on ? TEXT : SOFT, Face.SANS, Canvas.RIGHT);
        }
        p.fit("시즌이 끝나면 모두 처음 점수로 돌아가요.", x + w / 2, y + h - 24, 15, w - 30, DIM, Face.SANS, Canvas.CENTER);
    }
    static String clock(int sec) { return sec / 60 + ":" + String.format("%02d", sec % 60); }

    /** 파티 2/3 panel (큐디치): members with crown and … menu, + 초대. */
    static void party(Pen p, Data d) {
        var list = d.party();
        int size = Math.max(1, list.size());
        p.text("파티", 1320, 471, 23, TEXT, Face.SANS_BOLD, Canvas.LEFT);
        p.text(size + "/3", 1384, 471, 23, TEXT, Face.SANS, Canvas.LEFT);
        p.image(d.partyOpen() ? "lobby/icon_chevron_up" : "lobby/icon_chevron_down", 1586, 460, 22, 22);
        p.hit(PARTY_TOGGLE, 1310, 448, 310, 46);
        if (!d.partyOpen()) return;
        float y = 525;
        if (list.isEmpty()) list = List.of(new Member(d.me(), d.myNick(), d.mySkin(), false, true));
        for (int i = 0; i < list.size() && i < 3; i++, y += 61) {
            var m = list.get(i);
            p.avatar(m.id(), m.skin(), 1360, y, 26, 0x60FFFFFF);
            p.fit(m.nick(), 1404, y, 20, 150, TEXT, Face.SANS_BOLD, Canvas.LEFT);
            if (m.leader()) Theme.icon(p, "icons/crown", 1404 + Math.min(150, p.width(m.nick(), 20, Face.SANS_BOLD)) + 22, y - 1, 26, 20);
            if (list.size() > 1) {
                boolean over = p.over(1570, y - 18, 40, 36);
                for (int k = -1; k <= 1; k++) p.c.arc(1591 + k * 8, y, 0, 2.4f, 0, 1, over ? CYAN : SOFT);
                p.hit(menuId(i), 1570, y - 18, 40, 36);
            }
        }
        if (d.canInvite() && list.size() < 3) {
            boolean over = p.over(1330, y - 28, 200, 56);
            p.c.arc(1360, y, 24.5f, 26, 0, 1, over ? CYAN : 0xB0FFFFFF);
            p.c.fill(1350, y - 1, 20, 2, TEXT); p.c.fill(1359, y - 10, 2, 20, TEXT);
            p.text("초대", 1404, y, 20, over ? CYAN : GOLD, Face.SANS_BOLD, Canvas.LEFT);
            p.hit(INVITE, 1330, y - 28, 200, 56);
        }
        // … menu (drawn last, on top)
        int f = d.menuFor();
        if (f >= 0 && f < list.size()) {
            var target = list.get(f);
            boolean leaderMe = list.stream().anyMatch(x -> x.me() && x.leader());
            List<String[]> acts = target.me() ? List.<String[]>of(new String[]{"leave", "파티 나가기"})
                    : leaderMe ? List.of(new String[]{"promote", "파티장 넘기기"}, new String[]{"kick", "내보내기"}) : List.<String[]>of();
            if (acts.isEmpty()) return;
            float my = 525 + f * 61 + 22, mh = acts.size() * 40 + 12;
            p.panel(1452, my, 170, mh, 10, 0xF00C1420, 0x50FFFFFF);
            for (int k = 0; k < acts.size(); k++) {
                float ry = my + 6 + k * 40;
                boolean over = p.over(1456, ry, 162, 40);
                if (over) p.round(1458, ry + 2, 158, 36, 6, 0x3022D3EE);
                p.text(acts.get(k)[1], 1476, ry + 20, 18, acts.get(k)[0].equals("kick") ? 0xFFFF9C94 : TEXT, Face.SANS_BOLD, Canvas.LEFT);
                p.hit(actionId(acts.get(k)[0], f), 1456, ry, 162, 40);
            }
        }
    }

    // ================================================================== 진행 중인 경기
    static void live(Pen p, Data d) {
        boolean duel = d.mode() == DUEL;
        p.text("진행 중인 경기", 68, 280, 76, TEXT, Face.TITLE, Canvas.LEFT);
        long playing = d.cards().stream().filter(c -> c.phase() >= 2).count();
        p.text("진행 " + playing + "  ·  준비 " + (d.cards().size() - playing), 70, 353, 22, SOFT, Face.SANS, Canvas.LEFT);
        if (d.cards().isEmpty()) {
            p.panel(67, 400, 1539, 125, 12, GLASS, GLASS_LINE);
            p.text(d.liveLoaded() ? "지금 진행 중인 경기가 없어요." : "불러오는 중…", 836, 462, 24, SOFT, Face.SANS, Canvas.CENTER);
            return;
        }
        int pages = Math.max(1, (d.cards().size() + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE), page = Math.clamp(d.livePage(), 0, pages - 1);
        for (int i = 0; i < CARDS_PER_PAGE; i++) {
            int idx = page * CARDS_PER_PAGE + i;
            if (idx >= d.cards().size()) break;
            var c = d.cards().get(idx);
            float y = 400 + i * 143;
            p.panel(67, y, 1539, 125, 12, GLASS, GLASS_LINE);
            if (duel) duelCard(p, c, y); else teamCard(p, c, y);
            // arena · time
            p.c.fill(1046, y + 30, 1.2f, 66, 0x30FFFFFF);
            p.image("lobby/icon_pin", 1104, y + 32, 24, 24);
            p.fit(c.arena(), 1138, y + 44, 19, 200, SOFT, Face.SANS, Canvas.LEFT);
            Theme.icon(p, "icons_line/clock", 1116, y + 84, 22, 22);
            boolean prep = c.phase() < 2;
            String t = prep ? (duel ? "준비 중 · " + Theme.time(c.timeLeftMs()) : "–") : Theme.time2(c.timeLeftMs());
            p.text(t, 1138, y + 85, 23, TEXT, Face.SANS, Canvas.LEFT);
            // 관전 >
            float sx = 1366, sy = y + 30, sw = 217, sh = 65;
            boolean over = !c.full() && p.over(sx, sy, sw, sh);
            if (over) p.round(sx, sy, sw, sh, 14, 0x30F0C86A);
            p.outline(sx, sy, sw, sh, 14, 2, c.full() ? 0x60FFFFFF : over ? 0xFFFFE2A0 : 0xFFE9CF95);
            p.text(c.full() ? "가득 참" : "관전", sx + sw / 2 - (c.full() ? 0 : 12), sy + sh / 2, 23, c.full() ? DIM : TEXT, Face.SANS_BOLD, Canvas.CENTER);
            if (!c.full()) { p.image("lobby/icon_chevron_right", sx + sw / 2 + 30, sy + sh / 2 - 10, 20, 20); p.hit(spectateId(c.arena()), sx, sy, sw, sh); }
        }
        pager(p, pages, page, 869, true);
    }

    static void teamCard(Pen p, Card c, float y) {
        crest(p, c.houseA(), 165, y + 63, 88, true);
        p.fit(c.nameA(), 241, y + 49, 26, 220, TEXT, Face.SANS_BOLD, Canvas.LEFT);
        heads(p, c.a(), 243, y + 86);
        if (c.phase() < 2) p.text("준비 중  ·  " + Theme.time(c.timeLeftMs()), 562, y + 63, 30, SOFT, Face.SANS, Canvas.CENTER);
        else {
            p.text(String.valueOf(c.scoreA()), 534, y + 64, 50, 0xFF7FC4FF, Face.TITLE, Canvas.RIGHT);
            p.text(":", 562, y + 62, 46, TEXT, Face.TITLE, Canvas.CENTER);
            p.text(String.valueOf(c.scoreB()), 590, y + 64, 50, 0xFFF2D28A, Face.TITLE, Canvas.LEFT);
        }
        crest(p, c.houseB(), 802, y + 63, 88, false);
        p.fit(c.nameB(), 879, y + 49, 26, 160, TEXT, Face.SANS_BOLD, Canvas.LEFT);
        heads(p, c.b(), 881, y + 86);
    }
    private static void heads(Pen p, List<Fighter> f, float x, float cy) {
        for (int k = 0; k < f.size() && k < 3; k++) p.avatar(f.get(k).id(), f.get(k).skin(), x + 13 + k * 32, cy, 13, 0x50FFFFFF);
    }
    static void crest(Pen p, int house, float cx, float cy, float h, boolean blue) {
        if (house >= 0 && house <= 3) Theme.crest(p, house, cx, cy, h);
        else p.image(blue ? "result/banner_blue" : "result/banner_red", cx - h * .38f, cy - h / 2, h * .76f, h * .86f);
    }

    static void duelCard(Pen p, Card c, float y) {
        fighter(p, c.a().isEmpty() ? null : c.a().get(0), 168, 252, y);
        fighter(p, c.b().isEmpty() ? null : c.b().get(0), 697, 779, y);
        float cy = y + 64;
        String vs = c.phase() >= 2 ? c.scoreA() + "   VS   " + c.scoreB() : "VS";
        float half = p.width(vs, 26, Face.SERIF) / 2 + 16;
        p.c.fill(440, cy, Math.max(0, 548 - half - 440), 1, 0x40FFFFFF); p.c.fill(548 + half, cy, Math.max(0, 640 - 548 - half), 1, 0x40FFFFFF);
        p.text(vs, 548, cy, 26, SOFT, Face.SERIF, Canvas.CENTER);
    }
    private static void fighter(Pen p, Fighter f, float cx, float tx, float y) {
        if (f == null) return;
        p.avatar(f.id(), f.skin(), cx, y + 62, 51, 0x70FFFFFF);
        p.fit(f.nick(), tx, y + 36, 24, 170, TEXT, Face.SANS_BOLD, Canvas.LEFT);
        p.image(Theme.tier(f.tier()), tx + 2, y + 60, 34, 34);
        p.text(tierName(f.tier()), tx + 50, y + 71, 19, tierColor(f.tier()), Face.SANS, Canvas.LEFT);
        p.text(String.valueOf(f.rating()), tx + 50, y + 98, 19, SOFT, Face.SANS, Canvas.LEFT);
    }

    /** < dots > (pages ≤ 7 as dots) or < 1 2 3 4 5 > (numbers, windowed). */
    static void pager(Pen p, int pages, int page, float y, boolean dots) {
        if (pages <= 1 && dots) return;
        float span = dots ? Math.min(pages, 7) * 33 : Math.min(pages, 5) * 50;
        float left = 836 - span / 2 - 70, right = 836 + span / 2 + 70;
        boolean canPrev = page > 0, canNext = page < pages - 1;
        if (canPrev && p.over(left - 20, y - 22, 40, 44)) p.round(left - 20, y - 20, 40, 40, 20, 0x2022D3EE);
        p.c.image("lobby/icon_chevron_left", left - 11, y - 11, 22, 22, canPrev ? 1 : .3f);
        if (canPrev) p.hit(PREV, left - 20, y - 22, 40, 44);
        if (canNext && p.over(right - 20, y - 22, 40, 44)) p.round(right - 20, y - 20, 40, 40, 20, 0x2022D3EE);
        p.c.image("lobby/icon_chevron_right", right - 11, y - 11, 22, 22, canNext ? 1 : .3f);
        if (canNext) p.hit(NEXT, right - 20, y - 22, 40, 44);
        if (dots) {
            int n = Math.min(pages, 7), first = Math.clamp(page - 3, 0, pages - n);
            for (int i = 0; i < n; i++) {
                int pg = first + i; float x = 836 - span / 2 + 16.5f + i * 33;
                p.c.arc(x, y, 0, pg == page ? 7 : 5.5f, 0, 1, pg == page ? 0xFF39C6F0 : 0x60FFFFFF);
                p.hit(pageId(pg), x - 14, y - 16, 28, 32);
            }
        } else {
            int n = Math.min(pages, 5), first = Math.clamp(page - 2, 0, pages - n);
            for (int i = 0; i < n; i++) {
                int pg = first + i; float x = 836 - span / 2 + 25 + i * 50;
                boolean on = pg == page, over = !on && p.over(x - 20, y - 20, 40, 40);
                if (on) { p.c.arc(x, y, 0, 20, 0, 1, 0x40102A3A); p.c.arc(x, y, 18.5f, 20.5f, 0, 1, 0xFF39C6F0); }
                else if (over) p.c.arc(x, y, 0, 20, 0, 1, 0x20FFFFFF);
                p.text(String.valueOf(pg + 1), x, y, 20, on ? TEXT : SOFT, on ? Face.SANS_BOLD : Face.SANS, Canvas.CENTER);
                p.hit(pageId(pg), x - 20, y - 20, 40, 40);
            }
        }
    }

    // ================================================================== 랭킹
    static final float[] RANK_COLS = {153, 306, 535, 876, 1024, 1171, 1320, 1491};
    static void ranking(Pen p, Data d) {
        p.text("랭킹", 72, 238, 72, TEXT, Face.TITLE, Canvas.LEFT);
        field(p, d.rankSearch(), "플레이어 닉네임 검색", 1196, 208, 400, 50, RANK_SEARCH, RANK_SEARCH_GO);
        p.panel(77, 316, 1520, 442, 12, GLASS, GLASS_LINE);
        String[] heads = {"순위", "티어", "플레이어", "레이팅", "승", "패", "무", "승률"};
        float[] hx = {153, 338, 615, 876, 1024, 1171, 1320, 1491};
        for (int i = 0; i < heads.length; i++) p.text(heads[i], hx[i], 345, 19, SOFT, Face.SANS, Canvas.CENTER);
        p.c.fill(81, 369, 1512, 1.2f, 0x30FFFFFF);
        if (d.rows().isEmpty()) p.text(d.ranksLoaded() ? "아직 이번 시즌 랭킹이 없어요." : "불러오는 중…", 836, 560, 24, SOFT, Face.SANS, Canvas.CENTER);
        for (int i = 0; i < d.rows().size() && i < 6; i++) {
            var r = d.rows().get(i);
            float top = 370 + i * 64.7f, cy = top + 32;
            boolean hl = d.highlight() > 0 && r.rank() == d.highlight(), me = r.id().equals(d.me());
            if (hl) { p.round(82, top + 2, 1510, 61, 6, 0x2A22D3EE); p.outline(82, top + 2, 1510, 61, 6, 1.5f, CYAN); }
            else if (me) p.round(82, top + 2, 1510, 61, 6, 0x1622D3EE);
            if (i > 0 && !hl) p.c.fill(81, top, 1512, 1, LINE);
            rankRow(p, r, cy, false);
            p.hit(rowId(i), 82, top, 1510, 64);
        }
        // my row
        p.panel(77, 776, 1520, 64, 12, 0xC00C1420, 0x3AFFFFFF);
        if (d.mine() == null) p.text("이번 시즌 랭크 경기 기록이 없어요", 836, 808, 21, SOFT, Face.SANS, Canvas.CENTER);
        else { rankRow(p, d.mine(), 808, true); p.hit(ME_ROW, 77, 776, 1520, 64); }
        pager(p, Math.max(1, d.pages()), Math.max(0, d.page() - 1), 884, false);
    }
    private static void rankRow(Pen p, Row r, float cy, boolean mine) {
        if (!mine && r.rank() >= 1 && r.rank() <= 3) Theme.icon(p, "medals/rank_" + r.rank(), RANK_COLS[0], cy, 64, 50);
        else p.text(r.rank() > 0 ? String.valueOf(r.rank()) : "-", RANK_COLS[0] + 2, cy, mine ? 25 : 24, TEXT, mine ? Face.SANS_BOLD : Face.SANS, Canvas.CENTER);
        p.image(Theme.tier(r.tier()), RANK_COLS[1] - 26, cy - 26, 52, 52);
        p.text(tierName(r.tier()), 355, cy, 19, mine ? tierColor(r.tier()) : SOFT, Face.SANS, Canvas.LEFT);
        p.avatar(r.id(), r.skin(), RANK_COLS[2], cy, 25, 0x50FFFFFF);
        p.fit(r.nick(), 580, cy, 22, 200, TEXT, Face.SANS_BOLD, Canvas.LEFT);
        p.text(String.valueOf(r.rating()), RANK_COLS[3], cy, 22, TEXT, Face.SANS_BOLD, Canvas.CENTER);
        p.text(String.valueOf(r.wins()), RANK_COLS[4], cy, 21, SOFT, Face.SANS, Canvas.CENTER);
        p.text(String.valueOf(r.losses()), RANK_COLS[5], cy, 21, SOFT, Face.SANS, Canvas.CENTER);
        p.text(String.valueOf(r.draws()), RANK_COLS[6], cy, 21, SOFT, Face.SANS, Canvas.CENTER);
        int games = r.wins() + r.losses() + r.draws();
        p.text(games == 0 ? "-" : Math.round(100.0 * r.wins() / games) + "%", RANK_COLS[7], cy, 21, TEXT, Face.SANS, Canvas.CENTER);
    }

    /** Search field with the magnifier as its button. */
    static void field(Pen p, Field f, String placeholder, float x, float y, float w, float h, String id, String go) {
        boolean over = p.over(x, y, w, h);
        p.panel(x, y, w, h, 10, 0xC00A111A, f.focused() ? CYAN_DIM : over ? 0x60FFFFFF : 0x40FFFFFF);
        float tx = x + 18, cy = y + h / 2;
        if (f.text().isEmpty() && !f.focused()) p.fit(placeholder, tx, cy, 18, w - 70, DIM, Face.SANS, Canvas.LEFT);
        else {
            p.fit(f.text(), tx, cy, 19, w - 70, TEXT, Face.SANS, Canvas.LEFT);
            if (f.focused() && f.caret()) p.c.fill(tx + Math.min(w - 70, p.width(f.text(), 19, Face.SANS)) + 2, cy - 11, 2, 22, CYAN);
        }
        p.hit(id, x, y, w - 48, h);
        boolean go1 = p.over(x + w - 48, y, 48, h);
        p.c.image("lobby/icon_search", x + w - 36, cy - 11, 22, 22, go1 ? 1 : .8f);
        p.hit(go, x + w - 48, y, 48, h);
    }

    // ================================================================== 전적
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm");
    static void record(Pen p, Data d) {
        boolean duel = d.mode() == DUEL;
        p.push(edgeR, 0, 1);
        field(p, d.recordSearch(), "다른 유저의 전적을 검색하세요.", 1219, 42, 407, 46, RECORD_SEARCH, RECORD_SEARCH_GO);
        p.pop();
        var r = d.profile();
        if (r == null) {
            p.panel(68, 220, 1539, 140, 12, GLASS, GLASS_LINE);
            p.text(d.profileLoaded() ? "이번 시즌 전적이 없어요." : "불러오는 중…", 836, 290, 24, SOFT, Face.SANS, Canvas.CENTER);
            return;
        }
        p.avatar(r.id(), r.skin(), 137, 285, 68, 0x80FFFFFF);
        p.fit(r.nick(), 233, 258, 40, 260, TEXT, Face.SERIF, Canvas.LEFT);
        float tx = 233 + Math.min(260, p.width(r.nick(), 40, Face.SERIF)) + 30;
        p.image(Theme.tier(r.tier()), tx, 240, 40, 40);
        p.text(tierName(r.tier()), tx + 48, 262, 26, tierColor(r.tier()), Face.SANS, Canvas.LEFT);
        float rx = tx + 48 + p.width(tierName(r.tier()), 26, Face.SANS) + 22;
        p.text(String.valueOf(r.rating()), rx, 262, 26, TEXT, Face.SANS, Canvas.LEFT);
        if (r.rank() > 0) p.text(r.rank() + "위", rx + p.width(String.valueOf(r.rating()), 26, Face.SANS) + 20, 262, 22, CYAN, Face.SANS_BOLD, Canvas.LEFT);
        p.text(r.games() + "경기  ·  " + r.wins() + "승 " + r.losses() + "패 " + r.draws() + "무", 237, 312, 23, SOFT, Face.SANS, Canvas.LEFT);
        // stat boxes
        int g = Math.max(1, r.games());
        String kd = String.format("%.2f", r.kills() / (float) Math.max(1, r.deaths()));
        String[] names = duel ? new String[]{"킬", "데스", "K/D", "평균 피해"} : new String[]{"킬", "데스", "K/D", "평균 피해", "골", "스니치", "아군 치유", "블러저 명중"};
        String[] vals = duel ? new String[]{"" + r.kills(), "" + r.deaths(), kd, Theme.number(Math.round(r.damage() / g))}
                : new String[]{"" + r.kills(), "" + r.deaths(), kd, Theme.number(Math.round(r.damage() / g)), "" + r.goals(), "" + r.snitch(),
                Theme.number(Math.round(r.heal())), "" + r.deflectHits()};
        for (int i = 0; i < names.length; i++) {
            float x = 68 + i * 194.5f;
            p.panel(x, 381, 180, 115, 12, GLASS, GLASS_LINE);
            p.text(names[i], x + 90, 411, 18, SOFT, Face.SANS, Canvas.CENTER);
            p.fit(vals[i], x + 90, 455, 40, 160, TEXT, Face.SERIF, Canvas.CENTER);
        }
        // recent games
        p.text("최근 경기 기록", 68, 541, 23, TEXT, Face.SANS_BOLD, Canvas.LEFT);
        p.panel(68, 568, 1539, 300, 12, GLASS, GLASS_LINE);
        String[] heads = duel ? new String[]{"결과", "상대", "킬", "데스", "가한 피해", "레이팅", "경기일시"} : new String[]{"결과", "상대 팀", "골", "킬", "데스", "점수", "경기일시"};
        float[] hx = duel ? new float[]{133, 278, 620, 762, 920, 1120, 1436} : new float[]{133, 290, 664, 830, 983, 1140, 1436};
        for (int i = 0; i < heads.length; i++) p.text(heads[i], hx[i], 595, 18, SOFT, Face.SANS, Canvas.CENTER);
        p.c.fill(72, 618, 1531, 1.2f, 0x30FFFFFF);
        var list = r.recent();
        if (list.isEmpty()) { p.text("이번 시즌 경기 기록이 없어요.", 836, 740, 22, SOFT, Face.SANS, Canvas.CENTER); return; }
        int from = Math.clamp(d.recentScroll(), 0, Math.max(0, list.size() - RECENT_ROWS));
        for (int i = 0; i < RECENT_ROWS && from + i < list.size(); i++) {
            var gm = list.get(from + i);
            float top = 619 + i * 62, cy = top + 31;
            if (i > 0) p.c.fill(72, top, 1531, 1, LINE);
            String res = gm.outcome() == 1 ? "승리" : gm.outcome() == 2 ? "무승부" : "패배";
            p.text(res, 133, cy, 22, gm.outcome() == 1 ? WIN : gm.outcome() == 2 ? TEXT : LOSE, Face.SANS_BOLD, Canvas.CENTER);
            if (duel) {
                p.avatar(UUID.nameUUIDFromBytes(gm.opponent().getBytes(java.nio.charset.StandardCharsets.UTF_8)), gm.opponentSkin(), 277, cy, 23, 0x50FFFFFF);
                p.fit(gm.opponent(), 316, cy, 21, 220, TEXT, Face.SANS, Canvas.LEFT);
                p.text(String.valueOf(gm.kills()), 620, cy, 21, TEXT, Face.SANS, Canvas.CENTER);
                p.text(String.valueOf(gm.deaths()), 762, cy, 21, TEXT, Face.SANS, Canvas.CENTER);
                p.text(Theme.number(Math.round(gm.damage())), 920, cy, 21, TEXT, Face.SANS, Canvas.CENTER);
                String ra = String.valueOf(gm.ratingAfter()), de = gm.delta() > 0 ? "(+" + gm.delta() + ")" : gm.delta() < 0 ? "(−" + -gm.delta() + ")" : "(0)";
                float w1 = p.width(ra, 21, Face.SANS), w2 = p.width(de, 21, Face.SANS), sx = 1120 - (w1 + 12 + w2) / 2;
                p.text(ra, sx, cy, 21, 0xFFF2D28A, Face.SANS, Canvas.LEFT);
                p.text(de, sx + w1 + 12, cy, 21, gm.delta() > 0 ? GAIN : gm.delta() < 0 ? LOSE : SOFT, Face.SANS, Canvas.LEFT);
            } else {
                crest(p, gm.opponentHouse(), 265, cy, 40, false);
                p.fit(gm.opponent().isEmpty() ? "상대 팀" : gm.opponent(), 310, cy, 21, 260, TEXT, Face.SANS, Canvas.LEFT);
                p.text(String.valueOf(gm.goals()), 664, cy, 21, TEXT, Face.SANS, Canvas.CENTER);
                p.text(String.valueOf(gm.kills()), 830, cy, 21, TEXT, Face.SANS, Canvas.CENTER);
                p.text(String.valueOf(gm.deaths()), 983, cy, 21, TEXT, Face.SANS, Canvas.CENTER);
                p.text(gm.myScore() + " : " + gm.theirScore(), 1140, cy, 21, TEXT, Face.SANS, Canvas.CENTER);
            }
            p.text(DATE.format(Instant.ofEpochMilli(gm.endedAt()).atZone(ZoneId.systemDefault())), 1446, cy, 20, SOFT, Face.SANS, Canvas.CENTER);
        }
        if (list.size() > RECENT_ROWS)
            p.text((from + 1) + "–" + Math.min(list.size(), from + RECENT_ROWS) + " / " + list.size() + "  (휠)", 1600, 892, 16, DIM, Face.SANS, Canvas.RIGHT);
    }
}
