package school.magiccodex.protocol;

/** Shared by server casting and client preview. Positive cooldowns never round down to zero. */
public final class MagicHaste {
    public static final double MAX=1_000_000;
    private MagicHaste(){}
    public static double valid(double value){if(!Double.isFinite(value)||value<0||value>MAX)throw new IllegalArgumentException("Magic haste must be 0..1000000");return value;}
    public static int cooldown(int baseMillis,double haste){
        if(baseMillis<0||baseMillis>86_400_000)throw new IllegalArgumentException("Invalid cooldown");valid(haste);
        return baseMillis==0?0:Math.max(1,(int)Math.ceil(baseMillis*100.0/(100.0+haste)));
    }
    public static double reduction(double haste){valid(haste);return 100.0*haste/(100.0+haste);}
}
