package kr.chacademi.chatlayout;

import java.util.List;
import kr.chacademi.chatlayout.LayoutMath.Rect;

/** Hit the displayed tab rectangles, including an input-row offset, clipped to their window. */
public final class TabHitTest {
    private TabHitTest() {}
    public static int find(List<Rect> tabs,int first,Rect frame,double x,double y){
        if(!frame.contains(x,y))return -1;
        for(int i=Math.max(0,first);i<tabs.size();i++){
            if(tabs.get(i).contains(x,y))return i;
        }
        return -1;
    }
}