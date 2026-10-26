package school.magiccodex.client;

/** Independent smooth drift: no equal spacing, common orbit or per-frame allocation. */
public final class StatsStars {
    private final float[] xs=new float[9],ys=new float[9],depths=new float[9];
    private int count;
    public void update(int circle,double time){
        count=Math.clamp(circle,0,9);
        for(int i=0;i<count;i++){
            double p=i*2.3999632297;
            xs[i]=(float)(Math.sin(time*(.19+i*.013)+p)*.72+Math.sin(time*.113+p*1.71)*.24);
            ys[i]=(float)(Math.sin(time*(.147+i*.009)+p*1.37)*.73+Math.cos(time*.097+p)*.22);
            depths[i]=(float)Math.sin(time*(.23+i*.007)+p*1.93);
        }
    }
    public int count(){return count;}
    public float x(int i){return xs[i];}
    public float y(int i){return ys[i];}
    public float depth(int i){return depths[i];}
    // Foreground stars pass over the robe, leaving the face readable.
    public boolean foreground(int i){return depths[i]>0 && ys[i]>.10f;}
}
