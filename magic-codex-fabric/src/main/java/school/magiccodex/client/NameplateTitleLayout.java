package school.magiccodex.client;
/** A whole colored title line has one measured advance and one shared scale; no inter-slot separator. */
public final class NameplateTitleLayout {
 private NameplateTitleLayout(){}
 public static float fittedSize(float measuredAdvance,float base,float maxWidth){return base*Math.min(1,maxWidth/Math.max(1,measuredAdvance));}
}
