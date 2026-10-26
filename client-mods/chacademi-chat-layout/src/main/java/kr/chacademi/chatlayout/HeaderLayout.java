package kr.chacademi.chatlayout;
import kr.chacademi.chatlayout.LayoutMath.Panel;
import kr.chacademi.chatlayout.LayoutMath.Rect;
/** Persistent single header: tabs left, lock and close controls right; no lower tab strip. */
public final class HeaderLayout {
    public static final int HEIGHT=16, CONTROLS=24;
    private HeaderLayout(){}
    public static Panel apply(Panel original,int naturalWidth,int viewportWidth){
        if(original.mode()==UiState.Mode.CLOSED)return original;
        int width=Math.min(viewportWidth-4,original.frame().width());
        int x=Math.max(2,Math.min(original.frame().x(),viewportWidth-width-2));
        int y=original.frame().top(),bodyHeight=original.mode()==UiState.Mode.MAXIMIZED?original.frame().height()-HEIGHT:original.body().height();
        return new Panel(new Rect(x,y,width,HEIGHT+bodyHeight),new Rect(x,y,width,HEIGHT),
                new Rect(x,y+HEIGHT,width,bodyHeight),original.tabs(),original.mode());
    }
    public static Rect tabs(Panel panel){
        Rect header=panel.header();
        int right=panel.control(0).x()-2;
        return new Rect(header.x()+3,header.top()+1,Math.max(0,right-header.x()-3),header.height()-2);
    }
}