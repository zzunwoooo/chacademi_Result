package school.magiccodex.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HudTextureStatsTest {
    @Test void closedInstancesLeaveTheGlobalTotals(){
        var before=HudTextureStats.snapshot();
        HudTextureStats.opened();HudTextureStats.uploaded(1000);HudTextureStats.uploaded(500);HudTextureStats.evicted(500);
        var during=HudTextureStats.snapshot();assertEquals(before.instances()+1,during.instances());assertEquals(before.textures()+1,during.textures());assertEquals(before.bytes()+1000,during.bytes());assertEquals(before.uploads()+2,during.uploads());
        HudTextureStats.closed(1000,1);
        var after=HudTextureStats.snapshot();assertEquals(before.instances(),after.instances());assertEquals(before.textures(),after.textures());assertEquals(before.bytes(),after.bytes());
    }
}
