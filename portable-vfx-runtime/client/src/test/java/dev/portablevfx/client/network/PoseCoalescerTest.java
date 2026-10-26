package dev.portablevfx.client.network;

import dev.portablevfx.protocol.EffectBasis;
import dev.portablevfx.protocol.PoseEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PoseCoalescerTest {
    private static PoseEffect pose(UUID id, long sequence) {
        return new PoseEffect(id, "minecraft:overworld", sequence, sequence, 0, 0, EffectBasis.identity());
    }

    @Test void keepsNewestPerInstanceAndNeverRegresses() {
        var poses = new PoseCoalescer(); UUID a = UUID.randomUUID();
        poses.offer(pose(a, 3)); poses.offer(pose(a, 5)); poses.offer(pose(a, 4));
        List<PoseEffect> applied = new ArrayList<>(); poses.drain(applied::add);
        assertEquals(List.of(pose(a, 5)), applied);
        assertEquals(0, poses.size());
    }

    @Test void evictsStalestInstanceAtCapacity() {
        var poses = new PoseCoalescer(); UUID stale = UUID.randomUUID(), fresh = UUID.randomUUID();
        poses.offer(pose(stale, 1)); poses.offer(pose(fresh, 1));
        for (int i = 0; i < PoseCoalescer.MAX_PENDING - 2; i++) poses.offer(pose(UUID.randomUUID(), 1));
        poses.offer(pose(fresh, 2)); // refreshed: moves behind every other instance
        poses.offer(pose(UUID.randomUUID(), 1));
        assertEquals(PoseCoalescer.MAX_PENDING, poses.size());
        assertNull(poses.take(stale), "least recently refreshed pose is dropped first");
        assertEquals(pose(fresh, 2), poses.take(fresh));
    }

    @Test void directPosesAndStopsSupersedeDeferredOnes() {
        var poses = new PoseCoalescer(); UUID a = UUID.randomUUID();
        poses.offer(pose(a, 1)); poses.supersede(a);
        assertNull(poses.take(a));
        poses.offer(pose(a, 2)); poses.clear(); assertEquals(0, poses.size());
    }
}
