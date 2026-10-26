package kr.chacademi.chatlayout;

/** GUI-coordinate placement shared by painting, pointer hit tests and resizing. */
public final class LayoutMath {
    public static final int MIN_WIDTH=130, MIN_HEIGHT=8, HEADER=12, TAB_RESERVE=15, OPEN_WIDTH=34, BUTTON_WIDTH=9;
    private static final int EDGE=2, INPUT_RESERVE=20;
    private LayoutMath() {}
    public record Rect(int x,int top,int width,int height) {
        public int bottom(){return top+height;} public int right(){return x+width;}
        public boolean contains(double mx,double my){return mx>=x&&mx<right()&&my>=top&&my<bottom();}
    }
    public record Panel(Rect frame,Rect header,Rect body,int tabs,UiState.Mode mode) {
        public Rect control(int index){return new Rect(frame.right()-3-(2-index)*(BUTTON_WIDTH+1),header.top()+(header.height()-10)/2,BUTTON_WIDTH,10);}
        public Rect resizeHandle(){return new Rect(frame.right()-6,frame.bottom()-6,6,6);}
        public boolean nativeBodyVisible(){return mode==UiState.Mode.NORMAL||mode==UiState.Mode.MAXIMIZED;}
    }
    public record Pair(Panel upper,Panel lower,int viewportWidth,int viewportHeight){}
    public static Pair calculate(int w,int h,UiState s,int systemHeight,boolean upperTabs,boolean lowerTabs) {
        if(w<MIN_WIDTH+4||h<90)throw new IllegalArgumentException("Viewport cannot fit usable panels");
        Panel up=panel(w,h,s.upperX,s.upperY,s.upperWidth,s.upperHeight,s.upper,true,systemHeight,upperTabs);
        double x=s.lowerX!=null?s.lowerX:(double)up.frame().x()/w;
        double y=s.lowerY!=null?s.lowerY:(double)up.frame().bottom()/h;
        Panel low=panel(w,h,x,y,s.lowerWidth,s.lowerHeight,s.lower,false,systemHeight,lowerTabs);
        return new Pair(up,low,w,h);
    }
    public static Panel window(int w,int h,WindowState s,int systemHeight,int naturalTabWidth) {
        if(w<MIN_WIDTH+4||h<90)throw new IllegalArgumentException("Viewport cannot fit usable panels");
        Double width=s.width;
        if(width==null&&naturalTabWidth>Math.max(MIN_WIDTH,round(w*0.23))) width=(double)Math.min(naturalTabWidth,w-4)/w;
        Double height=s.height;
        int minimumLine=Math.max(MIN_HEIGHT,(systemHeight+1)/2);
        double preferred=height!=null?h*height:s.systemDefault?systemHeight:h*0.22;
        if(preferred<minimumLine)height=(double)minimumLine/h;
        if(s.mode==UiState.Mode.NORMAL){
            int body=Math.min(h-4-HeaderLayout.HEIGHT-InputLayout.ROW_HEIGHT,
                Math.max(minimumLine,round(height!=null?h*height:s.systemDefault?systemHeight:h*0.22)));
            int bw=Math.min(w-4,Math.max(MIN_WIDTH,round(width!=null?w*width:w*0.23)));
            int x=clamp(round(w*s.x),2,w-bw-2);
            int y=clamp(round(h*s.y),2,h-2-HeaderLayout.HEIGHT-body-InputLayout.ROW_HEIGHT);
            return new Panel(new Rect(x,y,bw,HeaderLayout.HEIGHT+body),
                new Rect(x,y,bw,HeaderLayout.HEIGHT),new Rect(x,y+HeaderLayout.HEIGHT,bw,body),TAB_RESERVE,s.mode);
        }
        Panel result=panel(w,h,s.x,s.y,width,height,s.mode,!s.systemDefault,systemHeight,true);
        if(s.mode==UiState.Mode.MINIMIZED){
            // Reserve room above the tab labels so unread badges cannot cover the header controls.
            int gap=6,top=Math.min(result.frame().top(),h-20-2-result.frame().height()-gap);
            int x=result.frame().x(),bodyWidth=result.body().width();
            return new Panel(new Rect(x,top,result.frame().width(),result.frame().height()+gap),
                    new Rect(x,top,result.header().width(),HEADER),
                    new Rect(x,top+HEADER+gap,bodyWidth,0),result.tabs(),result.mode());
        }
        return result;
    }

    private static Panel panel(int w,int h,double ax,double ay,Double widthRatio,Double heightRatio,
            UiState.Mode mode,boolean top,int systemHeight,boolean hasTabs) {
        boolean max=mode==UiState.Mode.MAXIMIZED,visible=mode==UiState.Mode.NORMAL||max;
        int tabs=hasTabs&&mode!=UiState.Mode.CLOSED?TAB_RESERVE:0;
        int width=max?w-2*EDGE:Math.max(MIN_WIDTH,round(w*(widthRatio!=null?widthRatio:0.23)));
        width=Math.min(width,w-2*EDGE);
        int maxBody=h-INPUT_RESERVE-2*EDGE-HEADER-tabs;
        int preferred=heightRatio!=null?round(h*heightRatio):top?round(h*0.22):systemHeight;
        int body=visible?Math.min(maxBody,Math.max(MIN_HEIGHT,preferred)):0;
        if(max)body=maxBody;
        int full=HEADER+body+tabs;
        int x=max?EDGE:clamp(round(w*ax),EDGE,w-width-EDGE);
        int y=max?EDGE:clamp(round(h*ay),EDGE,h-INPUT_RESERVE-EDGE-full);
        int frameWidth=mode==UiState.Mode.CLOSED?OPEN_WIDTH:width;
        return new Panel(new Rect(x,y,frameWidth,full),new Rect(x,y,frameWidth,HEADER),new Rect(x,y+HEADER,width,body),tabs,mode);
    }
    private static int round(double v){return(int)Math.round(v);}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(v,hi));}
}
