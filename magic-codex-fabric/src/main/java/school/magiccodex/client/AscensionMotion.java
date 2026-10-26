package school.magiccodex.client;

/** Time-based choreography, independent of frame rate; no frame-accumulated particle objects. */
public final class AscensionMotion {
    public static final double ARRIVAL=3.4, COMPLETE=5.2;
    public static float smooth(double value){float t=(float)Math.clamp(value,0,1);return t*t*(3-2*t);}
    public static float reveal(double age){return smooth((age-1.25)/2.15);}
    public static float burst(double age){return (float)Math.exp(-Math.pow((age-ARRIVAL)/.28,2));}
    public static float depth(int index,double time){return (float)Math.sin(index*2.39996+time*(.19+index*.013));}
    public static float x(int index,double time){double a=index*2.39996+time*(.19+index*.013);return 470+(float)Math.cos(a)*172+(float)Math.sin(time*.43+index)*12;}
    public static float y(int index,double time){double a=index*2.39996+time*(.19+index*.013);return 351+(float)Math.sin(a)*53-(float)Math.cos(a)*25+(float)Math.sin(time*.31+index*1.7)*14;}
    private AscensionMotion(){}
}
