package school.magiccodex.client;
/** The approved tank camera equations, separated from Minecraft for scale/transition tests. */
final class FishTankUiMath {
 record Magnification(float zoom,float presentation){}
 static Magnification magnification(float shown){float zoom=Math.clamp(380/shown,1.3f,2.2f);return new Magnification(zoom,Math.clamp(380/(shown*zoom),1f,2.4f));}
 static boolean fresh(float progress,String selected){return progress<=.001f||selected==null;}
 static float ease(float t){t=Math.clamp(t,0,1);return t*t*t*(t*(t*6-15)+10);}
 static float progress(float value,boolean selected,float dt){float target=selected?1:0;float rate=selected?1/.85f:1/.7f;return target>value?Math.min(target,value+dt*rate):Math.max(target,value-dt*rate);}
 static float blend(float dt,float rate){return 1-(float)Math.exp(-dt*rate);}
 static float local(float world,float camera,float zoom,float center){return (world-camera)*zoom+center;}
 static float world(double local,float camera,float zoom,float center){return (float)((local-center)/zoom+camera);}
 /** Absolute size (cm -> px at depth 1). Tiny fish go down to 18 px; steeper than the old 20*cm^.36 (min 40) so sizes read. */
 static float absoluteLength(float cm){float v=Float.isFinite(cm)?Math.max(.5f,cm):1;return Math.clamp(9*(float)Math.pow(v,.55),18,140);}
 /** Species base size plus in-species rank: +-10% between the smallest and largest fish of that species in the tank. */
 static float length(float cm,float speciesMin,float speciesMax){
  float spread=speciesMax-speciesMin,ratio=Float.isFinite(cm)&&Float.isFinite(spread)&&spread>Math.max(.01f,speciesMax*.05f)?Math.clamp((cm-speciesMin)/spread,0,1):.5f;
  return absoluteLength(cm)*(.9f+.2f*ratio);
 }
 /** Depth scale for swimming fish (was .62..1, which masked real size differences). */
 static float depthScale(float z){return .88f+.12f*Math.clamp(z,0,1);}
}
