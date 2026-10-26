package kr.chacademy.motion.broom;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.Util;
/**
 * Flight HUD, fixed just above the MagicCodex skill bar (bottom right) so it never covers the view or gets cut off.
 * No backing panel and no text: the boost ring (clockwise sweep, no countdown number) and the balance arch float on the screen.
 * Everything is drawn through {@link HudArt} (mipmapped textures, float coordinates, Pretendard text at screen
 * resolution), and both gauges ease toward their value with a feathered leading edge instead of a hard cut.
 * Layout is in the skill bar's reference units (1672x941 screen), scaled the same way as that bar.
 */
public final class BroomHud {
 /** Skill bar geometry (MagicCodex SkillHudRenderer): left = w-402s, top = h-76s, mode-key row 33 above top. */
 static final float SKILL_LEFT=402,SKILL_TOP=76,SKILL_ROW=33,SKILL_WIDTH=382;
 static final float PANEL_W=340,PANEL_H=64,GAP=12;
 /** Ring texture: the circle centre and the fill's outer radius, as fractions of the 333x337 image. */
 static final float RING_CX=161.5f/333,RING_CY=172.5f/337,RING_R=.345f;
 private static float shownBalance=-1,trailBalance=-1,shownReady=-1;private static long lastFrame;
 private static int lastProgress,wrongIndex=-1;private static long wrongUntil;
 private BroomHud(){}
 /** Reference units -> GUI pixels for the skill-bar placement. */
 static float scale(int width,int height){return Math.min(width/1672f,height/941f);}
 /** Exponential approach, frame-rate independent. */
 static float ease(float shown,float target,float seconds,float tau){return shown<0?target:shown+(target-shown)*(1-(float)Math.exp(-seconds/tau));}
 private static int argb(float alpha,int rgb){return (Math.round(Mth.clamp(alpha,0,1)*255)<<24)|(rgb&0xFFFFFF);}
 public static void render(GuiGraphics g,DeltaTracker dt){
  var mc=Minecraft.getInstance();if(mc.options.hideGui||mc.player==null||(mc.screen!=null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) && !mc.screen.getClass().getName().equals("school.magiccodex.client.HudCursorScreen")))return;
  long now=Util.getMillis();float seconds=lastFrame==0?0:Math.min(.25f,(now-lastFrame)/1000f);lastFrame=now;
  if(BroomFlight.catchTicks()>0){drawCatch(g,mc,now,dt.getGameTimeDeltaPartialTick(false));return;}
  var ph=BroomFlight.physics();if(ph==null){shownBalance=trailBalance=shownReady=-1;return;}
  float pt=dt.getGameTimeDeltaPartialTick(false),balance=Mth.clamp(Mth.lerp(pt,ph.prevBalance,ph.balance)/ph.balanceCap(),0,1);
  float ready=1-Mth.clamp(ph.boostCooldown/(float)Math.max(1,ph.stats.boostCooldown()),0,1);
  shownBalance=ease(shownBalance,balance,seconds,.08f);
  // The pale trail lingers where the balance was, then slides down to it (rises follow immediately).
  trailBalance=trailBalance<shownBalance?shownBalance:ease(trailBalance,shownBalance,seconds,.35f);
  shownReady=ready>=1?1:ease(shownReady,ready,seconds,.06f);
  int w=g.guiWidth(),h=g.guiHeight();float s=scale(w,h);
  float cx=w-SKILL_LEFT*s+SKILL_WIDTH/2*s,bottom=h-(SKILL_TOP+SKILL_ROW+GAP)*s;
  var pose=g.pose();pose.pushPose();
  try{
   pose.translate(cx-PANEL_W/2*s,bottom-PANEL_H*s,0);pose.scale(s,s,1);
   ring(g,6,4,56,shownReady);
   arch(g,84,7,240,49,shownBalance,trailBalance,now);
  }finally{pose.popPose();}
 }
 /** Boost ring: the cyan disc sweeps clockwise from the top as the boost recharges; full disc = ready. */
 private static void ring(GuiGraphics g,float x,float y,float size,float ready){
  var fill=HudArt.image("ring_fill");
  if(fill.isPresent()&&ready>0){
   var t=fill.get();float h=size*337/333f,ccx=x+size*RING_CX,ccy=y+h*RING_CY,r=size*RING_R*1.06f;
   if(ready>=.999f)HudArt.quad(g,t,x,y,x+size,y+h,0,0,1,1,-1,-1,-1,-1);
   else{
    double end=Math.PI*2*ready,feather=Math.min(end,Math.toRadians(14));
    int steps=Math.max(2,(int)Math.ceil(end/Math.toRadians(6)));float[] c={ccx,ccy};
    for(int i=0;i<steps;i++){
     double a0=end*i/steps,a1=end*(i+1)/steps;
     // Alpha ramps to zero over the last few degrees so the sweep has no hard edge.
     float f0=(float)Mth.clamp((end-a0)/feather,0,1),f1=(float)Mth.clamp((end-a1)/feather,0,1);
     HudArt.triangle(g,t,x,y,size,h,c,point(ccx,ccy,r,a0),point(ccx,ccy,r,a1),argb((f0+f1)/2,0xFFFFFF),argb(f0,0xFFFFFF),argb(f1,0xFFFFFF));
    }
   }
  }
  HudArt.image(g,"ring",x,y,size,size*337/333f,0xFFFFFFFF);
  float pulse=ready>=1?.85f+.15f*(float)Math.sin(Util.getMillis()/220.0):.55f+.3f*ready;
  HudArt.image(g,"feather",x+size*RING_CX-11,y+size*337/333f*RING_CY-13,22,25,argb(pulse,0xFFFFFF));
 }
 private static float[] point(float cx,float cy,float r,double angle){return new float[]{cx+(float)Math.sin(angle)*r,cy-(float)Math.cos(angle)*r};}
 /** Balance arch: gold track, cyan fill from the left with a feathered edge and a pale trail after a sudden drop. */
 private static void arch(GuiGraphics g,float x,float y,float w,float h,float value,float trail,long now){
  HudArt.image(g,"arch",x,y,w,h,0xFFFFFFFF);
  var fill=HudArt.image("arch_fill");if(fill.isEmpty())return;var t=fill.get();
  float danger=value<.25f?.75f+.25f*(float)Math.sin(now/120.0):1;
  if(trail>value+.004f)span(g,t,x,y,w,h,value,trail,argb(.45f,0xFFFFFF));
  span(g,t,x,y,w,h,0,value,argb(danger,0xFFFFFF));
 }
 /** Draws the arch fill between two fractions; the right end fades out over a short distance. */
 private static void span(GuiGraphics g,HudArt.Tex t,float x,float y,float w,float h,float from,float to,int color){
  if(to<=from)return;float feather=Math.min(.05f,to-from);float a=from,b=to-feather,c=to;
  int clear=color&0x00FFFFFF;
  if(b>a)HudArt.quad(g,t,x+w*a,y,x+w*b,y+h,a,0,b,1,color,color,color,color);
  HudArt.quad(g,t,x+w*b,y,x+w*c,y+h,b,0,c,1,color,color,clear,clear);
 }
 /** Fall: a framed card in the middle of the screen with key caps (current = gold, done = cyan, wrong = red flash) and a time bar. */
 private static void drawCatch(GuiGraphics g,Minecraft mc,long now,float partial){
  var sequence=BroomFlight.catchSequence();if(sequence==null)return;int[] keys=sequence.keys();
  int progress=sequence.progress();
  if(progress<lastProgress){wrongIndex=lastProgress;wrongUntil=now+380;}
  lastProgress=progress;
  var bindings=new net.minecraft.client.KeyMapping[]{mc.options.keyUp,mc.options.keyLeft,mc.options.keyDown,mc.options.keyRight};
  int w=g.guiWidth(),h=g.guiHeight();float s=scale(w,h);
  float size=58,gap=12,total=keys.length*size+(keys.length-1)*gap,cardW=total+72,cardH=166;
  var pose=g.pose();pose.pushPose();
  try{
   pose.translate(w/2f,h*.6f,0);pose.scale(s,s,1);
   float x0=-total/2,top=-cardH/2;
   // Card art (textures/gui/hud/catch/card.png): navy panel, gold frame with scooped corners, like the notification card.
   HudArt.image(g,"catch/card",-cardW/2,top,cardW,cardH,0xF2FFFFFF);
   HudArt.text(g,"균형을 잃었습니다!",0,top+26,19,0xFFFFE1A5);
   HudArt.text(g,"표시된 키를 순서대로 누르세요",0,top+50,12.5f,0xFFD5DEE8);
   float ky=top+68;
   for(int i=0;i<keys.length;i++){
    float x=x0+i*(size+gap);boolean done=i<progress,current=i==progress,wrong=i==wrongIndex&&now<wrongUntil;
    // Each cap image is 318 px with the frame occupying the central 230 px.
    float pad=size*44/230f;
    HudArt.image(g,"catch/"+(wrong?"key_wrong":done?"key_done":current?"key_current":"key"),x-pad,ky-pad,size+2*pad,size+2*pad,0xFFFFFFFF);
    HudArt.text(g,bindings[keys[i]].getTranslatedKeyMessage().getString(),x+size/2,ky+size/2,24,wrong?0xFFFFB4A8:done?0xFF7FE6F2:current?0xFFFFEC96:0xFFE6EEF5);
   }
   float barY=ky+size+16,left=Mth.clamp((BroomFlight.catchTicks()-partial)/(float)BroomFlight.CATCH_TICKS,0,1);
   HudArt.image("bar").ifPresent(t->{
    // Dim full-length track, then the glowing remaining time with a soft right end.
    int track=0x40FFFFFF,c=0xFFFFFFFF,clear=0x00FFFFFF;float end=x0+total*left,fade=Math.min(12,total*left);
    HudArt.quad(g,t,x0,barY-6,x0+total,barY+6,0,0,1,1,track,track,track,track);
    if(end-fade>x0)HudArt.quad(g,t,x0,barY-6,end-fade,barY+6,0,0,1,1,c,c,c,c);
    HudArt.quad(g,t,end-fade,barY-6,end,barY+6,0,0,1,1,c,c,clear,clear);
   });
  }finally{pose.popPose();}
 }
}
