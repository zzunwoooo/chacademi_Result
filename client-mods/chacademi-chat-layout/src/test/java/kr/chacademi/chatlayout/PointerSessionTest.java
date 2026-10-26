package kr.chacademi.chatlayout;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PointerSessionTest {
    @Test void movementUsesFractionsAcrossGuiScaleChange() {
        var drag=DragSession.begin(.1,.2,100,200,1000,1000);
        var moved=drag.move(100,150,500,500);
        assertEquals(.2,moved.x(),1e-9);assertEquals(.3,moved.y(),1e-9);
    }
    @Test void resizeUsesFractionsAcrossGuiScaleChangeAndClampsMinimum() {
        var resize=ResizeSession.begin(300,200,400,500,1000,1000);
        var resized=resize.move(250,300,500,500);
        assertEquals(.4,resized.width(),1e-9);assertEquals(.3,resized.height(),1e-9);
        var minimum=resize.move(0,0,500,500);
        assertEquals(.26,minimum.width(),1e-9);assertEquals(.016,minimum.height(),1e-9);
    }
}
