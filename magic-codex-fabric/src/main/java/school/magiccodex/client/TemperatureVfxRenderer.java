package school.magiccodex.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Util;

/** One soft animated mesh before the HUD: no textures, framebuffer copies, blur or screen distortion. */
public final class TemperatureVfxRenderer {
    private static final int COLS=16,ROWS=10;
    private static final int[] COLORS=new int[(COLS+1)*(ROWS+1)];
    private static float heat,cold;
    private static long last;
    private TemperatureVfxRenderer(){}
    public static void reset(){heat=cold=0;last=0;}
    public static void render(DrawContext ctx){
        var c=MinecraftClient.getInstance();
        if(!TemperatureClient.effects()||!PlayerHudClient.active()||c.options.hudHidden||!c.player.isAlive()
                ||(c.currentScreen!=null&&!(c.currentScreen instanceof HudCursorScreen))){last=0;return;}
        long now=Util.getMeasuringTimeMs();float dt=last==0?1/60f:(now-last)/1000f;last=now;
        float value=TemperatureClient.current();
        heat=TemperatureVisuals.approach(heat,TemperatureVisuals.heat(value),dt);
        cold=TemperatureVisuals.approach(cold,TemperatureVisuals.cold(value),dt);
        float strength=heat+cold;if(strength<.002f)return;
        float coldWeight=cold/strength;
        double time=now/1000d;
        for(int y=0;y<=ROWS;y++)for(int x=0;x<=COLS;x++){
            float nx=2f*x/COLS-1,ny=2f*y/ROWS-1;
            float mix=(float)(.5+.5*Math.sin(time*.22+nx*.9+ny*.6));
            int hot=rgb(235,78,42,255,139,62,mix),ice=rgb(78,146,224,196,228,250,mix);
            int color=blend(hot,ice,coldWeight);
            int alpha=Math.round(255*TemperatureVisuals.opacity(nx,ny,strength,time));
            COLORS[y*(COLS+1)+x]=(alpha<<24)|color;
        }
        float w=c.getWindow().getScaledWidth(),h=c.getWindow().getScaledHeight();
        var m=ctx.getMatrices().peek().getPositionMatrix();
        ctx.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            for(int y=0;y<ROWS;y++)for(int x=0;x<COLS;x++){
                int i=y*(COLS+1)+x;
                float l=w*x/COLS,r=w*(x+1)/COLS,t=h*y/ROWS,b=h*(y+1)/ROWS;
                v.vertex(m,l,b,0).color(COLORS[i+COLS+1]);v.vertex(m,r,b,0).color(COLORS[i+COLS+2]);
                v.vertex(m,r,t,0).color(COLORS[i+1]);v.vertex(m,l,t,0).color(COLORS[i]);
            }
        });
    }
    private static int rgb(int ar,int ag,int ab,int br,int bg,int bb,float t){return Math.round(ar+(br-ar)*t)<<16|Math.round(ag+(bg-ag)*t)<<8|Math.round(ab+(bb-ab)*t);}
    private static int blend(int a,int b,float t){return rgb(a>>16&255,a>>8&255,a&255,b>>16&255,b>>8&255,b&255,t);}
}
