package school.magiccodex.paper;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import school.magiccodex.protocol.PermissionProtocol;
import static org.junit.jupiter.api.Assertions.*;

class PermissionSubscriptionsTest {
    final PermissionSubscriptions engine = new PermissionSubscriptions(10000, 45000, PermissionProtocol.MAX_PERMISSIONS);
    final UUID player = UUID.randomUUID();
    final FakeAccess access = new FakeAccess();
    static class FakeAccess implements PermissionSubscriptions.Access {
        int checks;
        boolean granted, online = true;
        final List<PermissionProtocol.Response> sent = new ArrayList<>();
        final Set<UUID> served = new HashSet<>();
        public boolean online(UUID id) { return online; }
        public boolean hasPermission(UUID id, String permission) { checks++; return granted; }
        public void send(UUID id, PermissionProtocol.Response response) { served.add(id); sent.add(response); }
    }
    private void open(UUID id, long generation, int count) {
        engine.accept(id, new PermissionProtocol.Request(PermissionProtocol.OPEN, generation,
                IntStream.range(0, count).mapToObj(i -> "magic.learned.spell_" + i).toList()), 0);
    }
    @Test void closedCodicesPerformNoPermissionChecks() {
        engine.process(0, access); assertEquals(0, access.checks);
        open(player, 1, 18); engine.process(0, access);
        engine.accept(player, new PermissionProtocol.Request(PermissionProtocol.CLOSE, 1, List.of()), 100);
        engine.process(20000, access);
        assertEquals(18, access.checks); assertEquals(0, engine.size());
    }
    @Test void unchangedResultsAreNotSentAndNoChecksBetweenRefreshes() {
        open(player, 1, 18); engine.process(0, access);
        for (int i = 1; i < 10; i++) engine.process(i * 1000, access);
        assertEquals(18, access.checks); assertEquals(1, access.sent.size());
        engine.process(10000, access);
        assertEquals(36, access.checks); assertEquals(1, access.sent.size());
    }
    @Test void dirtyEventsCoalesceAndRevocationIsSent() {
        open(player, 1, 2); engine.process(0, access);
        access.granted = true;
        for (int i = 0; i < 1000; i++) engine.dirty(player);
        engine.process(1000, access);
        assertEquals(4, access.checks);
        assertEquals(List.of(true, true), access.sent.getLast().granted());
        access.granted = false; engine.dirty(player); engine.process(2000, access);
        assertEquals(List.of(false, false), access.sent.getLast().granted());
        assertEquals(3, access.sent.size());
    }
    @Test void burstIsSpreadAcrossPassesWithoutStarvation() {
        for (int i = 0; i < 20; i++) open(new UUID(0, i), 1, PermissionProtocol.MAX_PERMISSIONS);
        for (int i = 0; i < 20; i++) {
            int before = access.checks;
            engine.process(i * 1000, access);
            assertTrue(access.checks - before <= PermissionProtocol.MAX_PERMISSIONS);
        }
        assertEquals(20, access.served.size());
        assertEquals(20 * PermissionProtocol.MAX_PERMISSIONS, access.checks);
    }
    @Test void immediateAndTimerPassesCannotMultiplyTheOneSecondBudget() {
        open(player, 1, PermissionProtocol.MAX_PERMISSIONS);
        open(UUID.randomUUID(), 1, PermissionProtocol.MAX_PERMISSIONS);
        engine.process(0, access);
        engine.process(50, access);
        engine.process(500, access);
        engine.process(999, access);
        assertEquals(PermissionProtocol.MAX_PERMISSIONS, access.checks);
        assertEquals(1, access.sent.size());
        engine.process(1000, access);
        assertEquals(2 * PermissionProtocol.MAX_PERMISSIONS, access.checks);
        assertEquals(2, access.sent.size());
    }
    @Test void staleCloseAndKeepaliveCannotAffectNewGeneration() {
        open(player, 2, 1);
        engine.accept(player, new PermissionProtocol.Request(PermissionProtocol.CLOSE, 1, List.of()), 100);
        assertEquals(1, engine.size());
        engine.accept(player, new PermissionProtocol.Request(PermissionProtocol.KEEPALIVE, 1, List.of()), 44000);
        engine.process(46000, access);
        assertEquals(0, engine.size()); assertEquals(0, access.checks);
    }
    @Test void keepaliveRenewsLeaseWithoutCheckingPermissions() {
        open(player, 1, 1); engine.process(0, access);
        engine.accept(player, new PermissionProtocol.Request(PermissionProtocol.KEEPALIVE, 1, List.of()), 30000);
        assertEquals(1, access.checks);
        engine.process(46000, access); assertEquals(1, engine.size());
        engine.process(76000, access); assertEquals(0, engine.size());
    }
    @Test void offlinePlayersAndUnknownDirtyEventsAreDiscarded() {
        engine.dirty(player); engine.process(0, access); assertEquals(0, access.checks);
        open(player, 1, 1); access.online = false; engine.process(0, access);
        assertEquals(0, engine.size()); assertEquals(0, access.checks);
    }
    @Test void openRateLimitIsPerPlayerAndQuitClearsIt() {
        assertTrue(engine.allowOpen(player, 0)); assertFalse(engine.allowOpen(player, 999));
        assertTrue(engine.allowOpen(UUID.randomUUID(), 100));
        assertTrue(engine.allowOpen(player, 1000)); engine.remove(player);
        assertTrue(engine.allowOpen(player, 1001));
    }
    @Test void newOpenReceivesSnapshotEvenWhenBitsMatchPrevious() {
        open(player, 1, 1); engine.process(0, access);
        open(player, 2, 1); engine.process(1000, access);
        assertEquals(2, access.sent.size()); assertEquals(2, access.sent.getLast().id());
    }
}
