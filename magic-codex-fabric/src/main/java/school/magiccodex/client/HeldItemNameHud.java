package school.magiccodex.client;

import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.text.Style;

/** Retains vanilla rich text and fade; only its HUD placement and size change. */
public final class HeldItemNameHud {
    private HeldItemNameHud(){}
    public static int draw(DrawContext c,TextRenderer renderer,Text name,int color){
        if(!PlayerHudSettings.get().visible())return 0;
        var window=MinecraftClient.getInstance().getWindow();
        var layout=PlayerHudLayout.player(window.getScaledWidth(),window.getScaledHeight(),PlayerHudSettings.get().percent());
        float scale=layout.scale()*1.65f;
        int max=Math.max(1,(int)Math.min(600,(window.getScaledWidth()-32)/scale));
        float titleY=(layout.hotbarTop()-39-14*1.65f)*layout.scale();
        var portrait=PlayerHudLayout.of(window.getScaledWidth(),window.getScaledHeight()); // fixed size, never scaled
        if(titleY<(portrait.frameY()+90)*portrait.scale()&&titleY+14*scale>(portrait.frameY()-90)*portrait.scale())
            max=Math.max(1,Math.min(max,(int)(2*(window.getScaledWidth()/2f-(portrait.frameX()+90)*portrait.scale()-8)/scale)));
        Text display=name;
        if(renderer.getWidth(name)>max){
            var trimmed=renderer.trimToWidth(name,Math.max(1,max-renderer.getWidth("…")));
            var out=Text.empty();
            trimmed.visit((style,value)->{out.append(Text.literal(value).setStyle(style));return Optional.empty();},Style.EMPTY);
            display=out.append(Text.literal("…").setStyle(name.getStyle()));
        }
        int width=renderer.getWidth(display);
        float x=(layout.hotbarLeft()+238)*layout.scale()/scale-width/2f;
        // Chips extend 14 logical pixels above hotbarTop-25; keep the whole text above them.
        float y=(layout.hotbarTop()-39)*layout.scale()/scale-14;
        var customization=HudCustomization.begin(c,"held-item-name",x*scale,y*scale,Math.max(1,width*scale),14*scale);
        c.getMatrices().push();
        try{
            c.getMatrices().scale(scale,scale,1);
            return c.drawTextWithBackground(renderer,display,Math.round(x),Math.round(y),width,color);
        }finally{c.getMatrices().pop();customization.close();}
    }
}
