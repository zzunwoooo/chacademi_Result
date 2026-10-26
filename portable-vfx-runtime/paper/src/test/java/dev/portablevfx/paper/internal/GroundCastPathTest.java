package dev.portablevfx.paper.internal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundCastPathTest {
    @Test void nineTickHoldKeepsRiseStationaryBeforeFirstMotionSample() {
        for(int tick=0;tick<=9;tick++) assertTrue(GroundCastPath.holding(tick,9));
        assertFalse(GroundCastPath.holding(10,9));
        assertFalse(GroundCastPath.holding(1,0));
    }
    @Test void referencePreviewAndWidthBoundsFitFiniteLifetime() {
        for(double width:new double[]{3.5,7,15.4,64}) assertDoesNotThrow(()->GroundCastPath.validate(width,.4,12,9,200));
        assertDoesNotThrow(()->GroundCastPath.validate(7,4,128,0,200));
    }
    @Test void unsafeValuesAndPathsThatCannotCollapseBeforeExpiryAreRejected() {
        for(double width:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,64.1})
            assertThrows(IllegalArgumentException.class,()->GroundCastPath.validate(width,.4,12,9,200));
        for(double speed:new double[]{0,.099,4.1,Double.NaN})
            assertThrows(IllegalArgumentException.class,()->GroundCastPath.validate(7,speed,12,9,200));
        for(double distance:new double[]{0,128.1,Double.NaN})
            assertThrows(IllegalArgumentException.class,()->GroundCastPath.validate(7,.4,distance,9,200));
        for(int hold:new int[]{-1,101})
            assertThrows(IllegalArgumentException.class,()->GroundCastPath.validate(7,.4,12,hold,200));
        assertThrows(IllegalArgumentException.class,()->GroundCastPath.validate(7,.1,128,9,200));
        assertThrows(IllegalArgumentException.class,()->GroundCastPath.validate(7,.4,12,9,39));
        assertDoesNotThrow(()->GroundCastPath.validate(7,.4,12,9,40));
    }
}
