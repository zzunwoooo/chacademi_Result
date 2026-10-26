package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import school.magiccodex.client.kui.DashboardView;
import school.magiccodex.client.kui.DashboardView.Tab;
import school.magiccodex.client.kui.Pen;
import school.magiccodex.protocol.KyudichiQueueProtocol;

/**
 * 큐디치 대시보드 (DashboardView): 경기 현황 (관전) · 랭킹 · 플레이어 기록 (검색). Opened by /큐디치 현황 · 랭킹 · 정보
 * (the proxy sends the data without a request) and from the preview command.
 */
final class KyudichiDashboardScreen extends KyudichiViewScreen {
    private Tab tab;
    private int liveScroll, rankScroll;
    private long refreshAt;
    private boolean searchFocused;
    private TextFieldWidget search;
    private String notice = ""; private long noticeAt;

    KyudichiDashboardScreen(Tab tab) { super("큐디치"); this.tab = tab; }

    void show(Tab t) { tab = t; liveScroll = rankScroll = 0; }

    @Override float designW() { return DashboardView.W; }
    @Override float designH() { return DashboardView.H; }

    @Override protected void init() {
        super.init();
        if (search == null) { search = new TextFieldWidget(textRenderer, 0, 0, 200, 20, Text.literal("닉네임")); search.setMaxLength(16); }
        if (KyudichiQueueClient.dash == null) KyudichiQueueClient.requestDash();
        if (tab == Tab.RANKING && KyudichiQueueClient.ranking == null) KyudichiQueueClient.requestRanking();
        if (tab == Tab.RECORD && KyudichiQueueClient.record == null) KyudichiQueueClient.requestRecord("");
    }

    @Override public void tick() {
        long now = Util.getMeasuringTimeMs();
        if (tab == Tab.LIVE && now > refreshAt) { refreshAt = now + 3000; KyudichiQueueClient.requestDash(); }
    }

    @Override void paint(Pen p) {
        var dash = KyudichiQueueClient.dash;
        var live = new ArrayList<DashboardView.Live>();
        if (dash != null) for (var l : dash.live()) live.add(new DashboardView.Live(l.arena(), l.phase(), l.scoreA(), l.scoreB(), l.timeLeftMs(), l.spectators(), whos(l.teamA()), whos(l.teamB())));
        liveScroll = Math.clamp(liveScroll, 0, Math.max(0, live.size() - DashboardView.LIVE_VISIBLE));
        var rk = KyudichiQueueClient.ranking;
        var ranks = new ArrayList<DashboardView.Rank>();
        DashboardView.Rank mine = null;
        if (rk != null) { for (var r : rk.top()) ranks.add(rank(r)); if (rk.mine() != null) mine = rank(rk.mine()); }
        rankScroll = Math.clamp(rankScroll, 0, DashboardView.maxRankScroll(ranks.size()));
        var rr = KyudichiQueueClient.record;
        DashboardView.PlayerRec rec = null;
        if (rr != null && rr.record() != null) {
            var r = rr.record();
            var recent = new ArrayList<DashboardView.Recent>();
            for (var g : r.recent()) recent.add(new DashboardView.Recent(g.outcome(), g.team(), g.scoreA(), g.scoreB(), g.goals(), g.kills(), g.deaths()));
            rec = new DashboardView.PlayerRec(r.id(), r.nick(), r.skin(), r.rating(), r.tier(), r.rank(), r.games(), r.wins(), r.losses(), r.draws(),
                    r.goals(), r.kills(), r.deaths(), r.damage(), r.heal(), r.snitch(), r.deflectHits(), r.mvp(), recent);
        }
        String n = Util.getMeasuringTimeMs() - noticeAt < 3000 ? notice : "";
        DashboardView.paint(p, new DashboardView.Data(tab, dash == null ? 0 : dash.queuedParties(), live, liveScroll, ranks, mine, rankScroll,
                rec, rr != null, search.getText(), searchFocused, Util.getMeasuringTimeMs() / 500 % 2 == 0, n));
    }
    private static List<DashboardView.Who> whos(List<KyudichiQueueProtocol.Who> l) { return l.stream().map(w -> new DashboardView.Who(w.id(), w.nick(), w.skin())).toList(); }
    private static DashboardView.Rank rank(KyudichiQueueProtocol.Rank r) { return new DashboardView.Rank(r.rank(), r.id(), r.nick(), r.skin(), r.rating(), r.tier(), r.games(), r.wins(), r.losses()); }

    @Override void clicked(String id, int button) {
        if (id.equals(DashboardView.CLOSE)) { close(); return; }
        if (id.startsWith("tab:")) {
            Tab t = Tab.values()[Integer.parseInt(id.substring(4))];
            show(t);
            if (t == Tab.LIVE) KyudichiQueueClient.requestDash();
            if (t == Tab.RANKING) KyudichiQueueClient.requestRanking();
            if (t == Tab.RECORD && KyudichiQueueClient.record == null) KyudichiQueueClient.requestRecord("");
            return;
        }
        if (id.startsWith("spectate:")) { KyudichiQueueClient.send(new KyudichiQueueProtocol.SpectateRequest(id.substring(9))); close(); return; }
        if (id.equals(DashboardView.SEARCH)) { doSearch(); return; }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        searchFocused = false; if (search != null) search.setFocused(false);
        return super.mouseClicked(x, y, button);
    }
    @Override void pressed(String id, float x, float y) { searchFocused = id.equals(DashboardView.SEARCH_FIELD); search.setFocused(searchFocused); }

    private void doSearch() {
        String nick = search.getText().strip();
        if (nick.isEmpty()) { KyudichiQueueClient.requestRecord(""); return; }
        KyudichiQueueClient.requestRecord(nick);
    }

    @Override public boolean mouseScrolled(double x, double y, double h, double v) {
        int d = -(int) Math.signum(v);
        if (tab == Tab.LIVE) liveScroll += d; else if (tab == Tab.RANKING) rankScroll += d * 3;
        return true;
    }
    @Override public boolean charTyped(char ch, int mods) { return tab == Tab.RECORD && searchFocused ? search.charTyped(ch, mods) : super.charTyped(ch, mods); }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (tab == Tab.RECORD && searchFocused) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { doSearch(); return true; }
            if (key != GLFW.GLFW_KEY_ESCAPE) return search.keyPressed(key, scan, mods);
        }
        return super.keyPressed(key, scan, mods);
    }
}
