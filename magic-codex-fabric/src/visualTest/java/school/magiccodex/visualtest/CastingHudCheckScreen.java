package school.magiccodex.visualtest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import school.magiccodex.client.*;

/** Deterministic render fixture; command attempts are collected, never sent to a server. */
final class CastingHudCheckScreen extends Screen {
    final CastingState state=new CastingState();
    final List<String> commands=new ArrayList<>();
    long now=3000;
    boolean bright;
    boolean comparison;
    private final net.minecraft.util.Identifier frame=net.minecraft.util.Identifier.of("magiccodex","textures/gui/hud_arcane_slot.png");
    private HudTextureCache comparisonTextures;
    private SkillHudRenderer renderer;
    CastingHudCheckScreen() {
        super(Text.literal("HUD rendering check"));
        var spells=CodexData.previewSpells().stream().filter(CodexData.Spell::discovered).limit(6).toList();
        state.catalog(spells);
        var keys=new SpellKeySettings();
        for(int i=0;i<6;i++) { keys.select(i,spells.get(i).id()); keys.bind(i,i==5?82:49+i); }
        state.setBindings(keys);
        press(90,0); press(49,1000); press(82,1000);
    }
    void press(int key,long time) {
        state.input(key,0,key==90,true,time,commands::add);
        state.input(key,1,key==90,true,time,commands::add);
    }
    @Override public void render(DrawContext ctx,int mouseX,int mouseY,float delta) {
        if(comparison) { renderComparison(ctx); return; }
        ctx.fillGradient(0,0,width,height,0xFF354756,0xFF18232D);
        if(bright) for(int y=height*2/3;y<height;y+=24) for(int x=0;x<width;x+=24)
            ctx.fill(x,y,Math.min(width,x+24),Math.min(height,y+24),((x/24+y/24)&1)==0?0xFFD2D2D2:0xFFABABAB);
        ctx.fill(width/2-3,height/2,width/2+4,height/2+1,0x668EABAF);
        ctx.fill(width/2,height/2-3,width/2+1,height/2+4,0x668EABAF);
        // Vanilla hotbar footprint at the same GUI scale, for overlap checks.
        ctx.fill(width/2-91,height-22,width/2+91,height,0x99212B31);
        for(int i=0;i<9;i++) ctx.drawBorder(width/2-90+i*20,height-21,20,20,0x666A7477);
        if(renderer==null) renderer=new SkillHudRenderer(client);
        renderer.render(ctx,state,width,height,now,"Z");
    }
    void verifyRenderer() { renderer.verifyGpuState(); if(renderer.textureCount()!=7) throw new IllegalStateException("Expected six icons and one shared frame"); }
    private void renderComparison(DrawContext ctx) {
        ctx.fill(0,0,width,height,0xFF303C48);
        ctx.drawText(client.textRenderer,"SAME PNG / SAME SIZE / DIFFERENT RENDERER",24,16,0xFFF0E9D8,false);
        ctx.drawText(client.textRenderer,"Bilinear only (previous)",24,47,0xFFE6C489,false);
        ctx.drawText(client.textRenderer,"Alpha-correct trilinear (new)",24,160,0xFF8CDAC8,false);
        if(comparisonTextures==null) comparisonTextures=new HudTextureCache(client);
        int[] sizes={64,96,144,192};
        for(int i=0;i<sizes.length;i++) {
            int size=(int)Math.round(sizes[i]/client.getWindow().getScaleFactor()),x=80+i*145;
            ctx.drawText(client.textRenderer,sizes[i]+"px",x-10,290,0xFFD5DEE3,false);
            ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,frame,x-size/2,104-size/2,0,0,size,size,1254,1254,1254,1254,0xFFFFFFFF);
            comparisonTextures.draw(ctx,frame.toString(),x,221,size,0xFFFFFFFF);
        }
        comparisonTextures.verifyGpuState();
    }
    @Override public void removed() {
        if(renderer!=null) { renderer.close(); renderer=null; }
        if(comparisonTextures!=null) { comparisonTextures.close(); comparisonTextures=null; }
    }
    @Override public boolean shouldPause() { return false; }
}
