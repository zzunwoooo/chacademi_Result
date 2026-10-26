package school.magiccodex.client;

/** PNGs, text and hit targets all use the approved 1080 x 880 coordinate system. */
public final class TitleLayout {
    public record Fit(float x,float y,float scale){public double localX(double x){return (x-this.x)/scale;}public double localY(double y){return (y-this.y)/scale;}}
    // Approved header reference was 1014px across this 1080px layout. Normalize its relative shift.
    private static final float REFERENCE_SCALE=1014f/1080f;
    public static final int CLOSE_X=999-Math.round(13/REFERENCE_SCALE),CLOSE_Y=24+Math.round(25/REFERENCE_SCALE),CLOSE_SIZE=42;
    public static boolean close(double x,double y){return contains(x,y,CLOSE_X,CLOSE_Y,CLOSE_SIZE,CLOSE_SIZE);}
    public static Fit fit(int width,int height){float s=Math.max(.02f,Math.min((width-24)/1080f,(height-24)/880f));return new Fit((width-1080*s)/2,(height-880*s)/2,s);}
    public static int row(double x,double y,int side){int left=side==0?64:576;if(x<left||x>=left+426||y<416||y>=708)return -1;int row=(int)((y-416)/60);return (y-416)%60<52&&row<5?row:-1;}
    public static boolean contains(double x,double y,int a,int b,int w,int h){return x>=a&&x<a+w&&y>=b&&y<b+h;}
    static String elide(String value,float max,java.util.function.ToDoubleFunction<String> width){if(width.applyAsDouble(value)<=max)return value;int low=0,high=value.codePointCount(0,value.length());while(low<high){int mid=(low+high+1)/2;String s=value.substring(0,value.offsetByCodePoints(0,mid))+"…";if(width.applyAsDouble(s)<=max)low=mid;else high=mid-1;}return value.substring(0,value.offsetByCodePoints(0,low))+"…";}
    private TitleLayout(){}
}
