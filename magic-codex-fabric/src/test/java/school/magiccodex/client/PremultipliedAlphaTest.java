package school.magiccodex.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremultipliedAlphaTest {
    @Test void hiddenRgbCannotBleedIntoFilteredEdges() {
        assertEquals(0,PremultipliedAlpha.pixel(0x00FF0000));
        assertEquals(0,PremultipliedAlpha.pixel(0x000000FF));
    }
    @Test void opaqueColorIsUnchanged() {
        assertEquals(0xFFDBAD71,PremultipliedAlpha.pixel(0xFFDBAD71));
    }
    @Test void partialAlphaIsAppliedOnceAndPreserved() {
        assertEquals(0x80804020,PremultipliedAlpha.pixel(0x80FF8040));
    }
    @Test void halfCoveredRedEdgeStaysRedInsteadOfMixingInvisibleBlue() {
        int red=PremultipliedAlpha.pixel(0xFFFF0000),hiddenBlue=PremultipliedAlpha.pixel(0x000000FF);
        double a=((red>>>24)+(hiddenBlue>>>24))/2.0;
        double r=(((red>>>16)&255)+((hiddenBlue>>>16)&255))/2.0;
        double b=((red&255)+(hiddenBlue&255))/2.0;
        assertEquals(255,r/a*255,0.001);
        assertEquals(0,b);
    }
}
