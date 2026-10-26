package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;

/** A flowing translucent nebula, not a looping video. Fixed geometry budget, no particle allocation. */
final class AscensionVeil {
    private static int color(int rgb,double opacity){return (int)(Math.clamp(opacity,0,1)*255)<<24|rgb;}
    static void draw(DrawContext c,double t,int rank,float opacity,boolean front){
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            for(int ribbon=0;ribbon<(front?1:4);ribbon++){
                int rgb=ribbon%2==0?0x68ADCF:0xA09ACF;
                float spread=front?15:38+ribbon*15;
                for(int i=0;i<80;i++){
                    double u=i/80.0,n=(i+1)/80.0;
                    float x=(float)(35+u*785),nx=(float)(35+n*785);
                    float y=center(u,ribbon,t,front),ny=center(n,ribbon,t,front);
                    double a=Math.pow(Math.sin(u*Math.PI),2)*(front?.30:.23+rank*.01)*opacity;
                    double b=Math.pow(Math.sin(n*Math.PI),2)*(front?.30:.23+rank*.01)*opacity;
                    v.vertex(m,x,y,0).color(color(rgb,a));v.vertex(m,nx,ny,0).color(color(rgb,b));
                    v.vertex(m,nx,ny-spread,0).color(rgb);v.vertex(m,x,y-spread,0).color(rgb);
                    v.vertex(m,x,y+spread,0).color(rgb);v.vertex(m,nx,ny+spread,0).color(rgb);
                    v.vertex(m,nx,ny,0).color(color(rgb,b));v.vertex(m,x,y,0).color(color(rgb,a));
                }
            }
            // Small dust points are distinct from the rank's large orbiting stars.
            for(int i=0;i<(front?20:74);i++){
                double seed=i*2.399963+(front?7:0),u=(i*.618034+t*(.008+(i%5)*.001))%1;
                float x=(float)(45+u*765),y=center(u,i%4,t,front)+(float)Math.sin(seed+t*.17)*29;
                float r=(float)(.65+(i%3)*.28);
                int tint=color(i%3==0?0xF0D5A4:0xB4DCEC,(.12+.24*(.5+.5*Math.sin(seed+t*.6)))*Math.sin(u*Math.PI)*opacity);
                v.vertex(m,x-r,y+r,0).color(tint);v.vertex(m,x+r,y+r,0).color(tint);
                v.vertex(m,x+r,y-r,0).color(tint);v.vertex(m,x-r,y-r,0).color(tint);
            }
        });
    }
    private static float center(double u,int r,double t,boolean front){
        return (float)((front?401:310)+r*33+(u-.5)*(r%2==0?-170:130)+Math.sin(u*6+t*.13+r*1.7)*68+Math.sin(u*12-t*.08+r)*13);
    }
    private AscensionVeil(){}
}
