package school.magiccodex.client;

import java.util.ArrayList;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.Util;
import school.magiccodex.client.kui.Pen;
import school.magiccodex.client.kui.PrepareView;
import school.magiccodex.protocol.KyudichiProtocol;

/**
 * 경기 준비 화면 (PrepareView). Opens by itself when a match's 60 s preparation starts; F (the 큐디치 key) opens it again.
 * The centre panel opens the spell picker; 준비 완료 sends READY (in the server-sent preview it only toggles locally).
 */
final class KyudichiPrepareScreen extends KyudichiViewScreen {
    KyudichiPrepareScreen() { super(KyudichiClient.duel() ? "1:1 대전 준비" : "큐디치 경기 준비"); }

    @Override float designW() { return PrepareView.W; }
    @Override float designH() { return PrepareView.H; }
    @Override int dim() { return 0x66060B12; }

    @Override public void tick() {
        if (!KyudichiClient.active() || KyudichiClient.match.phase() != 0 || KyudichiClient.spectator()) close();
    }

    @Override void paint(Pen p) {
        var m = KyudichiClient.match;
        if (m == null) return;
        int left = Math.max(0, m.timeLeftMs() - (int) (Util.getMeasuringTimeMs() - KyudichiClient.matchAt));
        var me = client == null || client.player == null ? null : client.player.getUuid();
        int myTeam = m.myTeam();
        var mine = new ArrayList<PrepareView.Row>(); var enemy = new ArrayList<PrepareView.Row>();
        boolean ready = false;
        for (var person : m.players()) {
            var r = KyudichiClient.roster.get(person.id());
            boolean duel = (m.flags() & 16) != 0; // 1:1 대전 is on the ground: no broom shown
            String broom = duel || r == null ? "" : r.broom();
            String broomName = duel ? "" : broom.isEmpty() ? "연습용 빗자루" : BroomUiLink.text("name", broom);
            var row = new PrepareView.Row(person.id(), person.nick(), "", (person.flags() & 4) != 0, r == null ? -1 : r.house(), broom, broomName, r == null ? 0 : r.rating());
            if (person.id().equals(me)) { ready = row.ready(); mine.add(0, row); }
            else if (person.team() == myTeam) mine.add(row); else enemy.add(row);
        }
        var spells = new ArrayList<PrepareView.Spell>();
        var loadout = KyudichiLoadout.get();
        var keys = loadout.bindings(CastingClient.base().orderedSlots());
        for (int i = 0; i < KyudichiLoadout.SIZE; i++) {
            var spell = CastingClient.state().spell(loadout.get(i));
            int code = keys.get(i).keyCode();
            spells.add(spell == null ? null : new PrepareView.Spell(spell.icon(), spell.name(), code < 0 ? "" : KeySettingsLayout.name(code)));
        }
        PrepareView.paint(p, new PrepareView.Data(left, mine, enemy, spells, ready, true));
    }

    @Override void clicked(String id, int button) {
        switch (id) {
            case PrepareView.SPELLS -> client.setScreen(new KyudichiSpellPickScreen(this));
            case PrepareView.READY -> toggleReady();
            default -> { }
        }
    }

    private void toggleReady() {
        if (KyudichiPreview.active()) { KyudichiPreview.toggleReady(); return; }
        var m = KyudichiClient.match; if (m == null || client.player == null) return;
        boolean ready = m.players().stream().anyMatch(p -> p.id().equals(client.player.getUuid()) && (p.flags() & 4) != 0);
        if (!ready && !KyudichiLoadout.get().duel() && KyudichiLoadout.get().flightSlot() < 0) return;
        if (ClientPlayNetworking.canSend(KyudichiClient.Payload.ID))
            ClientPlayNetworking.send(new KyudichiClient.Payload(KyudichiProtocol.encode(new KyudichiProtocol.Ready(!ready))));
    }

    @Override public void close() { KyudichiLoadout.get().save(); CastingClient.refreshBindings(); super.close(); }

}
