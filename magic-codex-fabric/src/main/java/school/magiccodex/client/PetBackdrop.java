package school.magiccodex.client;
import net.minecraft.client.gui.DrawContext;
/** Quiet moving haze and individually moving stardust; no baked galaxy photograph. */
final class PetBackdrop {
    static void draw(DrawContext c,double t,boolean front){
        if(!front){
            haze(c);
            PetAssets.draw(c,"nebula_soft",18+(float)Math.sin(t*.055)*22,85+(float)Math.cos(t*.045)*14,1234,562,0x42A9C3D8);
            PetAssets.draw(c,"nebula_soft",-20+(float)Math.cos(t*.038)*28,115+(float)Math.sin(t*.04)*16,1280,480,0x209DABC9);
        }
        for(int i=0;i<92;i++){
            if((i%13==0)!=front)continue;
            double speed=.0035+(i%7)*.00033;
            double u=(i*.61803398875+t*speed)%1;
            float x=70+(float)u*1140;
            float spread=(float)Math.sin(i*91.73)*67;
            float y=285+(float)Math.sin(u*5.4+t*.07)*55+(float)u*170+spread;
            float fade=(float)Math.pow(Math.sin(u*Math.PI),.7);
            float pulse=(float)(.65+.35*Math.sin(t*(.35+i%5*.03)+i));
            int alpha=(int)((front?160:110)*fade*pulse);
            float size=front?7+i%3*2:1+i%3*.55f;
            if(front)PetAssets.draw(c,"sparkle_node",x-size/2,y-size/2,size,size,alpha<<24|0xDAE8F2);
            else HudMesh.quad(c,x,y,size,size,alpha<<24|0xBCD5E4,alpha<<24|0xBCD5E4);
        }
    }
    private static void haze(DrawContext c){
        // A continuous, transparent falloff: no visible panel edge behind the pet.
        var matrix=c.getMatrices().peek().getPositionMatrix();
        c.draw(provider->{var v=provider.getBuffer(net.minecraft.client.render.RenderLayer.getGui());
            for(int i=0;i<64;i++){
                double a=i*Math.PI/32,b=(i+1)*Math.PI/32;
                v.vertex(matrix,640,366,0).color(0xA009182A);
                v.vertex(matrix,640+(float)Math.cos(b)*635,366+(float)Math.sin(b)*330,0).color(0x0009182A);
                v.vertex(matrix,640+(float)Math.cos(a)*635,366+(float)Math.sin(a)*330,0).color(0x0009182A);
                v.vertex(matrix,640,366,0).color(0xA009182A);
            }
        });
    }
}
