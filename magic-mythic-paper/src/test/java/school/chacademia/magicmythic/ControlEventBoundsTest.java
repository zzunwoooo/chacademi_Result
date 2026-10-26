package school.chacademia.magicmythic;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ControlEventBoundsTest {
    @Test void reactionControlsClampStackedArtifactsAndNormalBonus() {
        var event = new MagicReactionEvent(null,null,"bind","water","earth");
        assertTrue(event.isControl());
        event.multiplyControl(.1); assertEquals(.25,event.getControlMultiplier());
        event.multiplyControl(100); assertEquals(3,event.getControlMultiplier());
        event.setNormalControlBonusTicks(1000); assertEquals(60,event.getNormalControlBonusTicks());
        event.setNormalControlBonusTicks(-1); assertEquals(0,event.getNormalControlBonusTicks());
        for(var id:new String[]{"steam","rainbow","flash"})
            assertFalse(new MagicReactionEvent(null,null,id,"water","earth").isControl());
    }
    @Test void statusCancellationIsIndependentOfMinimumDuration() {
        var event = new MagicStatusEvent(null,null,"stun",20);
        event.setTicks(0); assertEquals(1,event.getTicks());
        event.setCancelled(true); assertTrue(event.isCancelled());
        event.setTicks(8); assertEquals(8,event.getTicks()); assertTrue(event.isCancelled());
    }
}
