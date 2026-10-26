package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;

/** Soft moving light and the circle seal behind the player; no additional star sprites. */
final class StatsBackdrop {
    private StatsBackdrop(){}
    static void draw(DrawContext ctx,float circle,double time){
        float level=(Math.clamp(circle,1,9)-1)/8f;
        ctx.enableScissor(102,210,650,729);
        try{
            glow(ctx,365,466,248,245,0x429CBF,Math.round(7+level*25));
            glow(ctx,439+(float)Math.sin(time*.11)*25,416,192,170,0xA98BDC,Math.round(level*24));
            for(int layer=0;layer<4;layer++){
                float weight=layer==0?.18f+level*.82f:Math.clamp(level*3-layer+.45f,0,1);
                if(weight<=0)continue;
                ribbon(ctx,layer,level,weight,time);
            }
            StatsSigil.draw(ctx,circle,time);
        }finally{ctx.disableScissor();}
    }
    private static void glow(DrawContext c,float x,float y,float rx,float ry,int rgb,int alpha){
        if(alpha<=0)return;
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            for(int i=0;i<48;i++){
                double a=i*Math.PI/24,b=(i+1)*Math.PI/24;
                v.vertex(m,x,y,0).color((alpha<<24)|rgb);
                v.vertex(m,x+(float)Math.cos(b)*rx,y+(float)Math.sin(b)*ry,0).color(rgb);
                v.vertex(m,x+(float)Math.cos(a)*rx,y+(float)Math.sin(a)*ry,0).color(rgb);
                v.vertex(m,x,y,0).color((alpha<<24)|rgb);
            }
        });
    }
    private static float center(float u,int layer,double time){
        return 440+layer*24+(u-.5f)*(175-layer*66)
                +(float)Math.sin(u*5.8+time*(.12+layer*.018)+layer*2.1)*49
                +(float)Math.sin(u*12.0-time*.075+layer)*13;
    }
    private static void ribbon(DrawContext c,int layer,float level,float weight,double time){
        var m=c.getMatrices().peek().getPositionMatrix();
        int rgb=layer%2==0?0x66CEE1:0xB29FEA;
        float spread=27+level*37+layer*6;
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            for(int i=0;i<64;i++){
                float u=i/64f,w=(i+1)/64f,x=119+u*517,nx=119+w*517;
                float y=center(u,layer,time),ny=center(w,layer,time);
                int a=Math.round((float)Math.pow(Math.sin(u*Math.PI),1.5)*(15+level*34)*weight);
                int b=Math.round((float)Math.pow(Math.sin(w*Math.PI),1.5)*(15+level*34)*weight);
                v.vertex(m,x,y,0).color((a<<24)|rgb);v.vertex(m,nx,ny,0).color((b<<24)|rgb);
                v.vertex(m,nx,ny-spread,0).color(rgb);v.vertex(m,x,y-spread,0).color(rgb);
                v.vertex(m,x,y+spread,0).color(rgb);v.vertex(m,nx,ny+spread,0).color(rgb);
                v.vertex(m,nx,ny,0).color((b<<24)|rgb);v.vertex(m,x,y,0).color((a<<24)|rgb);
            }
        });
    }
}
