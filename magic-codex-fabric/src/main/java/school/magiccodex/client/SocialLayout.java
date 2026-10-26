package school.magiccodex.client;

/** Logical pixels shared by drawing, dragging and hit tests. */
public final class SocialLayout {
    public record Fit(float x,float y,float scale){public double x(double screen){return (screen-x)/scale;}public double y(double screen){return (screen-y)/scale;}}
    public static Fit friends(int width,int height){float s=Math.min(width*.82f/1000,height*.88f/870);return new Fit((width-1000*s)/2,(height-870*s)/2,s);}
    public static Fit whisper(int width,int height){float s=Math.min(width*.72f/1200,height*.38f/450);return new Fit((width-1200*s)/2,height-450*s-height*.085f,s);}
    public static float maxScroll(int count){return Math.max(0,count*90-540);}
    public static int dorm(String name){return switch(name){case "아르케온"->1;case "루미나"->2;case "베스티아즈"->3;case "노크세르"->4;default->0;};}
    public static int color(String name){return switch(dorm(name)){case 1->0xFFF0A0A2;case 2->0xFF90C5FF;case 3->0xFF9ED8AD;case 4->0xFFF1D685;default->0xFFCAD4DD;};}
}
