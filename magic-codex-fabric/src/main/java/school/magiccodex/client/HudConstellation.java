package school.magiccodex.client;

/** Small reusable geometry buffer: exactly one moving star per circle, no frame allocations. */
public final class HudConstellation {
    private int count=1;
    private final float[] x=new float[9],y=new float[9];
    public void update(int rank,double seconds) {
        if(rank<1 || rank>9) throw new IllegalArgumentException("Circle must be 1..9");
        count=rank;
        double turn=seconds*(rank>=5?.09:.13);
        for(int i=0;i<rank;i++) {
            double angle=turn+Math.PI*2*i/rank-Math.PI/2;
            // Higher circles form a regular polygon outside the frame. Small ranks mix orbits.
            double radius=rank>=5?1.065:(i%2==0?1.065:.66);
            x[i]=(float)(Math.cos(angle)*radius);
            y[i]=(float)(Math.sin(angle)*radius);
        }
    }
    public int count(){return count;}
    public float x(int i){return x[i];}
    public float y(int i){return y[i];}
    public boolean connected(){return count>=5;}
    public int chordStep(){return count>=7?(count==8?3:2):0;}
}

