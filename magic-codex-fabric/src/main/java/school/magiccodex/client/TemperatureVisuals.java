package school.magiccodex.client;

/** Visual thresholds in Celsius. These do not impose damage or gameplay penalties. */
public final class TemperatureVisuals {
    public static final float HOT_START=30,HOT_FULL=45,COLD_START=5,COLD_FULL=-15;
    private TemperatureVisuals(){}
    public static float heat(float celsius){return Float.isFinite(celsius)?smooth((celsius-HOT_START)/(HOT_FULL-HOT_START)):0;}
    public static float cold(float celsius){return Float.isFinite(celsius)?smooth((COLD_START-celsius)/(COLD_START-COLD_FULL)):0;}
    private static float smooth(float t){t=Math.clamp(t,0,1);return t*t*(3-2*t);}
    public static float approach(float current,float target,float seconds){
        return current+(target-current)*(1-(float)Math.exp(-Math.clamp(seconds,0,.25f)/1.2f));
    }
    public static int labelColor(float celsius){
        if(heat(celsius)>0)return 0xFFF2B078;
        if(cold(celsius)>0)return 0xFFBBDFF6;
        return 0xFFE8E4D9;
    }
    /** Readable center (2% maximum), with a pronounced 40% tint at the outer edge. */
    public static float opacity(float x,float y,float intensity,double time){
        float edge=(float)Math.pow(Math.max(Math.abs(x),Math.abs(y)),4);
        float breath=(float)(.96+.04*Math.sin(time*.85+x*1.3+y*.7));
        return Math.clamp(intensity,0,1)*(.02f+.38f*edge)*breath;
    }
}
