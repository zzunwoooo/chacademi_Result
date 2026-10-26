package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;

/** The seal rotates independently of the irregular drifting stars. No extra star particles. */
final class StatsSigil {
    private static final float X=377,Y=456,TAU=(float)(Math.PI*2);
    private StatsSigil(){}
    static void draw(DrawContext c,float circle,double time){
        float rotation=(float)(time*.025),level=(Math.clamp(circle,1,9)-1)/8;
        int rgb=0xC5DCCD;
        ring(c,196,0,TAU,.78f,rgb);
        polygon(c,3,160,rotation-.5f,.40f+level*.26f,rgb);
        ring(c,184,0,TAU,stage(circle,2)*.46f,rgb);
        for(int i=0;i<3;i++)ring(c,205,rotation+i*TAU/3,.65f,stage(circle,3)*.83f,0xE3CFA2);
        float ticks=stage(circle,4);
        for(int i=0;i<24;i++)radial(c,rotation+i*TAU/24,185,194,ticks*.7f,rgb);
        polygon(c,3,160,rotation-.5f+.5f*TAU,stage(circle,5)*.66f,rgb);
        ring(c,221,0,TAU,stage(circle,6)*.68f,0xE3CFA2);
        for(int i=0;i<12;i++)rune(c,i,rotation,stage(circle,7)*.83f);
        polygon(c,6,175,-rotation*.6f,stage(circle,8)*.5f,0xB2DFF1);
        float highest=stage(circle,9);
        for(int i=0;i<6;i++)ring(c,232,-rotation+i*TAU/6,.43f,highest*.8f,0xDBC7F0);
    }
    private static float stage(float circle,int at){return Math.clamp(circle-at+1,0,1);}
    private static int color(int rgb,float alpha){return Math.round(Math.clamp(alpha,0,1)*190)<<24|rgb;}
    private static void ring(DrawContext c,float r,float start,float sweep,float alpha,int rgb){
        if(alpha<=0)return;
        HudMesh.arc(c,X,Y,r,5.5f,start,sweep,color(rgb,alpha*.12f));
        HudMesh.arc(c,X,Y,r,1.9f,start,sweep,color(rgb,alpha));
    }
    private static void line(DrawContext c,float ax,float ay,float bx,float by,float alpha,int rgb){
        if(alpha<=0)return;
        HudMesh.line(c,ax,ay,bx,by,4.6f,color(rgb,alpha*.12f));
        HudMesh.line(c,ax,ay,bx,by,1.7f,color(rgb,alpha));
    }
    private static void polygon(DrawContext c,int sides,float r,float rotate,float alpha,int rgb){
        if(alpha<=0)return;
        for(int i=0;i<sides;i++){
            float a=rotate+i*TAU/sides,b=rotate+(i+1)*TAU/sides;
            line(c,X+(float)Math.cos(a)*r,Y+(float)Math.sin(a)*r,X+(float)Math.cos(b)*r,Y+(float)Math.sin(b)*r,alpha,rgb);
        }
    }
    private static void radial(DrawContext c,float a,float r1,float r2,float alpha,int rgb){
        line(c,X+(float)Math.cos(a)*r1,Y+(float)Math.sin(a)*r1,X+(float)Math.cos(a)*r2,Y+(float)Math.sin(a)*r2,alpha,rgb);
    }
    private static void rune(DrawContext c,int index,float rotation,float alpha){
        if(alpha<=0)return;
        float angle=rotation+index*TAU/12,x=X+(float)Math.cos(angle)*211,y=Y+(float)Math.sin(angle)*211;
        c.getMatrices().push();
        try{
            c.getMatrices().translate(x,y,0);c.getMatrices().multiply(new org.joml.Quaternionf().rotateZ(angle));
            line(c,-5,-4,5,-4,alpha,0xE3CFA2);line(c,0,-4,0,5,alpha,0xE3CFA2);
            if(index%2==0)line(c,-4,1,4,1,alpha,0xE3CFA2);
            else line(c,0,5,5,1,alpha,0xE3CFA2);
        }finally{c.getMatrices().pop();}
    }
}
