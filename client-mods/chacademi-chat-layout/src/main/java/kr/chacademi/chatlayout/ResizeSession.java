package kr.chacademi.chatlayout;

/** Resize deltas use screen fractions, so mid-gesture GUI scaling does not multiply movement. */
public record ResizeSession(double startWidth,double startHeight,double startMouseX,double startMouseY) {
    public record Size(double width,double height){}
    public static ResizeSession begin(int bodyWidth,int bodyHeight,double mx,double my,int w,int h) {
        return new ResizeSession((double)bodyWidth/w,(double)bodyHeight/h,mx/w,my/h);
    }
    public Size move(double mx,double my,int w,int h) {
        return new Size(Math.max((double)LayoutMath.MIN_WIDTH/w,startWidth+mx/w-startMouseX),
                Math.max((double)LayoutMath.MIN_HEIGHT/h,startHeight+my/h-startMouseY));
    }
}
