package school.magiccodex.client.kui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Sample data copied from the reference images, one entry per view. */
final class Samples {
    record View(String name, int w, int h, float mx, float my, Consumer<Pen> paint) {}
    static UUID id(int i) { return new UUID(0x51, i); }
    static final List<View> ALL = new ArrayList<>();
    static {
        ALL.add(new View("hud", 1672, 941, -1, -1, p -> {
            HudView.score(p, new HudView.Score("우리 팀", 30, Theme.BLUE, "상대 팀", 20, Theme.RED, "06:12", "", false));
            HudView.feed(p, List.of(new HudView.FeedLine(1, "준우", "", 10, Theme.WHITE, 0, 1),
                    new HudView.FeedLine(2, "루나", "이안", 3, Theme.WHITE, Theme.RED, 1),
                    new HudView.FeedLine(1, "서하", "", 10, Theme.WHITE, 0, 1)));
            HudView.keys(p, new HudView.Keys(3, 0, 0, 7_400, 10_000));
        }));
        ALL.add(new View("application", 1672, 941, -1, -1, p -> {
            ApplicationView.paint(p, new ApplicationView.Data(1250, 18, 12, 1250, List.of(
                    new ApplicationView.Member(id(1), "준우", "", 0, 1250, true),
                    new ApplicationView.Member(id(2), "루나", "", 1, 1280, false),
                    new ApplicationView.Member(id(3), "하린", "", 2, 1220, false)), true, "파티장만 시작할 수 있습니다.", false));
            ApplicationView.queue(p, new ApplicationView.Queue(42, "±250", false), 0);
        }));
        ALL.add(new View("prepare", 1672, 941, -1, -1, p -> {
            var mine = List.of(new PrepareView.Row(id(1), "준우", "", true, 0, "b", "별빛 빗자루", 1250),
                    new PrepareView.Row(id(2), "루나", "", false, 1, "b", "은하수", 1280),
                    new PrepareView.Row(id(3), "하린", "", true, 2, "b", "바이올렛 트윈테일", 1220));
            var enemy = List.of(new PrepareView.Row(id(4), "서하", "", true, 0, "b", "별빛 빗자루", 1260),
                    new PrepareView.Row(id(5), "이안", "", false, 3, "b", "은하수", 1240),
                    new PrepareView.Row(id(6), "유진", "", true, 1, "b", "바이올렛 트윈테일", 1250));
            var spells = List.of(new PrepareView.Spell("x", "가속 마법", "Q"), new PrepareView.Spell("x", "보호막", "E"), new PrepareView.Spell("x", "흑요석 구체", "R"),
                    new PrepareView.Spell("x", "바람 제어", "T"), new PrepareView.Spell("x", "섬광", "G"), new PrepareView.Spell("x", "순간이동", "V"));
            PrepareView.paint(p, new PrepareView.Data(45_000, mine, enemy, spells, false, true));
        }));
        ALL.add(new View("spellpick", 1536, 1024, 400, 470, p -> {
            var eq = new ArrayList<SpellPickView.Cell>();
            String[] n = {"가속 마법", "보호막", "흑요석 구체", "바람 제어", "섬광", "비행"};
            for (int i = 0; i < 6; i++) eq.add(new SpellPickView.Cell("s" + i, "x", n[i], ""));
            var cat = new ArrayList<SpellPickView.Cell>();
            for (int i = 0; i < 30; i++) cat.add(new SpellPickView.Cell(i < 6 ? "s" + i : "c" + i, "x", i < 6 ? n[i] : "마법 " + (i + 1), "재사용 8초 · 마나 20",
                    "짧은 시간 동안 이동 속도가 크게 오르고, 다음 공격이 적을 뒤로 밀쳐 냅니다. 빗자루를 타고 있을 때도 사용할 수 있어요."));
            SpellPickView.paint(p, new SpellPickView.Data(eq, List.of("Q", "E", "R", "", "G", "V"), 5, cat, 1, 0, -1, "", 2));
        }));
        ALL.add(new View("result", 1672, 941, -1, -1, p -> {
            var mine = List.of(new ResultView.Line(id(1), "준우", "", 3, 2, 0, true), new ResultView.Line(id(2), "루나", "", 2, 1, 1, false), new ResultView.Line(id(3), "하린", "", 1, 3, 0, false));
            var enemy = List.of(new ResultView.Line(id(4), "서하", "", 2, 1, 0, false), new ResultView.Line(id(5), "이안", "", 2, 2, 0, false), new ResultView.Line(id(6), "유진", "", 1, 1, 0, false));
            ResultView.paint(p, new ResultView.Data(1, "우리 팀", "상대 팀", 80, 50, mine, enemy, 1250, 1268, true));
        }));
        ALL.add(new View("accept", 1586, 992, -1, -1, p -> AcceptView.paint(p, new AcceptView.Data(8, 3, 6, false))));
        for (var tab : DashboardView.Tab.values()) ALL.add(new View("dashboard_" + tab.name().toLowerCase(), 1672, 941, -1, -1, p -> DashboardView.paint(p, dash(tab))));
        ALL.add(new View("dashboard_ranking_scrolled", 1672, 941, -1, -1, p -> { var d = dash(DashboardView.Tab.RANKING);
            DashboardView.paint(p, new DashboardView.Data(d.tab(), 4, d.live(), 0, d.ranking(), d.mine(), 18, d.record(), true, "", false, false, "")); }));
        String[] tabs = {"match", "live", "rank", "record"};
        for (int t = 0; t < 4; t++) for (int m = 0; m < 2; m++) {
            int tt = t, mm = m;
            ALL.add(new View("lobby_" + tabs[t] + (m == 0 ? "_q" : "_d"), 1672, 941, -1, -1, p -> LobbyView.paint(p, lobby(tt, mm))));
        }
        for (int m = 0; m < 2; m++) {
            int mm = m;
            // a 1909×1004 window: 59 design px beyond the canvas on each side
            ALL.add(new View("lobby_wide_" + (m == 0 ? "q" : "d"), 1790, 941, -1, -1, p -> {
                LobbyView.edges(59, 59); p.push(59, 0, 1);
                p.push(-59, 0, 1790 / 1672f); LobbyView.background(p, 0, mm); p.pop();
                LobbyView.content(p, lobby(0, mm)); p.pop(); LobbyView.edges(0, 0); }));
        }
        ALL.add(new View("hud_duel", 1672, 941, -1, -1, p -> HudView.score(p, new HudView.Score("준우", 1, Theme.BLUE, "서하", 0, Theme.RED, "02:36", "", false, true))));
        ALL.add(new View("result_duel", 1672, 941, -1, -1, p -> ResultView.paint(p, new ResultView.Data(1, "준우", "서하", 2, 1,
                List.of(new ResultView.Line(id(1), "준우", "", 0, 2, 0, true, 1)), List.of(new ResultView.Line(id(4), "서하", "", 0, 1, 0, false, 2)), 1250, 1264, true, true))));
        ALL.add(new View("lobby_tierinfo", 1672, 941, -1, -1, p -> { LobbyView.tierInfo(true); LobbyView.paint(p, lobby(0, 0)); LobbyView.tierInfo(false); }));
        ALL.add(new View("lobby_match_q_menu", 1672, 941, 1500, 600, p -> { var d = lobby(0, 0);
            LobbyView.paint(p, new LobbyView.Data(d.tab(), d.mode(), d.me(), d.myNick(), d.mySkin(), d.info(), d.party(), true, 1, true, d.spells(), d.specialKey(),
                    new LobbyView.Queue(1, 0, 42), d.cards(), 0, true, d.rows(), d.mine(), d.page(), d.pages(), 0, d.rankSearch(), true, d.profile(), true, 0, d.recordSearch(), "")); }));
    }
    static LobbyView.Data lobby(int tab, int mode) {
        var info = new LobbyView.Mode(1, 12 * 86400 + 3000, 1250, 2, 1100, 1300, 32, 18, 2, mode == 0 ? 14 : 6, true, "");
        var party = List.of(new LobbyView.Member(id(1), "준우", "", true, true), new LobbyView.Member(id(2), "서하", "", false, false));
        var spells = new ArrayList<LobbyView.Spell>();
        for (int i = 0; i < 6; i++) spells.add(new LobbyView.Spell("x", String.valueOf(i + 1)));
        var cards = new ArrayList<LobbyView.Card>();
        if (mode == 0) {
            cards.add(new LobbyView.Card("제 1 경기장", 2, 30, 20, 372_000, 0, 1, "준우 파티", "서하 파티", List.of(f(1, "준우", 1250, 2), f(2, "루나", 1280, 2)), List.of(f(4, "서하", 1780, 5)), false));
            cards.add(new LobbyView.Card("제 2 경기장", 2, 10, 40, 275_000, 2, 3, "유진 파티", "지안 파티", List.of(f(5, "유진", 980, 1)), List.of(f(6, "지안", 1648, 4)), false));
            cards.add(new LobbyView.Card("제 3 경기장", 0, 0, 0, 32_000, 1, 0, "도윤 파티", "하린 파티", List.of(f(7, "도윤", 650, 0)), List.of(f(8, "하린", 1250, 2)), false));
            cards.add(new LobbyView.Card("제 4 경기장", 2, 0, 0, 32_000, -1, -1, "가 파티", "나 파티", List.of(), List.of(), false));
        } else {
            cards.add(new LobbyView.Card("결투장 1", 2, 1, 0, 156_000, -1, -1, "준우", "서하", List.of(f(1, "준우", 1250, 2)), List.of(f(4, "서하", 1780, 5)), false));
            cards.add(new LobbyView.Card("결투장 2", 2, 0, 1, 108_000, -1, -1, "유진", "지안", List.of(f(5, "유진", 980, 1)), List.of(f(6, "지안", 1648, 4)), false));
            cards.add(new LobbyView.Card("결투장 3", 0, 0, 0, 32_000, -1, -1, "도윤", "하린", List.of(f(7, "도윤", 650, 0)), List.of(f(8, "하린", 1250, 2)), false));
        }
        var rows = new ArrayList<LobbyView.Row>();
        String[] n = {"준서", "하린", "도윤", "서하", "유진", "지안"}; int[] r = {1924, 1886, 1832, 1780, 1726, 1648}; int[] tier = {6, 7, 8, 5, 5, 4};
        int[][] wld = {{32, 18, 2}, {28, 14, 4}, {27, 16, 3}, {25, 17, 4}, {22, 18, 6}, {20, 19, 5}};
        for (int i = 0; i < 6; i++) rows.add(new LobbyView.Row(i + 1, id(20 + i), n[i], "", r[i], tier[i], wld[i][0], wld[i][1], wld[i][2]));
        var mine = new LobbyView.Row(47, id(1), "준우", "", 1250, 2, 32, 18, 2);
        long t0 = 1_735_384_440_000L;
        var games = mode == 0 ? List.of(
                new LobbyView.Game(t0, 1, 14, 1264, 12, 14, 6, 2410, 120, 80, "호크문 기사단", "", 0),
                new LobbyView.Game(t0 - 90_000_000L, 0, -12, 1238, 8, 7, 11, 1320, 80, 100, "청월의 비상", "", 1),
                new LobbyView.Game(t0 - 170_000_000L, 2, 0, 1250, 10, 9, 9, 1760, 100, 100, "새벽의 매", "", -1),
                new LobbyView.Game(t0 - 260_000_000L, 1, 16, 1250, 14, 11, 5, 2880, 140, 90, "흑담의 창", "", 2))
                : List.of(
                new LobbyView.Game(t0, 1, 14, 1264, 0, 8, 3, 2410, 2, 1, "서리월", "", -1),
                new LobbyView.Game(t0 - 90_000_000L, 0, -12, 1238, 0, 3, 7, 1320, 0, 2, "하린", "", -1),
                new LobbyView.Game(t0 - 170_000_000L, 1, 16, 1250, 0, 10, 4, 2880, 2, 0, "네로", "", -1),
                new LobbyView.Game(t0 - 260_000_000L, 2, 0, 1234, 0, 5, 5, 1760, 1, 1, "카엘", "", -1));
        var prof = new LobbyView.Profile(id(1), "준우", "", 1250, 2, 47, 52, 32, 18, 2, 24, 86, 54, 7, 42, 64_480, 3200, games);
        var empty = new LobbyView.Field("", false, false);
        return new LobbyView.Data(tab, mode, id(1), "준우", "", info, party, true, -1, true, spells, "F", new LobbyView.Queue(0, 0, 0),
                cards, 0, true, rows, mine, 1, 9, 0, empty, true, prof, true, 0, empty, "");
    }
    static LobbyView.Fighter f(int i, String n, int rating, int tier) { return new LobbyView.Fighter(id(i), n, "", rating, tier); }
    static DashboardView.Who who(int i, String n) { return new DashboardView.Who(id(i), n, ""); }
    static DashboardView.Data dash(DashboardView.Tab tab) {
        var live = List.of(
                new DashboardView.Live("별빛 경기장", 2, 30, 20, 372_000, 3, List.of(who(1, "준우"), who(2, "루나"), who(3, "하린")), List.of(who(4, "서하"), who(5, "이안"), who(6, "유진"))),
                new DashboardView.Live("하늘 경기장", 2, 50, 40, 228_000, 3, List.of(who(2, "루나"), who(3, "하린"), who(1, "준우")), List.of(who(5, "이안"), who(4, "서하"), who(6, "유진"))),
                new DashboardView.Live("새벽 경기장", 0, 0, 0, 32_000, 0, List.of(who(3, "하린"), who(1, "준우"), who(2, "루나")), List.of(who(6, "유진"), who(5, "이안"), who(4, "서하"))));
        var rank = new ArrayList<DashboardView.Rank>();
        String[] n = {"도윤", "서하", "루나", "이안", "유진", "하린", "지우"}; int[] r = {1920, 1884, 1812, 1764, 1720, 1688, 1624}; int[] wr = {72, 69, 67, 64, 62, 60, 58};
        for (int i = 0; i < 7; i++) rank.add(new DashboardView.Rank(i + 1, id(10 + i), n[i], "", r[i], i < 3 ? 6 + i : i < 5 ? 5 : 4, 100, wr[i], 100 - wr[i]));
        for (int i = 7; i < 40; i++) rank.add(new DashboardView.Rank(i + 1, id(10 + i), "플레이어" + (i + 1), "", 1600 - i * 5, 3, 50, 25, 25));
        var mine = new DashboardView.Rank(47, id(1), "준우", "", 1342, 3, 50, 27, 21);
        int[][] g = {{1, 80, 50, 3, 5, 2}, {0, 40, 60, 1, 4, 5}, {1, 70, 30, 4, 6, 3}, {0, 20, 50, 0, 2, 6}, {1, 90, 70, 5, 8, 4}, {0, 30, 60, 1, 3, 7}, {1, 60, 40, 2, 5, 3}, {2, 50, 50, 2, 4, 4}, {1, 80, 60, 3, 6, 3}, {0, 30, 40, 1, 2, 5}};
        var recent = new ArrayList<DashboardView.Recent>();
        for (var x : g) recent.add(new DashboardView.Recent(x[0], 0, x[1], x[2], x[3], x[4], x[5]));
        var rec = new DashboardView.PlayerRec(id(1), "준우", "", 1342, 3, 47, 50, 27, 21, 2, 128, 96, 72, 24_580, 8_240, 9, 147, 12, recent);
        return new DashboardView.Data(tab, 4, live, 0, rank, mine, 0, rec, true, "", false, false, "");
    }
}
