package kr.chacademi.chatlayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingViewRestoreTest {
    private record EqualKey(String value) {}

    /** Models the native getNormalizedHeight integer remainder with a copied scale but cold line cache. */
    private static final class CachedRenderer {
        int lineHeight;
        float scale = 1.0f;
        int restores, scroll = -1;
        boolean newMessages;

        boolean ready() { return PendingViewRestore.cacheReady(lineHeight, scale); }

        void restore(PendingViewRestore.Snapshot snapshot) {
            int bodyHeight = 18;
            int normalizedHeight = bodyHeight - bodyHeight % lineHeight;
            int visibleRows = (int)(normalizedHeight / lineHeight / scale);
            scroll = Math.min(snapshot.scroll(), Math.max(0, 100 - visibleRows));
            newMessages = snapshot.newMessages();
            restores++;
        }
    }

    @Test void coldWidthRescaleAndFinishedRefreshWaitForWarmGeometry() {
        Object tab = new Object();
        var pending = new PendingViewRestore<Object>();
        var renderer = new CachedRenderer();
        var snapshot = new PendingViewRestore.Snapshot(27, true);
        // The old unconditional restoration reaches the native integer remainder while lineHeight is zero.
        assertThrows(ArithmeticException.class, () -> renderer.restore(snapshot));
        pending.capture(tab, snapshot.scroll(), snapshot.newMessages());

        assertDoesNotThrow(() -> pending.onRescale(tab, renderer.ready(), renderer::restore));
        assertDoesNotThrow(() -> pending.onRefresh(tab, false, renderer.ready(), renderer::restore));
        assertDoesNotThrow(() -> pending.onGeometryReady(tab, renderer.ready(), renderer::restore));
        assertEquals(0, renderer.restores);
        assertTrue(pending.contains(tab));

        renderer.lineHeight = 9;
        pending.onGeometryReady(tab, renderer.ready(), renderer::restore);
        assertEquals(1, renderer.restores);
        assertEquals(27, renderer.scroll);
        assertTrue(renderer.newMessages);
        assertFalse(pending.contains(tab));
        pending.onGeometryReady(tab, true, renderer::restore);
        pending.onRescale(tab, true, renderer::restore);
        pending.onRefresh(tab, false, true, renderer::restore);
        assertEquals(1, renderer.restores);
    }

    @Test void warmRepeatedRescalesKeepSnapshotUntilActualRefreshCompletes() {
        Object tab = new Object();
        var pending = new PendingViewRestore<Object>();
        var renderer = new CachedRenderer();
        renderer.lineHeight = 9;
        pending.capture(tab, 11, false);
        pending.onRescale(tab, true, renderer::restore);
        renderer.scroll = 0; // Another native resize resets the same view before its refresh.
        pending.onRescale(tab, true, renderer::restore);
        pending.onGeometryReady(tab, true, renderer::restore);
        assertEquals(11, renderer.scroll);
        assertEquals(3, renderer.restores);
        assertTrue(pending.contains(tab));

        pending.onRefresh(tab, false, true, renderer::restore);
        assertFalse(pending.contains(tab));
        assertEquals(4, renderer.restores);
        pending.onRescale(tab, true, renderer::restore);
        assertEquals(4, renderer.restores);
    }

    @Test void refreshingEarlyReturnDoesNotMarkSnapshotCompletedOrConsumeIt() {
        Object tab = new Object();
        var pending = new PendingViewRestore<Object>();
        var restored = new ArrayList<PendingViewRestore.Snapshot>();
        pending.capture(tab, 8, true);
        pending.onRefresh(tab, true, true, restored::add);
        assertTrue(restored.isEmpty());
        assertTrue(pending.contains(tab));
        pending.onGeometryReady(tab, true, restored::add);
        assertEquals(1, restored.size());
        assertTrue(pending.contains(tab)); // The early return was not an actual completed refresh.
        pending.onRefresh(tab, false, true, restored::add);
        assertEquals(2, restored.size());
        assertFalse(pending.contains(tab));
    }

    @Test void completedColdRefreshCanCommitOnTheNextReadyRescale() {
        Object tab = new Object();
        var pending = new PendingViewRestore<Object>();
        var restored = new ArrayList<PendingViewRestore.Snapshot>();
        pending.capture(tab, 19, false);
        pending.onRefresh(tab, false, false, restored::add);
        assertTrue(restored.isEmpty());
        pending.onRescale(tab, true, restored::add);
        assertEquals(List.of(new PendingViewRestore.Snapshot(19, false)), restored);
        assertFalse(pending.contains(tab));
    }

    @Test void completedColdRefreshCanCommitOnALaterReadyRefresh() {
        Object tab = new Object();
        var pending = new PendingViewRestore<Object>();
        var restored = new ArrayList<PendingViewRestore.Snapshot>();
        pending.capture(tab, 20, true);
        pending.onRefresh(tab, false, false, restored::add);
        pending.onRefresh(tab, false, true, restored::add);
        assertEquals(List.of(new PendingViewRestore.Snapshot(20, true)), restored);
        assertFalse(pending.contains(tab));
    }

    @Test void equalButDistinctTabsOwnSeparatePendingSnapshots() {
        EqualKey first = new EqualKey("same"), second = new EqualKey("same");
        assertEquals(first, second);
        var pending = new PendingViewRestore<EqualKey>();
        var restored = new ArrayList<PendingViewRestore.Snapshot>();
        pending.capture(first, 1, true);
        pending.capture(second, 2, false);
        pending.onRefresh(first, false, true, restored::add);
        assertFalse(pending.contains(first));
        assertTrue(pending.contains(second));
        pending.onRefresh(second, false, true, restored::add);
        assertEquals(List.of(new PendingViewRestore.Snapshot(1, true),
                            new PendingViewRestore.Snapshot(2, false)), restored);
    }

    @Test void removedTabsAreDiscardedWhileEqualLiveIdentityRemains() {
        EqualKey removed = new EqualKey("same"), live = new EqualKey("same");
        var pending = new PendingViewRestore<EqualKey>();
        pending.capture(removed, 4, true);
        pending.capture(live, 9, false);
        Set<EqualKey> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        identities.add(live);
        pending.retainKeys(identities);
        assertFalse(pending.contains(removed));
        assertTrue(pending.contains(live));
        var restored = new ArrayList<PendingViewRestore.Snapshot>();
        pending.onRefresh(live, false, true, restored::add);
        assertEquals(List.of(new PendingViewRestore.Snapshot(9, false)), restored);
    }

    @Test void failedCompletedRefreshRetainsSnapshotForGeometryRetry() {
        Object tab = new Object();
        var pending = new PendingViewRestore<Object>();
        pending.capture(tab, 7, true);
        assertThrows(IllegalStateException.class,
            () -> pending.onRefresh(tab, false, true, snapshot -> { throw new IllegalStateException("retry"); }));
        assertTrue(pending.contains(tab));
        var restored = new ArrayList<PendingViewRestore.Snapshot>();
        pending.onGeometryReady(tab, true, restored::add);
        assertEquals(List.of(new PendingViewRestore.Snapshot(7, true)), restored);
        assertFalse(pending.contains(tab));
    }

    @Test void failedGeometryCommitRetainsCompletedSnapshotForRetry() {
        Object tab = new Object();
        var pending = new PendingViewRestore<Object>();
        pending.capture(tab, 13, false);
        pending.onRefresh(tab, false, false, snapshot -> fail("Cold refresh must defer"));
        assertThrows(IllegalArgumentException.class,
            () -> pending.onGeometryReady(tab, true, snapshot -> { throw new IllegalArgumentException("retry"); }));
        assertTrue(pending.contains(tab));
        var restored = new ArrayList<PendingViewRestore.Snapshot>();
        pending.onGeometryReady(tab, true, restored::add);
        assertEquals(List.of(new PendingViewRestore.Snapshot(13, false)), restored);
        assertFalse(pending.contains(tab));
    }

    @Test void failedWarmRescaleRetainsIncompleteSnapshotUntilRefresh() {
        Object tab = new Object();
        var pending = new PendingViewRestore<Object>();
        pending.capture(tab, 6, false);
        assertThrows(IllegalStateException.class,
            () -> pending.onRescale(tab, true, snapshot -> { throw new IllegalStateException("retry"); }));
        var restored = new ArrayList<PendingViewRestore.Snapshot>();
        pending.onGeometryReady(tab, true, restored::add);
        assertTrue(pending.contains(tab));
        pending.onRefresh(tab, false, true, restored::add);
        assertEquals(List.of(new PendingViewRestore.Snapshot(6, false),
                            new PendingViewRestore.Snapshot(6, false)), restored);
        assertFalse(pending.contains(tab));
    }

    @Test void freshCaptureResetsAnOlderCompletedRefreshMarker() {
        Object tab = new Object();
        var pending = new PendingViewRestore<Object>();
        var restored = new ArrayList<PendingViewRestore.Snapshot>();
        pending.capture(tab, 3, false);
        pending.onRefresh(tab, false, false, restored::add);
        pending.capture(tab, 21, true); // A second move occurs before the first cold view can commit.
        pending.onGeometryReady(tab, true, restored::add);
        assertTrue(pending.contains(tab));
        pending.onRefresh(tab, false, true, restored::add);
        assertEquals(List.of(new PendingViewRestore.Snapshot(21, true),
                            new PendingViewRestore.Snapshot(21, true)), restored);
        assertFalse(pending.contains(tab));
    }

    @Test void captureDuringRestoreIsNotConsumedWithTheOlderEntry() {
        Object tab = new Object();
        var pending = new PendingViewRestore<Object>();
        var restored = new ArrayList<PendingViewRestore.Snapshot>();
        pending.capture(tab, 3, false);
        pending.onRefresh(tab, false, true, snapshot -> {
            restored.add(snapshot);
            pending.capture(tab, 25, true);
        });
        assertTrue(pending.contains(tab));
        pending.onRefresh(tab, false, true, restored::add);
        assertEquals(List.of(new PendingViewRestore.Snapshot(3, false),
                            new PendingViewRestore.Snapshot(25, true)), restored);
        assertFalse(pending.contains(tab));
    }

    @Test void callbacksForAnUntrackedTabDoNotRestoreOrConsumeTheLiveTab() {
        Object live = new Object(), other = new Object();
        var pending = new PendingViewRestore<Object>();
        pending.capture(live, 2, true);
        pending.onRescale(other, true, snapshot -> fail("Untracked tab"));
        pending.onRefresh(other, false, true, snapshot -> fail("Untracked tab"));
        pending.onGeometryReady(other, true, snapshot -> fail("Untracked tab"));
        assertTrue(pending.contains(live));
    }

    @Test void readinessRequiresPositiveLineHeightAndFinitePositiveCachedScale() {
        assertTrue(PendingViewRestore.cacheReady(9, 1.0f));
        assertTrue(PendingViewRestore.cacheReady(1, 0.001f));
        assertFalse(PendingViewRestore.cacheReady(0, 1.0f));
        assertFalse(PendingViewRestore.cacheReady(-9, 1.0f));
        assertFalse(PendingViewRestore.cacheReady(9, 0.0f));
        assertFalse(PendingViewRestore.cacheReady(9, -0.0f));
        assertFalse(PendingViewRestore.cacheReady(9, -1.0f));
        assertFalse(PendingViewRestore.cacheReady(9, Float.NaN));
        assertFalse(PendingViewRestore.cacheReady(9, Float.POSITIVE_INFINITY));
        assertFalse(PendingViewRestore.cacheReady(9, Float.NEGATIVE_INFINITY));
    }
}