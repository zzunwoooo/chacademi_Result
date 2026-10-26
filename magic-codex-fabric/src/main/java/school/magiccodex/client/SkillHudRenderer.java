package school.magiccodex.client;

import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

/** Six small floating icons; no outer frame, title, counters, or codex footer. */
public final class SkillHudRenderer implements AutoCloseable {
    private static final Identifier FONT=Identifier.of("magiccodex","label");
    private static final Identifier FRAME=Identifier.of("magiccodex","textures/gui/hud_arcane_slot.png");
    private static final int CIRCLE_STEPS=96;
    private static final float[] CIRCLE_X=new float[CIRCLE_STEPS+1], CIRCLE_Y=new float[CIRCLE_STEPS+1];
    static {
        for(int i=0;i<=CIRCLE_STEPS;i++) {
            double angle=2*Math.PI*i/CIRCLE_STEPS;
            CIRCLE_X[i]=(float)Math.sin(angle); CIRCLE_Y[i]=-(float)Math.cos(angle);
        }
    }
    private final MinecraftClient client;
    private final CodexTypography type;
    private final HudTextureCache textures;
    public SkillHudRenderer(MinecraftClient client) { this.client=client; type=new CodexTypography(client); textures=new HudTextureCache(client); }
    public void verifyGpuState() { textures.verifyGpuState(); }
    public int textureCount() { return textures.size(); }
    public void render(DrawContext ctx,CastingState state,int width,int height,long now,String modeKey) {
        type.beginFrame();
        textures.beginFrame();
        float[] o=origin(width,height);
        float scale=o[2],left=o[0],top=o[1];
        try(var customization=HudCustomization.begin(ctx,"skills",left-4*scale,top-35*scale,394*scale,100*scale)){
        ctx.getMatrices().push();
        try {
            ctx.getMatrices().translate(left,top+GeneralPresetClient.lift(now),0); ctx.getMatrices().scale(scale,scale,1);
            keycap(ctx,306,-33,27,25,0x9F9F8A61);
            text(ctx,fitKey(modeKey),319.5f,-20.5f,14,0xFFE1DFD6);
            ctx.fill(338,-32,382,-9,0x880C161D);
            diamond(ctx,346,-20,3,state.enabled()?0xFF81D8C9:0xFF6E7D84);
            text(ctx,CastingClient.presetLabel(),366,-20,14,state.enabled()?0xFF80D8C5:0xFF97A2AE);
            var bindings=state.bindings();
            for(int i=0;i<6;i++) {
                int x=i*65; var b=bindings.get(i); var spell=state.spell(b.spellId());
                arc(ctx,x+26,26,0,26,0,1,state.enabled()?0xCE111C25:0xA8111C25);
                if(b.empty()) {
                    frame(ctx,x,false,true);
                    text(ctx,"·",x+26,25,22,0xFF606F7B); continue;
                }
                boolean usable=spell!=null && spell.permissionKnown() && spell.discovered();
                if(spell!=null) art(ctx,spell.icon(),x+26,26,42,state.enabled() && usable?0xFFFFFFFF:0x667C8898);
                else text(ctx,"?",x+26,23,24,0xFF82909B);
                long remaining=state.remaining(b.spellId(),now);
                if(remaining>0) {
                    float fraction=state.fraction(b.spellId(),now);
                    arc(ctx,x+26,26,0,24.5f,1-fraction,1,0xB8071019);
                }
                frame(ctx,x,state.enabled() && usable,false);
                if(remaining>0) {
                    float fraction=state.fraction(b.spellId(),now);
                    arc(ctx,x+26,26,26.7f,28.4f,1-fraction,1,state.enabled()?0xE3CCE3E4:0xA184999F);
                    String seconds=countdown(remaining);
                    float size=Math.min(19,19*44/Math.max(1,type.width(seconds,19,FONT)));
                    text(ctx,seconds,x+26,26,size,state.enabled()?0xFFF5F0DC:0xFFADB9BE);
                } else if(state.enabled() && !usable) text(ctx,spell!=null && !spell.permissionKnown()?"…":"×",x+26,26,20,0xFFE3CEC0);
                String key=fitKey(KeySettingsLayout.name(b.keyCode()));
                float keySize=key.length()>2?11:15;
                int keyWidth=Math.round(Math.min(44,Math.max(19,type.width(key,keySize,FONT)+8)));
                keycap(ctx,x+56-keyWidth,44,keyWidth,20,state.enabled()?0xB8988B72:0x665F6D74);
                text(ctx,key,x+56-keyWidth/2f,54,keySize,state.enabled()?0xFFF1EBDD:0xFFA3AFBA);
            }
            drag(ctx,state,left,top,scale);
        } finally { ctx.getMatrices().pop(); }

        }
}
    /** {left, top, scale} of the six-slot bar in GUI pixels. */
    static float[] origin(int width,int height) {
        float scale=(PlayerHudClient.active()?1f:1.25f)*Math.min(width/1672f,height/941f);
        float left=width-402*scale,top=height-76*scale;
        // Large Minecraft GUI scales leave less space beside its 182-unit vanilla hotbar.
        if(!PlayerHudClient.active() && left<width/2f+98) top=Math.min(top,height-38-58*scale);
        return new float[]{left,top,scale};
    }
    /** Slot index under a GUI-pixel point, or -1. */
    static int slotAt(double x,double y,int width,int height) {
        var p=HudCustomization.pointer("skills",x,y);x=p[0];y=p[1];
        float[] o=origin(width,height);
        for(int i=0;i<6;i++) {
            double cx=o[0]+(i*65+26)*o[2],cy=o[1]+26*o[2],r=29*o[2];
            if((x-cx)*(x-cx)+(y-cy)*(y-cy)<=r*r) return i;
        }
        return -1;
    }
    /** Drag feedback (Ctrl / chat): rings on the picked and hovered slots, the picked icon under the pointer. */
    private void drag(DrawContext ctx,CastingState state,float left,float top,float scale) {
        int from=HudSlotDrag.from(); if(from<0) return;
        var c=MinecraftClient.getInstance();
        int over=slotAt(HudPointer.x(),HudPointer.y(),c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight());
        arc(ctx,from*65+26,26,27.6f,29.6f,0,1,0xF0E8C979);
        if(over>=0 && over!=from) arc(ctx,over*65+26,26,27.6f,29.6f,0,1,0xF08FE3D2);
        var b=state.bindings().get(from); var spell=state.spell(b.spellId());
        if(spell!=null) {
            float mx=(float)((HudPointer.x()-left)/scale),my=(float)((HudPointer.y()-top)/scale);
            art(ctx,spell.icon(),Math.round(mx),Math.round(my),40,0xCCFFFFFF);
        }
    }
    private void frame(DrawContext ctx,int x,boolean active,boolean empty) {
        // Raster detail is smaller than a screen pixel after reduction. Draw the structural
        // circles at a readable width and use PNG detail for the metallic stars and engraving.
        arc(ctx,x+26,26,26.8f,27.5f,0,1,0xB5071018);
        int outline=active?0xF0C6AA75:empty?0x986D7A83:0xB0869398;
        arc(ctx,x+26,26,25.55f,26.85f,0,1,outline);
        arc(ctx,x+26,26,23.1f,23.85f,0,1,active?0xBD98805A:0x6C566871);
        int tint=active?0xDCEBD7AA:empty?0x627C858B:0x727E878D;
        // Keep the original RGBA source; its transparent padding is accounted for in the 70-unit draw.
        textures.draw(ctx,FRAME.toString(),x+26,26,70,tint);
    }
    private static void keycap(DrawContext ctx,int x,int y,int width,int height,int border) {
        ctx.fill(x+2,y,x+width-2,y+height,border);
        ctx.fill(x,y+2,x+width,y+height-2,border);
        ctx.fill(x+2,y+1,x+width-2,y+height-1,0xED111A20);
        ctx.fill(x+1,y+2,x+width-1,y+height-2,0xED111A20);
    }
    private static void diamond(DrawContext ctx,int x,int y,int radius,int color) {
        var matrix=ctx.getMatrices().peek().getPositionMatrix();
        ctx.draw(provider->{
            var v=provider.getBuffer(RenderLayer.getGui());
            v.vertex(matrix,x,y-radius,0).color(color); v.vertex(matrix,x-radius,y,0).color(color);
            v.vertex(matrix,x,y+radius,0).color(color); v.vertex(matrix,x+radius,y,0).color(color);
        });
    }
    /** Bounded mesh, no new images or textures per animation frame. Clockwise from twelve o'clock. */
    private static void arc(DrawContext ctx,float cx,float cy,float inner,float outer,float start,float end,int color) {
        if(end<=start) return;
        float pixelsPerUnit=Math.abs(ctx.getMatrices().peek().getPositionMatrix().m00())*(float)MinecraftClient.getInstance().getWindow().getScaleFactor();
        float feather=Math.min(0.65f/Math.max(0.01f,pixelsPerUnit),(outer-inner)/2);
        int transparent=color&0xFFFFFF;
        arcBand(ctx,cx,cy,inner==0?0:inner+feather,outer-feather,start,end,color,color);
        arcBand(ctx,cx,cy,outer-feather,outer+feather,start,end,color,transparent);
        if(inner>0) arcBand(ctx,cx,cy,Math.max(0,inner-feather),inner+feather,start,end,transparent,color);
    }
    private static void arcBand(DrawContext ctx,float cx,float cy,float inner,float outer,float start,float end,int innerColor,int outerColor) {
        if(outer<=inner) return;
        var matrix=ctx.getMatrices().peek().getPositionMatrix();
        ctx.draw(provider->{
            var v=provider.getBuffer(RenderLayer.getGui());
            int first=Math.max(0,(int)(start*CIRCLE_STEPS)),last=Math.min(CIRCLE_STEPS,(int)Math.ceil(end*CIRCLE_STEPS));
            for(int i=first;i<last;i++) {
                float from=Math.max(0,start*CIRCLE_STEPS-i),to=Math.min(1,end*CIRCLE_STEPS-i);
                float ax=CIRCLE_X[i]+(CIRCLE_X[i+1]-CIRCLE_X[i])*from;
                float ay=CIRCLE_Y[i]+(CIRCLE_Y[i+1]-CIRCLE_Y[i])*from;
                float bx=CIRCLE_X[i]+(CIRCLE_X[i+1]-CIRCLE_X[i])*to;
                float by=CIRCLE_Y[i]+(CIRCLE_Y[i+1]-CIRCLE_Y[i])*to;
                v.vertex(matrix,cx+ax*outer,cy+ay*outer,0).color(outerColor);
                v.vertex(matrix,cx+ax*inner,cy+ay*inner,0).color(innerColor);
                v.vertex(matrix,cx+bx*inner,cy+by*inner,0).color(innerColor);
                v.vertex(matrix,cx+bx*outer,cy+by*outer,0).color(outerColor);
            }
        });
    }
    public static String countdown(long remaining) {
        if(remaining<=0) return "";
        if(remaining>=10000) return Long.toString((remaining+999)/1000);
        long tenths=(remaining+99)/100;
        return (tenths/10)+"."+(tenths%10);
    }
    private static String fitKey(String key) {
        return switch(key) { case "Backspace" -> "Bksp"; case "Enter" -> "Enter"; default -> key.length()>5?key.substring(0,5):key; };
    }
    private void text(DrawContext ctx,String value,float x,float y,float size,int color) { type.draw(ctx,value,x,y,size,color,FONT,true); }
    private void art(DrawContext ctx,String path,int x,int y,int size,int tint) {
        if(!textures.draw(ctx,path,x,y,size,tint)) text(ctx,"?",x,y,22,tint);
    }
    @Override public void close() { type.close(); textures.close(); }
}
