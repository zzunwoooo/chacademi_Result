package kr.chacademi.chatlayout;
import kr.chacademi.chatlayout.LayoutMath.Rect;
/** Compact two-column controls stay inside each pane and outside its message text. */
public final class AppearanceControls {
    public static final int RESERVE=24;
    private AppearanceControls(){}
    public static Rect button(Rect body,int index){
        if(index<0||index>3)throw new IllegalArgumentException("control index");
        int h=Math.min(10,Math.max(1,(body.height()-3)/2));
        int y=index<2?body.top()+1:Math.max(body.top()+h+2,body.bottom()-h-1);
        return new Rect(body.right()-23+(index%2)*11,y,10,h);
    }
    public static double opacity(double value,int direction){
        return Math.round(Math.max(0,Math.min(1,value+direction*0.05))*100)/100.0;
    }
    public static float scale(float value,int direction){
        return Math.round(Math.max(0.5,Math.min(2,value+direction*0.05))*100)/100f;
    }
    public static int rightPadding(float scale){
        return Math.max(1,Math.round((ContentInsets.GUI+RESERVE)/scale));
    }
}