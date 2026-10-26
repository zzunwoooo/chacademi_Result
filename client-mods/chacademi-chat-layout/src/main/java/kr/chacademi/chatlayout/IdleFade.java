package kr.chacademi.chatlayout;
/** Monotonic per-window idle timing; message stamps are opaque change tokens. */
public final class IdleFade {
    public static final long HOLD_MS=8000, FADE_MS=1200;
    private long stamp,lastActive;
    public IdleFade(long now,long stamp){this.lastActive=now;this.stamp=stamp;}
    public float update(long now,long messageStamp,boolean interacting){
        if(interacting||messageStamp!=stamp||now<lastActive){lastActive=now;stamp=messageStamp;}
        double t=Math.max(0,Math.min(1,(now-lastActive-HOLD_MS)/(double)FADE_MS));
        return (float)(1-t*t*(3-2*t));
    }
    public static int color(int argb,float opacity){
        int alpha=Math.round((argb>>>24)*Math.max(0,Math.min(1,opacity)));
        return (argb&0xFFFFFF)|(alpha<<24);
    }
}