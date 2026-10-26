package school.magiccodex.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;

/** Screen-space meshes with a soft edge; no per-frame texture allocation. */
final class HudMesh {
    private HudMesh(){}
    static void quad(DrawContext c,float x,float y,float w,float h,int left,int right){
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            v.vertex(m,x,y+h,0).color(left);v.vertex(m,x+w,y+h,0).color(right);
            v.vertex(m,x+w,y,0).color(right);v.vertex(m,x,y,0).color(left);
        });
    }
    static void line(DrawContext c,float ax,float ay,float bx,float by,float thickness,int color){
        float dx=bx-ax,dy=by-ay,len=(float)Math.sqrt(dx*dx+dy*dy);
        if(len<.001f)return;
        float nx=-dy/len*thickness/2,ny=dx/len*thickness/2;
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            v.vertex(m,ax-nx,ay-ny,0).color(color);v.vertex(m,ax+nx,ay+ny,0).color(color);
            v.vertex(m,bx+nx,by+ny,0).color(color);v.vertex(m,bx-nx,by-ny,0).color(color);
        });
    }
    /** Rectangle with a top-to-bottom colour gradient. */
    static void vertical(DrawContext c,float x,float y,float w,float h,int top,int bottom){
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            v.vertex(m,x,y+h,0).color(bottom);v.vertex(m,x+w,y+h,0).color(bottom);
            v.vertex(m,x+w,y,0).color(top);v.vertex(m,x,y,0).color(top);
        });
    }
    static void star(DrawContext c,float x,float y,float radius,int color){
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            v.vertex(m,x,y-radius,0).color(color);v.vertex(m,x-radius*.7f,y,0).color(color);
            v.vertex(m,x,y+radius,0).color(color);v.vertex(m,x+radius*.7f,y,0).color(color);
        });
    }
    static void disk(DrawContext c,float x,float y,float radius,int color){
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            for(int i=0;i<24;i++){
                double a=i*Math.PI/12,b=(i+1)*Math.PI/12;
                v.vertex(m,x,y,0).color(color);
                v.vertex(m,x+(float)Math.cos(b)*radius,y+(float)Math.sin(b)*radius,0).color(color);
                v.vertex(m,x+(float)Math.cos(a)*radius,y+(float)Math.sin(a)*radius,0).color(color);
                v.vertex(m,x,y,0).color(color);
            }
        });
    }
    static void capsule(DrawContext c,float x,float y,float w,float h,int from,int to){
        if(w<=0)return;
        float r=Math.min(h/2,w/2);
        quad(c,x+r,y,w-2*r,h,from,to);
        // Only the outer semicircles: full disks would overlap the middle quad and
        // blend translucent backgrounds twice, leaving two dark vertical seams.
        semicircle(c,x+r,y+h/2,r,(float)(Math.PI/2),from);
        semicircle(c,x+w-r,y+h/2,r,(float)(-Math.PI/2),to);
    }
    private static void semicircle(DrawContext c,float x,float y,float radius,float start,int color){
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            for(int i=0;i<24;i++){
                double a=start+i*Math.PI/24,b=start+(i+1)*Math.PI/24;
                v.vertex(m,x,y,0).color(color);
                v.vertex(m,x+(float)Math.cos(b)*radius,y+(float)Math.sin(b)*radius,0).color(color);
                v.vertex(m,x+(float)Math.cos(a)*radius,y+(float)Math.sin(a)*radius,0).color(color);
                v.vertex(m,x,y,0).color(color);
            }
        });
    }
    /**
     * A textured disk (round avatar): the texture rectangle u0..u1 × v0..v1 (0..1) mapped onto the circle's bounding square.
     * The layer must use POSITION_TEXTURE_COLOR quads (gui textured, the portrait layer).
     */
    static void texturedDisk(DrawContext c,RenderLayer layer,float x,float y,float radius,float u0,float v0,float u1,float v1,int color){
        var m=c.getMatrices().peek().getPositionMatrix();
        int n=Math.clamp(Math.round(radius*1.5f),24,96);
        float uc=(u0+u1)/2,vc=(v0+v1)/2,ur=(u1-u0)/2,vr=(v1-v0)/2;
        c.draw(p->{
            var v=p.getBuffer(layer);
            for(int i=0;i<n;i++){
                double a=i*Math.PI*2/n,b=(i+1)*Math.PI*2/n;
                float ca=(float)Math.cos(a),sa=(float)Math.sin(a),cb=(float)Math.cos(b),sb=(float)Math.sin(b);
                v.vertex(m,x,y,0).texture(uc,vc).color(color);
                v.vertex(m,x+cb*radius,y+sb*radius,0).texture(uc+cb*ur,vc+sb*vr).color(color);
                v.vertex(m,x+ca*radius,y+sa*radius,0).texture(uc+ca*ur,vc+sa*vr).color(color);
                v.vertex(m,x,y,0).texture(uc,vc).color(color);
            }
        });
    }
    /** Continuous annular strip, batched into one draw; signed sweep fills from bottom to top. */
    static void arc(DrawContext c,float cx,float cy,float radius,float thickness,float start,float sweep,int color){
        if(Math.abs(sweep)<.0001f || thickness<=0)return;
        int steps=Math.max(1,(int)Math.ceil(Math.abs(sweep)*radius/2));
        var m=c.getMatrices().peek().getPositionMatrix();
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            float inner=radius-thickness/2,outer=radius+thickness/2;
            for(int i=0;i<steps;i++){
                double a=start+sweep*i/steps,b=start+sweep*(i+1)/steps;
                // GUI layer culls back faces: both sweep directions need the same winding.
                if(sweep>0){double swap=a;a=b;b=swap;}
                float ca=(float)Math.cos(a),sa=(float)Math.sin(a),cb=(float)Math.cos(b),sb=(float)Math.sin(b);
                v.vertex(m,cx+ca*inner,cy+sa*inner,0).color(color);
                v.vertex(m,cx+ca*outer,cy+sa*outer,0).color(color);
                v.vertex(m,cx+cb*outer,cy+sb*outer,0).color(color);
                v.vertex(m,cx+cb*inner,cy+sb*inner,0).color(color);
            }
        });
    }
    /**
     * Radial gradient ring from r0 (inner colour) to r1 (outer colour), then the outer colour out to r2.
     * Used for the soft vignette around a focused subject.
     */
    static void radial(DrawContext c,float cx,float cy,float r0,float r1,float r2,int inner,int outer){
        var m=c.getMatrices().peek().getPositionMatrix();
        int n=64;
        c.draw(p->{
            var v=p.getBuffer(RenderLayer.getGui());
            for(int i=0;i<n;i++){
                // same winding as arc() (the GUI layer culls back faces): larger angle first
                double a=(i+1)*Math.PI*2/n,b=i*Math.PI*2/n;
                float ca=(float)Math.cos(a),sa=(float)Math.sin(a),cb=(float)Math.cos(b),sb=(float)Math.sin(b);
                v.vertex(m,cx+ca*r0,cy+sa*r0,0).color(inner);v.vertex(m,cx+ca*r1,cy+sa*r1,0).color(outer);
                v.vertex(m,cx+cb*r1,cy+sb*r1,0).color(outer);v.vertex(m,cx+cb*r0,cy+sb*r0,0).color(inner);
                v.vertex(m,cx+ca*r1,cy+sa*r1,0).color(outer);v.vertex(m,cx+ca*r2,cy+sa*r2,0).color(outer);
                v.vertex(m,cx+cb*r2,cy+sb*r2,0).color(outer);v.vertex(m,cx+cb*r1,cy+sb*r1,0).color(outer);
            }
        });
    }
}
