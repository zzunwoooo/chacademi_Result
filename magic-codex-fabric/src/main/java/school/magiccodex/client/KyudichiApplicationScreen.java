package school.magiccodex.client;

import java.util.ArrayList;
import net.minecraft.util.Util;
import school.magiccodex.client.kui.ApplicationView;
import school.magiccodex.client.kui.Pen;
import school.magiccodex.protocol.KyudichiQueueProtocol;

/**
 * 큐디치 신청 창 (ApplicationView): /큐디치 opens it (the proxy sends APPLY). 매칭 시작 = JOIN; while queued the
 * 매칭 대기 중 card is drawn beside it (top right, as in the reference) with 취소.
 */
final class KyudichiApplicationScreen extends KyudichiViewScreen {
    KyudichiApplicationScreen() { super("큐디치 신청"); }

    @Override float designW() { return ApplicationView.W; }
    @Override float designH() { return ApplicationView.H; }

    @Override void paint(Pen p) {
        var a = KyudichiQueueClient.apply;
        if (a == null) return;
        var members = new ArrayList<ApplicationView.Member>();
        for (var m : a.members()) members.add(new ApplicationView.Member(m.id(), m.nick(), m.skin(), m.house(), m.rating(), m.leader()));
        int st = KyudichiQueueClient.state();
        boolean queued = st == KyudichiQueueProtocol.S_SEARCHING || st == KyudichiQueueProtocol.S_OFFER || st == KyudichiQueueProtocol.S_MOVING;
        ApplicationView.paint(p, new ApplicationView.Data(a.myRating(), a.wins(), a.losses(), a.baseRating(), members, a.canStart(), a.note(), queued));
        if (st == KyudichiQueueProtocol.S_SEARCHING) {
            int waited = KyudichiQueueClient.status.waitedSec() + (int) ((Util.getMeasuringTimeMs() - KyudichiQueueClient.statusAt) / 1000);
            ApplicationView.queue(p, new ApplicationView.Queue(waited, ApplicationView.range(waited), false), Util.getMeasuringTimeMs());
        }
    }

    @Override void clicked(String id, int button) {
        switch (id) {
            case ApplicationView.START -> KyudichiQueueClient.send(new KyudichiQueueProtocol.Join());
            case ApplicationView.CANCEL -> KyudichiQueueClient.send(new KyudichiQueueProtocol.Leave());
            default -> { }
        }
    }
}
