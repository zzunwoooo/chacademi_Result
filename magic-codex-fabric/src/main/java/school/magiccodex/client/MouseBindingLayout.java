package school.magiccodex.client;
/** Full 280x497 PNG coordinates: draw and hit tests share the exact same transform. */
public final class MouseBindingLayout {
 public static final float X=1318,Y=196,W=190,H=337.25f,S=W/280f;
 private static final double[][] ONE={{16,190},{26,187},{39,229},{38,260},{25,249},{17,217}};
 private static final double[][] TWO={{26,269},{38,276},{45,313},{42,338},{32,329},{25,298}};
 public static double[][] polygon(int code){return code==KeySettingsLayout.THUMB_1?ONE:TWO;}
 public static int hit(double x,double y){double px=(x-X)/S,py=(y-Y)/S;return inside(ONE,px,py)?KeySettingsLayout.THUMB_1:inside(TWO,px,py)?KeySettingsLayout.THUMB_2:-1;}
 private static boolean inside(double[][] p,double x,double y){boolean hit=false;for(int i=0,j=p.length-1;i<p.length;j=i++){if((p[i][1]>y)!=(p[j][1]>y)&&x<(p[j][0]-p[i][0])*(y-p[i][1])/(p[j][1]-p[i][1])+p[i][0])hit=!hit;}return hit;}
}
