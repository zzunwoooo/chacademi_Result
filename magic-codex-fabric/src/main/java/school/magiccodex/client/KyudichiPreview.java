package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import school.magiccodex.protocol.KyudichiProtocol;
import school.magiccodex.protocol.KyudichiQueueProtocol;

/**
 * 큐디치 화면 미리보기 — 서버 /테스트 큐디치 화면 (magiccodex:preview)으로 열고, 가짜 데이터로 띄워 보는 UI 테스트용 (아무것도 서버로 안 보냄).
 * 로비(결투장) · 준비 · 경기 · 결투(1:1 경기 HUD) · 결과 · 수락 · 매칭 · 끄기. 미리보기 중에는 매칭/수락/준비 버튼이 화면 안에서만 반응한다.
 */
final class KyudichiPreview {
    private KyudichiPreview() {}
    enum Mode { NONE, PREPARE, MATCH, QUEUE }
    private static Mode mode = Mode.NONE;
    private static long until;
    private static final UUID[] FAKE = new UUID[5];
    private static final String[] NAMES = {"루나", "하린", "서하", "이안", "유진"};
    private static boolean ready, duelMatch;
    private static int fakeQueueMode;
    private static final boolean[] fakeReady = {true, false, true, false, false};

    static boolean active() { return mode != Mode.NONE; }

    static void initialize() {
        for (int i = 0; i < FAKE.length; i++) FAKE[i] = new UUID(0x6b79756469636869L, i + 1);
        ClientTickEvents.END_CLIENT_TICK.register(KyudichiPreview::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> mode = Mode.NONE);
    }

    /**
     * Server preview (/테스트 큐디치 화면 → magiccodex:preview "kyudichi &lt;값&gt;").
     * lobby · duel_lobby · apply · duel · prepare · match · result · accept · matching · dashboard · off. False when unknown.
     */
    static boolean show(String value) {
        switch (value) {
            case "lobby", "apply", "dashboard" -> later(() -> lobby(0));
            case "duel_lobby" -> later(() -> lobby(1));
            case "duel" -> later(KyudichiPreview::duel);
            case "prepare" -> later(KyudichiPreview::prepare);
            case "match" -> later(KyudichiPreview::match);
            case "result" -> later(KyudichiPreview::result);
            case "accept" -> later(() -> queue(KyudichiQueueProtocol.S_OFFER));
            case "matching" -> later(() -> queue(KyudichiQueueProtocol.S_SEARCHING));
            case "off" -> stop();
            default -> { return false; }
        }
        return true;
    }

    private static int later(Runnable r) { var c = MinecraftClient.getInstance(); c.send(() -> c.send(r)); return 1; }

    private static UUID me() { var p = MinecraftClient.getInstance().player; return p == null ? new UUID(0, 0) : p.getUuid(); }
    /** MagicCodex 한글 닉네임 (the account name only before the server sent it). */
    private static String myName() { var p = MinecraftClient.getInstance().player; return SchoolClient.nickname(p == null ? "나" : p.getName().getString()); }

    // ------------------------------------------------------------------ fake data
    private static KyudichiProtocol.Match fakeMatch(int phase, int timeLeftMs) {
        if (duelMatch) {
            var one = List.of(new KyudichiProtocol.Person(me(), myName(), 0, ready ? 4 : 0), new KyudichiProtocol.Person(FAKE[2], NAMES[2], 1, phase == 0 ? 4 : 0));
            return new KyudichiProtocol.Match(phase, 0, phase == 0 ? 0 : 1, 0, timeLeftMs, 16, "결투장 1", one);
        }
        var list = new ArrayList<KyudichiProtocol.Person>();
        list.add(new KyudichiProtocol.Person(me(), myName(), 0, ready ? 4 : 0));
        for (int i = 0; i < 5; i++) list.add(new KyudichiProtocol.Person(FAKE[i], NAMES[i], i < 2 ? 0 : 1, phase == 0 && fakeReady[i] ? 4 : 0));
        return new KyudichiProtocol.Match(phase, 0, phase == 0 ? 0 : 30, phase == 0 ? 0 : 20, timeLeftMs, phase == 0 ? 0 : 1, "미리보기", List.copyOf(list));
    }

    private static void prepare() {
        KyudichiClient.reset(); mode = Mode.PREPARE; ready = false; duelMatch = false; until = Util.getMeasuringTimeMs() + 60_000;
        KyudichiClient.receive(fakeMatch(0, 60_000));
        var entries = new ArrayList<KyudichiProtocol.Entry>();
        entries.add(new KyudichiProtocol.Entry(me(), 0, "", 1250));
        int[] houses = {1, 2, 0, 3, 1}; int[] ratings = {1280, 1220, 1260, 1240, 1250};
        for (int i = 0; i < 5; i++) entries.add(new KyudichiProtocol.Entry(FAKE[i], houses[i], "", ratings[i]));
        KyudichiClient.receive(new KyudichiProtocol.Roster(entries));
        MinecraftClient.getInstance().setScreen(new KyudichiPrepareScreen());
    }

    /** 1:1 대전 HUD: names and rounds on the score board, no F key, no balls. */
    private static void duel() {
        KyudichiClient.reset(); mode = Mode.MATCH; duelMatch = true; until = Util.getMeasuringTimeMs() + 60_000;
        KyudichiClient.receive(fakeMatch(2, 156_000));
        KyudichiClient.receive(new KyudichiProtocol.Feed(2, 0, myName(), NAMES[2], 0));
    }

    private static void match() {
        KyudichiClient.reset(); mode = Mode.MATCH; duelMatch = false; until = Util.getMeasuringTimeMs() + 60_000;
        KyudichiClient.receive(fakeMatch(2, 372_000));
        var p = MinecraftClient.getInstance().player;
        if (p != null) {
            var e = p.getPos(); var look = p.getRotationVec(1f);
            KyudichiClient.receive(new KyudichiProtocol.Shrines(List.of(
                    new KyudichiProtocol.Shrine(0, (float) (e.x + look.x * 12 + 6), (float) e.y + 4, (float) (e.z + look.z * 12)),
                    new KyudichiProtocol.Shrine(3, (float) (e.x - look.x * 10), (float) e.y + 6, (float) (e.z - look.z * 10)))));
        }
        KyudichiClient.receive(new KyudichiProtocol.Feed(1, 0, myName(), "", 10));
        KyudichiClient.receive(new KyudichiProtocol.Feed(2, 0, "루나", "이안", 3));
        KyudichiClient.receive(new KyudichiProtocol.Feed(1, 1, "서하", "", 10));
        KyudichiClient.receive(new KyudichiProtocol.Key(1, 0, 0));
    }

    private static void result() {
        stop();
        var lines = new ArrayList<KyudichiProtocol.Line>();
        lines.add(new KyudichiProtocol.Line(me(), myName(), 0, 3, 2, 0, 0, true));
        for (int i = 0; i < 5; i++) lines.add(new KyudichiProtocol.Line(FAKE[i], NAMES[i], i < 2 ? 0 : 1, 2 - i % 2, 1 + i % 3, i % 2, i == 0 ? 1 : 0, false));
        KyudichiClient.receive(new KyudichiProtocol.Result(1, 80, 50, 18, 1268, 3, 3, List.copyOf(lines)));
    }

    private static void queue(int state) {
        stop(); mode = Mode.QUEUE; until = Util.getMeasuringTimeMs() + 60_000;
        KyudichiQueueClient.receive(new KyudichiQueueProtocol.Status(state, 42, state == KyudichiQueueProtocol.S_OFFER ? 10 : 0, 2, 6, false, 4, 0));
    }

    // ------------------------------------------------------------------ 결투장 로비 (fake)
    private static void lobby(int m) {
        stop(); mode = Mode.QUEUE; until = Util.getMeasuringTimeMs() + 600_000;
        KyudichiQueueClient.receive(lobbyFrame(0, m, true));
    }
    private static KyudichiQueueProtocol.Lobby lobbyFrame(int tab, int m, boolean open) {
        var modes = List.of(new KyudichiQueueProtocol.ModeInfo(1, 12 * 86400 + 3600, 1250, 2, 1100, 1300, 32, 18, 2, 14, true, ""),
                new KyudichiQueueProtocol.ModeInfo(1, 12 * 86400 + 3600, 1180, 2, 1100, 1300, 21, 15, 1, 6, true, ""));
        var party = List.of(new KyudichiQueueProtocol.Member(me(), myName(), "", 0, 1250, true), new KyudichiQueueProtocol.Member(FAKE[2], NAMES[2], "", 1, 1260, false));
        return new KyudichiQueueProtocol.Lobby(open, tab, m, modes, party);
    }
    private static KyudichiQueueProtocol.Fighter fighter(int i, int rating, int tier) {
        return i < 0 ? new KyudichiQueueProtocol.Fighter(me(), myName(), "", rating, tier) : new KyudichiQueueProtocol.Fighter(FAKE[i], NAMES[i], "", rating, tier);
    }
    private static KyudichiQueueProtocol.Board board(int m) {
        if (m == 1) return new KyudichiQueueProtocol.Board(1, List.of(
                new KyudichiQueueProtocol.Card("결투장 1", 2, 1, 0, 156_000, 2, -1, -1, NAMES[0], NAMES[2], List.of(fighter(0, 1250, 2)), List.of(fighter(2, 1780, 5))),
                new KyudichiQueueProtocol.Card("결투장 2", 2, 0, 1, 108_000, 0, -1, -1, NAMES[3], NAMES[4], List.of(fighter(3, 980, 1)), List.of(fighter(4, 1648, 4))),
                new KyudichiQueueProtocol.Card("결투장 3", 0, 0, 0, 32_000, 0, -1, -1, NAMES[1], myName(), List.of(fighter(1, 650, 0)), List.of(fighter(-1, 1250, 2)))));
        return new KyudichiQueueProtocol.Board(0, List.of(
                new KyudichiQueueProtocol.Card("제 1 경기장", 2, 30, 20, 372_000, 3, 0, 1, myName() + " 파티", NAMES[2] + " 파티", List.of(fighter(-1, 1250, 2), fighter(0, 1280, 2)), List.of(fighter(2, 1260, 2))),
                new KyudichiQueueProtocol.Card("제 2 경기장", 2, 10, 40, 275_000, 20, 2, 3, NAMES[3] + " 파티", NAMES[4] + " 파티", List.of(fighter(3, 1100, 2)), List.of(fighter(4, 1500, 4))),
                new KyudichiQueueProtocol.Card("제 3 경기장", 0, 0, 0, 32_000, 0, 1, 0, NAMES[1] + " 파티", NAMES[0] + " 파티", List.of(fighter(1, 900, 1)), List.of(fighter(0, 1280, 2))),
                new KyudichiQueueProtocol.Card("제 4 경기장", 2, 20, 20, 120_000, 1, -1, -1, "가람 파티", "나래 파티", List.of(), List.of())));
    }
    private static KyudichiQueueProtocol.Ranks ranks(int m, int page, String search) {
        String[] n = {"준서", "하린", "도윤", "서하", "유진", "지안"};
        int pages = 9, hl = 0; String notice = "";
        if (!search.isBlank()) { if (search.equals(myName())) { page = 8; hl = 47; } else notice = "'" + search + "' 님의 이번 시즌 기록이 없어요."; }
        page = Math.clamp(page, 1, pages);
        var rows = new ArrayList<KyudichiQueueProtocol.Row>();
        for (int i = 0; i < 6; i++) {
            int rank = (page - 1) * 6 + i + 1, rating = 1924 - (rank - 1) * 14;
            int tier = rank <= 3 ? 5 + rank : rating >= 1700 ? 5 : rating >= 1500 ? 4 : rating >= 1300 ? 3 : 2;
            boolean meRow = rank == 47;
            rows.add(new KyudichiQueueProtocol.Row(rank, meRow ? me() : new UUID(7, rank), meRow ? myName() : page == 1 ? n[i] : "플레이어" + rank, "", rating, tier, 30 - i, 18, i % 3));
        }
        return new KyudichiQueueProtocol.Ranks(m, page, pages, rows, new KyudichiQueueProtocol.Row(47, me(), myName(), "", 1250, 2, 32, 18, 2), hl, notice);
    }
    private static KyudichiQueueProtocol.ProfileReply profile(int m, String nick) {
        String who = nick.isBlank() ? myName() : nick;
        long now = System.currentTimeMillis();
        var games = new ArrayList<KyudichiQueueProtocol.Game>();
        String[] teams = {"호크문 기사단", "청월의 비상", "새벽의 매", "흑담의 창", "은빛 늑대", "붉은 여명"};
        int[][] g = {{1, 14}, {0, -12}, {2, 0}, {1, 16}, {0, -10}, {1, 12}};
        int rating = 1250;
        for (int i = 0; i < g.length; i++) {
            games.add(new KyudichiQueueProtocol.Game(now - i * 86_400_000L, g[i][0], g[i][1], rating, 10 + i, 8 - i % 3, 3 + i % 4, 2410 - i * 230,
                    m == 1 ? (g[i][0] == 1 ? 2 : g[i][0] == 2 ? 1 : 0) : 120 - i * 10, m == 1 ? (g[i][0] == 0 ? 2 : 1) : 80 + i * 5,
                    m == 1 ? NAMES[i % 5] : teams[i], "", m == 1 ? -1 : i % 5 - 1));
            rating -= g[i][1];
        }
        return new KyudichiQueueProtocol.ProfileReply(m, nick, new KyudichiQueueProtocol.Profile(nick.isBlank() ? me() : new UUID(7, 99), who, "", 1250, 2, 47, 52, 32, 18, 2,
                24, 86, 54, 7, 42, 64_480f, 3200f, List.copyOf(games)));
    }

    private static void application() {
        stop(); mode = Mode.QUEUE; until = Util.getMeasuringTimeMs() + 600_000;
        KyudichiQueueClient.receive(apply());
    }
    private static KyudichiQueueProtocol.Apply apply() {
        return new KyudichiQueueProtocol.Apply(1250, 18, 12, 1280, true, "파티장만 시작할 수 있습니다.", List.of(
                new KyudichiQueueProtocol.Member(me(), myName(), "", 0, 1250, true),
                new KyudichiQueueProtocol.Member(FAKE[0], NAMES[0], "", 1, 1280, false),
                new KyudichiQueueProtocol.Member(FAKE[1], NAMES[1], "", 2, 1220, false)));
    }

    private static void dashboard() {
        stop(); mode = Mode.QUEUE; until = Util.getMeasuringTimeMs() + 600_000;
        KyudichiQueueClient.dash = dash(); KyudichiQueueClient.ranking = ranking(); KyudichiQueueClient.record = record(myName());
        MinecraftClient.getInstance().setScreen(new KyudichiDashboardScreen(school.magiccodex.client.kui.DashboardView.Tab.LIVE));
    }
    private static KyudichiQueueProtocol.Dash dash() {
        return new KyudichiQueueProtocol.Dash(4, List.of(
                new KyudichiQueueProtocol.LiveCard("별빛 경기장", 2, 30, 20, 372_000, 3, team(-1, 0, 1), team(2, 3, 4)),
                new KyudichiQueueProtocol.LiveCard("하늘 경기장", 2, 50, 40, 228_000, 20, team(0, 1, -1), team(3, 2, 4)),
                new KyudichiQueueProtocol.LiveCard("새벽 경기장", 0, 0, 0, 32_000, 0, team(1, -1, 0), team(4, 3, 2))));
    }
    /** -1 = me, 0..4 = the fake players. */
    private static List<KyudichiQueueProtocol.Who> team(int... who) {
        var l = new ArrayList<KyudichiQueueProtocol.Who>();
        for (int i : who) l.add(i < 0 ? new KyudichiQueueProtocol.Who(me(), myName(), "") : new KyudichiQueueProtocol.Who(FAKE[i], NAMES[i], ""));
        return l;
    }
    private static KyudichiQueueProtocol.Ranking ranking() {
        var top = new ArrayList<KyudichiQueueProtocol.Rank>();
        String[] n = {"도윤", "서하", "루나", "이안", "유진", "하린", "지우"};
        int[] r = {1920, 1884, 1812, 1764, 1720, 1688, 1624};
        for (int i = 0; i < 40; i++) {
            int rating = i < 7 ? r[i] : 1600 - i * 12;
            int tier = i < 3 ? 6 + i : rating >= 1700 ? 5 : rating >= 1500 ? 4 : rating >= 1300 ? 3 : 2;
            top.add(new KyudichiQueueProtocol.Rank(i + 1, new UUID(7, i), i < 7 ? n[i] : "플레이어" + (i + 1), "", rating, tier, 50, 30, 20));
        }
        return new KyudichiQueueProtocol.Ranking(List.copyOf(top), new KyudichiQueueProtocol.Rank(47, me(), myName(), "", 1342, 3, 50, 27, 21));
    }
    private static KyudichiQueueProtocol.RecordReply record(String nick) {
        var recent = new ArrayList<KyudichiQueueProtocol.Recent>();
        int[][] g = {{1, 80, 50}, {0, 40, 60}, {1, 70, 30}, {0, 20, 50}, {1, 90, 70}, {0, 30, 60}, {1, 60, 40}, {2, 50, 50}, {1, 80, 60}, {0, 30, 40}};
        long now = System.currentTimeMillis();
        for (int i = 0; i < g.length; i++) recent.add(new KyudichiQueueProtocol.Recent(now - i * 3_600_000L, g[i][0], 0, g[i][1], g[i][2], 3 - i % 3, 2 + i % 4, 2 + i % 3, i == 0, g[i][0] == 1 ? 18 : g[i][0] == 2 ? 0 : -15));
        return new KyudichiQueueProtocol.RecordReply(new KyudichiQueueProtocol.PlayerRecord(me(), nick, "", 1342, 3, 47, 50, 27, 21, 2, 128, 96, 72, 40, 9, 147, 12,
                24_580f, 8_240f, List.copyOf(recent)));
    }

    // ------------------------------------------------------------------ actions inside the preview (instead of the network)
    /** KyudichiQueueClient.send while previewing: answer locally. */
    static void queueAction(KyudichiQueueProtocol.FromClient m) {
        switch (m) {
            case KyudichiQueueProtocol.Join x -> KyudichiQueueClient.receive(new KyudichiQueueProtocol.Status(KyudichiQueueProtocol.S_SEARCHING, 0, 0, 0, 0, false, 4, 0));
            case KyudichiQueueProtocol.Leave x -> KyudichiQueueClient.receive(new KyudichiQueueProtocol.Status(0, 0, 0, 0, 0, false, 0, 0));
            case KyudichiQueueProtocol.Decline x -> KyudichiQueueClient.receive(new KyudichiQueueProtocol.Status(0, 0, 0, 0, 0, false, 0, 0));
            case KyudichiQueueProtocol.Accept x -> KyudichiQueueClient.receive(new KyudichiQueueProtocol.Status(KyudichiQueueProtocol.S_OFFER, 0, 6, 3, 6, true, 4, fakeQueueMode));
            case KyudichiQueueProtocol.DashRequest x -> KyudichiQueueClient.receive(dash());
            case KyudichiQueueProtocol.RankingRequest x -> KyudichiQueueClient.receive(ranking());
            case KyudichiQueueProtocol.RecordRequest x -> KyudichiQueueClient.receive(record(x.nick().isBlank() ? myName() : x.nick()));
            case KyudichiQueueProtocol.ApplyRequest x -> KyudichiQueueClient.receive(apply());
            case KyudichiQueueProtocol.LobbyRequest x -> KyudichiQueueClient.receive(lobbyFrame(x.tab(), x.mode(), false));
            case KyudichiQueueProtocol.BoardRequest x -> KyudichiQueueClient.receive(board(x.mode()));
            case KyudichiQueueProtocol.RanksRequest x -> KyudichiQueueClient.receive(ranks(x.mode(), x.page(), x.search()));
            case KyudichiQueueProtocol.ProfileRequest x -> KyudichiQueueClient.receive(profile(x.mode(), x.nick()));
            case KyudichiQueueProtocol.JoinMode x -> { fakeQueueMode = x.mode(); KyudichiQueueClient.receive(new KyudichiQueueProtocol.Status(KyudichiQueueProtocol.S_SEARCHING, 0, 0, 0, 0, false, 4, x.mode())); }
            default -> MinecraftClient.getInstance().player.sendMessage(Text.literal("미리보기에서는 실제로 이동하지 않아요."), true);
        }
    }
    /** Prepare screen ready button while previewing. */
    static void toggleReady() { ready = !ready; KyudichiClient.receive(fakeMatch(0, (int) Math.max(1000, until - Util.getMeasuringTimeMs()))); }

    static void stop() {
        if (mode == Mode.PREPARE || mode == Mode.MATCH) { KyudichiClient.reset(); duelMatch = false; }
        if (mode == Mode.QUEUE) {
            KyudichiQueueClient.receive(new KyudichiQueueProtocol.Status(0, 0, 0, 0, 0, false, 0, 0));
            // fake 로비 / 현황 / 랭킹 / 기록 / 신청 data must not stay behind for the real windows
            KyudichiQueueClient.dash = null; KyudichiQueueClient.ranking = null; KyudichiQueueClient.record = null; KyudichiQueueClient.apply = null;
            KyudichiQueueClient.clearLobby();
        }
        mode = Mode.NONE;
    }
    /** A real packet from the arena server ends a 준비 / 경기 preview. */
    static void realArena() { if (mode == Mode.PREPARE || mode == Mode.MATCH) stop(); }
    /** Real 현황 · 랭킹 · 기록 · 신청 data, or a real queue state, from the proxy ends a queue / dashboard preview. */
    static void realQueue(KyudichiQueueProtocol.ToClient m) {
        if (mode != Mode.QUEUE) return;
        if (m instanceof KyudichiQueueProtocol.Status st && st.state() == 0) return;
        stop();
    }

    /** Keeps the fake match alive (the HUD drops a match after 4 s without packets) and moves the balls around the player. */
    private static void tick(MinecraftClient c) {
        if (mode == Mode.NONE) return;
        long now = Util.getMeasuringTimeMs();
        if (now > until || c.player == null) { stop(); return; }
        if (now % 1000 < 50 || KyudichiClient.match == null) {
            int left = (int) (until - now);
            if (mode == Mode.PREPARE) KyudichiClient.receive(fakeMatch(0, left));
            if (mode == Mode.MATCH) KyudichiClient.receive(fakeMatch(2, (duelMatch ? 120_000 : 312_000) + left));
        }
        if (mode == Mode.MATCH && !duelMatch) {
            var e = c.player.getPos(); double t = now / 1000.0;
            KyudichiClient.receive(new KyudichiProtocol.Balls(List.of(
                    new KyudichiProtocol.Ball(0, 0, (float) (e.x + Math.cos(t) * 8), (float) e.y + 3, (float) (e.z + Math.sin(t) * 8), 0),
                    new KyudichiProtocol.Ball(1, 0, (float) (e.x - Math.cos(t * .7) * 14), (float) e.y + 5, (float) (e.z + Math.sin(t * .7) * 14), 2),
                    new KyudichiProtocol.Ball(1, 1, (float) (e.x + 30), (float) e.y + 2, (float) (e.z - 20), 0),
                    new KyudichiProtocol.Ball(2, 0, (float) (e.x + Math.sin(t * 1.3) * 25), (float) e.y + 10, (float) (e.z - 30), 0))));
            int action = (int) ((now / 2500) % 5);
            if (action != KyudichiClient.key.action()) KyudichiClient.receive(new KyudichiProtocol.Key(action, 0, action == 2 ? 7000 : 0));
        }
    }
}
