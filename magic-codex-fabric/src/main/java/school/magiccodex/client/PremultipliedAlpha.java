package school.magiccodex.client;

/** Weight color before filtering so invisible RGB cannot bleed into a visible edge. */
public final class PremultipliedAlpha {
    private PremultipliedAlpha() {}
    public static int pixel(int pixel) {
        int a=pixel>>>24;
        int c0=((pixel&255)*a+127)/255,c1=(((pixel>>>8)&255)*a+127)/255,c2=(((pixel>>>16)&255)*a+127)/255;
        return a<<24 | c2<<16 | c1<<8 | c0;
    }
}
