package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.kui.LobbyView;
import school.magiccodex.client.kui.Pen;
import school.magiccodex.protocol.KyudichiQueueProtocol;
import school.magiccodex.protocol.PartyUiProtocol;

/**
 * 결투장 로비 (LobbyView): /큐디치 opens it (the proxy sends LOBBY with open). Tabs 매칭 · 진행 중인 경기 · 랭킹 · 전적, mode
 * 큐디치 / 1:1 대전. Data comes from the proxy (LOBBY · BOARD · RANKS · PROFILE), asked for when a tab or mode is shown and
 * refreshed while it stays open. The background covers the whole screen; the view itself is fitted.
 */
final class KyudichiLobbyScreen extends KyudichiViewScreen {
    private int tab, mode;
    private boolean partyOpen = true;
    private int menuFor = -1, livePage, recentScroll;
    private TextFieldWidget rankSearch, recordSearch;
    private int focus; // 0 none, 1 ranking search, 2 record search
    private long lobbyRefreshAt, boardRefreshAt, partyRefreshAt;
    private String notice = ""; private long noticeAt;

    KyudichiLobbyScreen(int tab, int mode) { super("결투장"); this.tab = Math.clamp(tab, 0, 3); this.mode = Math.clamp(mode, 0, 1); }
    /** #108 하우징 큐디치 트로피장: 처음 열 때 이 닉네임의 전적 탭으로 (null = 보통). */
    private String recordNick;
    static KyudichiLobbyScreen record(int mode, String nick) { var s = new KyudichiLobbyScreen(LobbyView.RECORD, mode); s.recordNick = nick == null ? "" : nick; return s; }

    void show(int t, int m) { tab = Math.clamp(t, 0, 3); mode = Math.clamp(m, 0, 1); menuFor = -1; focus = 0; request(); }

    @Override float designW() { return LobbyView.W; }
    @Override float designH() { return LobbyView.H; }
    @Override float fill() { return 1; }
    @Override int dim() { return 0; }

    @Override protected void init() {
        super.init();
        if (rankSearch == null) { rankSearch = new TextFieldWidget(textRenderer, 0, 0, 200, 20, Text.literal("닉네임")); rankSearch.setMaxLength(16); }
        if (recordSearch == null) { recordSearch = new TextFieldWidget(textRenderer, 0, 0, 200, 20, Text.literal("닉네임")); recordSearch.setMaxLength(16); }
        if (recordNick != null) { String nick = recordNick; recordNick = null; openRecord(nick); lobbyRefreshAt = Util.getMeasuringTimeMs() + 5000; return; }
        request();
    }

    /** Ask for what the current tab shows (and the lobby frame itself). */
    private void request() {
        KyudichiQueueClient.send(new KyudichiQueueProtocol.LobbyRequest(tab, mode));
        lobbyRefreshAt = Util.getMeasuringTimeMs() + 5000;
        switch (tab) {
            case LobbyView.LIVE -> { KyudichiQueueClient.send(new KyudichiQueueProtocol.BoardRequest(mode)); boardRefreshAt = Util.getMeasuringTimeMs() + 3000; }
            case LobbyView.RANKING -> { var r = KyudichiQueueClient.ranks[mode]; KyudichiQueueClient.send(new KyudichiQueueProtocol.RanksRequest(mode, r == null ? 1 : r.page(), "")); }
            case LobbyView.RECORD -> { var r = KyudichiQueueClient.profile[mode]; KyudichiQueueClient.send(new KyudichiQueueProtocol.ProfileRequest(mode, r == null ? "" : r.query())); }
            default -> { }
        }
    }

    @Override public void tick() {
        long now = Util.getMeasuringTimeMs();
        if (now > lobbyRefreshAt) { lobbyRefreshAt = now + 5000; KyudichiQueueClient.send(new KyudichiQueueProtocol.LobbyRequest(tab, mode)); }
        if (tab == LobbyView.LIVE && now > boardRefreshAt) { boardRefreshAt = now + 3000; KyudichiQueueClient.send(new KyudichiQueueProtocol.BoardRequest(mode)); }
        if (partyRefreshAt > 0 && now > partyRefreshAt) { partyRefreshAt = 0; KyudichiQueueClient.send(new KyudichiQueueProtocol.LobbyRequest(tab, mode)); }
    }

    // ================================================================== data → view
    private LobbyView.Data data() {
        var c = client;
        UUID me = c != null && c.player != null ? c.player.getUuid() : new UUID(0, 0);
        String myNick = NicknameClient.display(c != null && c.player != null ? c.player.getName().getString() : "나");
        var lobby = KyudichiQueueClient.lobby;
        LobbyView.Mode info = null;
        var party = new ArrayList<LobbyView.Member>();
        if (lobby != null) {
            var mi = lobby.modes().get(Math.min(mode, lobby.modes().size() - 1));
            info = new LobbyView.Mode(mi.season(), Math.max(0, mi.seasonLeftSec() - (int) ((Util.getMeasuringTimeMs() - KyudichiQueueClient.lobbyAt) / 1000)),
                    mi.rating(), mi.tier(), mi.tierFloor(), mi.nextFloor(), mi.wins(), mi.losses(), mi.draws(), mi.queuedPlayers(), mi.canStart(), mi.note());
            for (var m : lobby.party()) {
                party.add(new LobbyView.Member(m.id(), m.nick(), m.skin(), m.leader(), m.id().equals(me)));
                if (m.id().equals(me) && !m.nick().isBlank()) myNick = m.nick();
            }
        }
        boolean leaderMe = party.size() <= 1 || party.stream().anyMatch(m -> m.me() && m.leader());
        // spells of this mode, with the key each slot gets in a match
        var l = KyudichiLoadout.forMode(mode);
        var binds = l.bindings(CastingClient.base().orderedSlots());
        var spells = new ArrayList<LobbyView.Spell>();
        for (int i = 0; i < KyudichiLoadout.SIZE; i++) {
            var s = CastingClient.state().spell(l.get(i));
            int key = i < binds.size() ? binds.get(i).keyCode() : -1;
            spells.add(new LobbyView.Spell(s == null ? "" : s.icon(), key < 0 ? "" : KeySettingsLayout.name(key)));
        }
        var st = KyudichiQueueClient.status;
        int waited = st.waitedSec() + (int) ((Util.getMeasuringTimeMs() - KyudichiQueueClient.statusAt) / 1000);
        var queue = new LobbyView.Queue(st.state(), st.mode(), st.state() == KyudichiQueueProtocol.S_SEARCHING ? waited : 0);
        // 진행 중인 경기
        var b = KyudichiQueueClient.board[mode];
        var cards = new ArrayList<LobbyView.Card>();
        if (b != null) for (var card : b.cards()) {
            int left = Math.max(0, card.timeLeftMs() - (int) (Util.getMeasuringTimeMs() - KyudichiQueueClient.boardAt));
            cards.add(new LobbyView.Card(card.arena(), card.phase(), card.scoreA(), card.scoreB(), left, card.houseA(), card.houseB(), card.nameA(), card.nameB(),
                    fighters(card.a()), fighters(card.b()), card.spectators() >= 20));
        }
        int pages = Math.max(1, (cards.size() + LobbyView.CARDS_PER_PAGE - 1) / LobbyView.CARDS_PER_PAGE);
        livePage = Math.clamp(livePage, 0, pages - 1);
        // 랭킹
        var r = KyudichiQueueClient.ranks[mode];
        var rows = new ArrayList<LobbyView.Row>();
        if (r != null) for (var row : r.rows()) rows.add(row(row));
        // 전적
        var pr = KyudichiQueueClient.profile[mode];
        LobbyView.Profile profile = null;
        if (pr != null && pr.profile() != null) {
            var f = pr.profile();
            var games = new ArrayList<LobbyView.Game>();
            for (var g : f.recent()) games.add(new LobbyView.Game(g.endedAt(), g.outcome(), g.delta(), g.ratingAfter(), g.goals(), g.kills(), g.deaths(), g.damage(),
                    g.myScore(), g.theirScore(), g.opponent(), g.opponentSkin(), g.opponentHouse()));
            profile = new LobbyView.Profile(f.id(), f.nick(), f.skin(), f.rating(), f.tier(), f.rank(), f.games(), f.wins(), f.losses(), f.draws(), f.goals(),
                    f.kills(), f.deaths(), f.snitch(), f.deflectHits(), f.damage(), f.heal(), games);
            recentScroll = Math.clamp(recentScroll, 0, Math.max(0, games.size() - LobbyView.RECENT_ROWS));
        }
        boolean caret = Util.getMeasuringTimeMs() / 500 % 2 == 0;
        String n = Util.getMeasuringTimeMs() - noticeAt < 3500 ? notice : "";
        if (n.isEmpty() && tab == LobbyView.RANKING && r != null && !r.notice().isEmpty() && Util.getMeasuringTimeMs() - KyudichiQueueClient.ranksAt < 3500) n = r.notice();
        if (n.isEmpty() && tab == LobbyView.RECORD && pr != null && pr.profile() == null && !pr.query().isEmpty() && Util.getMeasuringTimeMs() - KyudichiQueueClient.profileAt < 3500)
            n = "'" + pr.query() + "' 님의 이번 시즌 전적이 없어요.";
        return new LobbyView.Data(tab, mode, me, myNick, "", info, party, partyOpen, menuFor, leaderMe, spells, KyudichiClient.keyLabel(), queue,
                cards, livePage, b != null,
                rows, r == null || r.mine() == null ? null : row(r.mine()), r == null ? 1 : r.page(), r == null ? 1 : r.pages(), r == null ? 0 : r.highlight(),
                new LobbyView.Field(rankSearch == null ? "" : rankSearch.getText(), focus == 1, caret), r != null,
                pr != null && pr.profile() == null && !pr.query().isEmpty() ? null : profile, pr != null, recentScroll,
                new LobbyView.Field(recordSearch == null ? "" : recordSearch.getText(), focus == 2, caret), n);
    }
    private static List<LobbyView.Fighter> fighters(List<KyudichiQueueProtocol.Fighter> l) {
        return l.stream().map(f -> new LobbyView.Fighter(f.id(), f.nick(), f.skin(), f.rating(), f.tier())).toList();
    }
    private static LobbyView.Row row(KyudichiQueueProtocol.Row r) {
        return new LobbyView.Row(r.rank(), r.id(), r.nick(), r.skin(), r.rating(), r.tier(), r.wins(), r.losses(), r.draws());
    }

    private LobbyView.Data last;
    @Override void paint(Pen p) {
        last = data();
        LobbyView.edges(ox / scale, ox / scale); // wide windows: menus to the left edge, season / right column to the right edge
        LobbyView.keyCapture(capturing);
        LobbyView.tierInfo(tierInfo && tab == LobbyView.MATCH);
        LobbyView.content(p, last);
    }

    // ================================================================== 특수 키 (vanilla swap-hands) set from the lobby
    private boolean capturing, tierInfo;
    private void bindSpecial(int key) {
        capturing = false;
        if (key == GLFW.GLFW_KEY_ESCAPE) return;
        if (!KeySettingsLayout.allowed(key)) { say("그 키는 쓸 수 없어요."); return; }
        if (UiKeys.isUiKey(key)) { say(KeySettingsLayout.name(key) + " 키는 UI 단축키로 쓰고 있어요. 키 설정에서 먼저 바꿔 주세요."); return; }
        for (var b : CastingClient.base().orderedSlots())
            if (!b.empty() && b.keyCode() == key) { say(KeySettingsLayout.name(key) + " 키에는 마법이 있어요. 키 설정에서 먼저 바꿔 주세요."); return; }
        var o = client.options;
        o.swapHandsKey.setBoundKey(net.minecraft.client.util.InputUtil.Type.KEYSYM.createFromCode(key));
        net.minecraft.client.option.KeyBinding.updateKeysByCode();
        o.write();
        say("특수 키를 " + KeySettingsLayout.name(key) + " 키로 바꿨어요.");
    }

    /** Background scaled to cover the whole window (the view is fitted, so wide or tall windows have no bars). */
    @Override void backdrop(KyudichiCanvas canvas) {
        float s = Math.max(width / LobbyView.W, height / LobbyView.H);
        canvas.push((width - LobbyView.W * s) / 2, (height - LobbyView.H * s) / 2, s);
        try { LobbyView.background(new Pen(canvas, -1, -1, null), tab, mode); } finally { canvas.pop(); }
    }

    private void say(String s) { notice = s; noticeAt = Util.getMeasuringTimeMs(); }

    // ================================================================== input
    @Override void clicked(String id, int button) {
        if (!id.startsWith("menu:") && !id.startsWith("act:")) menuFor = -1;
        if (!id.equals(LobbyView.SPECIAL_KEY)) capturing = false;
        if (id.equals(LobbyView.TIER_INFO)) { tierInfo = !tierInfo; return; }
        tierInfo = false;
        if (id.startsWith("tab:")) { int t = Integer.parseInt(id.substring(4)); if (t != tab) { tab = t; focus = 0; request(); } return; }
        if (id.startsWith("mode:")) { int m = Integer.parseInt(id.substring(5)); if (m != mode) { mode = m; livePage = 0; recentScroll = 0; request(); } return; }
        switch (id) {
            case LobbyView.PRACTICE -> { KyudichiQueueClient.send(new KyudichiQueueProtocol.PracticeMode(mode)); close(); return; }
            case LobbyView.SPELLS -> { client.setScreen(new KyudichiSpellPickScreen(this, mode)); return; }
            case LobbyView.START -> { start(); return; }
            case LobbyView.PARTY_TOGGLE -> { partyOpen = !partyOpen; return; }
            case LobbyView.INVITE -> { client.setScreen(new PartyScreen(this)); return; }
            case LobbyView.SPECIAL_KEY -> { capturing = !capturing; return; }
            case LobbyView.RANK_SEARCH_GO -> { searchRanks(); return; }
            case LobbyView.RECORD_SEARCH_GO -> { searchRecord(recordSearch.getText()); return; }
            case LobbyView.ME_ROW -> { openRecord(""); return; }
            case LobbyView.PREV -> { page(-1); return; }
            case LobbyView.NEXT -> { page(1); return; }
            default -> { }
        }
        if (id.startsWith("page:")) { goPage(Integer.parseInt(id.substring(5))); return; }
        if (id.startsWith("spectate:")) { KyudichiQueueClient.send(new KyudichiQueueProtocol.SpectateRequest(id.substring(9))); close(); return; }
        if (id.startsWith("row:")) {
            var r = KyudichiQueueClient.ranks[mode]; int i = Integer.parseInt(id.substring(4));
            if (r != null && i < r.rows().size()) openRecord(r.rows().get(i).nick());
            return;
        }
        if (id.startsWith("menu:")) { int i = Integer.parseInt(id.substring(5)); menuFor = menuFor == i ? -1 : i; return; }
        if (id.startsWith("act:")) {
            String[] a = id.split(":"); int i = Integer.parseInt(a[2]); menuFor = -1;
            var lobby = KyudichiQueueClient.lobby;
            if (lobby == null || i >= lobby.party().size()) return;
            UUID target = lobby.party().get(i).id();
            boolean sent = switch (a[1]) {
                case "promote" -> PartyUiClient.send(PartyUiProtocol.PROMOTE, target);
                case "kick" -> PartyUiClient.send(PartyUiProtocol.KICK, target);
                case "leave" -> PartyUiClient.send(PartyUiProtocol.LEAVE, null);
                default -> false;
            };
            if (!sent) say("파티 서버에 연결되지 않았어요.");
            partyRefreshAt = Util.getMeasuringTimeMs() + 600;
        }
    }

    private void start() {
        var st = KyudichiQueueClient.status;
        if (st.state() == KyudichiQueueProtocol.S_SEARCHING && st.mode() == mode) KyudichiQueueClient.send(new KyudichiQueueProtocol.Leave());
        else if (st.state() == KyudichiQueueProtocol.S_NONE) KyudichiQueueClient.send(new KyudichiQueueProtocol.JoinMode(mode));
        lobbyRefreshAt = Util.getMeasuringTimeMs() + 400;
    }

    private void page(int d) {
        if (tab == LobbyView.LIVE) { livePage += d; return; }
        var r = KyudichiQueueClient.ranks[mode];
        if (tab == LobbyView.RANKING && r != null) goPage(r.page() - 1 + d);
    }
    /** pg is 0-based (pager ids); the ranking request is 1-based. */
    private void goPage(int pg) {
        if (tab == LobbyView.LIVE) { livePage = pg; return; }
        if (tab == LobbyView.RANKING) KyudichiQueueClient.send(new KyudichiQueueProtocol.RanksRequest(mode, Math.max(1, pg + 1), ""));
    }
    private void searchRanks() {
        String q = rankSearch.getText().strip();
        KyudichiQueueClient.send(new KyudichiQueueProtocol.RanksRequest(mode, 1, q));
        focus = 0;
    }
    private void searchRecord(String nick) {
        KyudichiQueueClient.send(new KyudichiQueueProtocol.ProfileRequest(mode, nick == null ? "" : nick.strip()));
        recentScroll = 0; focus = 0;
    }
    private void openRecord(String nick) {
        tab = LobbyView.RECORD; recordSearch.setText(nick); searchRecord(nick);
        KyudichiQueueClient.send(new KyudichiQueueProtocol.LobbyRequest(tab, mode));
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        String id = hitAt(mx(x), my(y));
        if (id == null) { menuFor = -1; tierInfo = false; }
        focus = 0;
        return super.mouseClicked(x, y, button);
    }
    @Override void pressed(String id, float x, float y) {
        focus = id.equals(LobbyView.RANK_SEARCH) ? 1 : id.equals(LobbyView.RECORD_SEARCH) ? 2 : 0;
        rankSearch.setFocused(focus == 1); recordSearch.setFocused(focus == 2);
    }

    @Override public boolean mouseScrolled(double x, double y, double h, double v) {
        int d = -(int) Math.signum(v);
        if (tab == LobbyView.RECORD) recentScroll += d;
        else if (tab == LobbyView.LIVE || tab == LobbyView.RANKING) page(d);
        return true;
    }
    private TextFieldWidget field() { return focus == 1 ? rankSearch : focus == 2 ? recordSearch : null; }
    @Override public boolean charTyped(char ch, int mods) { var f = field(); return f != null ? f.charTyped(ch, mods) : super.charTyped(ch, mods); }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (capturing) { bindSpecial(key); return true; }
        // the 파티창 key opens the party window inside the lobby (closing it comes back here)
        if (field() == null && key == UiKeys.code(UiKeys.Action.PARTY)) { client.setScreen(new PartyScreen(this)); return true; }
        var f = field();
        if (f != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { if (focus == 1) searchRanks(); else searchRecord(recordSearch.getText()); return true; }
            if (key == GLFW.GLFW_KEY_ESCAPE) { focus = 0; return true; }
            return f.keyPressed(key, scan, mods);
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && menuFor >= 0) { menuFor = -1; return true; }
        return super.keyPressed(key, scan, mods);
    }
}
