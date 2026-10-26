package school.magiccodex.client;

import net.minecraft.util.Util;
import school.magiccodex.client.kui.AcceptView;
import school.magiccodex.client.kui.Pen;
import school.magiccodex.protocol.KyudichiQueueProtocol;

/** 경기 수락 팝업 (AcceptView): opens when a match is found, closes when the offer ends (accepted by all, declined, timed out). */
final class KyudichiAcceptScreen extends KyudichiViewScreen {
    KyudichiAcceptScreen() { super("큐디치 경기 수락"); }

    @Override float designW() { return AcceptView.W; }
    @Override float designH() { return AcceptView.H; }
    @Override float fill() { return .7f; }
    @Override int dim() { return 0x55060B12; }

    @Override public void tick() { if (KyudichiQueueClient.state() != KyudichiQueueProtocol.S_OFFER) close(); }

    @Override void paint(Pen p) {
        var s = KyudichiQueueClient.status;
        int left = Math.max(0, s.offerLeftSec() - (int) ((Util.getMeasuringTimeMs() - KyudichiQueueClient.statusAt) / 1000));
        AcceptView.paint(p, new AcceptView.Data(left, s.accepted(), Math.max(1, s.total()), s.iAccepted()));
    }

    @Override void clicked(String id, int button) {
        if (id.equals(AcceptView.ACCEPT)) KyudichiQueueClient.send(new KyudichiQueueProtocol.Accept());
        if (id.equals(AcceptView.DECLINE)) KyudichiQueueClient.send(new KyudichiQueueProtocol.Decline());
    }
}
