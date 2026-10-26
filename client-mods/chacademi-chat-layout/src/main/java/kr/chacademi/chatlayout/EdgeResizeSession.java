package kr.chacademi.chatlayout;

/** Eight edge grips in normalized GUI coordinates. Opposite edges stay fixed, without aspect locking. */
public record EdgeResizeSession(double left,double top,double right,double bottom,
        double pointerX,double pointerY,int footer,int edges,int minimumBodyHeight,int reserveBottom) {
    public static final int LEFT=1,RIGHT=2,TOP=4,BOTTOM=8,GRIP=5;
    public record Bounds(double x,double y,double width,double height){}
    public static int edges(LayoutMath.Rect frame,double x,double y){
        if(!frame.contains(x,y))return 0;
        return (x<frame.x()+GRIP?LEFT:x>=frame.right()-GRIP?RIGHT:0)
            |(y<frame.top()+GRIP?TOP:y>=frame.bottom()-GRIP?BOTTOM:0);
    }
    public static EdgeResizeSession begin(LayoutMath.Panel p,double x,double y,int w,int h,int edges,int minimum){
        var f=p.frame();
        return new EdgeResizeSession((double)f.x()/w,(double)f.top()/h,(double)f.right()/w,
            (double)f.bottom()/h,x/w,y/h,f.height()-p.body().height(),edges,minimum,
            f.height()-p.body().height()==HeaderLayout.HEIGHT?InputLayout.ROW_HEIGHT:0);
    }
    public Bounds move(double x,double y,int w,int h){
        double dx=x/w-pointerX,dy=y/h-pointerY,marginX=2.0/w,marginY=2.0/h;
        double minW=(double)LayoutMath.MIN_WIDTH/w;
        // Header and optional native input row use current GUI pixels after a scale change.
        int extra=footer;
        double minH=(double)(minimumBodyHeight+extra)/h;
        double l=left,r=right,t=top,b=bottom;
        if((edges&LEFT)!=0)l=clamp(left+dx,marginX,r-minW);
        if((edges&RIGHT)!=0)r=clamp(right+dx,l+minW,1-marginX);
        if((edges&TOP)!=0)t=clamp(top+dy,marginY,b-minH);
        if((edges&BOTTOM)!=0)b=clamp(bottom+dy,t+minH,1-marginY-(double)reserveBottom/h);
        return new Bounds(l,t,r-l,Math.max((double)minimumBodyHeight/h,b-t-(double)footer/h));
    }
    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(value,max));}
}
