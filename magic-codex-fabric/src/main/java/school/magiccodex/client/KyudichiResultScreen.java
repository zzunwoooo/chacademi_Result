package school.magiccodex.client;

import java.util.ArrayList;
import java.util.UUID;
import school.magiccodex.client.kui.Pen;
import school.magiccodex.client.kui.ResultView;
import school.magiccodex.protocol.KyudichiProtocol;

/** 결과 창 (ResultView): opens when the result arrives, 확인 closes it. */
final class KyudichiResultScreen extends KyudichiViewScreen {
    private final ResultView.Data data;

    KyudichiResultScreen(KyudichiProtocol.Result r) {
        super(KyudichiClient.lastDuel ? "1:1 대전 결과" : "큐디치 결과");
        boolean duel = KyudichiClient.lastDuel;
        UUID me = net.minecraft.client.MinecraftClient.getInstance().player == null ? null : net.minecraft.client.MinecraftClient.getInstance().player.getUuid();
        int myTeam = 0; boolean spectator = true;
        for (var l : r.lines()) if (l.id().equals(me)) { myTeam = l.team(); spectator = false; }
        var mine = new ArrayList<ResultView.Line>(); var enemy = new ArrayList<ResultView.Line>();
        for (var l : r.lines()) (l.team() == myTeam ? mine : enemy).add(new ResultView.Line(l.id(), l.nick(), "", l.goals(), l.kills(), l.snitch(), l.mvp(), l.deaths()));
        int myScore = myTeam == 0 ? r.scoreA() : r.scoreB(), enemyScore = myTeam == 0 ? r.scoreB() : r.scoreA();
        boolean rated = r.outcome() != 3 && (r.delta() != 0 || r.outcome() == 2) && !spectator;
        String myLabel = spectator ? (myTeam == 0 ? "파랑 팀" : "빨강 팀") : "우리 팀", enemyLabel = spectator ? "빨강 팀" : "상대 팀";
        if (duel) { myLabel = mine.isEmpty() ? myLabel : mine.get(0).nick(); enemyLabel = enemy.isEmpty() ? enemyLabel : enemy.get(0).nick(); }
        data = new ResultView.Data(r.outcome(), myLabel, enemyLabel, myScore, enemyScore, mine, enemy, r.rating() - r.delta(), r.rating(), rated, duel);
    }

    @Override float designW() { return ResultView.W; }
    @Override float designH() { return ResultView.H; }
    @Override void paint(Pen p) { ResultView.paint(p, data); }
    @Override void clicked(String id, int button) { if (id.equals(ResultView.CONFIRM)) close(); }
}
