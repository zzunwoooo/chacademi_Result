package dev.portablevfx.client.claude;

/** Attachment basis contract, independent of Minecraft and rendering. Columns: right, up, forward. */
public final class ClaudeFrames {
    private ClaudeFrames() {}
    public static float[] projectile(double[] direction) {
        double[] forward=unit(direction),up={0,1,0};
        if(Math.abs(dot(forward,up))>.999)up=new double[]{1,0,0};
        double[] right=unit(cross(up,forward));return pack(right,cross(forward,right),forward);
    }
    public static float[] impact(double[] normal,double[] incoming) {
        double[] up=unit(normal),direction=unit(incoming);
        double d=dot(direction,up);double[] forward={direction[0]-up[0]*d,direction[1]-up[1]*d,direction[2]-up[2]*d};
        if(dot(forward,forward)<1e-10)forward=cross(Math.abs(up[1])<.9?new double[]{0,1,0}:new double[]{1,0,0},up);
        forward=unit(forward);return pack(cross(up,forward),up,forward);
    }
    private static double[] unit(double[] a) {
        if(a==null||a.length!=3)throw new IllegalArgumentException("Expected vector3");
        for(double v:a)if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite direction");
        double length=Math.sqrt(dot(a,a));if(length<1e-10)throw new IllegalArgumentException("Zero direction");
        return new double[]{a[0]/length,a[1]/length,a[2]/length};
    }
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    private static float[] pack(double[] r,double[] u,double[] f){return new float[]{(float)r[0],(float)r[1],(float)r[2],(float)u[0],(float)u[1],(float)u[2],(float)f[0],(float)f[1],(float)f[2]};}
}
