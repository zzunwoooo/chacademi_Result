package school.magiccodex.client;
import java.util.List;
/** Terrain-independent planar geometry. Animation may change tint/decoration, never these vertices. */
final class AimPlane {
 record V(double x,double y,double z){V add(V b){return new V(x+b.x,y+b.y,z+b.z);}V mul(double k){return new V(x*k,y*k,z*k);}double dot(V b){return x*b.x+y*b.y+z*b.z;}double length(){return Math.sqrt(dot(this));}V unit(){double l=length();return l<1e-9?new V(0,0,1):mul(1/l);}V cross(V b){return new V(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x);}}
 record Frame(V forward,V right,V normal){V at(double distance,double side){return forward.mul(distance).add(right.mul(side));}}
 static Frame frame(double x,double y,double z){var f=new V(x,y,z).unit();var r=new V(-f.z,0,f.x);if(r.length()<1e-6)r=new V(1,0,0);r=r.unit();return new Frame(f,r,f.cross(r).unit());}
 static List<V[]> arrow(Frame f,double length,double width){
  double l=Math.max(0,length),w=Math.max(.01,width),start=Math.min(.35,l*.15),head=Math.min(l*.35,Math.max(.35,w*1.3)),neck=Math.max(start,l-head),tail=w*.23;
  return List.of(new V[]{f.at(start,tail),f.at(start,-tail),f.at(neck,-tail),f.at(neck,tail)},new V[]{f.at(neck,w/2),f.at(neck,-w/2),f.at(l,0),f.at(l,0)});
 }
 static double[] aim(double ex,double ey,double ez,double tx,double ty,double tz,double range){var d=new V(tx-ex,ty-ey,tz-ez).unit();return new double[]{ex+d.x*range,ey+d.y*range,ez+d.z*range};}
}
