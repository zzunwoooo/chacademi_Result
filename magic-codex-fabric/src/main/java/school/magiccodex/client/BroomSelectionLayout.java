package school.magiccodex.client;

/** Reference-space geometry shared by drawing and input, including bounded gauge popups. */
final class BroomSelectionLayout {
    static final int PAGE_SIZE=5;
    record Rect(int x,int y,int w,int h) {
        boolean contains(double px,double py){return px>=x&&px<x+w&&py>=y&&py<y+h;}
    }
    static Rect card(int index){return new Rect(86+index*302,145,288,426);}
    static final Rect EQUIP=new Rect(524,848,280,64),BACK=new Rect(866,848,280,64),CLOSE=new Rect(1502,54,90,90);
    static final Rect PREVIOUS=new Rect(74,429,54,68),NEXT=new Rect(1540,429,54,68);
    static int pages(int count){return Math.max(1,(count+PAGE_SIZE-1)/PAGE_SIZE);}
    static int page(int requested,int count){return Math.clamp(requested,0,pages(count)-1);}
    static Rect tooltip(int cardIndex){var r=card(cardIndex);int x=r.x()+r.w()+12;if(x+350>1600)x=r.x()-362;return new Rect(Math.clamp(x,60,1250),Math.clamp(r.y()+110,150,530),350,270);}
    static CodexLayout equipmentFit(int width,int height,boolean broom){
        if(!broom)return CodexLayout.codexFit(width,height);
        float s=Math.min(width/1672f,height/1061f)*.85f;
        return new CodexLayout((width-1672*s)/2,(height-1061*s)/2,s);
    }
    static float equipmentStretch(boolean broom){return 1;}
}
