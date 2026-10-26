package kr.chacademi.chatlayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ContentInsetsTest {
    @Test void messageAndTabEdgesMatchWhileInputReservesGlyphOverhang(){
        var body=new LayoutMath.Rect(47,60,130,18);
        var frame=new LayoutMath.Rect(47,48,130,45);
        var pane=new LayoutMath.Panel(frame,new LayoutMath.Rect(47,48,130,12),body,15,UiState.Mode.NORMAL);
        var field=InputLayout.field(InputLayout.attach(pane));
        assertEquals(57,field.x());
        assertEquals(110,field.width());
        assertEquals(field.x()-5,body.x()+ContentInsets.TAB_BACKGROUND+2);
        for(float scale:new float[]{0.25f,0.5f,0.75f,0.9f,1f}){
            float messageX=body.x()+ContentInsets.nativePadding(scale)*scale;
            assertEquals(field.x()-5,messageX,scale/2.0+0.0001);
        }
    }
    @Test void nativeWrappingKeepsTheSameLeftAndRightVisibleMargins(){
        for(float scale:new float[]{0.25f,0.5f,0.75f,0.9f,1f}){
            int padding=ContentInsets.nativePadding(scale);
            double visibleTextWidth=(130.0/scale-2*padding)*scale;
            double left=padding*scale;
            double right=130-left-visibleTextWidth;
            assertEquals(left,right,0.00001);
            assertEquals(5,left,scale/2.0+0.0001);
            assertTrue(visibleTextWidth>118);
        }
    }
    @Test void guiScaleOnlyMultipliesTheFinalCommonMargin(){
        for(int guiScale:new int[]{1,2,3,4}){
            assertEquals(5*guiScale,ContentInsets.nativePadding(1)*guiScale);
            assertEquals(5*guiScale,(ContentInsets.TAB_BACKGROUND+2)*guiScale);
        }
    }
    @Test void unusableTextScalesFailBeforeAnyNativePaddingMutation(){
        for(float scale:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->ContentInsets.nativePadding(scale));
    }
    @Test void messagesStopAboveBottomBorderEvenWhenInputExtendsTheFrame(){
        var s=new WindowState();
        var p=HeaderLayout.apply(LayoutMath.window(480,270,s,23,205),205,480);
        var typing=InputLayout.attach(p);
        var clip=ContentInsets.messageClip(p.body());
        assertEquals(clip,ContentInsets.messageClip(typing.body()));
        assertEquals(p.body().bottom()-1,clip.bottom());
        assertFalse(clip.contains(p.body().x()+10,p.body().bottom()));
        assertFalse(clip.contains(p.body().x()+10,InputLayout.field(typing).top()));
        assertTrue(clip.right()<p.frame().right());
    }
    @Test void clippingRemainsInsideTinyAndResizedBodies(){
        for(int height:new int[]{1,8,18,100,400}){
            var body=new LayoutMath.Rect(4,20,130,height);
            var clip=ContentInsets.messageClip(body);
            assertTrue(clip.top()>=body.top());
            assertTrue(clip.bottom()<body.bottom());
            assertTrue(clip.height()>=0);
        }
    }
}
