package dev.portablevfx.client.claude;

import java.util.Arrays;
import java.util.List;
import dev.portablevfx.client.claude.ClaudeEffect.*;
import dev.portablevfx.client.claude.ClaudeSimulation.ParticleView;

/** Pure CPU geometry. Each vertex is position.xyz, uv.xy, sRGB color.rgb, straight alpha,
 * then the current/next atlas frame and interpolation fraction. Geometry/topology is unchanged. */
public final class ClaudeGeometry {
    public static final int STRIDE=12;
    private static final int[] QUAD={0,1,2,0,2,3};
    private ClaudeGeometry() { }

    public static float[] particles(ClaudeEffect effect,List<ParticleView> particles,Vec3 right,Vec3 up,Vec3 camera,int maxVertices){
        return particles(effect,particles,right,up,camera,Vec3.ZERO,maxVertices);
    }
    public static float[] particles(ClaudeEffect effect,List<ParticleView> particles,Vec3 right,Vec3 up,Vec3 camera,Vec3 origin,int maxVertices){
        var out=new Vertices(maxVertices,origin);
        for(var particle:particles){
            if(particle.size()<=0||particle.alpha()<=0)continue;
            switch(particle.layer().render().type()){
                case "billboard"->billboard(out,particle,right,up);
                case "stretched"->stretched(out,particle,camera,right);
                case "mesh"->mesh(out,particle,effect.meshes().get(particle.layer().render().mesh()));
                default->throw new IllegalStateException("Unsupported Claude geometry: "+particle.layer().render().type());
            }
        }
        return out.finish();
    }
    /** Camera-facing per-particle strips, newest point first, with authored width/color curves. */
    public static float[] trails(List<ParticleView> particles,Vec3 camera,Vec3 fallbackRight,int maxVertices){
        return trails(particles,camera,fallbackRight,Vec3.ZERO,maxVertices);
    }
    public static float[] trails(List<ParticleView> particles,Vec3 camera,Vec3 fallbackRight,Vec3 origin,int maxVertices){
        var out=new Vertices(maxVertices,origin);
        for(var p:particles){
            var trail=p.layer().trail();if(trail==null||p.trail().isEmpty()||p.alpha()<=0||p.size()<=0)continue;
            var points=new java.util.ArrayList<Vec3>();points.add(p.worldPosition());
            for(var point:p.trail())if(length(sub(point.worldPosition(),points.getLast()))>1e-8)points.add(point.worldPosition());
            if(points.size()<2)continue;
            int count=points.size();Vec3[] left=new Vec3[count],right=new Vec3[count],colors=new Vec3[count];double[] alpha=new double[count];
            for(int i=0;i<count;i++){
                double k=(double)i/(count-1);Vec3 position=points.get(i);
                Vec3 tangent=sub(points.get(Math.min(count-1,i+1)),points.get(Math.max(0,i-1)));
                Vec3 side=cross(tangent,sub(camera,position));
                if(length(side)<1e-6)side=fallbackRight;
                side=mul(normalize(side),p.size()*Math.max(0,trail.widthAt(k))*.5);
                left[i]=sub(position,side);right[i]=add(position,side);
                var tint=trail.colorAt(k);colors[i]=new Vec3(p.color().x()*tint.x(),p.color().y()*tint.y(),p.color().z()*tint.z());
                alpha[i]=p.alpha()*(1-k);
            }
            for(int i=0;i<count-1&&out.room(6);i++){
                double k0=(double)i/(count-1),k1=(double)(i+1)/(count-1);
                out.put(left[i],k0,0,colors[i],alpha[i]);out.put(right[i],k0,1,colors[i],alpha[i]);out.put(right[i+1],k1,1,colors[i+1],alpha[i+1]);
                out.put(left[i],k0,0,colors[i],alpha[i]);out.put(right[i+1],k1,1,colors[i+1],alpha[i+1]);out.put(left[i+1],k1,0,colors[i+1],alpha[i+1]);
            }
        }
        return out.finish();
    }
    private static void billboard(Vertices out,ParticleView p,Vec3 right,Vec3 up){
        double c=Math.cos(p.rotation()),s=Math.sin(p.rotation()),half=p.size()*.5;
        Vec3 x=mul(add(mul(right,c),mul(up,s)),half),y=mul(add(mul(up,c),mul(right,-s)),half);
        Vec3 center=p.worldPosition();
        quad(out,p,new Vec3[]{sub(sub(center,x),y),sub(add(center,x),y),add(add(center,x),y),add(sub(center,x),y)});
    }
    private static void stretched(Vertices out,ParticleView p,Vec3 camera,Vec3 fallbackRight){
        double speed=length(p.worldVelocity());if(speed<1e-4)return;
        Vec3 direction=mul(p.worldVelocity(),1/speed);
        double length=p.size()*p.layer().render().lengthScale()+speed*p.layer().render().velocityScale();
        if(length<=0)return;
        Vec3 side=cross(direction,sub(camera,p.worldPosition()));
        if(length(side)<1e-6)side=cross(direction,fallbackRight);
        if(length(side)<1e-6)side=cross(direction,new Vec3(0,1,0));
        side=mul(normalize(side),p.size()*.5);
        Vec3 head=p.worldPosition(),tail=sub(head,mul(direction,length));
        quad(out,p,new Vec3[]{sub(tail,side),sub(head,side),add(head,side),add(tail,side)});
    }
    private static void quad(Vertices out,ParticleView p,Vec3[] vertices){
        if(!out.room(QUAD.length))return;
        double[] u={0,1,1,0},v={0,0,1,1};
        var blend=frameBlend(p);
        for(int corner:QUAD){double[] uv=uv(p.layer().flipbook(),p.normalizedAge(),u[corner],v[corner]);
            out.put(vertices[corner],uv[0],uv[1],p.color(),p.alpha(),blend);}
    }
    private static void mesh(Vertices out,ParticleView p,Mesh mesh){
        if(mesh==null)throw new IllegalStateException("Missing inline mesh: "+p.layer().render().mesh());
        if(!out.room(mesh.triangles().size()))return;
        var blend=frameBlend(p);
        for(int index:mesh.triangles()){
            Vec3 local=rotateEuler(mul(mesh.vertices().get(index),p.size()),p.rotation3D());
            Vec3 point=add(p.worldPosition(),p.basis().apply(local,p.widthScale()));
            var sourceUv=mesh.uvs().get(index);double[] uv=uv(p.layer().flipbook(),p.normalizedAge(),sourceUv.u(),sourceUv.v());
            out.put(point,uv[0],uv[1],p.color(),p.alpha(),blend);
        }
    }
    private static Flipbook.FrameBlend frameBlend(ParticleView p){
        return p.layer().flipbook()==null?null:p.layer().flipbook().frameBlend(p.normalizedAge());
    }
    /** Bottom-left mesh UV to a top-left, row-major authored flipbook, then back to GL UV. */
    public static double[] uv(Flipbook flipbook,double age,double u,double v){
        if(flipbook==null)return new double[]{u,v};
        int frame=flipbook.frame(age),column=frame%flipbook.columns(),row=frame/flipbook.columns();
        return new double[]{(column+u)/flipbook.columns(),1-(row+1-v)/flipbook.rows()};
    }
    /** Authored Euler order is Z, then X, then Y, not JOML's rotateXYZ order. */
    public static Vec3 rotateEuler(Vec3 p,Vec3 r){
        double cz=Math.cos(r.z()),sz=Math.sin(r.z()),cx=Math.cos(r.x()),sx=Math.sin(r.x()),cy=Math.cos(r.y()),sy=Math.sin(r.y());
        double x=p.x()*cz-p.y()*sz,y=p.x()*sz+p.y()*cz,z=p.z();
        double yy=y*cx-z*sx,zz=y*sx+z*cx;
        return new Vec3(x*cy+zz*sy,yy,-x*sy+zz*cy);
    }
    static Vec3 add(Vec3 a,Vec3 b){return new Vec3(a.x()+b.x(),a.y()+b.y(),a.z()+b.z());}
    static Vec3 sub(Vec3 a,Vec3 b){return new Vec3(a.x()-b.x(),a.y()-b.y(),a.z()-b.z());}
    static Vec3 mul(Vec3 v,double s){return new Vec3(v.x()*s,v.y()*s,v.z()*s);}
    static Vec3 cross(Vec3 a,Vec3 b){return new Vec3(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
    static double length(Vec3 v){return Math.sqrt(v.x()*v.x()+v.y()*v.y()+v.z()*v.z());}
    static Vec3 normalize(Vec3 v){double n=length(v);return n<1e-12?new Vec3(0,0,0):mul(v,1/n);}
    private static final class Vertices {
        private float[] values;private int size;private final int limit;private final Vec3 origin;
        Vertices(int maxVertices,Vec3 origin){this.origin=origin;if(maxVertices<0||maxVertices>2_000_000)throw new IllegalArgumentException("Invalid vertex budget");limit=maxVertices*STRIDE;values=new float[Math.min(4096,limit)];}
        /** Whole primitives are skipped once the frame budget is exhausted; put() never receives a partial primitive. */
        boolean room(int vertices){return size+(long)vertices*STRIDE<=limit;}
        void put(Vec3 position,double u,double v,Vec3 color,double alpha){
            put(position,u,v,color,alpha,null);
        }
        void put(Vec3 position,double u,double v,Vec3 color,double alpha,Flipbook.FrameBlend blend){
            if(size+STRIDE>limit)throw new IllegalStateException("Claude geometry exceeds the per-frame vertex budget");
            if(size+STRIDE>values.length)values=Arrays.copyOf(values,Math.min(limit,Math.max(size+STRIDE,values.length*2)));
            values[size++]=f(position.x()-origin.x());values[size++]=f(position.y()-origin.y());values[size++]=f(position.z()-origin.z());
            values[size++]=f(u);values[size++]=f(v);values[size++]=f(color.x());values[size++]=f(color.y());values[size++]=f(color.z());values[size++]=f(alpha);
            values[size++]=blend==null?0:blend.current();values[size++]=blend==null?0:blend.next();values[size++]=blend==null?0:f(blend.fraction());
        }
        private static float f(double value){if(!Double.isFinite(value)||Math.abs(value)>Float.MAX_VALUE)throw new IllegalStateException("Nonfinite Claude geometry");return (float)value;}
        float[] finish(){return Arrays.copyOf(values,size);}
    }
}
